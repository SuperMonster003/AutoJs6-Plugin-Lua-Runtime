from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SOURCE_ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "lua_runtime_verify_repository",
    SOURCE_ROOT / "tools/verify_repository.py",
)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def write_json(path: Path, value: object) -> None:
    write_text(path, json.dumps(value, indent=2) + "\n")


def protocol_lock(status: str = "not-staged", revision: object = None) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "status": status,
        "sourceRepository": "AutoJs6",
        "sourceRevision": revision,
        "artifacts": [
            {
                "file": file_name,
                "sourceModule": source_module,
                "sha256": None,
            }
            for file_name, source_module in verifier.PROTOCOL_MODULES.items()
        ],
    }


def stage_protocol(root: Path, lock: dict[str, object]) -> None:
    protocol_root = root / "protocol"
    for index, entry in enumerate(lock["artifacts"]):
        artifact = protocol_root / entry["file"]
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_bytes(f"artifact-{index}".encode("ascii"))
        entry["sha256"] = verifier.sha256(artifact)
    write_json(protocol_root / "protocol-artifacts.lock.json", lock)


def vendor_lock(status: str = "not-vendored") -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "status": status,
        "runtimeSlot": "lua54",
        "name": "PUC Lua",
        "version": "5.4.8",
        "archive": "lua-5.4.8.tar.gz",
        "sourceUrl": "https://www.lua.org/ftp/lua-5.4.8.tar.gz",
        "sha256": verifier.LUA_SHA256,
        "sourceDirectory": "lua-5.4.8",
        "sourceTreeRoot": "lua-5.4.8/src",
        "sourceFileCount": None,
        "sourceTreeSha256": None,
        "vendoredAt": None,
    }


def stage_vendor(root: Path, lock: dict[str, object], files: dict[str, bytes]) -> None:
    source_root = root / "app/src/main/cpp/vendor/lua-5.4.8/src"
    for relative, content in files.items():
        path = source_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    count, digest = verifier.source_tree_fingerprint(source_root)
    lock["status"] = "vendored"
    lock["sourceFileCount"] = count
    lock["sourceTreeSha256"] = digest
    lock["vendoredAt"] = "2026-08-10T00:00:00+00:00"
    write_json(root / "app/src/main/cpp/vendor/vendor-lock.json", lock)


def write_default_off_fixture(root: Path) -> None:
    shutil.copy2(SOURCE_ROOT / "verification.properties", root / "verification.properties")
    write_text(
        root / "gradle.properties",
        "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
        "autojs.lua.faultHarness.enabled=false\n"
        "autojs.lua.releaseCandidate.enabled=false\n",
    )
    write_text(
        root / ".github/workflows/ci.yml",
        """jobs:
  build:
    steps:
      - run: >-
          for build_attempt in 1 2 3; do
            if ./gradlew \\
              :app:assembleDebug \\
              -Pautojs.lua.native.enabled=true \\
              -Pautojs.lua.provider.enabled=false \\
              --no-daemon; then
              exit 0
            fi
          done
      - run: ./tools/verify_debug_artifacts.ps1 -BuildToolsVersion 36.0.0
      - run: python tools/verify_repository.py --require-build-ready --github-output
""",
    )
    write_text(
        root
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt",
        "override fun onBind(intent: Intent?): IBinder = binder\n",
    )


class VerificationPropertiesTest(unittest.TestCase):
    def test_jvm_count_is_positive_unique_and_the_only_verification_property(self) -> None:
        invalid = (
            "JVM_TEST_COUNT=0\n",
            "JVM_TEST_COUNT=2\nJVM_TEST_COUNT=3\n",
            "JVM_TEST_COUNT=2\nUNREVIEWED_GATE=true\n",
            "JVM_TEST_COUNT=two\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy2(SOURCE_ROOT / "verification.properties", root / "verification.properties")
            with mock.patch.object(verifier, "ROOT", root):
                self.assertGreater(verifier.expected_jvm_test_count(), 0)
        for value in invalid:
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                write_text(root / "verification.properties", value)
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.expected_jvm_test_count()


class LocalizationWorkflowTest(unittest.TestCase):
    FILES = (
        "tools/generate_localized_content.py",
        "localization/locales.json",
        "localization/source/en/README.md",
        "localization/source/en/CHANGELOG.md",
        "localization/source/en/strings.json",
        "localization/source/zh-CN/README.md",
        "localization/source/zh-CN/CHANGELOG.md",
        "localization/source/zh-CN/strings.json",
        "README.md",
        "README.zh-CN.md",
        "CHANGELOG.md",
        "CHANGELOG.zh-CN.md",
        "app/src/main/res/values/strings.xml",
        "app/src/main/res/values-zh-rCN/strings.xml",
    )

    def copy_workflow(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_reviewed_bilingual_outputs_are_exactly_generated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflow(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_localization_workflow()

    def test_generator_recreates_missing_and_drifted_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workflow(root)
            write_text(root / "README.md", "drift\n")
            (root / "CHANGELOG.zh-CN.md").unlink()
            completed = subprocess.run(
                [
                    sys.executable,
                    str(root / "tools/generate_localized_content.py"),
                    "--root",
                    str(root),
                ],
                cwd=root,
                capture_output=True,
                text=True,
                encoding="utf-8",
                timeout=30,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_localization_workflow()

    def test_output_drift_placeholder_sources_and_duplicate_manifest_keys_fail_closed(self) -> None:
        mutations = (
            (
                "generated README drift",
                lambda root: write_text(
                    root / "README.md",
                    (root / "README.md").read_text("utf-8") + "unreviewed drift\n",
                ),
            ),
            (
                "planned locale source",
                lambda root: write_text(
                    root / "localization/source/ja/README.md",
                    "# Placeholder\n",
                ),
            ),
            (
                "unexpected localized output",
                lambda root: write_text(root / "README.ja.md", "# Placeholder\n"),
            ),
            (
                "translation marker",
                lambda root: write_text(
                    root / "localization/source/zh-CN/README.md",
                    (root / "localization/source/zh-CN/README.md").read_text("utf-8")
                    + "TODO_TRANSLATION\n",
                ),
            ),
            (
                "translated protected literal drift",
                lambda root: write_text(
                    root / "localization/source/zh-CN/README.md",
                    (root / "localization/source/zh-CN/README.md").read_text("utf-8").replace(
                        "4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae",
                        "0" * 64,
                        1,
                    ),
                ),
            ),
            (
                "duplicate manifest key",
                lambda root: write_text(
                    root / "localization/locales.json",
                    (root / "localization/locales.json").read_text("utf-8").replace(
                        '"sourceLocale": "en",',
                        '"sourceLocale": "en",\n  "sourceLocale": "en",',
                        1,
                    ),
                ),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workflow(root)
                mutate(root)
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_localization_workflow()


class R4DesignRecordTest(unittest.TestCase):
    FILES = (
        "docs/pcall-boundary-decision.md",
        "docs/result-model-v2.md",
        "docs/safe-standard-library-subset.md",
        "docs/module-snapshot-v2.md",
        "docs/console-levels-decision.md",
        "docs/storage-kv-v1.md",
        "protocol/protocol-artifacts.lock.json",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt",
        "docs/ui-toast-v1.md",
        "docs/execution-statistics-v1.md",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/"
        "LuaExecutionStatisticsProtocolBoundaryTest.kt",
    )

    def copy_records(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_r4_decisions_and_v2_designs_are_complete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_records(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_r4_design_records()

    def test_decision_or_compatibility_evidence_removal_is_rejected(self) -> None:
        mutations = (
            (
                "pcall status",
                self.FILES[0],
                lambda text: text.replace("REJECTED FOR R4", "UNDECIDED", 1),
            ),
            (
                "pcall nested catch gate",
                self.FILES[0],
                lambda text: text.replace(
                    "repeated catches cannot defer termination",
                    "termination remains untested",
                    1,
                ),
            ),
            (
                "result capability negotiation",
                self.FILES[1],
                lambda text: text.replace("result.model.v2", "unreviewed.result"),
            ),
            (
                "host coordination",
                self.FILES[1],
                lambda text: text.replace("## Host-side changes required", "## Deferred host work", 1),
            ),
            (
                "frozen protocol identity",
                self.FILES[6],
                lambda text: text.replace(
                    "3b7378758c5a4f68e8680a78cf2c541c23628489",
                    "1111111111111111111111111111111111111111",
                    1,
                ),
            ),
            (
                "safe random seed boundary",
                self.FILES[2],
                lambda text: text.replace(
                    "rejection of zero-argument `math.randomseed()`",
                    "zero-argument seeding remains exposed",
                    1,
                ),
            ),
            (
                "module V2 fallback boundary",
                self.FILES[3],
                lambda text: text.replace(
                    "must never retry a denied, missing, malformed, or failed V2 lookup",
                    "may retry a V2 lookup as V1",
                    1,
                ),
            ),
            (
                "module V2 aggregate quota",
                self.FILES[3],
                lambda text: text.replace("at most 512 KiB of verified source bytes", "unbounded source bytes", 1),
            ),
            (
                "console stream inventory",
                self.FILES[4],
                lambda text: text.replace("exactly `STDOUT` and `STDERR`", "additional local streams", 1),
            ),
            (
                "storage principal isolation",
                self.FILES[5],
                lambda text: text.replace(
                    "single Host-wide or Provider-wide namespace is forbidden",
                    "a single shared namespace is allowed",
                    1,
                ),
            ),
            (
                "storage mutation retry",
                self.FILES[5],
                lambda text: text.replace("Provider never retries", "Provider retries mutations", 1),
            ),
            (
                "toast dispatch retry",
                self.FILES[8],
                lambda text: text.replace(
                    "Neither layer contains a retry loop",
                    "Both layers may retry",
                    1,
                ),
            ),
            (
                "toast Host boundary",
                self.FILES[8],
                lambda text: text.replace(
                    "visual delivery therefore remains a coordinated Host follow-up",
                    "Provider smoke proves end-to-end delivery",
                    1,
                ),
            ),
            (
                "execution statistics protocol status",
                self.FILES[9],
                lambda text: text.replace("需宿主协议演进", "PROVIDER-LOCAL EXTENSION", 1),
            ),
            (
                "execution statistics field inventory",
                self.FILES[9],
                lambda text: text.replace("instructionHookInvocations", "unboundedHookEvents"),
            ),
            (
                "execution statistics JVM boundary",
                self.FILES[10],
                lambda text: text.replace(
                    "frozenProtocolRequiresHostEvolutionForExecutionStatistics",
                    "statisticsCarrierAssumedWithoutEvidence",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_records(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_r4_design_records()


class StrictJsonParsingTest(unittest.TestCase):
    def test_duplicate_keys_are_rejected_at_every_lock_depth(self) -> None:
        cases = (
            (
                "protocol top-level",
                "protocol/protocol-artifacts.lock.json",
                json.dumps(protocol_lock(), indent=2).replace(
                    '"schemaVersion": 1,',
                    '"schemaVersion": 1,\n  "schemaVersion": 1,',
                    1,
                ),
                verifier.verify_protocol,
            ),
            (
                "protocol artifact entry",
                "protocol/protocol-artifacts.lock.json",
                json.dumps(protocol_lock(), indent=2).replace(
                    '"file": "common-plugin-api.aar",',
                    '"file": "common-plugin-api.aar",\n      "file": "common-plugin-api.aar",',
                    1,
                ),
                verifier.verify_protocol,
            ),
            (
                "vendor top-level",
                "app/src/main/cpp/vendor/vendor-lock.json",
                json.dumps(vendor_lock(), indent=2).replace(
                    '"schemaVersion": 1,',
                    '"schemaVersion": 1,\n  "schemaVersion": 1,',
                    1,
                ),
                verifier.verify_vendor,
            ),
        )
        for label, relative, payload, verify in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                write_text(root / relative, payload + "\n")
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaisesRegex(RuntimeError, "Duplicate JSON key"):
                        verify()


class ProtocolLockTest(unittest.TestCase):
    def test_unstaged_lock_is_valid_but_not_ready(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root / "protocol/protocol-artifacts.lock.json", protocol_lock())
            with mock.patch.object(verifier, "ROOT", root):
                self.assertFalse(verifier.verify_protocol())

    def test_staged_lock_binds_exact_revision_inventory_and_digests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock = protocol_lock("staged", "a" * 40)
            stage_protocol(root, lock)
            with mock.patch.object(verifier, "ROOT", root):
                self.assertTrue(verifier.verify_protocol())

    def test_schema_repository_and_revision_drift_fail_closed(self) -> None:
        mutations = {
            "boolean schema": lambda lock: lock.__setitem__("schemaVersion", True),
            "repository case": lambda lock: lock.__setitem__("sourceRepository", "autojs6"),
            "short revision": lambda lock: lock.__setitem__("sourceRevision", "a" * 39),
            "uppercase revision": lambda lock: lock.__setitem__("sourceRevision", "A" * 40),
            "null object revision": lambda lock: lock.__setitem__("sourceRevision", "0" * 40),
            "unversioned extra field": lambda lock: lock.__setitem__("unexpected", True),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                lock = protocol_lock("staged", "a" * 40)
                mutate(lock)
                stage_protocol(root, lock)
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_protocol()

    def test_unlisted_aar_is_rejected_in_both_states(self) -> None:
        for status in ("not-staged", "staged"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                lock = protocol_lock(status, "a" * 40 if status == "staged" else None)
                if status == "staged":
                    stage_protocol(root, lock)
                else:
                    write_json(root / "protocol/protocol-artifacts.lock.json", lock)
                (root / "protocol/rogue.aar").write_bytes(b"rogue")
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_protocol()


class VendorProvenanceTest(unittest.TestCase):
    def test_unvendored_lock_is_valid_but_not_ready(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root / "app/src/main/cpp/vendor/vendor-lock.json", vendor_lock())
            with mock.patch.object(verifier, "ROOT", root):
                self.assertFalse(verifier.verify_vendor())

    def test_archive_to_tree_identity_drift_fails_closed(self) -> None:
        mutations = {
            "boolean schema": lambda lock: lock.__setitem__("schemaVersion", True),
            "archive": lambda lock: lock.__setitem__("archive", "lua.tar.gz"),
            "source URL": lambda lock: lock.__setitem__("sourceUrl", "http://example.invalid/lua.tar.gz"),
            "archive digest": lambda lock: lock.__setitem__("sha256", "0" * 64),
            "source directory": lambda lock: lock.__setitem__("sourceDirectory", "lua-current"),
            "tree root": lambda lock: lock.__setitem__("sourceTreeRoot", "../outside"),
            "unversioned extra field": lambda lock: lock.__setitem__("unexpected", True),
            "premature timestamp": lambda lock: lock.__setitem__("vendoredAt", "2026-08-10T00:00:00Z"),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                lock = vendor_lock()
                mutate(lock)
                write_json(root / "app/src/main/cpp/vendor/vendor-lock.json", lock)
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_vendor()

    def test_vendored_tree_count_digest_and_timestamp_are_bound(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock = vendor_lock()
            stage_vendor(root, lock, {"lapi.c": b"api", "lua.h": b"header"})
            with mock.patch.object(verifier, "ROOT", root):
                self.assertTrue(verifier.verify_vendor())
                (root / "app/src/main/cpp/vendor/lua-5.4.8/src/lapi.c").write_bytes(b"changed")
                with self.assertRaises(RuntimeError):
                    verifier.verify_vendor()

    def test_boolean_file_count_is_not_a_json_integer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock = vendor_lock()
            stage_vendor(root, lock, {"lapi.c": b"api"})
            lock["sourceFileCount"] = True
            write_json(root / "app/src/main/cpp/vendor/vendor-lock.json", lock)
            with mock.patch.object(verifier, "ROOT", root):
                with self.assertRaises(RuntimeError):
                    verifier.verify_vendor()


class DefaultOffTest(unittest.TestCase):
    def test_default_off_and_ci_native_only_build_are_admitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_default_off_fixture(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_default_off()

    def test_duplicate_or_enabled_default_is_rejected(self) -> None:
        invalid = (
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=true\n"
            "autojs.lua.faultHarness.enabled=false\n"
            "autojs.lua.releaseCandidate.enabled=false\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.provider.enabled=false\nautojs.lua.faultHarness.enabled=false\n"
            "autojs.lua.releaseCandidate.enabled=false\n",
            "autojs.lua.native.enabled=false\\\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=false\n"
            "autojs.lua.releaseCandidate.enabled=false\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=false\n"
            "autojs.lua.releaseCandidate.enabled=false\n"
            "systemProp.org.gradle.project.autojs.lua.provider.enabled=true\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=true\n"
            "autojs.lua.releaseCandidate.enabled=false\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=false\n"
            "autojs.lua.releaseCandidate.enabled=true\n",
        )
        for properties in invalid:
            with self.subTest(properties=properties):
                with self.assertRaises(RuntimeError):
                    verifier.parse_default_off_flags(properties)

    def test_inline_or_indirect_ci_provider_enablement_is_rejected(self) -> None:
        additions = (
            "      - run: gradle :app:tasks -Pautojs.lua.provider.enabled=true\n",
            "    env:\n      ORG_GRADLE_PROJECT_autojs.lua.provider.enabled: true\n",
            "      - run: gradle :app:tasks -Pautojs.lua.faultHarness.enabled=true\n",
            "      - run: gradle :app:tasks -Pautojs.lua.releaseCandidate.enabled=true\n",
        )
        for addition in additions:
            with self.subTest(addition=addition), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                write_default_off_fixture(root)
                ci = (root / ".github/workflows/ci.yml").read_text("utf-8")
                write_text(root / ".github/workflows/ci.yml", ci + addition)
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_default_off()

    def test_ci_cannot_downgrade_readiness_or_bypass_the_wrapper(self) -> None:
        mutations = (
            lambda text: text.replace("--require-build-ready ", ""),
            lambda text: text.replace("if ./gradlew \\", "if gradle \\", 1),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                write_default_off_fixture(root)
                ci_path = root / ".github/workflows/ci.yml"
                write_text(ci_path, mutate(ci_path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_default_off()


class CiResilienceTest(unittest.TestCase):
    def test_current_sdk_cache_and_two_retry_loop_are_admitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = root / ".github/workflows/ci.yml"
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / ".github/workflows/ci.yml", destination)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_ci_resilience()

    def test_cache_or_bounded_retry_removal_is_rejected(self) -> None:
        mutations = (
            lambda text: text.replace("actions/cache@v4", "actions/cache@removed", 1),
            lambda text: text.replace("for attempt in 1 2 3; do", "for attempt in 1; do", 1),
            lambda text: text.replace("gradle/actions/setup-gradle@v4", "gradle/actions/setup-gradle@removed", 1),
            lambda text: text.replace("for build_attempt in 1 2 3; do", "for build_attempt in 1; do", 1),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                destination = root / ".github/workflows/ci.yml"
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = (SOURCE_ROOT / ".github/workflows/ci.yml").read_text("utf-8")
                write_text(destination, mutate(source))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_ci_resilience()


class InputWorkflowTest(unittest.TestCase):
    INPUT_FILES = (
        "verification.properties",
        ".gitattributes",
        ".gitignore",
        "build.gradle.kts",
        "app/build.gradle.kts",
        "tools/stage_protocol_artifacts.ps1",
        "tools/stage_lua_source.ps1",
        "tools/verify_debug_artifacts.ps1",
        "tools/build_runnable_provider.ps1",
        "tools/verify_release_candidate_artifacts.ps1",
        "tools/verify_local.ps1",
        "tools/verify_lua_archive.ps1",
    )

    def copy_inputs(self, root: Path) -> None:
        for relative in self.INPUT_FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_input_workflows_are_pinned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_inputs(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_input_workflows()

    def test_missing_git_exception_or_archive_lock_comparison_is_rejected(self) -> None:
        mutations = (
            (
                "legacy Android Kotlin plugin",
                "app/build.gradle.kts",
                lambda text: text.replace(
                    'id("com.android.application")',
                    'id("com.android.application")\n    id("org.jetbrains.kotlin.android")',
                    1,
                ),
            ),
            (
                "missing upstream Lua whitespace exception",
                ".gitattributes",
                lambda text: text.replace(
                    "app/src/main/cpp/vendor/lua-5.4.8/src/** -whitespace\n",
                    "",
                ),
            ),
            (
                "missing native build output ignore",
                ".gitignore",
                lambda text: text.replace(".cxx/\n", ""),
            ),
            ("missing AAR exception", ".gitignore", lambda text: text.replace("!protocol/*.aar\n", "")),
            ("late JAR ignore", ".gitignore", lambda text: text + "*.jar\n"),
            (
                "archive no longer compared with lock",
                "tools/stage_lua_source.ps1",
                lambda text: text.replace(
                    "$archiveSha256 -ne $expectedArchiveSha256 -or $archiveSha256 -ne $lock.sha256",
                    "$archiveSha256 -ne $expectedArchiveSha256",
                ),
            ),
            (
                "Lua vendor timestamp exceeds verifier precision",
                "tools/stage_lua_source.ps1",
                lambda text: text.replace(
                    "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
                ),
            ),
            (
                "protocol build cache admitted",
                "tools/stage_protocol_artifacts.ps1",
                lambda text: text.replace("--no-build-cache", "--build-cache"),
            ),
            (
                "protocol build uses caller working directory",
                "tools/stage_protocol_artifacts.ps1",
                lambda text: text.replace(
                    '"--project-dir=$checkout"',
                    '"--project-dir=$repositoryRoot"',
                ),
            ),
            (
                "debug artifact gate weakens ZIP alignment",
                "tools/verify_debug_artifacts.ps1",
                lambda text: text.replace("zipalign -c -P 16 4", "zipalign -c -P 4 4"),
            ),
            (
                "debug artifact gate admits AndroidTest BuildConfig",
                "tools/verify_debug_artifacts.ps1",
                lambda text: text.replace(
                    "app/build/generated/source/buildConfig/debug/",
                    "app/build/generated/source/buildConfig/",
                    1,
                ),
            ),
            (
                "debug artifact gate bypasses the JVM count source",
                "tools/verify_debug_artifacts.ps1",
                lambda text: text.replace(
                    "$expectedTests = [int]$verificationProperties.JVM_TEST_COUNT",
                    "$expectedTests = 30",
                    1,
                ),
            ),
            (
                "release artifact gate bypasses the JVM count source",
                "tools/verify_release_candidate_artifacts.ps1",
                lambda text: text.replace(
                    "$expectedTests = [int]$verificationProperties.JVM_TEST_COUNT",
                    "$expectedTests = 30",
                    1,
                ),
            ),
            (
                "invalid JVM count source",
                "verification.properties",
                lambda text: "JVM_TEST_COUNT=0\n",
            ),
            (
                "release artifact gate drops signer certificate derivation",
                "tools/verify_release_candidate_artifacts.ps1",
                lambda text: text.replace(
                    "keytoolCommand.Source -exportcert -rfc",
                    "keytoolCommand.Source -list -rfc",
                    1,
                ),
            ),
            (
                "local gate can reach the network",
                "tools/verify_local.ps1",
                lambda text: text.replace("'--offline'", "'--refresh-dependencies'", 1),
            ),
            (
                "runnable provider no longer compares the Host signer",
                "tools/build_runnable_provider.ps1",
                lambda text: text.replace(
                    "$hostSigner = Read-CertificateSha256 $resolvedHostApk",
                    "$hostSigner = $pluginSigner",
                    1,
                ),
            ),
            (
                "runnable provider no longer checks the Host version",
                "tools/build_runnable_provider.ps1",
                lambda text: text.replace(
                    "if ($hostVersionCode -lt $requiredHostVersionCode)",
                    "if ($false)",
                    1,
                ),
            ),
            (
                "runnable provider can reach the network",
                "tools/build_runnable_provider.ps1",
                lambda text: text.replace('"--offline"', '"--refresh-dependencies"', 1),
            ),
            (
                "runnable provider can reuse stale outputs",
                "tools/build_runnable_provider.ps1",
                lambda text: text.replace('        ":app:clean"\n', "", 1),
            ),
            (
                "runnable provider drops the strict artifact gate",
                "tools/build_runnable_provider.ps1",
                lambda text: text.replace(
                    '    & (Join-Path $root "tools/verify_release_candidate_artifacts.ps1") `',
                    "    Write-Host 'artifact gate skipped'",
                    1,
                ),
            ),
            (
                "runnable provider receipt drops revision binding",
                "tools/build_runnable_provider.ps1",
                lambda text: text.replace("revision=$sourceRevision", "revision=unknown", 1),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_inputs(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_input_workflows()

        for relative in (
            "protocol/.gitignore",
            "gradle/.gitignore",
            "gradle/wrapper/.gitignore",
        ):
            with self.subTest(label=f"nested ignore: {relative}"), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_inputs(root)
                write_text(root / relative, "*.aar\n*.jar\n")
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaisesRegex(RuntimeError, "Nested Git-ignore"):
                        verifier.verify_input_workflows()


class RepositoryCheckpointTest(unittest.TestCase):
    def test_repository_stays_valid_default_off_and_reports_readiness(self) -> None:
        with mock.patch.object(verifier, "ROOT", SOURCE_ROOT):
            protocol_ready = verifier.verify_protocol()
            vendor_ready = verifier.verify_vendor()
            verifier.verify_manifest()
            verifier.verify_default_off()
            verifier.verify_ci_resilience()
            verifier.verify_localization_workflow()
            verifier.verify_input_workflows()
            verifier.verify_r4_design_records()
            verifier.verify_native_boundary()
            verifier.verify_watchdog_boundary()
            verifier.verify_crash_diagnostic_boundary()
            verifier.verify_descriptor_boundary()
            verifier.verify_native_android_test_boundary()
            verifier.verify_fault_harness_boundary()
            with mock.patch.object(
                sys,
                "argv",
                ["verify_repository.py", "--require-build-ready"],
            ):
                if protocol_ready and vendor_ready:
                    verifier.main()
                else:
                    with self.assertRaisesRegex(RuntimeError, "not ready"):
                        verifier.main()


class NativeBoundaryTest(unittest.TestCase):
    FILES = (
        "ROADMAP-R4.md",
        "README.md",
        "app/proguard-rules.pro",
        "app/src/main/cpp/CMakeLists.txt",
        "app/src/main/cpp/cmake/lua54-sources.cmake",
        "app/src/main/cpp/lua_runtime_jni.cpp",
        "app/src/main/cpp/vendor/lua-5.4.8/src/lstate.c",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntimeBoundaryTest.kt",
        "docs/coroutine-control-boundary.md",
        "docs/native-execution-core.md",
        "docs/ui-toast-v1.md",
    )

    def copy_boundary(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_controlled_print_and_warn_boundary_is_admitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_boundary(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_native_boundary()

    def test_unbounded_or_missing_global_output_bridge_is_rejected(self) -> None:
        mutations = (
            lambda text: text.replace(
                'lua_setglobal(state, "print");',
                'lua_setglobal(state, "unreviewed_print");',
                1,
            ),
            lambda text: text.replace(
                'lua_pushcfunction(state, autojs_console_error);\n    lua_setglobal(state, "warn");',
                'lua_pushcfunction(state, autojs_console_print);\n    lua_setglobal(state, "warn");',
                1,
            ),
            lambda text: text.replace(
                "return emit_autojs_console(state, kStdoutStreamWireCode);",
                "return 0;",
                1,
            ),
            lambda text: text.replace(
                'lua_pushcfunction(state, autojs_console_log);\n    lua_setfield(state, -2, "info");',
                'lua_pushcfunction(state, autojs_console_error);\n    lua_setfield(state, -2, "info");',
                1,
            ),
            lambda text: text.replace("lua_gettop(state) != 0", "false", 1),
            lambda text: text.replace(
                "argument_count < 1 || argument_count > 2",
                "argument_count > 2",
                1,
            ),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                native = root / "app/src/main/cpp/lua_runtime_jni.cpp"
                write_text(native, mutate(native.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_native_boundary()

    def test_unreviewed_capability_registration_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_boundary(root)
            metadata = (
                root
                / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt"
            )
            write_text(
                metadata,
                metadata.read_text("utf-8").replace(
                    "NativeLuaHostCapabilityBridge.MODULE_SNAPSHOT_CAPABILITY,",
                    "NativeLuaHostCapabilityBridge.MODULE_SNAPSHOT_CAPABILITY,\n"
                    "                NativeLuaHostCapabilityBridge.UNREVIEWED_CAPABILITY,",
                    1,
                ),
            )
            with mock.patch.object(verifier, "ROOT", root):
                with self.assertRaisesRegex(RuntimeError, "fixed-shape bridge"):
                    verifier.verify_native_boundary()

    def test_toast_shape_quota_or_acknowledgement_drift_is_rejected(self) -> None:
        mutations = (
            (
                "native byte limit",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace(
                    "constexpr size_t kMaxToastTextBytes = 1024;",
                    "constexpr size_t kMaxToastTextBytes = 2048;",
                    1,
                ),
            ),
            (
                "native call quota",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace(
                    "constexpr uint32_t kMaxToastCallsPerExecution = 4U;",
                    "constexpr uint32_t kMaxToastCallsPerExecution = 8U;",
                    1,
                ),
            ),
            (
                "pre-dispatch quota charge",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace("++control->toast_dispatches;", "", 1),
            ),
            (
                "single native dispatch",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace("CallVoidMethod(", "CallObjectMethod(", 1),
            ),
            (
                "Lua table shape",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace(
                    'lua_setfield(state, -2, "ui");',
                    'lua_setfield(state, -2, "unreviewedUi");',
                    1,
                ),
            ),
            (
                "Kotlin byte limit",
                "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt",
                lambda text: text.replace("const val MAX_TOAST_TEXT_BYTES = 1024", "const val MAX_TOAST_TEXT_BYTES = 2048", 1),
            ),
            (
                "closed acknowledgement",
                "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt",
                lambda text: text.replace("require(accepted.value)", "checkNotNull(accepted)", 1),
            ),
            (
                "JVM grant and denial evidence",
                "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntimeBoundaryTest.kt",
                lambda text: text.replace(
                    "toastCapabilityGrantDenialAndClosedShapesStayDeterministic",
                    "toastBoundaryUnreviewed",
                    1,
                ),
            ),
            (
                "recorded toast artifact identity",
                "docs/ui-toast-v1.md",
                lambda text: text.replace(
                    "ab62c4bb40f77259f7d5f9eaad5ca213a72186ef8dea0897bd491ca4774aab5a",
                    "0" * 64,
                ),
            ),
            (
                "recorded Host boundary",
                "docs/ui-toast-v1.md",
                lambda text: text.replace(
                    "4a9718d63923834c9a99fd70e0cd58c898e138f6",
                    "0" * 40,
                ),
            ),
            (
                "Roadmap toast completion",
                "ROADMAP-R4.md",
                lambda text: text.replace(
                    "- [x] **`toast` 能力 (`ui.toast.v1`)**",
                    "- [ ] **`toast` 能力 (`ui.toast.v1`)**",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_native_boundary()

    def test_coroutine_control_or_inventory_drift_is_rejected(self) -> None:
        mutations = (
            (
                "missing coroutine source",
                "app/src/main/cpp/cmake/lua54-sources.cmake",
                lambda text: text.replace("    src/lcorolib.c\n", "", 1),
            ),
            (
                "unrestricted linit source",
                "app/src/main/cpp/cmake/lua54-sources.cmake",
                lambda text: text.replace("    src/lcorolib.c\n", "    src/lcorolib.c\n    src/linit.c\n", 1),
            ),
            (
                "coroutine library not opened",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace(
                    "        {LUA_COLIBNAME, luaopen_coroutine},\n",
                    "",
                    1,
                ),
            ),
            (
                "allocator breach not sticky",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace("budget->limit_exceeded = true;", "return nullptr;", 1),
            ),
            (
                "sticky OOM result bypass",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace("if (budget.limit_exceeded) {", "if (false) {", 1),
            ),
            (
                "child hook inheritance",
                "app/src/main/cpp/vendor/lua-5.4.8/src/lstate.c",
                lambda text: text.replace("L1->hook = L->hook;", "L1->hook = NULL;", 1),
            ),
            (
                "allocator teardown proof",
                "docs/coroutine-control-boundary.md",
                lambda text: text.replace("accounting_failed == false", "accounting is unknown", 1),
            ),
            (
                "recorded artifact identity",
                "docs/coroutine-control-boundary.md",
                lambda text: text.replace(
                    "244345a1eb0512ca8cb018012614713d43eef3aab500d0086a4d1978199ccaac",
                    "0" * 64,
                ),
            ),
            (
                "Roadmap completion",
                "ROADMAP-R4.md",
                lambda text: text.replace("- [x] **协程库受控引入**", "- [ ] **协程库受控引入**", 1),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_native_boundary()


class DescriptorBoundaryTest(unittest.TestCase):
    FILES = (
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionController.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/BinderLuaHostCapabilityInvoker.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaFileDescriptorLedger.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeExecutionManager.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionControllerTest.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaFileDescriptorLedgerTest.kt",
        "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/BinderLuaHostCapabilityInvokerInstrumentationTest.kt",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt",
    )

    def copy_boundary(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_descriptor_ledger_and_tiny_deadline_boundary_are_admitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_boundary(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_descriptor_boundary()

    def test_descriptor_release_or_timeout_terminal_removal_is_rejected(self) -> None:
        mutations = (
            (
                "incoming source release",
                self.FILES[4],
                lambda text: text.replace("incomingOwnership?.close()", "Unit", 1),
            ),
            (
                "duplicated source release",
                self.FILES[3],
                lambda text: text.replace("ownership.close()", "Unit", 1),
            ),
            (
                "host payload release",
                self.FILES[1],
                lambda text: text.replace("ownerships[index]?.close()", "Unit", 1),
            ),
            (
                "tiny deadline terminal",
                self.FILES[0],
                lambda text: text.replace(
                    "timeout?.let { failure -> deliver { it.onFailed(failure) } }",
                    "Unit",
                    1,
                ),
            ),
            (
                "fault harness incoming source release",
                self.FILES[8],
                lambda text: text.replace("incomingOwnership?.close()", "Unit", 1),
            ),
            (
                "host capability retry",
                self.FILES[1],
                lambda text: text.replace(
                    "val deadlineNanos = deadlineAfter(timeoutMillis)",
                    "broker.invoke(\n"
                    "            LuaRuntimeCodec.encodeHostRequest(request),\n"
                    "            emptyArray<ParcelFileDescriptor>(),\n"
                    "            callback,\n"
                    "        )\n\n"
                    "        val deadlineNanos = deadlineAfter(timeoutMillis)",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_descriptor_boundary()


class NativeAndroidBoundaryTest(unittest.TestCase):
    FILES = (
        "app/build.gradle.kts",
        "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntimeInstrumentationTest.kt",
    )

    def test_provider_enablement_or_service_binding_is_rejected(self) -> None:
        mutations = (
            (
                "provider assertion",
                lambda text: text.replace(
                    "assertFalse(BuildConfig.LUA_PROVIDER_ENABLED)",
                    "assertTrue(BuildConfig.LUA_PROVIDER_ENABLED)",
                    1,
                ),
            ),
            (
                "production bind",
                lambda text: text + "\n// bindService( production provider )\n",
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for relative in self.FILES:
                    destination = root / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(SOURCE_ROOT / relative, destination)
                test_path = root / self.FILES[1]
                write_text(test_path, mutate(test_path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_native_android_test_boundary()

    def test_coroutine_deadline_cancel_yield_or_oom_evidence_drift_is_rejected(self) -> None:
        mutations = (
            (
                "deadline",
                lambda text: text.replace(
                    "coroutineInfiniteLoopHonoursInheritedDeadlineHook",
                    "coroutineDeadlineUnverified",
                    1,
                ),
            ),
            (
                "cancel resume",
                lambda text: text.replace(
                    "local resumed = coroutine.resume(worker)",
                    "local resumed = true",
                ),
            ),
            (
                "yield accounting",
                lambda text: text.replace(
                    "coroutine.yield(index, #retained[index])",
                    "return index",
                    1,
                ),
            ),
            (
                "caught OOM",
                lambda text: text.replace(
                    "if resumed then return 1 end",
                    "error('OOM outcome unverified')",
                    1,
                ),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for relative in self.FILES:
                    destination = root / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(SOURCE_ROOT / relative, destination)
                test_path = root / self.FILES[1]
                write_text(test_path, mutate(test_path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_native_android_test_boundary()

    def test_toast_smoke_or_quota_evidence_drift_is_rejected(self) -> None:
        mutations = (
            (
                "successful fixed-shape smoke",
                lambda text: text.replace(
                    "nativeRunnerMapsTheFixedToastCapabilityWithoutAResultOrRetry",
                    "nativeRunnerToastSmokeRemoved",
                    1,
                ),
            ),
            (
                "four-call quota",
                lambda text: text.replace(
                    "for index = 1, 4 do toast('accepted-' .. index) end",
                    "for index = 1, 5 do toast('accepted-' .. index) end",
                    1,
                ),
            ),
            (
                "fifth call caught by coroutine",
                lambda text: text.replace(
                    "local child = coroutine.create(function() toast('fifth') end)",
                    "local child = coroutine.create(function() return true end)",
                    1,
                ),
            ),
            (
                "oversized text",
                lambda text: text.replace("string.rep('x', 1025)", "string.rep('x', 1024)", 1),
            ),
            (
                "malformed UTF-8",
                lambda text: text.replace("string.char(0xc3, 0x28)", "'valid'", 1),
            ),
            (
                "overlong UTF-8",
                lambda text: text.replace("string.char(0xc0, 0x80)", "'valid'", 1),
            ),
            (
                "surrogate UTF-8",
                lambda text: text.replace("string.char(0xed, 0xa0, 0x80)", "'valid'", 1),
            ),
            (
                "out-of-range UTF-8",
                lambda text: text.replace(
                    "string.char(0xf4, 0x90, 0x80, 0x80)",
                    "'valid'",
                    1,
                ),
            ),
            (
                "truncated UTF-8",
                lambda text: text.replace("string.char(0xf0, 0x90)", "'valid'", 1),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for relative in self.FILES:
                    destination = root / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(SOURCE_ROOT / relative, destination)
                test_path = root / self.FILES[1]
                write_text(test_path, mutate(test_path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_native_android_test_boundary()


class FaultHarnessBoundaryTest(unittest.TestCase):
    FILES = (
        "README.md",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/debug/AndroidManifest.xml",
        "app/src/main/cpp/CMakeLists.txt",
        "app/src/main/cpp/lua_runtime_jni.cpp",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/NativeLuaFaults.kt",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultPeerService.kt",
        "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaRuntimeFaultRecoveryInstrumentationTest.kt",
        "tools/verify_fault_harness_artifacts.ps1",
    )

    def copy_boundary(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_fault_harness_is_opt_in_isolated_and_uses_production_sessions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_boundary(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_fault_harness_boundary()

    def test_export_or_production_route_bypass_is_rejected(self) -> None:
        mutations = (
            (
                "exported service",
                "app/src/debug/AndroidManifest.xml",
                lambda text: text.replace('android:exported="false"', 'android:exported="true"'),
            ),
            (
                "manager bypass",
                "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt",
                lambda text: text.replace("executionManager.create(", "bypass.create(", 1),
            ),
            (
                "peer process isolation",
                "app/src/debug/AndroidManifest.xml",
                lambda text: text.replace(
                    'android:process=":lua_fault_peer"',
                    'android:process=":lua_runtime"',
                    1,
                ),
            ),
            (
                "peer kill control",
                "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultPeerService.kt",
                lambda text: text.replace(
                    "mainHandler.post { Process.killProcess(Process.myPid()) }",
                    "Unit",
                    1,
                ),
            ),
            (
                "blocked pipe evidence",
                "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/"
                "LuaRuntimeFaultRecoveryInstrumentationTest.kt",
                lambda text: text.replace("ParcelFileDescriptor.createPipe()", "emptyArray()", 1),
            ),
            (
                "release peer exclusion",
                "tools/verify_fault_harness_artifacts.ps1",
                lambda text: text.replace(
                    ".Contains('LuaRuntimeFault')",
                    ".Contains('LuaRuntimeFaultService')",
                    1,
                ),
            ),
            (
                "release audit intermediate",
                "app/build.gradle.kts",
                lambda text: text.replace(
                    '"packageReleaseUniversalApk",',
                    '"packageReleaseResources",',
                    1,
                ),
            ),
            (
                "unguarded native entry",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace(
                    "#if defined(AUTOJS_LUA_DEBUG_FAULT_HARNESS)\nextern \"C\" JNIEXPORT void JNICALL",
                    "extern \"C\" JNIEXPORT void JNICALL",
                    1,
                ),
            ),
            (
                "missing published release checklist",
                "README.md",
                lambda text: text.replace(
                    "RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS",
                    "UNVERIFIED_FAULT_HARNESS",
                    1,
                ),
            ),
            (
                "stale fault intermediates",
                "README.md",
                lambda text: text.replace(
                    "    '-Pautojs.lua.faultHarness.enabled=true'\n"
                    "    '--rerun-tasks'\n",
                    "    '-Pautojs.lua.faultHarness.enabled=true'\n",
                    1,
                ),
            ),
            (
                "unclean fault intermediates",
                "README.md",
                lambda text: text.replace(
                    "$faultArgs = @(\n    ':app:clean'\n",
                    "$faultArgs = @(\n",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_fault_harness_boundary()


class HostLifecycleBoundaryTest(unittest.TestCase):
    FILES = (
        "settings.gradle.kts",
        "ROADMAP-R4.md",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "host-lifecycle-test/build.gradle.kts",
        "host-lifecycle-test/src/main/AndroidManifest.xml",
        "host-lifecycle-test/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/host/"
        "lifecycle/test/LuaHostLifecycleInstrumentation.kt",
        "docs/host-lifecycle-matrix.md",
        "tools/verify_host_lifecycle_matrix.ps1",
    )

    def copy_boundary(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_host_lifecycle_matrix_targets_real_host_and_emulators_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_boundary(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_host_lifecycle_boundary()

    def test_target_package_emulator_guard_pid_or_watchdog_proof_drift_is_rejected(self) -> None:
        mutations = (
            (
                "module inclusion",
                "settings.gradle.kts",
                lambda text: text.replace('include(":host-lifecycle-test")', "", 1),
            ),
            (
                "real Host target",
                "host-lifecycle-test/src/main/AndroidManifest.xml",
                lambda text: text.replace(
                    'android:targetPackage="org.autojs.autojs6"',
                    'android:targetPackage="synthetic.host"',
                    1,
                ),
            ),
            (
                "stale watchdog window",
                "host-lifecycle-test/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/host/"
                "lifecycle/test/LuaHostLifecycleInstrumentation.kt",
                lambda text: text.replace(
                    "const val STALE_WATCHDOG_PROOF_MILLIS = 7_000L",
                    "const val STALE_WATCHDOG_PROOF_MILLIS = 1_000L",
                    1,
                ),
            ),
            (
                "same Binder survival",
                "host-lifecycle-test/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/host/"
                "lifecycle/test/LuaHostLifecycleInstrumentation.kt",
                lambda text: text.replace(
                    "providerBinder.isBinderAlive && providerBinder.pingBinder()",
                    "true",
                    1,
                ),
            ),
            (
                "emulator serial guard",
                "tools/verify_host_lifecycle_matrix.ps1",
                lambda text: text.replace(
                    "$Serial -notmatch '^emulator-[0-9]+$'",
                    "$false",
                    1,
                ),
            ),
            (
                "qemu property guard",
                "tools/verify_host_lifecycle_matrix.ps1",
                lambda text: text.replace("$isQemu -ne '1'", "$false", 1),
            ),
            (
                "runtime PID continuity",
                "tools/verify_host_lifecycle_matrix.ps1",
                lambda text: text.replace(
                    "$afterPid -ne $ExpectedRuntimePid",
                    "$false",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_host_lifecycle_boundary()


class WatchdogBoundaryTest(unittest.TestCase):
    WATCHDOG_FILES = (
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdog.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaWatchdogEventLogging.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionController.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeExecutionManager.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeProcessWatchdog.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdogTest.kt",
        "docs/watchdog-event-logging.md",
        "ROADMAP-R4.md",
    )

    def copy_watchdog_boundary(self, root: Path) -> None:
        for relative in self.WATCHDOG_FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_watchdog_boundary_is_token_bound_and_fail_stop(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_watchdog_boundary(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_watchdog_boundary()

    def test_stale_token_or_structured_logger_removal_is_rejected(self) -> None:
        mutations = (
            (
                "stale token",
                self.WATCHDOG_FILES[0],
                lambda text: text.replace("execution.token !== token", "false"),
            ),
            (
                "logger call",
                self.WATCHDOG_FILES[0],
                lambda text: text.replace("eventLogger.logFailStop(reason)", "Unit", 1),
            ),
            (
                "reason tag",
                self.WATCHDOG_FILES[1],
                lambda text: text.replace("stop_cleanup_expired", "deadline_cleanup_expired", 1),
            ),
            (
                "Android adapter",
                self.WATCHDOG_FILES[4],
                lambda text: text.replace(
                    "Log.e(LuaWatchdogLogContract.LOGCAT_TAG, LuaWatchdogLogContract.message(reason))",
                    "Unit",
                    1,
                ),
            ),
            (
                "production injection",
                self.WATCHDOG_FILES[3],
                lambda text: text.replace(
                    "eventLogger = AndroidLuaWatchdogEventLogger",
                    "eventLogger = LuaWatchdogEventLogger.NONE",
                    1,
                ),
            ),
            (
                "Roadmap completion",
                self.WATCHDOG_FILES[7],
                lambda text: text.replace(
                    "- [x] **watchdog 事件可追溯**",
                    "- [ ] **watchdog 事件可追溯**",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_watchdog_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_watchdog_boundary()


class CrashDiagnosticBoundaryTest(unittest.TestCase):
    FILES = (
        "app/src/main/cpp/lua_runtime_jni.cpp",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/diagnostic/LuaCrashDiagnostic.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/diagnostic/LuaRuntimeCrashDiagnostics.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionController.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdog.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeExecutionManager.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/diagnostic/LuaCrashDiagnosticTest.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionControllerTest.kt",
        "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaRuntimeFaultRecoveryInstrumentationTest.kt",
        "docs/crash-diagnostic-v1.md",
        "ROADMAP-R4.md",
    )

    def copy_boundary(self, root: Path) -> None:
        for relative in self.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_current_private_atomic_diagnostic_lifecycle_is_admitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_boundary(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_crash_diagnostic_boundary()

    def test_content_retention_or_lifecycle_bypass_is_rejected(self) -> None:
        mutations = (
            (
                "source retention",
                self.FILES[3],
                lambda text: text.replace(
                    "private val directory = File(root, DIRECTORY_NAME)",
                    "private val sourceUtf8 = ByteArray(0)\n"
                    "    private val directory = File(root, DIRECTORY_NAME)",
                    1,
                ),
            ),
            (
                "non-atomic finish",
                self.FILES[3],
                lambda text: text.replace("atomicFile.finishWrite(output)", "output.close()", 1),
            ),
            (
                "full digest retention",
                self.FILES[4],
                lambda text: text.replace(
                    "copyOf(LuaCrashDiagnostic.SOURCE_HASH_PREFIX_BYTES)",
                    "copyOf(32)",
                    1,
                ),
            ),
            (
                "unconditional runtime marker",
                self.FILES[4],
                lambda text: text.replace(
                    "if (present && LAST_ABNORMAL_TERMINATION_FLAG !in capabilities)",
                    "if (LAST_ABNORMAL_TERMINATION_FLAG !in capabilities)",
                    1,
                ),
            ),
            (
                "runner not bracketed",
                self.FILES[5],
                lambda text: text.replace(
                    "crashDiagnostics.nativeExecutionStarted()",
                    "Unit",
                    1,
                ),
            ),
            (
                "watchdog not observed",
                self.FILES[7],
                lambda text: text.replace(
                    "terminationObserver = LuaRuntimeCrashDiagnostics",
                    "terminationObserver = LuaProcessTerminationObserver.NONE",
                    1,
                ),
            ),
            (
                "missing recovery assertion",
                self.FILES[12],
                lambda text: text.replace(
                    "assertPersistedCrashDiagnostic(",
                    "skipPersistedCrashDiagnostic(",
                ),
            ),
            (
                "missing device completion evidence",
                self.FILES[14],
                lambda text: text.replace(
                    "- [x] **崩溃诊断落盘**",
                    "- [ ] **崩溃诊断落盘**",
                    1,
                ),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_boundary(root)
                path = root / relative
                write_text(path, mutate(path.read_text("utf-8")))
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_crash_diagnostic_boundary()


class WrapperInputTest(unittest.TestCase):
    WRAPPER_FILES = (
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
    )

    def copy_wrapper(self, root: Path) -> None:
        for relative in self.WRAPPER_FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE_ROOT / relative, destination)

    def test_wrapper_scripts_properties_and_jar_are_pinned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_wrapper(root)
            with mock.patch.object(verifier, "ROOT", root):
                verifier.verify_wrapper()

    def test_wrapper_property_or_jar_drift_is_rejected(self) -> None:
        mutations = (
            (
                "distribution checksum",
                "gradle/wrapper/gradle-wrapper.properties",
                lambda path: path.write_text(
                    path.read_text("utf-8").replace(
                        verifier.GRADLE_DISTRIBUTION_SHA256,
                        "0" * 64,
                    ),
                    encoding="utf-8",
                ),
            ),
            (
                "wrapper jar",
                "gradle/wrapper/gradle-wrapper.jar",
                lambda path: path.write_bytes(path.read_bytes() + b"drift"),
            ),
        )
        for label, relative, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_wrapper(root)
                mutate(root / relative)
                with mock.patch.object(verifier, "ROOT", root):
                    with self.assertRaises(RuntimeError):
                        verifier.verify_wrapper()


if __name__ == "__main__":
    unittest.main()
