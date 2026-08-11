from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
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
    write_text(
        root / "gradle.properties",
        "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
        "autojs.lua.faultHarness.enabled=false\n",
    )
    write_text(
        root / ".github/workflows/ci.yml",
        """jobs:
  build:
    steps:
      - run: >-
          ./gradlew
          :app:assembleDebug
          -Pautojs.lua.native.enabled=true
          -Pautojs.lua.provider.enabled=false
      - run: ./tools/verify_debug_artifacts.ps1 -ExpectedTests 42
      - run: python tools/verify_repository.py --require-build-ready --github-output
""",
    )
    write_text(
        root
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt",
        "override fun onBind(intent: Intent?): IBinder = binder\n",
    )


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
            "autojs.lua.faultHarness.enabled=false\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.provider.enabled=false\nautojs.lua.faultHarness.enabled=false\n",
            "autojs.lua.native.enabled=false\\\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=false\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=false\n"
            "systemProp.org.gradle.project.autojs.lua.provider.enabled=true\n",
            "autojs.lua.native.enabled=false\nautojs.lua.provider.enabled=false\n"
            "autojs.lua.faultHarness.enabled=true\n",
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
            lambda text: text.replace("          ./gradlew\n", "          gradle\n"),
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


class InputWorkflowTest(unittest.TestCase):
    INPUT_FILES = (
        ".gitattributes",
        ".gitignore",
        "build.gradle.kts",
        "app/build.gradle.kts",
        "tools/stage_protocol_artifacts.ps1",
        "tools/stage_lua_source.ps1",
        "tools/verify_debug_artifacts.ps1",
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
                "debug artifact gate stale JVM count",
                "tools/verify_debug_artifacts.ps1",
                lambda text: text.replace("[int] $ExpectedTests = 42", "[int] $ExpectedTests = 30"),
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
            verifier.verify_input_workflows()
            verifier.verify_native_boundary()
            verifier.verify_watchdog_boundary()
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


class FaultHarnessBoundaryTest(unittest.TestCase):
    FILES = (
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/debug/AndroidManifest.xml",
        "app/src/main/cpp/CMakeLists.txt",
        "app/src/main/cpp/lua_runtime_jni.cpp",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/NativeLuaFaults.kt",
        "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt",
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
                "unguarded native entry",
                "app/src/main/cpp/lua_runtime_jni.cpp",
                lambda text: text.replace(
                    "#if defined(AUTOJS_LUA_DEBUG_FAULT_HARNESS)\nextern \"C\" JNIEXPORT void JNICALL",
                    "extern \"C\" JNIEXPORT void JNICALL",
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


class WatchdogBoundaryTest(unittest.TestCase):
    WATCHDOG_FILES = (
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdog.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionController.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeExecutionManager.kt",
        "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeProcessWatchdog.kt",
        "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdogTest.kt",
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

    def test_removing_stale_token_check_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_watchdog_boundary(root)
            path = root / self.WATCHDOG_FILES[0]
            write_text(path, path.read_text("utf-8").replace("execution.token !== token", "false"))
            with mock.patch.object(verifier, "ROOT", root):
                with self.assertRaisesRegex(RuntimeError, "execution.token !== token"):
                    verifier.verify_watchdog_boundary()


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
