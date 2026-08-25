#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
PROTOCOL_MODULES = {
    "common-plugin-api.aar": ":plugin-api:common-plugin-api",
    "protocol-wire-api.aar": ":plugin-api:protocol-wire-api",
    "lua-runtime-api.aar": ":plugin-api:lua-runtime-api",
}
LUA_SHA256 = "4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae"
GRADLE_DISTRIBUTION_SHA256 = "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
GRADLE_WRAPPER_JAR_SHA256 = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
LOWER_SHA256 = re.compile(r"[0-9a-f]{64}")
FULL_GIT_REVISION = re.compile(r"[0-9a-f]{40}")
PROTOCOL_LOCK_KEYS = {
    "schemaVersion",
    "status",
    "sourceRepository",
    "sourceRevision",
    "artifacts",
}
PROTOCOL_ARTIFACT_KEYS = {"file", "sourceModule", "sha256"}
VENDOR_LOCK_KEYS = {
    "schemaVersion",
    "status",
    "runtimeSlot",
    "name",
    "version",
    "archive",
    "sourceUrl",
    "sha256",
    "sourceDirectory",
    "sourceTreeRoot",
    "sourceFileCount",
    "sourceTreeSha256",
    "vendoredAt",
}


def expected_jvm_test_count() -> int:
    path = ROOT / "verification.properties"
    require(path.is_file() and not path.is_symlink(), "Missing regular verification.properties")
    values: dict[str, str] = {}
    for raw_line in path.read_text("utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("=", 1)
        require(len(parts) == 2 and parts[0], f"Malformed verification property: {line}")
        key, value = parts[0].strip(), parts[1].strip()
        require(key not in values, f"Duplicate verification property: {key}")
        values[key] = value
    require(set(values) == {"JVM_TEST_COUNT"}, "Verification property inventory drift")
    count = values["JVM_TEST_COUNT"]
    require(re.fullmatch(r"[1-9][0-9]{0,5}", count) is not None, "Invalid JVM_TEST_COUNT")
    return int(count)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def reject_duplicate_object_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        require(key not in value, f"Duplicate JSON key: {key}")
        value[key] = item
    return value


def load_json_strict(path: Path) -> object:
    try:
        return json.loads(
            path.read_text("utf-8"),
            object_pairs_hook=reject_duplicate_object_pairs,
        )
    except json.JSONDecodeError as error:
        raise RuntimeError(f"Invalid JSON: {path}") from error


def require_exact_keys(value: object, expected: set[str], label: str) -> dict[str, object]:
    require(isinstance(value, dict), f"{label} must be a JSON object")
    actual = set(value)
    require(actual == expected, f"{label} key inventory drift: {sorted(actual)}")
    return value


def require_schema_one(value: object, label: str) -> None:
    require(type(value) is int and value == 1, f"{label} schema drift")


def is_full_git_revision(value: object) -> bool:
    return (
        isinstance(value, str)
        and FULL_GIT_REVISION.fullmatch(value) is not None
        and value != "0" * 40
    )


def require_utc_timestamp(value: object, label: str) -> None:
    require(isinstance(value, str) and bool(value), f"{label} must be a non-empty timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise RuntimeError(f"{label} is not ISO-8601") from error
    require(parsed.tzinfo is not None, f"{label} must include a timezone")
    require(parsed.utcoffset() == timezone.utc.utcoffset(parsed), f"{label} must be UTC")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_wrapper() -> None:
    script_tokens = {
        ROOT / "gradlew": "gradle/wrapper/gradle-wrapper.jar",
        ROOT / "gradlew.bat": r"gradle\wrapper\gradle-wrapper.jar",
    }
    for path, token in script_tokens.items():
        require(path.is_file() and not path.is_symlink(), f"Missing regular wrapper script: {path.name}")
        require(token in path.read_text("utf-8"), f"Wrapper script drift: {path.name}")

    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    require(jar.is_file() and not jar.is_symlink(), "Missing regular Gradle wrapper JAR")
    require(sha256(jar) == GRADLE_WRAPPER_JAR_SHA256, "Gradle wrapper JAR digest drift")

    properties_path = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    require(
        properties_path.is_file() and not properties_path.is_symlink(),
        "Missing regular Gradle wrapper properties",
    )
    properties: dict[str, str] = {}
    for raw_line in properties_path.read_text("utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        require("=" in line, "Malformed Gradle wrapper property")
        key, value = line.split("=", 1)
        require(key not in properties, f"Duplicate Gradle wrapper property: {key}")
        properties[key] = value
    require(
        properties
        == {
            "distributionBase": "GRADLE_USER_HOME",
            "distributionPath": "wrapper/dists",
            "distributionSha256Sum": GRADLE_DISTRIBUTION_SHA256,
            "distributionUrl": r"https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip",
            "networkTimeout": "10000",
            "retries": "0",
            "retryBackOffMs": "500",
            "validateDistributionUrl": "true",
            "zipStoreBase": "GRADLE_USER_HOME",
            "zipStorePath": "wrapper/dists",
        },
        "Gradle wrapper property inventory drift",
    )


def verify_protocol() -> bool:
    protocol_root = ROOT / "protocol"
    lock = require_exact_keys(
        load_json_strict(protocol_root / "protocol-artifacts.lock.json"),
        PROTOCOL_LOCK_KEYS,
        "Protocol lock",
    )
    require_schema_one(lock["schemaVersion"], "Protocol lock")
    require(type(lock["sourceRepository"]) is str, "Protocol source repository must be text")
    require(lock["sourceRepository"] == "AutoJs6", "Protocol source repository drift")
    entries = lock["artifacts"]
    require(isinstance(entries, list) and len(entries) == len(PROTOCOL_MODULES), "Protocol artifact count drift")
    for entry in entries:
        require_exact_keys(entry, PROTOCOL_ARTIFACT_KEYS, "Protocol artifact entry")
        require(type(entry["file"]) is str, "Protocol artifact file name must be text")
        require(type(entry["sourceModule"]) is str, "Protocol source module must be text")
    by_file = {entry["file"]: entry for entry in entries}
    require(len(by_file) == len(entries), "Protocol artifact names are duplicated")
    require(set(by_file) == set(PROTOCOL_MODULES), "Protocol artifact inventory drift")
    for file_name, source_module in PROTOCOL_MODULES.items():
        require(
            by_file[file_name].get("sourceModule") == source_module,
            f"Protocol source-module mapping drift: {file_name}",
        )
    present_artifacts = {
        path.name
        for path in protocol_root.glob("*.aar")
        if path.is_file() or path.is_symlink()
    }
    if lock["status"] == "not-staged":
        require(lock["sourceRevision"] is None, "Unstaged protocol lock has a revision")
        require(all(entry["sha256"] is None for entry in entries), "Unstaged protocol lock has digests")
        require(not present_artifacts, "Unpinned protocol AAR found")
        return False
    require(lock["status"] == "staged", "Unknown protocol lock status")
    require(
        is_full_git_revision(lock["sourceRevision"]),
        "Staged protocol lock requires a lowercase full Git revision",
    )
    require(present_artifacts == set(PROTOCOL_MODULES), "Staged protocol AAR inventory drift")
    for entry in entries:
        artifact = protocol_root / entry["file"]
        require(artifact.is_file(), f"Missing protocol artifact: {entry['file']}")
        require(not artifact.is_symlink(), f"Protocol artifact must not be a symlink: {entry['file']}")
        require(
            isinstance(entry["sha256"], str) and LOWER_SHA256.fullmatch(entry["sha256"]) is not None,
            f"Invalid protocol SHA-256: {entry['file']}",
        )
        require(sha256(artifact) == entry["sha256"], f"Protocol digest mismatch: {entry['file']}")
    return True


def source_tree_fingerprint(tree_root: Path) -> tuple[int, str]:
    require(tree_root.is_dir() and not tree_root.is_symlink(), f"Vendored source tree is invalid: {tree_root}")
    candidates = sorted(tree_root.rglob("*"))
    require(
        not any(path.is_symlink() for path in candidates),
        f"Vendored source tree contains a symlink: {tree_root}",
    )
    files = [path for path in candidates if path.is_file()]
    require(files, f"Vendored source tree is empty: {tree_root}")
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(tree_root).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256(path).encode("ascii"))
        digest.update(b"\n")
    return len(files), digest.hexdigest()


def load_vendor_lock() -> dict[str, object]:
    vendor_root = ROOT / "app/src/main/cpp/vendor"
    lock = require_exact_keys(
        load_json_strict(vendor_root / "vendor-lock.json"),
        VENDOR_LOCK_KEYS,
        "Lua vendor lock",
    )
    require_schema_one(lock["schemaVersion"], "Lua vendor lock")
    require(lock["name"] == "PUC Lua", "Lua vendor name drift")
    require(lock["version"] == "5.4.8", "Lua version drift")
    require(lock["runtimeSlot"] == "lua54", "Lua runtime slot drift")
    require(lock["archive"] == "lua-5.4.8.tar.gz", "Lua archive name drift")
    require(lock["sourceUrl"] == "https://www.lua.org/ftp/lua-5.4.8.tar.gz", "Lua source URL drift")
    require(lock["sha256"] == LUA_SHA256, "Lua archive digest drift")
    require(lock["sourceDirectory"] == "lua-5.4.8", "Lua source-directory drift")
    require(
        lock["sourceTreeRoot"] == f"{lock['sourceDirectory']}/src",
        "Lua source-tree root drift",
    )
    return lock


def vendor_source_tree(lock: dict[str, object]) -> Path:
    source_tree_root = ROOT / "app/src/main/cpp/vendor" / str(lock["sourceTreeRoot"])
    resolved_vendor_root = (ROOT / "app/src/main/cpp/vendor").resolve()
    resolved_source_tree = source_tree_root.resolve()
    require(
        resolved_source_tree.parent == (resolved_vendor_root / str(lock["sourceDirectory"])).resolve(),
        "Lua source-tree provenance escaped its locked source directory",
    )
    return source_tree_root


def verify_vendor() -> bool:
    lock = load_vendor_lock()
    source_tree_root = vendor_source_tree(lock)
    sentinel = source_tree_root / "lapi.c"
    if lock["status"] == "not-vendored":
        require(not source_tree_root.exists(), "Lua source tree exists without a completed vendor lock")
        require(lock["sourceFileCount"] is None, "Unvendored Lua lock has a source file count")
        require(lock["sourceTreeSha256"] is None, "Unvendored Lua lock has a source-tree digest")
        require(lock["vendoredAt"] is None, "Unvendored Lua lock has a vendor timestamp")
        return False
    require(lock["status"] == "vendored", "Unknown Lua vendor lock status")
    require(sentinel.is_file(), "Vendored Lua source is incomplete")
    require_utc_timestamp(lock["vendoredAt"], "Lua vendor timestamp")
    file_count, tree_sha256 = source_tree_fingerprint(source_tree_root)
    require(
        type(lock["sourceFileCount"]) is int
        and lock["sourceFileCount"] > 0
        and lock["sourceFileCount"] == file_count,
        "Vendored Lua source-file count mismatch",
    )
    require(
        isinstance(lock["sourceTreeSha256"], str)
        and LOWER_SHA256.fullmatch(lock["sourceTreeSha256"]) is not None,
        "Vendored Lua source-tree SHA-256 is invalid",
    )
    require(tree_sha256 == lock["sourceTreeSha256"], "Vendored Lua source-tree digest mismatch")
    return True


def verify_manifest() -> None:
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    require(application is not None, "Application manifest entry is missing")
    services = {service.get(ANDROID + "name"): service for service in application.findall("service")}
    expected = {
        ".service.LuaPluginInfoService": "org.autojs.plugin.INFO",
        ".service.LuaRuntimeService": "org.autojs.plugin.lua.RUNTIME",
    }
    require(set(services) == set(expected), "Unexpected INFO/RUNTIME service inventory")
    for name, action in expected.items():
        service = services[name]
        require(service.get(ANDROID + "process") == ":lua_runtime", f"{name} process drift")
        require(service.get(ANDROID + "exported") == "true", f"{name} must be exported")
        require(
            service.get(ANDROID + "permission") == "org.autojs.permission.PLUGIN",
            f"{name} permission drift",
        )
        require(
            service.get(ANDROID + "enabled") == "@bool/lua_runtime_provider_enabled",
            f"{name} must stay gate-controlled",
        )
        actions = {
            node.get(ANDROID + "name")
            for intent_filter in service.findall("intent-filter")
            for node in intent_filter.findall("action")
        }
        require(actions == {action}, f"{name} action drift")
        metadata = {
            node.get(ANDROID + "name"): node.get(ANDROID + "value")
            for node in service.findall("meta-data")
        }
        require(
            metadata == {"requiresHostVersion": "@string/lua_runtime_requires_host_version"},
            f"{name} host-version metadata drift",
        )


def parse_default_off_flags(properties: str) -> dict[str, str]:
    required_flags = {
        "autojs.lua.native.enabled",
        "autojs.lua.provider.enabled",
        "autojs.lua.faultHarness.enabled",
        "autojs.lua.releaseCandidate.enabled",
    }
    parsed_flags: dict[str, str] = {}
    reference_counts = {key: 0 for key in required_flags}
    patterns = {
        key: re.compile(
            rf"{re.escape(key)}(?:(?:[ \t]*[=:][ \t]*)|(?:[ \t]+))(.*)",
        )
        for key in required_flags
    }
    for raw_line in properties.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        require("\\" not in line, "Escaped or continued Gradle properties are not admitted")
        for key, pattern in patterns.items():
            reference_counts[key] += line.count(key)
            match = pattern.fullmatch(line)
            if match is None:
                continue
            require(key not in parsed_flags, f"Duplicate default-off property: {key}")
            parsed_flags[key] = match.group(1).strip().lower()
    require(set(parsed_flags) == required_flags, "Default-off property inventory drift")
    require(
        all(reference_counts[key] == 1 for key in required_flags),
        "Indirect or repeated default-off property reference",
    )
    require(all(value == "false" for value in parsed_flags.values()), "Lua runtime is not default-off")
    return parsed_flags


def ci_gradle_property_values(ci: str, key: str) -> list[str]:
    active_lines = [
        line
        for line in ci.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    active = "\n".join(active_lines)
    pattern = re.compile(
        rf"(?<![A-Za-z0-9_.-])-P{re.escape(key)}[ \t]*=[ \t]*([^\s#'\"\\]+)",
    )
    values = [match.group(1) for match in pattern.finditer(active)]
    require(
        active.count(key) == len(values),
        f"CI contains an unparseable or indirect Gradle property reference: {key}",
    )
    return values


def verify_default_off() -> None:
    expected_jvm_test_count()
    parse_default_off_flags((ROOT / "gradle.properties").read_text("utf-8"))

    ci = (ROOT / ".github/workflows/ci.yml").read_text("utf-8")
    require(
        ci_gradle_property_values(ci, "autojs.lua.provider.enabled") == ["false"],
        "Scaffold CI must not enable discovery before the execution gate",
    )
    require(
        ci_gradle_property_values(ci, "autojs.lua.native.enabled") == ["true"],
        "Post-intake CI must compile the pinned native scaffold exactly once",
    )
    require(
        ci_gradle_property_values(ci, "autojs.lua.faultHarness.enabled") == [],
        "CI must not enable the device-only native fault harness",
    )
    require(
        ci_gradle_property_values(ci, "autojs.lua.releaseCandidate.enabled") == [],
        "CI must not enable signed release-candidate mode",
    )
    require(
        ci.count("python tools/verify_repository.py --require-build-ready --github-output") == 1,
        "CI must fail closed when immutable inputs regress",
    )
    require(
        ci.count("if ./gradlew \\") == 1
        and "gradle-version:" not in ci,
        "CI must execute the repository-owned Gradle wrapper",
    )
    require(
        ci.count("./tools/verify_debug_artifacts.ps1 -BuildToolsVersion 36.0.0") == 1
        and "-ExpectedTests" not in ci,
        "CI must derive the JVM test count from verification.properties",
    )
    service = (ROOT / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt").read_text("utf-8")
    require(
        "override fun onBind(intent: Intent?): IBinder = binder" in service,
        "Runtime service no longer supports actionless explicit binding",
    )


def verify_ci_resilience() -> None:
    ci = (ROOT / ".github/workflows/ci.yml").read_text("utf-8")
    require_tokens(
        ci,
        (
            "android-actions/setup-android@v4",
            'packages: ""',
            "actions/cache@v4",
            "/usr/local/lib/android/sdk/platforms/android-36",
            "/usr/local/lib/android/sdk/build-tools/36.0.0",
            "/usr/local/lib/android/sdk/ndk/28.2.13676358",
            "/usr/local/lib/android/sdk/cmake/3.22.1",
            "android-sdk-${{ runner.os }}-api36-bt36.0.0-ndk28.2.13676358-cmake3.22.1",
            "for attempt in 1 2 3; do",
            'if [ "$attempt" -eq 3 ]; then',
            'echo "sdkmanager failed after two retries" >&2',
            "sleep $((attempt * 15))",
            "gradle/actions/setup-gradle@v4",
            "for build_attempt in 1 2 3; do",
            "if ./gradlew \\",
            'if [ "$build_attempt" -eq 3 ]; then',
            'echo "Gradle build failed after two retries" >&2',
            "sleep $((build_attempt * 15))",
        ),
        "CI Android SDK cache and retry boundary",
    )
    require(
        ci.count("sdkmanager \\") == 1
        and ci.count("for attempt in 1 2 3; do") == 1
        and ci.count("if ./gradlew \\") == 1
        and ci.count("for build_attempt in 1 2 3; do") == 1,
        "CI must have exactly one bounded SDK loop and one bounded Gradle loop",
    )


def require_tokens(text: str, tokens: tuple[str, ...], label: str) -> None:
    for token in tokens:
        require(token in text, f"{label} drift: {token}")


def verify_localization_workflow() -> None:
    generator = ROOT / "tools/generate_localized_content.py"
    require(
        generator.is_file() and not generator.is_symlink(),
        "Missing regular localized-content generator",
    )
    generator_text = generator.read_text("utf-8")
    require_tokens(
        generator_text,
        (
            "object_pairs_hook=reject_duplicate_pairs",
            "The localization workflow must retain exactly ten locale slots",
            "English and zh-CN must be the first active locales",
            "Only active locales may have source directories; planned locales must remain source-free",
            "Translated Markdown structure or protected literal drift",
            'config["humanReviewed"] is True',
            "PLACEHOLDER_MARKERS",
            "actual_outputs == expected_outputs",
            "Generated content drift:",
            "LOCALIZED_CONTENT_OK",
        ),
        "Localized-content generator",
    )
    try:
        completed = subprocess.run(
            [
                sys.executable,
                str(generator),
                "--check",
                "--root",
                str(ROOT),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Localized-content check could not complete") from error
    detail = (completed.stderr or completed.stdout).strip()
    require(completed.returncode == 0, f"Localized-content check failed: {detail}")
    require(
        completed.stdout.strip()
        == "LOCALIZED_CONTENT_OK active=2 planned=8 artifacts=6 mode=check",
        "Localized-content success receipt drift",
    )

    manifest = require_exact_keys(
        load_json_strict(ROOT / "localization/locales.json"),
        {
            "schemaVersion",
            "sourceLocale",
            "localeSlots",
            "activeLocales",
            "plannedLocales",
            "androidStrings",
        },
        "Locale manifest",
    )
    require_schema_one(manifest["schemaVersion"], "Locale manifest")
    require(
        list(manifest["activeLocales"]) == ["en", "zh-CN"]
        and len(manifest["plannedLocales"]) == 8,
        "Reviewed active/planned locale baseline drift",
    )
    require_tokens(
        (ROOT / "README.md").read_text("utf-8"),
        (
            "Generated from localization/source/en/README.md",
            "[English](README.md) | [简体中文](README.zh-CN.md)",
            "python tools/generate_localized_content.py --check",
        ),
        "Generated English README",
    )
    require_tokens(
        (ROOT / "README.zh-CN.md").read_text("utf-8"),
        (
            "Generated from localization/source/zh-CN/README.md",
            "其余八个槽位在获得真实翻译前没有源目录",
        ),
        "Generated Simplified Chinese README",
    )


def verify_r4_design_records() -> None:
    pcall_decision = (ROOT / "docs/pcall-boundary-decision.md").read_text("utf-8")
    require_tokens(
        pcall_decision,
        (
            "Status: **REJECTED FOR R4**",
            'remove_global(state, "pcall")',
            'remove_global(state, "xpcall")',
            "TerminationReason",
            "control-plane interruption to be non-catchable",
            "private light-userdata sentinel",
            "lua_pcallk",
            "controlled coroutine",
            "resume, yield, deadline, cancellation, and OOM",
            "Nested protected calls",
            "## Reconsideration gate",
            "repeated catches cannot defer termination",
        ),
        "R4 pcall/xpcall decision record",
    )

    result_design = (ROOT / "docs/result-model-v2.md").read_text("utf-8")
    protocol_lock = require_exact_keys(
        load_json_strict(ROOT / "protocol/protocol-artifacts.lock.json"),
        PROTOCOL_LOCK_KEYS,
        "Protocol lock",
    )
    source_revision = protocol_lock["sourceRevision"]
    require(
        isinstance(source_revision, str) and source_revision in result_design,
        "Result V2 design does not identify the frozen protocol revision",
    )
    require_tokens(
        result_design,
        (
            "Status: **DESIGN ONLY — NOT IMPLEMENTED**",
            "Frozen V1 remains unchanged",
            "SCHEMA_RESULT_V2",
            "result.model.v2",
            "LuaProtocolVersion(1, 1)",
            "requiredCapabilities",
            "ILuaExecutionCallback.onCompleted",
            "ParcelFileDescriptor[] descriptors",
            "zero returns",
            "one `nil`",
            "MAX_VALUE_DEPTH = 32",
            "MAX_VALUE_NODES = 4_096",
            "MAX_VALUE_DATA_BYTES = 256 KiB",
            "MAX_VALUE_CONTAINER_ENTRIES = 1_023",
            "## Host-side changes required",
            "## Provider-side changes required",
            "both V1 and initial V2 return zero descriptors",
        ),
        "R4 result-model V2 design",
    )
    metadata = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt"
    ).read_text("utf-8")
    require(
        "result.model.v2" not in metadata and "SCHEMA_RESULT_V2" not in metadata,
        "Design-only V2 result capability was advertised before implementation",
    )

    safe_subset = (ROOT / "docs/safe-standard-library-subset.md").read_text("utf-8")
    require_tokens(
        safe_subset,
        (
            "Status: **IMPLEMENTED WITHOUT OPENING `os`**",
            "`lstrlib.c` and `lmathlib.c`",
            "does not compile or open `loslib.c`",
            "`math.randomseed(seed1[, seed2])`",
            "address of the active `lua_State`",
            "must not be used for keys, tokens, nonces, signatures, authorization",
            "## Exact `autojs.now()` contract",
            "Unix epoch milliseconds",
            "wall clock and may move forward or backward",
            "## Rejected surface",
            "reviewedTimeFormatAndRandomSubsetStaysNarrow",
            "rejection of zero-argument `math.randomseed()`",
        ),
        "R4 safe Lua utility subset decision",
    )

    module_design = (ROOT / "docs/module-snapshot-v2.md").read_text("utf-8")
    require_tokens(
        module_design,
        (
            "Status: **DESIGN ONLY — NOT IMPLEMENTED OR ADVERTISED**",
            "`module.snapshot.v2`",
            "must never retry a denied, missing, malformed, or failed V2 lookup",
            "total encoded length from 1 through 255 bytes",
            "no more than 16 dot-separated segments",
            "[A-Za-z_][A-Za-z0-9_]{0,62}(?:\\.[A-Za-z_][A-Za-z0-9_]{0,62}){0,15}",
            "at most 64 distinct non-`autojs` module names",
            "at most 512 KiB of verified source bytes",
            "`cacheHits + cacheMisses == lookupRequests`",
            "not exposed to Lua, console output,",
            "or the current V1 result callback",
            "## Required implementation evidence",
            "No `MODULE_SNAPSHOT_V2_CAPABILITY` constant",
        ),
        "R4 module snapshot V2 design",
    )
    require(
        "module.snapshot.v2" not in metadata
        and "MODULE_SNAPSHOT_V2_CAPABILITY" not in metadata,
        "Design-only module snapshot V2 capability was advertised before implementation",
    )

    console_decision = (ROOT / "docs/console-levels-decision.md").read_text("utf-8")
    require_tokens(
        console_decision,
        (
            "Status: **IMPLEMENTED AS TWO-STREAM ALIASES**",
            "`autojs.console.info(string)`",
            "`autojs.console.warn(string)`",
            "`LuaOutputStream.STDOUT` | 1",
            "`LuaOutputStream.STDERR` | 2",
            "kStdoutStreamWireCode = 1",
            "kStderrStreamWireCode = 2",
            "exactly `STDOUT` and `STDERR`",
            "The mapping is intentionally lossy",
            "All six entry points",
            "consoleLevelAliasesRetainExactlyTwoWireStreams",
        ),
        "R4 console level decision",
    )

    storage_design = (ROOT / "docs/storage-kv-v1.md").read_text("utf-8")
    require(
        isinstance(source_revision, str) and source_revision in storage_design,
        "Storage KV V1 design does not identify the frozen protocol revision",
    )
    require_tokens(
        storage_design,
        (
            "Status: **DESIGN ONLY — NOT IMPLEMENTED OR ADVERTISED**",
            "`storage.kv.v1`",
            "Host's stable logical script principal",
            "single Host-wide or Provider-wide namespace is forbidden",
            "`[A-Za-z_][A-Za-z0-9._-]{0,63}`",
            'get     -> {op="get", key=string}',
            'put     -> {op="put", key=string, value=LuaValue}',
            'clear       -> {removedCount=int64}',
            "maximum depth 32",
            "maximum 4,096 nodes",
            "at most 256 keys per principal",
            "at most 2 MiB of canonical encoded key/value bytes per principal",
            "at most 64 storage operations per execution",
            "at most 32 mutations",
            "Provider never retries",
            "## Clear and retention policy",
            "deterministic `DENIED` / `HOST_CAPABILITY` path",
            "no `STORAGE_KV_CAPABILITY`",
        ),
        "R4 storage KV V1 design",
    )
    require(
        "storage.kv.v1" not in metadata and "STORAGE_KV_CAPABILITY" not in metadata,
        "Design-only storage KV capability was advertised before implementation",
    )


def verify_input_workflows() -> None:
    expected_jvm_test_count()
    root_build = (ROOT / "build.gradle.kts").read_text("utf-8")
    app_build = (ROOT / "app/build.gradle.kts").read_text("utf-8")
    require(
        "org.jetbrains.kotlin.android" not in root_build + app_build,
        "AGP 9 built-in Kotlin must not be combined with the legacy Android Kotlin plugin",
    )
    require(
        "kotlinOptions" not in app_build,
        "AGP 9 build must not use the legacy android.kotlinOptions DSL",
    )
    require_tokens(
        app_build,
        (
            'flag("autojs.lua.releaseCandidate.enabled")',
            '"lua_runtime_requires_host_version"',
            '"autojs.lua.release.signingPropertiesFile"',
            '"autojs.lua.release.signingStoreFile"',
            '"External release signing paths must be absolute"',
            'tasks.register("requireReleaseCandidate")',
            '"Release candidates require native=true, provider=true, and faultHarness=false"',
            '"Release candidates require a version name such as 0.1.0-rc.1"',
            'val releaseArtifactTaskNames = setOf(',
            '"packageReleaseUniversalApk"',
            "task.name in releaseArtifactTaskNames",
            'dependsOn("requireReleaseCandidate")',
        ),
        "Explicit signed release-candidate gate",
    )
    require(
        '"packageReleaseResources"' not in app_build,
        "Release resource intermediates must remain available to the unsigned exclusion audit",
    )
    runnable_provider = (ROOT / "tools/build_runnable_provider.ps1").read_text("utf-8")
    require_tokens(
        runnable_provider,
        (
            '":app:testDebugUnitTest"',
            '":app:assembleRelease"',
            '"-Pautojs.lua.native.enabled=true"',
            '"-Pautojs.lua.provider.enabled=true"',
            '"-Pautojs.lua.faultHarness.enabled=false"',
            '"-Pautojs.lua.releaseCandidate.enabled=true"',
            'bool/lua_runtime_provider_enabled',
            '"org.autojs.plugin.INFO"',
            '"org.autojs.plugin.lua.RUNTIME"',
            'foreach ($abi in @("arm64-v8a", "x86_64"))',
            '$pluginSigner = Read-CertificateSha256 $universalApk',
            '$hostSigner = Read-CertificateSha256 $resolvedHostApk',
            'if ($pluginSigner -ne $hostSigner)',
            '$requiredHostVersionCode = [long] $versionProperties["REQUIRED_HOST_VERSION_CODE"]',
            'if ($hostVersionCode -lt $requiredHostVersionCode)',
            'status --porcelain --untracked-files=all',
            'deviceVerified=false runtimeVerified=false',
        ),
        "Runnable same-signer Lua provider workflow",
    )

    attributes_lines = (ROOT / ".gitattributes").read_text("utf-8").splitlines()
    vendor_whitespace_rule = "app/src/main/cpp/vendor/lua-5.4.8/src/** -whitespace"
    require(
        attributes_lines.count(vendor_whitespace_rule) == 1,
        "Immutable upstream Lua whitespace exception drift",
    )

    ignore_lines = [
        line.strip()
        for line in (ROOT / ".gitignore").read_text("utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    exceptions = ("!protocol/*.aar", "!gradle/wrapper/gradle-wrapper.jar")
    require(ignore_lines.count(".cxx/") == 1, "Native build output ignore drift")
    for exception in exceptions:
        require(
            ignore_lines.count(exception) == 1,
            f"Immutable input Git exception drift: {exception}",
        )
    require(
        tuple(ignore_lines[-len(exceptions):]) == exceptions,
        "Immutable input Git exceptions must remain the final repository rules",
    )
    nested_ignore_files = (
        ROOT / "protocol/.gitignore",
        ROOT / "gradle/.gitignore",
        ROOT / "gradle/wrapper/.gitignore",
    )
    require(
        not any(path.exists() or path.is_symlink() for path in nested_ignore_files),
        "Nested Git-ignore rules may override immutable input exceptions",
    )

    protocol_intake = (ROOT / "tools/stage_protocol_artifacts.ps1").read_text("utf-8")
    require_tokens(
        protocol_intake,
        (
            "ValidatePattern('^[0-9a-fA-F]{40}$')",
            "git -C $checkout rev-parse HEAD",
            "$actualRevision -ne $ExpectedRevision.ToLowerInvariant()",
            "git -C $checkout status --porcelain --untracked-files=all",
            '"--project-dir=$checkout"',
            ":plugin-api:common-plugin-api:assembleDebug",
            ":plugin-api:protocol-wire-api:assembleDebug",
            ":plugin-api:lua-runtime-api:assembleDebug",
            "--rerun-tasks",
            "--no-build-cache",
            "--no-daemon",
            "Refusing to overwrite existing protocol artifacts",
            "Get-FileHash -LiteralPath $temporaryArtifact -Algorithm SHA256",
            "sourceRevision = $actualRevision",
        ),
        "Protocol artifact intake",
    )

    lua_intake = (ROOT / "tools/stage_lua_source.ps1").read_text("utf-8")
    require_tokens(
        lua_intake,
        (
            LUA_SHA256,
            "$archiveSha256 -ne $expectedArchiveSha256 -or $archiveSha256 -ne $lock.sha256",
            "tar -xzf $resolvedArchive -C $temporaryRoot",
            "$extractedRoot = Join-Path $temporaryRoot 'lua-5.4.8'",
            "$extractedSource = Join-Path $extractedRoot 'src'",
            "Move-Item -LiteralPath $extractedSource -Destination $destinationSource",
            "--print-vendor-tree",
            "$lock.status = 'vendored'",
            "$lock.sourceFileCount =",
            "$lock.sourceTreeSha256 =",
            "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'",
            "$lock.vendoredAt =",
            "tools/verify_repository.py",
        ),
        "Lua source intake",
    )
    artifact_gate = (ROOT / "tools/verify_debug_artifacts.ps1").read_text("utf-8")
    require_tokens(
        artifact_gate,
        (
            "status --porcelain --untracked-files=all",
            "verification.properties",
            "JVM_TEST_COUNT",
            "$expectedTests = [int]$verificationProperties.JVM_TEST_COUNT",
            "rev-list --count HEAD",
            "VERSION_BUILD must equal the positive commit count",
            "app/build/test-results/testDebugUnitTest",
            "app/build/outputs/apk/debug",
            "zipalign -c -P 16 4",
            "apksigner verify --verbose --print-certs",
            "llvm-readelf",
            "app/build/generated/source/buildConfig/debug/",
            "LUA_NATIVE_ENABLED = true;",
            "LUA_PROVIDER_ENABLED = false;",
            "DEBUG_ARTIFACT_GATE_PASS",
        ),
        "Debug artifact gate",
    )
    release_artifact_gate = (
        ROOT / "tools/verify_release_candidate_artifacts.ps1"
    ).read_text("utf-8")
    require_tokens(
        release_artifact_gate,
        (
            "InvocationStartedAtUtc",
            "SigningPropertiesFile",
            "SigningStoreFile",
            "keytoolCommand.Source -exportcert -rfc",
            "-storepass:env $passwordEnvironmentName",
            "status --porcelain --untracked-files=all",
            "verification.properties",
            "JVM_TEST_COUNT",
            "$expectedTests = [int]$verificationProperties.JVM_TEST_COUNT",
            "VERSION_BUILD must equal the positive commit count",
            "app/build/outputs/apk/release",
            "zipalign -c -P 16 4",
            "apksigner verify --verbose --print-certs",
            "Release APK must have exactly one signer",
            "LUA_NATIVE_ENABLED = true;",
            "LUA_PROVIDER_ENABLED = true;",
            "LUA_FAULT_HARNESS_ENABLED = false;",
            "$faultHarnessResourcePresent = $resourceText.Contains('lua_runtime_fault_harness_enabled')",
            "Packaged release fault harness resource is not false",
            "SIGNED_RELEASE_CANDIDATE_ARTIFACT_GATE_PASS",
            "deviceVerified = $false",
            "runtimeVerified = $false",
        ),
        "Signed release-candidate artifact gate",
    )
    local_gate = (ROOT / "tools/verify_local.ps1").read_text("utf-8")
    require_tokens(
        local_gate,
        (
            "verification.properties",
            "JVM_TEST_COUNT",
            "tools/verify_repository.py",
            "--require-build-ready",
            "unittest",
            "discover",
            ":app:testDebugUnitTest",
            "-Pautojs.lua.native.enabled=true",
            "-Pautojs.lua.provider.enabled=false",
            "--offline",
            "app/build/test-results/testDebugUnitTest",
            "LOCAL_OFFLINE_GATE_PASS",
        ),
        "One-command offline local gate",
    )
    archive_verifier = (ROOT / "tools/verify_lua_archive.ps1").read_text("utf-8")
    require_tokens(
        archive_verifier,
        (LUA_SHA256, "Get-FileHash -LiteralPath $resolvedArchive -Algorithm SHA256"),
        "Lua archive verifier",
    )


def verify_native_boundary() -> None:
    cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text("utf-8")
    require(
        "if(NOT DEFINED AUTOJS_LUA_RUNTIME_SLOT)" in cmake
        and 'if(NOT "${AUTOJS_LUA_RUNTIME_SLOT}" STREQUAL "lua54")' in cmake,
        "Native build no longer pins the lua54 runtime slot",
    )
    native = (ROOT / "app/src/main/cpp/lua_runtime_jni.cpp").read_text("utf-8")
    preflight = "new_size > budget->limit - used_without_old"
    realloc = "std::realloc(pointer, new_size)"
    require(preflight in native, "Allocator limit preflight is missing")
    require(native.index(preflight) < native.index(realloc), "Allocator limit runs after realloc")
    require(
        "if (accounted_old_size > budget->used)" in native
        and "budget->accounting_failed = true" in native,
        "Allocator accounting mismatch does not fail closed",
    )
    require(
        native.index("budget->accounting_failed = true") < native.index(realloc),
        "Allocator accounting failure is detected after realloc",
    )
    require(
        "return opened && !budget.accounting_failed && !budget.limit_exceeded && budget.used == 0U"
        in native,
        "Native probe ignores allocator accounting failure",
    )
    require(
        "bool limit_exceeded;" in native
        and "budget->limit_exceeded = true;" in native
        and "!budget.limit_exceeded" in native
        and "if (budget.limit_exceeded)" in native,
        "Allocator-limit rejection is not retained across protected coroutine resumes",
    )
    require(
        "Lua allocator accounting became inconsistent" in native
        and "budget.accounting_failed || budget.used != 0U" in native,
        "Native execution lacks an allocator accounting failure",
    )
    require("std::free(replacement)" not in native, "Allocator frees a successful realloc on failure")
    required_execution_tokens = {
        'lua_newstate(bounded_allocator, &budget)': "Each execution must use the bounded allocator",
        'luaL_loadbufferx(': "The native text loader is missing",
        'chunk_name,\n        "t")': "The native loader is not pinned to text-only mode",
        'lua_sethook(state, execution_hook, LUA_MASKCOUNT': "The cancellation/deadline hook is missing",
        '{LUA_COLIBNAME, luaopen_coroutine}': "The reviewed coroutine library is not opened exactly",
        'lua_pcall(state, 0, LUA_MULTRET, 0)': "Lua source is not executed through a protected call",
        'class LuaStateOwner': "Deterministic lua_State ownership is missing",
        'remove_global(state, "dofile")': "Lua dofile is still exposed",
        'remove_global(state, "load")': "Lua load can bypass the text-only loader",
        'remove_global(state, "loadfile")': "Lua loadfile is still exposed",
        'remove_global(state, "pcall")': "Lua pcall could swallow cancellation indefinitely",
        'remove_global(state, "xpcall")': "Lua xpcall could swallow cancellation indefinitely",
        'remove_global(state, "getmetatable")': "Lua can expose teardown metatables",
        'remove_global(state, "setmetatable")': "Lua can install an unbounded teardown finalizer",
        'remove_global(state, "print")': "Lua print can bypass output credits",
        'remove_global(state, "warn")': "Lua warnings can bypass output credits",
        'lua_setglobal(state, "print")': "Controlled Lua print bridge is missing",
        'lua_setglobal(state, "warn")': "Controlled Lua warn bridge is missing",
        'lua_setfield(state, -2, "dump")': "Lua string.dump is still exposed",
        'int restricted_require(lua_State* state)': "The controlled require boundary is missing",
        'std::memcmp(name, "autojs", 6U)': "The autojs module admission is not exact",
        'is_flat_ascii_module_name(': "The frozen module name allowlist is missing",
        'control->load_module_method': "The fixed module snapshot JNI call is missing",
        'module->chunk_name,\n        "t")': "Frozen modules are not pinned to text-only mode",
        'Lua module snapshot dependency cycle rejected': "Frozen module cycles do not fail closed",
        'lua_pushvalue(state, lua_upvalueindex(2))': "The execution-local module cache is missing",
        'lua_setfield(state, -2, "console")': "The controlled console module is missing",
        'constexpr jint kStdoutStreamWireCode = 1;': "The stdout wire constant drifted",
        'constexpr jint kStderrStreamWireCode = 2;': "The stderr wire constant drifted",
        'int autojs_now(lua_State* state)': "The controlled wall-clock API is missing",
        'int controlled_math_randomseed(lua_State* state)': "The explicit-seed PRNG wrapper is missing",
        'lua_setfield(state, -2, "arguments")': "The bounded argument snapshot is missing",
        "push_native_argument_value(": "The native argument decoder is missing",
        "lua_rawseti(state, -2": "Lua arrays are not installed with 1-based raw indices",
        "lua_rawset(state, -3)": "Lua maps are not installed with binary-safe raw keys",
        "reader.cursor == reader.end": "The native argument decoder accepts trailing bytes",
        'CallBooleanMethod(': "The native output bridge is missing",
    }
    for token, message in required_execution_tokens.items():
        require(token in native, message)
    print_start = native.index("int autojs_console_print(lua_State* state)")
    print_end = native.index("int autojs_console_log(lua_State* state)", print_start)
    print_boundary = native[print_start:print_end]
    require(
        "return emit_autojs_console(state, kStdoutStreamWireCode);" in print_boundary
        and "kMaxOutputChunkBytes" in print_boundary,
        "Global print no longer routes through the bounded stdout bridge",
    )
    now_start = native.index("int autojs_now(lua_State* state)")
    now_end = native.index("int controlled_math_randomseed(lua_State* state)", now_start)
    now_boundary = native[now_start:now_end]
    require_tokens(
        now_boundary,
        (
            "lua_gettop(state) != 0",
            "poll_execution_control(control)",
            "std::chrono::system_clock::now().time_since_epoch()",
            "std::chrono::duration_cast<std::chrono::milliseconds>",
            "lua_pushinteger(state, static_cast<lua_Integer>(unix_epoch_millis))",
        ),
        "Controlled autojs.now boundary",
    )
    randomseed_start = native.index("int controlled_math_randomseed(lua_State* state)")
    randomseed_end = native.index("int push_host_result(lua_State* state)", randomseed_start)
    randomseed_boundary = native[randomseed_start:randomseed_end]
    require_tokens(
        randomseed_boundary,
        (
            "argument_count < 1 || argument_count > 2",
            "!lua_isinteger(state, 1)",
            "lua_pushvalue(state, lua_upvalueindex(1))",
            "lua_call(state, argument_count, 2)",
            "return 2;",
        ),
        "Controlled math.randomseed boundary",
    )
    install_start = native.index("int install_autojs_module(lua_State* state)")
    install_end = native.index("bool throw_bridge_exception(", install_start)
    install_boundary = native[install_start:install_end]
    require(
        install_boundary.count(
            'lua_pushcfunction(state, autojs_console_print);\n    lua_setglobal(state, "print");'
        )
        == 1
        and install_boundary.count(
            'lua_pushcfunction(state, autojs_console_error);\n    lua_setglobal(state, "warn");'
        )
        == 1,
        "Global print/warn are not installed as the reviewed console bridges",
    )
    require(
        install_boundary.count(
            'lua_pushcfunction(state, autojs_console_log);\n    lua_setfield(state, -2, "info");'
        )
        == 1
        and install_boundary.count(
            'lua_pushcfunction(state, autojs_console_error);\n    lua_setfield(state, -2, "warn");'
        )
        == 1,
        "Console info/warn aliases are not installed as the reviewed two-stream bridges",
    )
    require_tokens(
        install_boundary,
        (
            'lua_pushcclosure(state, controlled_math_randomseed, 1);',
            'lua_setfield(state, -2, "randomseed");',
            'lua_pushcfunction(state, autojs_now);',
            'lua_setfield(state, -2, "now");',
        ),
        "Reviewed Lua utility installation",
    )
    host_copy_start = native.index("ProtectedHostMappingResult copy_and_map_host_result(")
    host_copy_end = native.index("int autojs_device_info(lua_State* state)", host_copy_start)
    host_copy_boundary = native[host_copy_start:host_copy_end]
    require(
        "std::unique_ptr" in host_copy_boundary
        and "lua_error(" not in host_copy_boundary
        and "luaL_error(" not in host_copy_boundary,
        "Device-info payload ownership can cross a Lua longjmp",
    )
    module_copy_start = native.index("ProtectedModuleLoadResult copy_and_load_module(")
    module_copy_end = native.index("int restricted_require(lua_State* state)", module_copy_start)
    module_copy_boundary = native[module_copy_start:module_copy_end]
    require(
        "std::unique_ptr" in module_copy_boundary
        and "lua_error(" not in module_copy_boundary
        and "luaL_error(" not in module_copy_boundary,
        "Module payload ownership can cross a Lua longjmp",
    )
    control_slot_at = native.index(
        "*static_cast<ExecutionControl**>(lua_getextraspace(state)) = &control;"
    )
    library_bootstrap_at = native.index(
        "lua_pushcfunction(state, open_safe_libraries)", control_slot_at
    )
    load_at = native.index("status = luaL_loadbufferx(", library_bootstrap_at)
    hook_at = native.index("lua_sethook(state, execution_hook, LUA_MASKCOUNT")
    protected_call_at = native.index("status = lua_pcall(state, 0, LUA_MULTRET, 0)")
    clear_hook_at = native.index("lua_sethook(state, nullptr, 0, 0)")
    result_at = native.index("jobject result = box_lua_result(environment, state)")
    require(
        control_slot_at < library_bootstrap_at < load_at < hook_at < protected_call_at
        < clear_hook_at < result_at,
        "Native load/hook/protected-call/result ordering drift",
    )
    termination_at = native.index(
        "if (control.termination_reason != TerminationReason::kNone)", protected_call_at
    )
    sticky_limit_at = native.index("if (budget.limit_exceeded)", termination_at)
    final_poll_at = native.index("if (!poll_execution_control(&control))", sticky_limit_at)
    final_status_at = native.index("if (status != LUA_OK)", final_poll_at)
    sticky_boundary = native[sticky_limit_at:final_poll_at]
    require(
        termination_at < sticky_limit_at < final_poll_at < final_status_at
        and "owner.close();" in sticky_boundary
        and "budget.accounting_failed || budget.used != 0U" in sticky_boundary
        and 'return fail(environment, "MEMORY_LIMIT"' in sticky_boundary,
        "Caught coroutine OOM can bypass sticky failure or exact allocator teardown",
    )
    close_before_result = native.index("owner.close();", result_at)
    final_return = native.index("return result;", close_before_result)
    require(
        result_at < close_before_result < final_return,
        "Successful native execution does not close lua_State before returning",
    )
    require("luaL_openlibs" not in native, "Unrestricted Lua library bootstrap is forbidden")
    library_start = native.index("static const luaL_Reg libraries[]")
    library_end = native.index("{nullptr, nullptr}", library_start)
    opened_libraries = set(re.findall(r"\{[^,]+,\s*(luaopen_[a-z0-9_]+)\}", native[library_start:library_end]))
    require(
        opened_libraries == {
            "luaopen_base",
            "luaopen_coroutine",
            "luaopen_math",
            "luaopen_string",
            "luaopen_table",
            "luaopen_utf8",
        },
        f"Native Lua library allowlist drift: {sorted(opened_libraries)}",
    )
    for forbidden_open in (
        "luaopen_debug",
        "luaopen_io",
        "luaopen_os",
        "luaopen_package",
    ):
        require(forbidden_open not in native, f"Forbidden Lua library is opened: {forbidden_open}")

    lua_state = (
        ROOT / "app/src/main/cpp/vendor/lua-5.4.8/src/lstate.c"
    ).read_text("utf-8")
    new_thread_start = lua_state.index("LUA_API lua_State *lua_newthread (lua_State *L)")
    new_thread_end = lua_state.index("void luaE_freethread", new_thread_start)
    new_thread_boundary = lua_state[new_thread_start:new_thread_end]
    coroutine_inheritance = (
        "L1->hookmask = L->hookmask;",
        "L1->basehookcount = L->basehookcount;",
        "L1->hook = L->hook;",
        "resethookcount(L1);",
        "memcpy(lua_getextraspace(L1), lua_getextraspace(g->mainthread),",
        "LUA_EXTRASPACE);",
    )
    require_tokens(
        new_thread_boundary,
        coroutine_inheritance,
        "Pinned Lua coroutine hook/extraspace inheritance",
    )
    require(
        all(
            new_thread_boundary.index(coroutine_inheritance[index])
            < new_thread_boundary.index(coroutine_inheritance[index + 1])
            for index in range(len(coroutine_inheritance) - 1)
        ),
        "Pinned Lua coroutine control inheritance ordering drift",
    )

    kotlin_boundary = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt"
    ).read_text("utf-8")
    require(
        "module.snapshot.v2" not in kotlin_boundary
        and "MODULE_SNAPSHOT_V2_CAPABILITY" not in kotlin_boundary
        and "module.snapshot.v2" not in native
        and "storage.kv.v1" not in kotlin_boundary
        and "STORAGE_KV_CAPABILITY" not in kotlin_boundary
        and "storage.kv.v1" not in native,
        "A design-only Host capability entered the implementation",
    )
    proguard_rules = (ROOT / "app/proguard-rules.pro").read_text("utf-8")
    require(
        "NativeLuaHostCapabilityBridge" in proguard_rules
        and "byte[] invokeDeviceInfo();" in proguard_rules
        and "byte[] loadModule(byte[]);" in proguard_rules
        and "int takeFailureKind();" in proguard_rules,
        "R8 can rename a JNI-reflected host capability bridge member",
    )
    for token in (
        "fun execute(request: NativeLuaExecutionRequest): NativeLuaExecutionValue",
        "internal typealias NativeLuaCancellationProbe = BooleanSupplier",
        "    DEADLINE_EXCEEDED,",
        "NativeLuaFailureKind.UNSUPPORTED_RESULT",
        "LuaRuntimeContract.MAX_VALUE_STRING_OR_BYTES",
        "internal object NativeLuaExecutionRunner : LuaExecutionRunner",
        "internal object NativeLuaArgumentCodec",
        "LuaValueValidation.validate(value)",
        "internal fun argumentsSnapshot(): ByteArray",
        "LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS",
        "fun NativeLuaExecutionValue.toProtocolValue(): LuaValue",
        "internal fun interface NativeLuaOutputEmitter",
        "fun emitNativeOutput(",
        "fun loadModule(nameUtf8: ByteArray): ByteArray?",
        'const val MODULE_SNAPSHOT_CAPABILITY = "module.snapshot.v1"',
        "internal fun validateModuleSnapshot(value: LuaValue): ByteArray?",
        'MessageDigest.getInstance("SHA-256").digest(source)',
    ):
        require(token in kotlin_boundary, f"Native Kotlin execution boundary drift: {token}")
    provider_metadata = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt"
    ).read_text("utf-8")
    capabilities_start = provider_metadata.index("capabilities = listOf(")
    capabilities_end = provider_metadata.index("),", capabilities_start)
    advertised_capabilities = re.findall(
        r"NativeLuaHostCapabilityBridge\.([A-Z][A-Z0-9_]+_CAPABILITY)",
        provider_metadata[capabilities_start:capabilities_end],
    )
    require(
        advertised_capabilities
        == ["DEVICE_INFO_CAPABILITY", "MODULE_SNAPSHOT_CAPABILITY"],
        f"Provider capability registry lacks a reviewed fixed-shape bridge: {advertised_capabilities}",
    )
    capability_boundaries = {
        "DEVICE_INFO_CAPABILITY": (
            'const val DEVICE_INFO_CAPABILITY = "device.info"',
            "fun invokeDeviceInfo(): ByteArray",
            "validateDeviceInfo(value)",
            'assertEquals("device.info", observedCapability)',
        ),
        "MODULE_SNAPSHOT_CAPABILITY": (
            'const val MODULE_SNAPSHOT_CAPABILITY = "module.snapshot.v1"',
            "fun loadModule(nameUtf8: ByteArray): ByteArray?",
            "validateModuleSnapshot(value)",
            'assertEquals("module.snapshot.v1", capability)',
        ),
    }
    boundary_tests = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntimeBoundaryTest.kt"
    ).read_text("utf-8")
    require_tokens(
        boundary_tests,
        (
            "consoleLevelAliasesRetainExactlyTwoWireStreams",
            "enumValues<LuaOutputStream>().toList()",
            "assertEquals(1, LuaOutputStream.STDOUT.wireCode)",
            "assertEquals(2, LuaOutputStream.STDERR.wireCode)",
            "deviceInfoCapabilityGrantAndDenialStayDeterministic",
            "moduleSnapshotCapabilityGrantAndDenialStayDeterministic",
            "LuaHostCapabilityFailureKind.DENIED",
            "HOST_FAILURE_REJECTED = 3",
        ),
        "Console and Host-capability JVM boundary",
    )
    require(
        boundary_tests.count("hostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING") == 2
        and boundary_tests.count(
            "assertEquals(LuaHostCapabilityFailureKind.DENIED, denial.kind)"
        )
        == 2
        and boundary_tests.count(
            "assertEquals(HOST_FAILURE_REJECTED, deniedBridge.takeFailureKind())"
        )
        == 2,
        "Registered Host capabilities lack symmetric grant/denial JVM evidence",
    )
    for capability in advertised_capabilities:
        for token in capability_boundaries[capability][:-1]:
            require(token in kotlin_boundary, f"Fixed-shape capability bridge drift: {token}")
        require(
            capability_boundaries[capability][-1] in boundary_tests,
            f"Fixed-shape capability JVM evidence drift: {capability}",
        )
    execute_start = kotlin_boundary.index(
        "fun execute(request: NativeLuaExecutionRequest): NativeLuaExecutionValue",
    )
    execute_end = kotlin_boundary.index("private fun requireNativeLoaded()", execute_start)
    execute_boundary = kotlin_boundary[execute_start:execute_end]
    require(
        execute_boundary.index("cancellationProbe.getAsBoolean()")
        < execute_boundary.index("requireNativeLoaded()")
        < execute_boundary.index("request.sourceSnapshot()"),
        "Native dispatch performs source/library work before pre-cancellation",
    )

    runtime_service = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt"
    ).read_text("utf-8")
    require(
        "runner = selectLuaExecutionRunner(BuildConfig.LUA_NATIVE_ENABLED)" in runtime_service,
        "Runtime service no longer selects its runner from the native build flag",
    )
    require(
        "internal fun selectLuaExecutionRunner(nativeEnabled: Boolean): LuaExecutionRunner" in runtime_service
        and "if (nativeEnabled) NativeLuaExecutionRunner else DisabledLuaExecutionRunner" in runtime_service,
        "Native/disabled runner selection drift",
    )

    native_doc = (ROOT / "docs/native-execution-core.md").read_text("utf-8")
    require(
        "COMPILED, PACKAGED, AND DEVICE-EXECUTED / PROVIDER DEFAULT-OFF" in native_doc,
        "Native evidence boundary is missing",
    )
    require("flat ASCII names" in native_doc, "Native module boundary is missing")
    require("## Bounded argument boundary" in native_doc, "Native argument boundary is missing")
    require("sequence, credit, chunk, and total-byte limits" in native_doc, "Native output boundary is missing")
    require("There is no general module loader" in native_doc, "Native capability limitation is missing")
    require(
        "## Controlled coroutine boundary" in native_doc
        and "sticky allocator-limit marker" in native_doc
        and "coroutine-control-boundary.md" in native_doc,
        "Native coroutine control boundary is missing",
    )
    require("infinite `__gc` or `__close` handler" in native_doc, "Native teardown limitation is missing")
    require("process-level cleanup watchdog" in native_doc, "Native cleanup watchdog gate is missing")
    require("Selecting the adapter does not load JNI" in native_doc, "Native adapter status is ambiguous")
    require("`autojs.now()`" in native_doc, "Native controlled wall-clock API is missing")
    require(
        "`math.randomseed(seed1[, seed2])` wrapper" in native_doc
        and "upstream no-argument branch" in native_doc,
        "Native PRNG seed boundary is missing",
    )
    require("console.info" in native_doc and "console.warn" in native_doc, "Native console aliases are missing")

    inventory = (ROOT / "app/src/main/cpp/cmake/lua54-sources.cmake").read_text("utf-8")
    inventory_lines = [line.strip() for line in inventory.splitlines()]
    admitted_library_sources = {
        line for line in inventory_lines if re.fullmatch(r"src/l[a-z0-9]+lib\.c", line)
    }
    require(
        admitted_library_sources
        == {
            "src/lauxlib.c",
            "src/lbaselib.c",
            "src/lcorolib.c",
            "src/lmathlib.c",
            "src/lstrlib.c",
            "src/ltablib.c",
            "src/lutf8lib.c",
        },
        f"Native Lua library source inventory drift: {sorted(admitted_library_sources)}",
    )
    require(
        inventory_lines.count("src/lcorolib.c") == 1
        and "src/linit.c" not in inventory_lines,
        "Controlled coroutine source is not unique or linit.c entered the inventory",
    )
    require(
        any(line.strip() == "src/lundump.c" for line in inventory.splitlines()),
        "Lua core inventory unexpectedly lost lundump.c",
    )
    for required_library in ("src/lmathlib.c", "src/lstrlib.c"):
        require(
            any(line.strip() == required_library for line in inventory.splitlines()),
            f"Reviewed Lua utility library left the native inventory: {required_library}",
        )
    coroutine_doc = (ROOT / "docs/coroutine-control-boundary.md").read_text("utf-8")
    require_tokens(
        coroutine_doc,
        (
            "Status: **IMPLEMENTED — PROVIDER DEFAULT-OFF**",
            "src/lcorolib.c",
            "`linit.c`, `ldblib.c`, `liolib.c`, `loslib.c`, and `loadlib.c` remain absent",
            "L1->hookmask = L->hookmask;",
            "L1->basehookcount = L->basehookcount;",
            "L1->hook = L->hook;",
            "lua_getextraspace(g->mainthread)",
            "10,000-instruction count",
            "`coroutine.resume`",
            "`TerminationReason`",
            "sticky `limit_exceeded` bit",
            "accounting_failed == false",
            "used == 0",
            "coroutineInfiniteLoopHonoursInheritedDeadlineHook",
            "coroutineCancellationCannotBeSwallowedByResume",
            "coroutineYieldResumeRetainsAllocatorAccounting",
            "coroutineOomCannotBecomeSuccessfulAndTheProcessRemainsReusable",
            "Coroutine objects remain unsupported V1 result values",
        ),
        "Controlled coroutine decision and conformance boundary",
    )
    readme = (ROOT / "README.md").read_text("utf-8")
    require(
        'luaL_loadbufferx(..., "t")' in readme,
        "The required text-only execution loader gate is not documented",
    )


def verify_watchdog_boundary() -> None:
    watchdog = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdog.kt"
    ).read_text("utf-8")
    require_tokens(
        watchdog,
        (
            "if (poisoned || active != null) return null",
            "execution.token !== token",
            "poisoned = true",
            "DEADLINE_CLEANUP_EXPIRED",
            "STOP_CLEANUP_EXPIRED",
            "WATCHDOG_CONTROL_FAILURE",
            "execution.stopRequestedNanos?.let",
            "tasks.forEach { task -> runCatching { task.cancel() } }",
        ),
        "Process watchdog token and fail-stop boundary",
    )

    controller = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionController.kt"
    ).read_text("utf-8")
    require(
        "if (!watchdog.executionDispatched())" in controller
        and controller.count("watchdog.stopRequestedQuietly()") >= 3
        and "watchdog.closeQuietly()" in controller,
        "Session lifecycle no longer arms, shortens, and revokes its watchdog lease",
    )

    manager = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeExecutionManager.kt"
    ).read_text("utf-8")
    require_tokens(
        manager,
        (
            "ProcessExecutionResources.watchdog.tryAcquire(",
            "watchdog = watchdogLease",
            "terminator = AndroidLuaRuntimeProcessTerminator",
            "cleanupGraceMillis = WATCHDOG_CLEANUP_GRACE_MILLIS",
            "WATCHDOG_CLEANUP_GRACE_MILLIS = 2_000L",
        ),
        "Runtime manager watchdog ownership",
    )

    process_guard = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeProcessWatchdog.kt"
    ).read_text("utf-8")
    kill_at = process_guard.index("Process.killProcess(pid)")
    halt_at = process_guard.index("Runtime.getRuntime().halt(")
    require(kill_at < halt_at, "Dedicated-process termination fallback ordering drift")

    tests = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionWatchdogTest.kt"
    ).read_text("utf-8")
    require(
        "normalFinishCancelsEveryTaskAndAStaleCallbackCannotKillReplacement" in tests
        and "schedulerFailurePoisonsProcessAndFailsDispatchClosed" in tests
        and "expiredDeadlineFailsClosedBeforeWorkerDispatchEvenIfTerminatorReturns" in tests
        and "expiredStopGraceFailsClosedBeforeWorkerDispatch" in tests,
        "Android-free watchdog race coverage is missing",
    )


def verify_descriptor_boundary() -> None:
    ledger = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaFileDescriptorLedger.kt"
    ).read_text("utf-8")
    require_tokens(
        ledger,
        (
            "INCOMING_SOURCE",
            "DUPLICATED_SOURCE",
            "HOST_CALLBACK_PAYLOAD",
            "RESULT_CALLBACK_PAYLOAD",
            "ownedCounters.acquired.incrementAndGet()",
            "ownedCounters.released.incrementAndGet()",
            "closed.compareAndSet(false, true)",
            "val isBalanced: Boolean",
        ),
        "Logical PFD ownership ledger",
    )

    service = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt"
    ).read_text("utf-8")
    require_tokens(
        service,
        (
            "incomingOwnership = executionManager.trackIncomingSource()",
            "source?.runCatching { close() }",
            "incomingOwnership?.close()",
        ),
        "Incoming source PFD ownership",
    )
    fault_service = (
        ROOT
        / "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt"
    ).read_text("utf-8")
    require_tokens(
        fault_service,
        (
            "incomingOwnership = executionManager.trackIncomingSource()",
            "source?.runCatching { close() }",
            "incomingOwnership?.close()",
        ),
        "Debug fault-harness incoming source PFD ownership",
    )

    manager = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeExecutionManager.kt"
    ).read_text("utf-8")
    require_tokens(
        manager,
        (
            "descriptorLedger.acquire(LuaFileDescriptorKind.INCOMING_SOURCE)",
            "descriptorLedger = descriptorLedger",
            "ownedSource?.close()",
            "LuaFileDescriptorKind.DUPLICATED_SOURCE",
            "if (!closed.compareAndSet(false, true)) return",
            "ownership.close()",
        ),
        "Duplicated source PFD ownership",
    )
    require(
        "callback.onCompleted(\n            LuaRuntimeCodec.encodeResult(result),\n            emptyArray<ParcelFileDescriptor>(),\n        )"
        in manager,
        "Protocol V1 result callback unexpectedly returns PFD payloads",
    )

    host_invoker = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/BinderLuaHostCapabilityInvoker.kt"
    ).read_text("utf-8")
    require_tokens(
        host_invoker,
        (
            "descriptorLedger.acquire(LuaFileDescriptorKind.HOST_CALLBACK_PAYLOAD)",
            "descriptors.forEachIndexed { index, descriptor ->",
            "runCatching { descriptor?.close() }",
            "ownerships[index]?.close()",
        ),
        "Host callback PFD ownership",
    )

    controller = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionController.kt"
    ).read_text("utf-8")
    require_tokens(
        controller,
        (
            "fun expireIfNotStarted(): Boolean",
            "timeout = timeoutFailure(LuaExecutionFailurePhase.QUEUE)",
            "timeout?.let { failure -> deliver { it.onFailed(failure) } }",
        ),
        "Unstarted deadline terminal convergence",
    )

    ledger_tests = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaFileDescriptorLedgerTest.kt"
    ).read_text("utf-8")
    controller_tests = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/LuaExecutionSessionControllerTest.kt"
    ).read_text("utf-8")
    android_tests = (
        ROOT
        / "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/BinderLuaHostCapabilityInvokerInstrumentationTest.kt"
    ).read_text("utf-8")
    require_tokens(
        ledger_tests,
        (
            "createFailureBalancesIncomingAndDuplicatedSourceExactlyOnce",
            "busyRejectionBalancesIncomingSourceWithoutCreatingADuplicate",
            "sourceReadAndControllerFinishStyleDoubleCloseReleaseOneLease",
            "hostPayloadsBalanceWhileV1ScalarResultOwnsNoDescriptors",
        ),
        "JVM PFD ledger matrix",
    )
    require_tokens(
        controller_tests,
        (
            "oneMillisecondDeadlineBeforeDelayedStartEmitsOneTimeoutAndReleasesLeases",
            "assertEquals(LuaExecutionErrorCode.TIMEOUT, observer.lastError?.code)",
            "assertEquals(LuaExecutionFailurePhase.QUEUE, observer.lastError?.phase)",
            "assertEquals(1, watchdog.closes.get())",
        ),
        "Tiny-deadline delayed-start race",
    )
    require_tokens(
        android_tests,
        (
            "rejectedHostPayloadIsClosedAndLogicallyBalanced",
            "assertFalse(payload.fileDescriptor.valid())",
        ),
        "Android host-payload close evidence",
    )


def verify_native_android_test_boundary() -> None:
    build = (ROOT / "app/build.gradle.kts").read_text("utf-8")
    require_tokens(
        build,
        (
            'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"',
            'androidTestImplementation("androidx.test:runner:1.7.0")',
            'androidTestImplementation("androidx.test.ext:junit:1.3.0")',
        ),
        "Provider-disabled native Android test configuration",
    )

    test = (
        ROOT
        / "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntimeInstrumentationTest.kt"
    ).read_text("utf-8")
    require_tokens(
        test,
        (
            "assertTrue(BuildConfig.LUA_NATIVE_ENABLED)",
            "assertFalse(BuildConfig.LUA_PROVIDER_ENABLED)",
            "providerRemainsDisabledDuringNativeTests",
            "nativeCoreAndRunnerReturnV1Scalars",
            "reviewedTimeFormatAndRandomSubsetStaysNarrow",
            "autojs.console.info('notice')",
            "autojs.console.warn('warning')",
            "execute(\"return require('autojs').now(1)\")",
            "execute(\"return math.randomseed()\")",
            "deniedModuleCapability",
            "assertEquals(LuaRunnerFailureKind.HOST_CAPABILITY, deniedModuleCapability.kind)",
            "nativeRunnerMapsV1ArgumentsIntoTheControlledAutoJsModule",
            "nativeRunnerLoadsFrozenModulesOnceAndRejectsDependencyCycles",
            "runnerRequest(\"return require('autojs').device.info()\")",
            "assertEquals(LuaRunnerFailureKind.HOST_CAPABILITY, denial.kind)",
            "syntaxAndRuntimeErrorsAreClassified",
            "infiniteLoopIsCancelledByHook",
            "infiniteLoopHonoursDeadline",
            "coroutineInfiniteLoopHonoursInheritedDeadlineHook",
            "local resumed = coroutine.resume(worker)",
            "coroutineCancellationCannotBeSwallowedByResume",
            "polls.incrementAndGet() >= 5",
            "coroutineYieldResumeRetainsAllocatorAccounting",
            "coroutine.yield(index, #retained[index])",
            "coroutineOomCannotBecomeSuccessfulAndTheProcessRemainsReusable",
            "assertNativeFailure(NativeLuaFailureKind.MEMORY_LIMIT)",
            "if resumed then return 1 end",
            "allocatorLimitFailsClosedAndTheProcessRemainsReusable",
            "unsupportedResultsAndMalformedArgumentsFailClosed",
        ),
        "Provider-disabled native Android test matrix",
    )
    require(
        "bindService(" not in test,
        "The provider-disabled native core gate must not bind production services",
    )


def verify_fault_harness_boundary() -> None:
    build = (ROOT / "app/build.gradle.kts").read_text("utf-8")
    require_tokens(
        build,
        (
            'flag("autojs.lua.faultHarness.enabled")',
            "luaFaultHarnessEnabled.get() && (!luaNativeEnabled.get() || luaProviderEnabled.get())",
            '"LUA_FAULT_HARNESS_ENABLED",\n                luaFaultHarnessEnabled.get().toString()',
            '"lua_runtime_fault_harness_enabled",\n                luaFaultHarnessEnabled.get().toString()',
            '"-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=ON"',
            'buildConfigField("boolean", "LUA_FAULT_HARNESS_ENABLED", "false")',
            'resValue("bool", "lua_runtime_fault_harness_enabled", "false")',
            '"-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=OFF"',
            "task.name in releaseArtifactTaskNames",
        ),
        "Explicit debug fault-harness build gate",
    )
    release_artifact_tasks_match = re.search(
        r"val releaseArtifactTaskNames = setOf\((.*?)\)",
        build,
        re.DOTALL,
    )
    require(release_artifact_tasks_match is not None, "Release artifact task gate is missing")
    release_artifact_tasks = set(re.findall(r'"([A-Za-z0-9]+)"', release_artifact_tasks_match.group(1)))
    require(
        release_artifact_tasks
        == {
            "assembleRelease",
            "bundleRelease",
            "packageRelease",
            "packageReleaseBundle",
            "packageReleaseUniversalApk",
        },
        "Release artifact task gate either misses an artifact or blocks audit intermediates",
    )

    debug_manifest_path = ROOT / "app/src/debug/AndroidManifest.xml"
    debug_manifest = ET.parse(debug_manifest_path).getroot()
    debug_application = debug_manifest.find("application")
    require(debug_application is not None, "Debug fault manifest has no application node")
    debug_services = debug_application.findall("service")
    expected_fault_services = {
        ".debug.LuaRuntimeFaultService": ":lua_runtime",
        ".debug.LuaRuntimeFaultPeerService": ":lua_fault_peer",
    }
    require(len(debug_services) == len(expected_fault_services), "Debug fault service inventory drift")
    services_by_name = {service.get(ANDROID + "name"): service for service in debug_services}
    require(set(services_by_name) == set(expected_fault_services), "Debug fault service names drift")
    for name, process in expected_fault_services.items():
        service = services_by_name[name]
        require(
            service.get(ANDROID + "enabled") == "@bool/lua_runtime_fault_harness_enabled"
            and service.get(ANDROID + "exported") == "false"
            and service.get(ANDROID + "process") == process
            and not service.findall("intent-filter"),
            f"Debug fault service isolation or explicit-only binding drift: {name}",
        )
    main_manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text("utf-8")
    require("LuaRuntimeFault" not in main_manifest, "Fault service entered the main manifest")
    release_root = ROOT / "app/src/release"
    if release_root.exists():
        release_text = "\n".join(
            path.read_text("utf-8", errors="ignore")
            for path in release_root.rglob("*")
            if path.is_file()
        )
        require("LuaRuntimeFault" not in release_text, "Fault harness entered release sources")

    service_text = (
        ROOT
        / "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt"
    ).read_text("utf-8")
    require_tokens(
        service_text,
        (
            "LuaRuntimeExecutionManager(",
            "object : ILuaRuntimeProvider.Stub()",
            "writeStrongBinder(runtimeProvider)",
            "LuaRuntimeValidation.validateRequestAgainst(",
            "executionManager.create(",
            "incomingOwnership = executionManager.trackIncomingSource()",
            "incomingSource = source",
            "source.contentEquals(FAULT_WEDGE_SOURCE) -> NativeLuaFaults.wedge()",
            "else -> NativeLuaExecutionRunner.execute(request)",
            "LuaRuntimeFaultProcessEpoch.nonce",
            "Application.getProcessName()",
            "TRANSACTION_OPEN_FD_COUNT",
            'File("/proc/self/fd").list()',
        ),
        "Fault harness production-session route",
    )
    for bypass in (
        "Executors.new",
        "LuaExecutionWatchdog(",
        "AndroidLuaRuntimeProcessTerminator",
        "NativeLuaRuntime.execute(",
    ):
        require(bypass not in service_text, f"Fault harness bypasses the production session route: {bypass}")

    peer_service_text = (
        ROOT
        / "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultPeerService.kt"
    ).read_text("utf-8")
    require_tokens(
        peer_service_text,
        (
            "check(BuildConfig.DEBUG && BuildConfig.LUA_FAULT_HARNESS_ENABLED)",
            "check(BuildConfig.LUA_NATIVE_ENABLED && !BuildConfig.LUA_PROVIDER_ENABLED)",
            "object : ILuaExecutionCallback.Stub()",
            "object : ILuaHostCapabilityBroker.Stub()",
            "Binder.getCallingUid() == Process.myUid()",
            "writeStrongBinder(callback)",
            "writeStrongBinder(broker)",
            "mainHandler.post { Process.killProcess(Process.myPid()) }",
            "TRANSACTION_SNAPSHOT",
            "TRANSACTION_KILL",
        ),
        "Debug-only independent Binder peer",
    )

    native_wrapper = (
        ROOT
        / "app/src/debug/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/NativeLuaFaults.kt"
    ).read_text("utf-8")
    require_tokens(
        native_wrapper,
        (
            "check(BuildConfig.DEBUG)",
            "check(BuildConfig.LUA_FAULT_HARNESS_ENABLED)",
            "check(BuildConfig.LUA_NATIVE_ENABLED)",
            "check(!BuildConfig.LUA_PROVIDER_ENABLED)",
            "private external fun nativeCrash()",
            "private external fun nativeWedge()",
        ),
        "Debug-only native fault wrapper",
    )

    cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text("utf-8")
    require_tokens(
        cmake,
        (
            "option(AUTOJS_LUA_DEBUG_FAULT_HARNESS",
            'AUTOJS_LUA_DEBUG_FAULT_HARNESS AND NOT "${CMAKE_BUILD_TYPE}" MATCHES "^[Dd]ebug$"',
            "AUTOJS_LUA_DEBUG_FAULT_HARNESS=1",
        ),
        "Native fault compile boundary",
    )
    native = (ROOT / "app/src/main/cpp/lua_runtime_jni.cpp").read_text("utf-8")
    production_end = native.index("return result;")
    guard_at = native.find("#if defined(AUTOJS_LUA_DEBUG_FAULT_HARNESS)", production_end)
    crash_at = native.find("NativeLuaFaults_nativeCrash", max(guard_at, production_end))
    wedge_at = native.find("NativeLuaFaults_nativeWedge", max(crash_at, production_end))
    end_at = native.find("#endif", max(wedge_at, production_end))
    require(
        production_end <= guard_at < crash_at < wedge_at < end_at,
        "Native fault JNI escaped its debug guard",
    )

    instrumentation = (
        ROOT
        / "app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaRuntimeFaultRecoveryInstrumentationTest.kt"
    ).read_text("utf-8")
    require_tokens(
        instrumentation,
        (
            "nativeCrashCausesBinderDeathAndRecoversInANewProcess",
            "nativeWedgeIsKilledByWatchdogAndRecoversInANewProcess",
            "osFileDescriptorsReturnToBaselineAcrossTerminalAndPeerDeathPaths",
            "hangingPipeSourceIsFailStoppedAndRecoversInANewProcess",
            "assertFalse(BuildConfig.LUA_PROVIDER_ENABLED)",
            "assertProductionProvidersDisabled(context)",
            'assertNotEquals("The fault harness did not enter a remote process", Process.myPid()',
            '"${context.packageName}:lua_runtime"',
            'assertEquals("A second live bind changed the Lua runtime PID"',
            '"The process epoch nonce was not stable across live binds"',
            "linkToDeath",
            "provider.createExecution(",
            "ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)",
            "sourceSha256 = LuaSha256.digest(source)",
            "session.start()",
            "callback.awaitStarted()",
            "LuaRuntimeCodec.decodeStarted(checkNotNull(metadata))",
            "assertStartedWithoutTerminal()",
            "assertCompletedOnce()",
            'assertNotEquals("The Lua runtime process nonce did not change"',
            "assertEquals(7L, executeReturnSeven(context, recovered.client.provider()))",
            "ParcelFileDescriptor.createPipe()",
            "pipe[1].close()",
            "BLOCKED_SOURCE_MAX_ELAPSED_MILLIS",
            "openFileDescriptorCount()",
            "FD_BATCH_REPETITIONS",
            "executeDigestMismatch(context, provider)",
            "executeCancellation(context, provider)",
            "PeerRole.CALLBACK",
            "PeerRole.BROKER",
            "peer.client.killProcess()",
            "awaitOpenFileDescriptorCount(runtime.client, fdBaseline)",
        ),
        "Native fault, FD, blocked-source, and peer-death instrumentation",
    )

    artifact_gate = (ROOT / "tools/verify_fault_harness_artifacts.ps1").read_text("utf-8")
    require_tokens(
        artifact_gate,
        (
            "InvocationStartedAtUtc",
            "status --porcelain --untracked-files=all",
            "VERSION_BUILD must equal the canonical commit count",
            "Artifact predates the canonical invocation",
            "merged_manifest/release",
            "LuaRuntimeService.class",
            "LUA_FAULT_HARNESS_ENABLED = false;",
            "arm64-v8a",
            "x86_64",
            "NativeLuaFaults_nativeCrash",
            "NativeLuaFaults_nativeWedge",
            "LuaRuntimeFaultPeerService",
            ".Contains('LuaRuntimeFault')",
            "':lua_fault_peer'",
            "RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS",
        ),
        "Release fault-harness physical exclusion gate",
    )
    readme = (ROOT / "README.md").read_text("utf-8")
    require_tokens(
        readme,
        (
            "### Pre-release fault-harness checklist",
            ":app:clean",
            ":app:assembleDebug",
            ":app:compileReleaseKotlin",
            ":app:processReleaseMainManifest",
            ":app:externalNativeBuildRelease",
            "-Pautojs.lua.faultHarness.enabled=true",
            "--rerun-tasks",
            "verify_fault_harness_artifacts.ps1",
            "-InvocationStartedAtUtc $faultStarted",
            "RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS",
            "LuaRuntimeFaultRecoveryInstrumentationTest",
        ),
        "Published fault-harness release checklist",
    )


def verify_host_lifecycle_boundary() -> None:
    settings = (ROOT / "settings.gradle.kts").read_text("utf-8")
    require(
        settings.count('include(":host-lifecycle-test")') == 1,
        "Host lifecycle test module is not included exactly once",
    )

    build = (ROOT / "host-lifecycle-test/build.gradle.kts").read_text("utf-8")
    require_tokens(
        build,
        (
            'id("com.android.application")',
            'applicationId = "io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test"',
            '"autojs.lua.hostLifecycle.signingPropertiesFile"',
            '"autojs.lua.hostLifecycle.signingStoreFile"',
            "Host lifecycle signing paths must be absolute",
            'create("hostLifecycle")',
            'signingConfigs.findByName("hostLifecycle")?.let { signingConfig = it }',
            'rootProject.file("protocol/common-plugin-api.aar")',
            'rootProject.file("protocol/protocol-wire-api.aar")',
            'rootProject.file("protocol/lua-runtime-api.aar")',
        ),
        "Standalone Host lifecycle test build boundary",
    )
    require(
        'project(":app")' not in build,
        "Host lifecycle test must not package or compile against the runtime app module",
    )

    manifest_path = ROOT / "host-lifecycle-test/src/main/AndroidManifest.xml"
    manifest = ET.parse(manifest_path).getroot()
    queries = manifest.find("queries")
    query_packages = [] if queries is None else [
        node.get(ANDROID + "name") for node in queries.findall("package")
    ]
    require(
        query_packages == ["io.github.supermonster003.autojs6.plugin.lua.runtime"],
        "Host lifecycle package visibility boundary drift",
    )
    application = manifest.find("application")
    require(application is not None and len(list(application)) == 0, "Host lifecycle APK gained an app component")
    instrumentations = manifest.findall("instrumentation")
    require(len(instrumentations) == 1, "Host lifecycle instrumentation inventory drift")
    instrumentation_manifest = instrumentations[0]
    require(
        instrumentation_manifest.get(ANDROID + "name") == ".LuaHostLifecycleInstrumentation"
        and instrumentation_manifest.get(ANDROID + "targetPackage") == "org.autojs.autojs6"
        and instrumentation_manifest.get(ANDROID + "functionalTest") == "true"
        and instrumentation_manifest.get(ANDROID + "handleProfiling") == "false",
        "Host lifecycle instrumentation no longer targets the real AutoJs6 package",
    )

    instrumentation = (
        ROOT
        / "host-lifecycle-test/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/host/"
        "lifecycle/test/LuaHostLifecycleInstrumentation.kt"
    ).read_text("utf-8")
    require_tokens(
        instrumentation,
        (
            "check(targetContext.packageName == HOST_PACKAGE)",
            'MODE_ARM -> arm(runId)',
            'MODE_VERIFY -> verify(runId)',
            'source = INFINITE_SOURCE',
            'session.start()',
            'callback.awaitStarted()',
            'CountDownLatch(1).await()',
            '"$ARMED_MARKER runId=$runId',
            'ComponentName(PROVIDER_PACKAGE, PROVIDER_SERVICE)',
            'provider.createExecution(',
            'ILuaExecutionCallback.Stub()',
            'ILuaHostCapabilityBroker.Stub()',
            'providerBinder.linkToDeath(providerDeathRecipient, 0)',
            'SystemClock.sleep(STALE_WATCHDOG_PROOF_MILLIS)',
            'providerBinder.isBinderAlive && providerBinder.pingBinder()',
            'providerBinder.unlinkToDeath(providerDeathRecipient, 0)',
            '"$VERIFY_MARKER runId=$runId',
            'val INFINITE_SOURCE = "while true do end"',
            'val RETURN_SEVEN_SOURCE = "return 7"',
        ),
        "Real-Host lifecycle instrumentation matrix",
    )
    require(
        instrumentation.count("executeReturnSeven(targetContext, binding.provider") == 2,
        "Host lifecycle verification must execute both before and after the stale-watchdog window",
    )
    arm_timeout = re.search(r"const val ARM_TIMEOUT_MILLIS = ([0-9_]+)L", instrumentation)
    stale_proof = re.search(r"const val STALE_WATCHDOG_PROOF_MILLIS = ([0-9_]+)L", instrumentation)
    require(arm_timeout is not None and stale_proof is not None, "Host lifecycle timing constants are missing")
    arm_timeout_millis = int(arm_timeout.group(1).replace("_", ""))
    stale_proof_millis = int(stale_proof.group(1).replace("_", ""))
    require(
        stale_proof_millis > arm_timeout_millis + 2_000,
        "Host lifecycle proof does not cross the armed deadline plus cleanup grace",
    )
    require(
        "Process.killProcess" not in instrumentation,
        "Host lifecycle instrumentation must die only through actual package lifecycle",
    )

    orchestrator = (ROOT / "tools/verify_host_lifecycle_matrix.ps1").read_text("utf-8")
    require_tokens(
        orchestrator,
        (
            "$Serial -notmatch '^emulator-[0-9]+$'",
            "'ro.kernel.qemu'",
            "$isQemu -ne '1'",
            "$baselineIdentity.VersionCode -ge $updatedIdentity.VersionCode",
            "$signers.Count -ne 1",
            "'lua_runtime_provider_enabled'",
            "Install-Apk $updatedHost -Replace",
            "$uninstallOutput = (Invoke-Adb @('uninstall', $HostPackage))",
            "Lua provider was removed with the Host package",
            "Invoke-RecoveryVerification",
            "$beforePid -ne $ExpectedRuntimePid",
            "$afterPid -ne $ExpectedRuntimePid",
            "HOST_LIFECYCLE_MATRIX_PASS",
            "update=pass uninstallReinstall=pass recoveryExecutions=4",
        ),
        "Emulator-only Host update/uninstall orchestrator",
    )
    for physical_serial in ("968e9f18", "BH900ASK9E", "QV710AF65F"):
        require(
            physical_serial not in orchestrator,
            "Host lifecycle orchestrator contains a physical-device serial",
        )
    app_build = (ROOT / "app/build.gradle.kts").read_text("utf-8")
    main_manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text("utf-8")
    require(
        "host-lifecycle-test" not in app_build
        and "LuaHostLifecycleInstrumentation" not in main_manifest,
        "Host lifecycle fixture entered the runtime app boundary",
    )
    design = (ROOT / "docs/host-lifecycle-matrix.md").read_text("utf-8")
    require_tokens(
        design,
        (
            "accepts only an online `emulator-*` serial",
            "target package is the real `org.autojs.autojs6` package",
            "Both the callback and",
            "Binder objects live in the actual AutoJs6 process",
            "strictly higher-version Host",
            "uninstall the Host package",
            "immediately executes `return 7`",
            "same provider Binder alive for seven seconds",
            "HOST_LIFECYCLE_MATRIX_PASS",
            "does not publish the provider",
        ),
        "Published Host lifecycle matrix boundary",
    )
    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- [x] **更广的对端死亡矩阵**",
            "R4-A 真实宿主生命周期证据 (2026-08-25)",
            "6b6019243c6cc9a66d54f559450776cf7829a059",
            "Provider versionCode 33",
            "HOST_LIFECYCLE_MATRIX_PASS",
            "`:lua_runtime` PID 均稳定为",
            "14807。每个 case",
        ),
        "R4-A Host lifecycle evidence ledger",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--github-output", action="store_true")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--print-vendor-tree", action="store_true")
    mode.add_argument("--require-build-ready", action="store_true")
    args = parser.parse_args()

    if args.print_vendor_tree:
        lock = load_vendor_lock()
        file_count, tree_sha256 = source_tree_fingerprint(
            vendor_source_tree(lock),
        )
        print(json.dumps({"sourceFileCount": file_count, "sourceTreeSha256": tree_sha256}, indent=2))
        return 0

    protocol_ready = verify_protocol()
    vendor_ready = verify_vendor()
    verify_manifest()
    verify_default_off()
    verify_ci_resilience()
    verify_localization_workflow()
    verify_input_workflows()
    verify_wrapper()
    verify_r4_design_records()
    verify_native_boundary()
    verify_watchdog_boundary()
    verify_descriptor_boundary()
    verify_native_android_test_boundary()
    verify_fault_harness_boundary()
    verify_host_lifecycle_boundary()
    build_ready = protocol_ready and vendor_ready
    if args.require_build_ready:
        require(
            build_ready,
            "Immutable protocol and Lua inputs are not ready for a build-required gate",
        )
    if args.github_output:
        output = os.environ.get("GITHUB_OUTPUT")
        require(bool(output), "GITHUB_OUTPUT is unavailable")
        with Path(output).open("a", encoding="utf-8") as stream:
            stream.write(f"build_ready={'true' if build_ready else 'false'}\n")
    print(
        "STATIC_SCAFFOLD_OK "
        f"protocol={'ready' if protocol_ready else 'not-staged'} "
        f"lua={'ready' if vendor_ready else 'not-vendored'} "
        f"build_ready={str(build_ready).lower()}"
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"STATIC_SCAFFOLD_FAILED: {error}", file=sys.stderr)
        sys.exit(1)
