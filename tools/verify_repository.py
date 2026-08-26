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
TOOLS = "{http://schemas.android.com/tools}"
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
PROTOCOL_SOURCE_PROVENANCE_KEYS = {
    "schemaVersion",
    "sourceRepository",
    "sourceRevision",
    "license",
    "licenseFile",
    "sourceTreeRoot",
    "sourceModules",
    "sourceFileCount",
    "sourceTreeSha256",
    "artifacts",
}
PROTOCOL_SOURCE_TREE_SHA256 = "0f845025cc46041a138de869fefcbcdbcc742e7e0d2f2c08eeffe1f375b97a69"
MPL_2_LICENSE_SHA256 = "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5"
APACHE_2_LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
LUA_LICENSE_SHA256 = "34ebc6be1c5c6be98c975f77aa7e76acf86ef0718e33e8635d7c982a8e43a9fc"
NDK_R28C_NOTICE_SHA256 = "f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1"
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
            service.get(ANDROID + "enabled") is None,
            f"{name} must be unconditionally enabled",
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


LEGACY_LUA_BUILD_SWITCHES = (
    "autojs.lua.native.enabled",
    "autojs.lua.provider.enabled",
    "autojs.lua.faultHarness.enabled",
    "autojs.lua.releaseCandidate.enabled",
    "LUA_NATIVE_ENABLED",
    "LUA_PROVIDER_ENABLED",
    "LUA_FAULT_HARNESS_ENABLED",
    "lua_runtime_provider_enabled",
    "lua_runtime_fault_harness_enabled",
)


def verify_build_modes() -> None:
    expected_jvm_test_count()
    ci = (ROOT / ".github/workflows/ci.yml").read_text("utf-8")
    active_build_files = (
        ROOT / "gradle.properties",
        ROOT / "app/build.gradle.kts",
        ROOT / ".github/workflows/ci.yml",
        ROOT / "tools/verify_local.ps1",
        ROOT / "tools/build_runnable_provider.ps1",
    )
    active_source_roots = (
        ROOT / "app/src/main/java",
        ROOT / "app/src/faultTest/java",
    )
    active_source_files = tuple(
        path
        for source_root in active_source_roots
        if source_root.is_dir()
        for path in sorted(source_root.rglob("*.kt"))
    )
    active_manifest_files = tuple(
        path
        for path in (
            ROOT / "app/src/main/AndroidManifest.xml",
            ROOT / "app/src/nativeTest/AndroidManifest.xml",
            ROOT / "app/src/faultTest/AndroidManifest.xml",
        )
        if path.is_file()
    )
    for path in active_build_files + active_source_files + active_manifest_files:
        text = path.read_text("utf-8")
        for legacy in LEGACY_LUA_BUILD_SWITCHES:
            require(legacy not in text, f"Legacy Lua build switch remains active in {path.name}: {legacy}")
    app_build = (ROOT / "app/build.gradle.kts").read_text("utf-8")
    require_tokens(
        app_build,
        (
            'val runtimeModeDimension = "runtimeMode"',
            'create("provider")',
            'create("nativeTest")',
            'applicationIdSuffix = ".native_test"',
            'create("faultTest")',
            'applicationIdSuffix = ".fault_test"',
            '"-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=ON"',
            'beforeVariants(selector().withBuildType("release"))',
            'if (runtimeMode != "provider")',
            "variantBuilder.enable = false",
            "providerRelease is unsigned; use tools/build_runnable_provider.ps1",
        ),
        "Explicit Lua runtime build variants",
    )
    require(
        'check(android.signingConfigs.findByName("release") != null)' not in app_build,
        "Unsigned aggregate Gradle builds must not require release signing material",
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
    require_tokens(
        ci,
        (":app:testProviderDebugUnitTest", ":app:assembleProviderDebug"),
        "CI providerDebug build posture",
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


def read_localized_readme(language_code: str) -> str:
    return (ROOT / f".readme/README-{language_code}.md").read_text("utf-8")


def verify_localization_workflow() -> None:
    generator = ROOT / ".python/generate_markdown.py"
    require(
        generator.is_file() and not generator.is_symlink(),
        "Missing regular family Markdown generator",
    )
    generator_text = generator.read_text("utf-8")
    require_tokens(
        generator_text,
        (
            'LANGUAGE_CODE_DEFAULT = "zh-Hans"',
            '"zh-Hant-HK"',
            '"zh-Hant-TW"',
            "ANDROID_CHANGELOG_ALIASES",
            "object_pairs_hook=reject_duplicate_pairs",
            "validate_key_parity",
            "validate_collection_shapes",
            "validate_changelog_shapes",
            "validate_no_fullwidth_symbols",
            "validate_localized_resources",
            "PLACEHOLDER_MARKERS",
            "len(artifacts) == 25",
            "actual_inventory == expected_inventory",
            "Generated content drift:",
            "MARKDOWN_OK",
        ),
        "Family Markdown generator",
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
            env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Family Markdown check could not complete") from error
    detail = (completed.stderr or completed.stdout).strip()
    require(completed.returncode == 0, f"Family Markdown check failed: {detail}")
    require(
        completed.stdout.strip()
        == "MARKDOWN_OK languages=10 artifacts=25 mode=check",
        "Family Markdown success receipt drift",
    )

    language_codes = (
        "zh-Hans",
        "zh-Hant-HK",
        "zh-Hant-TW",
        "en",
        "fr",
        "es",
        "ja",
        "ko",
        "ru",
        "ar",
    )
    require(
        tuple(
            path.stem.removeprefix("lang_")
            for path in sorted((ROOT / ".readme").glob("lang_*.json"))
        )
        == tuple(sorted(language_codes))
        and tuple(
            path.stem.removeprefix("lang_")
            for path in sorted((ROOT / ".changelog").glob("lang_*.json"))
        )
        == tuple(sorted(language_codes)),
        "Ten-language JSON source inventory drift",
    )
    root_readme = (ROOT / "README.md").read_text("utf-8")
    simplified_readme = read_localized_readme("zh-Hans")
    english_readme = read_localized_readme("en")
    require(root_readme == simplified_readme, "Root README is not the zh-Hans generated output")
    require_tokens(
        root_readme,
        (
            "简体中文 [zh-Hans] # 当前",
            ".python/generate_markdown.py",
            "python .\\.python\\generate_markdown.py --check",
            "ROADMAP-R5.md",
        ),
        "Generated Simplified Chinese default README",
    )
    require_tokens(
        english_readme,
        (
            "English [en] # current",
            "complete documentation in 10 languages",
            "the active R5 plan in [ROADMAP-R5.md]",
        ),
        "Generated English README",
    )
    require(
        len(list((ROOT / ".readme").glob("README-*.md"))) == 10
        and len(list((ROOT / "app/src/main/assets/doc").glob("CHANGELOG*.md"))) == 14,
        "Generated README/changelog inventory drift",
    )
    legacy_paths = (
        ROOT / "localization",
        ROOT / "tools/generate_localized_content.py",
        ROOT / "README.zh-CN.md",
        ROOT / "CHANGELOG.md",
        ROOT / "CHANGELOG.zh-CN.md",
        ROOT / "app/src/main/res/values-zh-rCN/strings.xml",
    )
    require(
        not any(path.exists() or path.is_symlink() for path in legacy_paths),
        "Legacy localization workflow re-entered the repository",
    )
    roadmap = (ROOT / "ROADMAP.md").read_text("utf-8")
    roadmap_r4 = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    roadmap_r5 = (ROOT / "ROADMAP-R5.md").read_text("utf-8")
    require(
        re.search(r"^## R5(?:\b|-)", roadmap_r4, re.MULTILINE) is None,
        "R5 content re-entered the R4 evidence ledger",
    )
    require_tokens(
        roadmap_r5,
        (
            "# Lua 运行时插件 Roadmap — R5 阶段 (功能优先)",
            "## R5-0 — 显式构建变体",
            "## R5-A — 使用者文档与家族多语言机制",
            "## R5-B — 语言与宿主能力精进",
            "## R5-C — 需宿主协议演进的能力",
            "## R5-D — 发布执行",
            "MARKDOWN_OK languages=10 artifacts=25 mode=check",
            "R5-A 多语言迁移证据 (2026-08-27)",
            "原先单列的",
            '"zh-TW / zh-HK 获得真实翻译后转 active"待办不再存在',
        ),
        "Independent R5 roadmap",
    )
    require(
        "繁体中文槽位真实翻译扩展" not in roadmap_r5,
        "Obsolete planned Traditional Chinese slot task re-entered R5",
    )
    require_tokens(
        roadmap,
        ("[ROADMAP-R4.md](ROADMAP-R4.md)", "[ROADMAP-R5.md](ROADMAP-R5.md)"),
        "Roadmap index",
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

    toast_decision = (ROOT / "docs/ui-toast-v1.md").read_text("utf-8")
    require_tokens(
        toast_decision,
        (
            "Status: **IMPLEMENTED PROVIDER-SIDE — HOST FOLLOW-UP REQUIRED**",
            '`ui.toast.v1`',
            'require("autojs").ui.toast("Saved")',
            'arguments  -> {text=string}',
            'result     -> {accepted=true}',
            "1 through 1,024 bytes inclusive",
            "strict UTF-8",
            "at most four valid toast dispatches",
            "slot is charged",
            "does not refund the slot",
            "performs exactly one Provider-to-",
            "`broker.invoke(...)`",
            "Neither layer contains a retry loop",
            "DENIED`/`HOST_CAPABILITY",
            "no Lua `ui.toast.v1` dispatcher",
            "visual delivery therefore remains a coordinated Host follow-up",
            "## Required evidence",
        ),
        "R4 UI toast V1 decision",
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

    statistics_design = (ROOT / "docs/execution-statistics-v1.md").read_text("utf-8")
    require(
        isinstance(source_revision, str) and source_revision in statistics_design,
        "Execution statistics design does not identify the frozen protocol revision",
    )
    require_tokens(
        statistics_design,
        (
            "Status: **需宿主协议演进 — FIELD DESIGN COMPLETE, NOT IMPLEMENTED**",
            "collects no otherwise-unobservable statistics",
            "SCHEMA_EXECUTION_STATISTICS",
            "`validityFlags`",
            "`peakLuaAllocatorBytes`",
            "`instructionHookInvocations`",
            "`acceptedOutputUtf8Bytes`",
            "`loadNanos`",
            "`executeNanos`",
            "`teardownNanos`",
            "PEAK_LUA_ALLOCATOR_BYTES_VALID",
            "TEARDOWN_NANOS_VALID",
            "LuaExecutionResult.statistics: LuaExecutionStatistics?",
            "LuaExecutionError.statistics: LuaExecutionStatistics?",
            "LuaExecutionCancellation.statistics: LuaExecutionStatistics?",
            "`SCHEMA_RESULT` | 4",
            "`SCHEMA_ERROR` | 6",
            "`SCHEMA_CANCELLATION` | 4",
            "requiredForReader = true",
            "LuaProtocolVersion(1, 1)",
            "`execution.stats.v1`",
            "`requiredCapabilities`",
            "`getRuntimeInfo()` remains the discovery location",
            "## Host-side changes required",
            "## Provider-side changes required",
            "## Current JVM guard",
            "no script source or hash",
        ),
        "R4 execution statistics protocol-evolution design",
    )
    require(
        "execution.stats.v1" not in metadata,
        "Design-only execution statistics capability was advertised before implementation",
    )

    statistics_boundary_test = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/"
        "LuaExecutionStatisticsProtocolBoundaryTest.kt"
    ).read_text("utf-8")
    require_tokens(
        statistics_boundary_test,
        (
            "frozenProtocolRequiresHostEvolutionForExecutionStatistics",
            "assertEquals(0, LuaRuntimeContract.PROTOCOL_MINOR)",
            "assertEquals(setOf(1, 2, 3), tags(LuaRuntimeCodec.encodeResult(result)))",
            "assertEquals((1..5).toSet(), tags(LuaRuntimeCodec.encodeError(error)))",
            "tags(LuaRuntimeCodec.encodeCancellation(cancellation))",
            "tags(LuaRuntimeCodec.encodeRuntimeInfo(runtimeInfo))",
            'getDeclaredMethod("getRuntimeInfo")',
            "ILuaExecutionCallback::class.java.declaredMethods",
            'it.name.contains("stat", ignoreCase = true)',
        ),
        "R4 frozen execution-statistics JVM boundary",
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
            '"lua_runtime_requires_host_version"',
            '"autojs.lua.release.signingPropertiesFile"',
            '"autojs.lua.release.signingStoreFile"',
            '"External release signing paths must be absolute"',
            'tasks.register("verifyReleasePreconditions")',
            '"Release candidates require a version name such as 0.1.0-rc.1"',
            '"providerRelease is unsigned; use tools/build_runnable_provider.ps1',
            'val releaseArtifactTaskNames = setOf(',
            '"packageProviderReleaseUniversalApk"',
            "task.name in releaseArtifactTaskNames",
            'dependsOn("verifyReleasePreconditions")',
        ),
        "Release metadata and strict signed artifact gate",
    )
    require(
        '"packageProviderReleaseResources"' not in app_build,
        "Release resource intermediates must remain available to the unsigned exclusion audit",
    )
    runnable_provider = (ROOT / "tools/build_runnable_provider.ps1").read_text("utf-8")
    require_tokens(
        runnable_provider,
        (
            '":app:clean"',
            '":app:testProviderDebugUnitTest"',
            '":app:assembleProviderRelease"',
            '"--rerun-tasks"',
            '"--offline"',
            'app/build/outputs/apk/provider/release',
            '"org.autojs.plugin.INFO"',
            '"org.autojs.plugin.lua.RUNTIME"',
            'foreach ($abi in @("arm64-v8a", "x86_64"))',
            '$pluginSigner = Read-CertificateSha256 $universalApk',
            '$hostSigner = Read-CertificateSha256 $resolvedHostApk',
            'if ($pluginSigner -ne $hostSigner)',
            '$requiredHostVersionCode = [long] $versionProperties["REQUIRED_HOST_VERSION_CODE"]',
            'if ($hostVersionCode -lt $requiredHostVersionCode)',
            'Runnable provider build requires a clean repository',
            '$sourceRevision = (& git -C $root rev-parse HEAD).Trim()',
            '$sourceCommitCount = [int] (& git -C $root rev-list --count HEAD).Trim()',
            'if ($expectedVersionCode -ne $sourceCommitCount)',
            '$invocationStartedAtUtc = [DateTimeOffset]::UtcNow.ToString(',
            'tools/verify_release_candidate_artifacts.ps1',
            'artifactGateVerified=$($artifactGateVerified.ToString().ToLowerInvariant())',
            'revision=$sourceRevision',
            'apkSha256=$universalSha256',
            'status --porcelain --untracked-files=all',
            'deviceVerified=false runtimeVerified=false',
        ),
        "Runnable same-signer Lua provider workflow",
    )

    candidate_doc = (ROOT / "docs/release-candidate-rc2.md").read_text("utf-8")
    require_tokens(
        candidate_doc,
        (
            "Status: **SIGNED PACKAGING VERIFIED — DEVICE/RUNTIME PENDING**",
            "a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "versionCode 43",
            "59/59",
            "All 87 Gradle tasks executed fresh",
            "93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd",
            "257f4c4a9dceed4fc58e089651370abaaa1384cf09c5f69e6fb21f244d409210",
            "c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12",
            "31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213",
            "SIGNED_RELEASE_CANDIDATE_ARTIFACT_GATE_PASS provider=true faultHarness=false",
            "RUNNABLE_LUA_PROVIDER_OK revision=a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "sourceClean=true artifactGateVerified=true deviceVerified=false runtimeVerified=false",
            "No APK was installed",
            "no physical device was touched",
        ),
        "R4-E signed packaging candidate evidence",
    )
    emulator_smoke = (ROOT / "docs/release-candidate-rc2-emulator-smoke.md").read_text("utf-8")
    require_tokens(
        emulator_smoke,
        (
            "Status: **X86_64 EMULATOR VERIFIED — ARM64 PHYSICAL DEVICE PENDING**",
            "a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "versionCode 43",
            "84dc0a24980deb43d4199bfecee3168e4401da78",
            "versionCode 45",
            "c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12",
            "705f1fb56127d807b8c7fcf759419de5f29592f4affd500444c730357f9ad762",
            "fd9fc6ba3ccc34f977ae2925745e1f525d0522c2a300ce8f109426cd5e57c14f",
            "31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213",
            "emulator-5554",
            "sdk_gphone16k_x86_64",
            "INSTALL_PROVIDER Success",
            "INSTRUMENTATION_RESULT: console=pass",
            "INSTRUMENTATION_RESULT: discovery=pass",
            "INSTRUMENTATION_RESULT: executions=2",
            "INSTRUMENTATION_RESULT: result=pass",
            "LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2emu-install-20260825051216",
            "Physical devices were not",
            "combined R4-E checkbox remains open",
            "does not prove arm64-v8a installation",
        ),
        "R4-E rc.2 exact x86_64 emulator smoke evidence",
    )
    require_tokens(
        candidate_doc,
        (
            "## Subsequent device evidence",
            "release-candidate-rc2-emulator-smoke.md",
            "rc2emu-install-20260825051216",
            "combined R4-E device item remains open",
        ),
        "R4-E candidate-to-emulator-evidence link",
    )
    api_matrix = (ROOT / "docs/release-candidate-rc2-api-matrix.md").read_text("utf-8")
    require_tokens(
        api_matrix,
        (
            "Status: **COMPLETE — API 24 / 31 / 36**",
            "52e41233705d20275945b88589b501af4e4a36c1",
            "a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "0303d66688beff27ed512b952b084f409b349abb908b049d64157ec07c6ceeb4",
            "c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12",
            "19a4a0f0ffae07b08682f87a50e8c6625bfeeab737b51419ecf23f084dceb59f",
            "DEX_R1_API24_X64",
            "AVD_API_31_Play",
            "DEX_R1_API36_X64",
            "rc2api24-20260825052417",
            "rc2api31-20260825052654",
            "rc2api36-20260825052833",
            "executions=2 discovery=pass result=pass console=pass",
            "UNINSTALL_PROVIDER Success packageAbsent=true runtimeAbsent=true",
            "Method.getParameterCount()",
            "method.parameterTypes.size",
            "Physical devices were not touched",
            "No API-specific Provider compatibility defect",
        ),
        "R4-E rc.2 API 24/31/36 compatibility evidence",
    )
    require_tokens(
        candidate_doc,
        (
            "release-candidate-rc2-api-matrix.md",
            "API 24, 31,",
            "and 36 install / real-Host execute / Provider uninstall matrix",
        ),
        "R4-E candidate-to-API-matrix link",
    )
    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- [x] **rc.2 候选重建**",
            "R4-E rc.2 签名打包证据 (2026-08-25)",
            "a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "59/59 JVM、R8、lintVital 与 87 个 fresh Gradle",
            "artifactGateVerified=true",
            "`deviceVerified=false`、`runtimeVerified=false`",
            "docs/release-candidate-rc2.md",
            "R4-E rc.2 x86_64 模拟器半程证据 (2026-08-25)",
            "84dc0a24980deb43d4199bfecee3168e4401da78",
            "LUA_HOST_OFFICIAL_SMOKE_PASS runId=rc2emu-install-20260825051216",
            "arm64-v8a 真机未获授权且未触碰",
            "docs/release-candidate-rc2-emulator-smoke.md",
            "- [x] **API 兼容矩阵扩展**",
            "- [x] **API 24 冒烟入口反射兼容修正**",
            "R4-E rc.2 API 兼容矩阵证据 (2026-08-25)",
            "52e41233705d20275945b88589b501af4e4a36c1",
            "rc2api24-20260825052417",
            "rc2api31-20260825052654",
            "rc2api36-20260825052833",
            "docs/release-candidate-rc2-api-matrix.md",
            "- **设备安装 + 宿主端到端冒烟归档 — 已裁撤 (2026-08-26)**",
        ),
        "R4-E rc.2 completion ledger",
    )
    require(
        "- [ ] **设备安装" not in roadmap and "- [x] **设备安装" not in roadmap,
        "The descoped device-smoke item must not regain a checkbox",
    )
    readme = read_localized_readme("en")
    require_tokens(
        readme,
        (
            "The current signed-packaging candidate belongs to clean plugin revision",
            "a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd",
            "device/runtime verification deliberately",
            "docs/release-candidate-rc2.md",
            "docs/release-candidate-rc2-emulator-smoke.md",
            "arm64 physical-device half was descoped on 2026-08-26",
            "docs/release-candidate-rc2-api-matrix.md",
            "install, real-Host execution, and Provider",
            "uninstall on API 24, 31, and 36",
        ),
        "Published current release-candidate identity",
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
            "app/build/test-results/testProviderDebugUnitTest",
            "app/build/outputs/apk/provider/debug",
            "zipalign -c -P 16 4",
            "apksigner verify --verbose --print-certs",
            "llvm-readelf",
            "app/build/generated/source/buildConfig/provider/debug/",
            'DEBUG = Boolean.parseBoolean("true");',
            "Legacy Lua build-switch resources entered the provider debug APK",
            "Legacy Lua build switches entered generated provider debug BuildConfig",
            "Packaged production service boundary drift",
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
            "app/build/outputs/apk/provider/release",
            "zipalign -c -P 16 4",
            "apksigner verify --verbose --print-certs",
            "Release APK must have exactly one signer",
            "app/build/generated/source/buildConfig/provider/release/",
            "DEBUG = false;",
            "Legacy Lua build-switch resources entered the provider release APK",
            "Legacy Lua build switches entered generated provider release BuildConfig",
            "Packaged release production service boundary drift",
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
            ":app:testProviderDebugUnitTest",
            "--offline",
            "app/build/test-results/testProviderDebugUnitTest",
            "LOCAL_OFFLINE_GATE_PASS",
            "provider=true network=disabled",
        ),
        "One-command offline local gate",
    )
    require(
        "-Pautojs.lua." not in local_gate,
        "The local offline gate must not resurrect legacy Lua build switches",
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
        'constexpr size_t kMaxToastTextBytes = 1024;': "The UI toast byte limit drifted",
        'constexpr uint32_t kMaxToastCallsPerExecution = 4U;': "The UI toast execution quota drifted",
        'int autojs_ui_toast(lua_State* state)': "The fixed UI toast native bridge is missing",
        'control->show_toast_method': "The fixed UI toast JNI call is missing",
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
    strict_utf8_start = native.index("bool is_strict_utf8(const char* text, size_t length)")
    strict_utf8_end = native.index("int autojs_ui_toast(lua_State* state)", strict_utf8_start)
    strict_utf8_boundary = native[strict_utf8_start:strict_utf8_end]
    require_tokens(
        strict_utf8_boundary,
        (
            "first >= 0xC2U && first <= 0xDFU",
            "first == 0xE0U",
            "second_min = 0xA0U",
            "first == 0xEDU",
            "second_max = 0x9FU",
            "first == 0xF0U",
            "second_min = 0x90U",
            "first == 0xF4U",
            "second_max = 0x8FU",
            "continuation < 0x80U || continuation > 0xBFU",
        ),
        "Strict native UI toast UTF-8 validator",
    )
    toast_start = native.index("int autojs_ui_toast(lua_State* state)")
    toast_end = native.index("bool is_flat_ascii_module_name(", toast_start)
    toast_boundary = native[toast_start:toast_end]
    require_tokens(
        toast_boundary,
        (
            "lua_gettop(state) != 1",
            "lua_type(state, 1) != LUA_TSTRING",
            "text_length == 0U",
            "text_length > kMaxToastTextBytes",
            "!is_strict_utf8(text, text_length)",
            "control->toast_dispatches >= kMaxToastCallsPerExecution",
            "++control->toast_dispatches;",
            "NewByteArray(static_cast<jsize>(text_length))",
            "CallVoidMethod(",
            "control->show_toast_method",
            "record_host_call_failure(control)",
            "return 0;",
        ),
        "Fixed native UI toast boundary",
    )
    toast_quota_check = toast_boundary.index(
        "control->toast_dispatches >= kMaxToastCallsPerExecution"
    )
    toast_quota_charge = toast_boundary.index("++control->toast_dispatches;")
    toast_jni_allocation = toast_boundary.index("NewByteArray(")
    toast_dispatch = toast_boundary.index("CallVoidMethod(")
    require(
        toast_quota_check < toast_quota_charge < toast_jni_allocation < toast_dispatch
        and toast_boundary.count("CallVoidMethod(") == 1
        and "for (" not in toast_boundary
        and "while (" not in toast_boundary,
        "UI toast quota is charged late or its native bridge can retry a dispatch",
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
    require(
        install_boundary.count(
            'lua_newtable(state);\n'
            '    lua_pushcfunction(state, autojs_ui_toast);\n'
            '    lua_setfield(state, -2, "toast");\n'
            '    lua_setfield(state, -2, "ui");'
        )
        == 1,
        "The fixed autojs.ui.toast table shape drifted",
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
        and "void showToast(byte[]);" in proguard_rules
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
        "fun showToast(textUtf8: ByteArray)",
        'const val UI_TOAST_CAPABILITY = "ui.toast.v1"',
        "const val MAX_TOAST_TEXT_BYTES = 1024",
        "const val MAX_TOAST_CALLS_PER_EXECUTION = 4",
        "internal fun validateToastAcknowledgement(value: LuaValue)",
        "require(accepted.value)",
        "HOST_FAILURE_INVALID_INPUT = 4",
    ):
        require(token in kotlin_boundary, f"Native Kotlin execution boundary drift: {token}")
    kotlin_toast_start = kotlin_boundary.index("fun showToast(textUtf8: ByteArray)")
    kotlin_toast_end = kotlin_boundary.index("private fun invokeHostCapability(", kotlin_toast_start)
    kotlin_toast_boundary = kotlin_boundary[kotlin_toast_start:kotlin_toast_end]
    require(
        kotlin_toast_boundary.count("invokeHostCapability(") == 1
        and 'capability = UI_TOAST_CAPABILITY' in kotlin_toast_boundary
        and 'mapOf(TOAST_TEXT_KEY to LuaValue.StringValue(text))' in kotlin_toast_boundary
        and "validateToastAcknowledgement(value)" in kotlin_toast_boundary
        and "for (" not in kotlin_toast_boundary
        and "while (" not in kotlin_toast_boundary,
        "Kotlin UI toast bridge is not fixed-shape or can retry",
    )
    provider_metadata = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt"
    ).read_text("utf-8")
    capabilities_start = provider_metadata.index("private val EXECUTION_CAPABILITIES = listOf(")
    capabilities_end = provider_metadata.index("),", capabilities_start)
    advertised_capabilities = re.findall(
        r"NativeLuaHostCapabilityBridge\.([A-Z][A-Z0-9_]+_CAPABILITY)",
        provider_metadata[capabilities_start:capabilities_end],
    )
    require(
        advertised_capabilities
        == ["DEVICE_INFO_CAPABILITY", "MODULE_SNAPSHOT_CAPABILITY", "UI_TOAST_CAPABILITY"],
        f"Provider capability registry lacks a reviewed fixed-shape bridge: {advertised_capabilities}",
    )
    require(
        "capabilities = LuaRuntimeCrashDiagnostics.reportedCapabilities(EXECUTION_CAPABILITIES)"
        in provider_metadata,
        "Runtime-info capabilities no longer decorate the reviewed execution registry",
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
        "UI_TOAST_CAPABILITY": (
            'const val UI_TOAST_CAPABILITY = "ui.toast.v1"',
            "fun showToast(textUtf8: ByteArray)",
            "validateToastAcknowledgement(value)",
            'assertEquals("ui.toast.v1", capability)',
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
            "toastCapabilityGrantDenialAndClosedShapesStayDeterministic",
            "NativeLuaHostCapabilityBridge.validateToastAcknowledgement",
            "HOST_FAILURE_INVALID_INPUT = 4",
            "LuaHostCapabilityFailureKind.DENIED",
            "HOST_FAILURE_REJECTED = 3",
        ),
        "Console and Host-capability JVM boundary",
    )
    require(
        boundary_tests.count("hostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING") == 3
        and boundary_tests.count(
            "assertEquals(LuaHostCapabilityFailureKind.DENIED, denial.kind)"
        )
        == 3
        and boundary_tests.count(
            "assertEquals(HOST_FAILURE_REJECTED, deniedBridge.takeFailureKind())"
        )
        == 3,
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
        "runner = NativeLuaExecutionRunner" in runtime_service,
        "Runtime service is not wired directly to the mandatory native runner",
    )
    require(
        "selectLuaExecutionRunner" not in runtime_service
        and "DisabledLuaExecutionRunner" not in runtime_service,
        "Legacy native/disabled runner selection remains in the runtime service",
    )

    native_doc = (ROOT / "docs/native-execution-core.md").read_text("utf-8")
    require(
        "COMPILED, PACKAGED, DEVICE-EXECUTED, AND MANDATORY" in native_doc,
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
    require("Constructing the adapter does not load JNI" in native_doc, "Native adapter status is ambiguous")
    require("`autojs.now()`" in native_doc, "Native controlled wall-clock API is missing")
    require(
        "`math.randomseed(seed1[, seed2])` wrapper" in native_doc
        and "upstream no-argument branch" in native_doc,
        "Native PRNG seed boundary is missing",
    )
    require("console.info" in native_doc and "console.warn" in native_doc, "Native console aliases are missing")
    require(
        '`require("autojs").ui.toast(text)`' in native_doc
        and "four toast calls" in native_doc
        and "ui-toast-v1.md" in native_doc
        and "passed 17/17" in native_doc
        and "e26fbc1356dc9e98a0fdf11e4ab732f06079eac4" in native_doc,
        "Native UI toast boundary or evidence is missing",
    )

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
            "fc964d448c85f950c27667e7891fbb7d337fe73e",
            "appVersionCode=35 testVersionCode=0 tests=15",
            "appSha256=244345a1eb0512ca8cb018012614713d43eef3aab500d0086a4d1978199ccaac",
            "testSha256=81dfc66b720b05fb8ded3cfbe34ef43c0a5b1a44172531c3f821ca00f8e12cdb",
            "signerSha256=2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8",
            "deadline=pass cancel=pass yieldResumeAccounting=pass caughtOom=pass processReusable=pass",
            "No physical device was installed, uninstalled, queried for mutation",
        ),
        "Controlled coroutine decision and conformance boundary",
    )
    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- [x] **协程库受控引入**",
            "R4-B 协程证据 (2026-08-25)",
            "fc964d448c85f950c27667e7891fbb7d337fe73e",
            "244345a1eb0512ca8cb018012614713d43eef3aab500d0086a4d1978199ccaac",
            "81dfc66b720b05fb8ded3cfbe34ef43c0a5b1a44172531c3f821ca00f8e12cdb",
            "15 项 `NativeLuaRuntimeInstrumentationTest`",
            "未操作物理设备",
        ),
        "R4 controlled coroutine completion evidence",
    )
    toast_doc = (ROOT / "docs/ui-toast-v1.md").read_text("utf-8")
    require_tokens(
        toast_doc,
        (
            "## Provider implementation",
            "## Exact Provider verification",
            "e26fbc1356dc9e98a0fdf11e4ab732f06079eac4",
            "42/42 Python repository/adversarial tests and 50/50 JVM tests passed",
            "passed 17/17 on API",
            "appVersionCode=37 testVersionCode=0 tests=17 jvmTests=50 pythonTests=42",
            "appBytes=1790161",
            "ab62c4bb40f77259f7d5f9eaad5ca213a72186ef8dea0897bd491ca4774aab5a",
            "testBytes=945715",
            "74b1be6024b78bf366d93d19b062841b1064a75c78f67ec490b8df2db5d5763e",
            "2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8",
            "fixedShape=pass quota=pass utf8=pass ack=pass denial=pass noRetry=pass",
            "4a9718d63923834c9a99fd70e0cd58c898e138f6",
            "contained 27",
            "No Host file was changed",
            "none was installed,",
            "`connectedAndroidTest` was not",
        ),
        "UI toast Provider conformance evidence",
    )
    require_tokens(
        roadmap,
        (
            "- [x] **`toast` 能力 (`ui.toast.v1`)**",
            "R4-C toast 证据 (2026-08-25)",
            "e26fbc1356dc9e98a0fdf11e4ab732f06079eac4",
            "42/42 Python、50/50 JVM",
            "17/17 通过",
            "ab62c4bb40f77259f7d5f9eaad5ca213a72186ef8dea0897bd491ca4774aab5a",
            "74b1be6024b78bf366d93d19b062841b1064a75c78f67ec490b8df2db5d5763e",
            "4a9718d63923834c9a99fd70e0cd58c898e138f6",
            "未操作\n物理设备",
        ),
        "R4 UI toast completion evidence",
    )
    readme = read_localized_readme("en")
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
            "internal fun interface LuaProcessTerminationObserver",
            "terminationObserver: LuaProcessTerminationObserver = LuaProcessTerminationObserver.NONE",
            "observeTerminationLocked(token, reason)",
            "runCatching { terminationObserver.beforeTermination(token, reason) }",
            "eventLogger: LuaWatchdogEventLogger = LuaWatchdogEventLogger.NONE",
            "runCatching { eventLogger.logFailStop(reason) }",
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

    log_contract = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/"
        "LuaWatchdogEventLogging.kt"
    ).read_text("utf-8")
    require_tokens(
        log_contract,
        (
            'const val LOGCAT_TAG = "AutoJs6LuaWatchdog"',
            'const val FAIL_STOP_EVENT_TAG = "lua_runtime_fail_stop"',
            'const val DEADLINE_CLEANUP_EXPIRED_TAG = "deadline_cleanup_expired"',
            'const val STOP_CLEANUP_EXPIRED_TAG = "stop_cleanup_expired"',
            'const val WATCHDOG_CONTROL_FAILURE_TAG = "watchdog_control_failure"',
            '"event=$FAIL_STOP_EVENT_TAG reason=${reason.tag()}"',
            "LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED -> DEADLINE_CLEANUP_EXPIRED_TAG",
            "LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED -> STOP_CLEANUP_EXPIRED_TAG",
            "LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE -> WATCHDOG_CONTROL_FAILURE_TAG",
            "internal fun interface LuaWatchdogEventLogger",
        ),
        "Closed content-free watchdog log contract",
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
            "terminationObserver = LuaRuntimeCrashDiagnostics",
            "eventLogger = AndroidLuaWatchdogEventLogger",
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
    require_tokens(
        process_guard,
        (
            "internal object AndroidLuaWatchdogEventLogger : LuaWatchdogEventLogger",
            "Log.e(LuaWatchdogLogContract.LOGCAT_TAG, LuaWatchdogLogContract.message(reason))",
        ),
        "Android watchdog logcat adapter",
    )
    terminate_start = watchdog.index("private fun terminateProcess(reason: LuaProcessTerminationReason)")
    log_at = watchdog.index("eventLogger.logFailStop(reason)", terminate_start)
    terminate_at = watchdog.index("terminator.terminate(reason)", log_at)
    require(log_at < terminate_at, "Watchdog can terminate before emitting its structured event")

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
    require(
        "terminationObserverRunsBeforeTerminatorAndCannotSuppressFailStop" in tests
        and 'events += "diagnostic"' in tests
        and 'events += "log"' in tests
        and 'events += "terminate"' in tests
        and 'assertEquals(listOf("diagnostic", "log", "terminate"), events)' in tests,
        "Watchdog diagnostic-before-termination ordering evidence is missing",
    )
    require_tokens(
        tests,
        (
            "everyFailStopReasonUsesTheClosedStructuredLogContract",
            "assertEquals(LuaProcessTerminationReason.entries, logged)",
            '"event=lua_runtime_fail_stop reason=deadline_cleanup_expired"',
            '"event=lua_runtime_fail_stop reason=stop_cleanup_expired"',
            '"event=lua_runtime_fail_stop reason=watchdog_control_failure"',
            'error("injected log failure")',
        ),
        "Three-reason watchdog structured-log JVM coverage",
    )

    logging_doc = (ROOT / "docs/watchdog-event-logging.md").read_text("utf-8")
    require_tokens(
        logging_doc,
        (
            "Status: **IMPLEMENTED — PROVIDER DEFAULT-OFF**",
            "event=lua_runtime_fail_stop reason=<closed_reason_tag>",
            "`DEADLINE_CLEANUP_EXPIRED` | `deadline_cleanup_expired`",
            "`STOP_CLEANUP_EXPIRED` | `stop_cleanup_expired`",
            "`WATCHDOG_CONTROL_FAILURE` | `watchdog_control_failure`",
            "`Log.e(LOGCAT_TAG, message)`",
            "diagnostic`, `log`, `terminate`",
            "no execution token, request ID, PID, UID, source name, source body",
            "44/44 Python tests and 59/59 JVM",
            "no new device claim is made",
        ),
        "Watchdog event logging documentation",
    )
    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- [x] **watchdog 事件可追溯**",
            "R4-D watchdog 日志证据 (2026-08-25)",
            "`AutoJs6LuaWatchdog`",
            "`event=lua_runtime_fail_stop reason=<closed_reason_tag>`",
            "`deadline_cleanup_expired`、`stop_cleanup_expired`、`watchdog_control_failure`",
            "`diagnostic, log, terminate`",
            "44/44 Python、59/59 JVM",
            "docs/watchdog-event-logging.md",
        ),
        "R4 watchdog event logging completion evidence",
    )


def verify_crash_diagnostic_boundary() -> None:
    record = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/diagnostic/"
        "LuaCrashDiagnostic.kt"
    ).read_text("utf-8")
    require_tokens(
        record,
        (
            '"diagnostic.last-abnormal-termination.v1"',
            "NATIVE_CRASH(1)",
            "DEADLINE_CLEANUP_EXPIRED(2)",
            "STOP_CLEANUP_EXPIRED(3)",
            "WATCHDOG_CONTROL_FAILURE(4)",
            "QUEUE(1)",
            "SOURCE_VALIDATION(2)",
            "NATIVE_EXECUTION(3)",
            "const val SOURCE_HASH_PREFIX_BYTES = 8",
            "const val ENCODED_BYTES = 20",
            "CRC32()",
            "AtomicLuaCrashDiagnosticStorage(context.applicationContext.noBackupFilesDir)",
            'const val DIRECTORY_NAME = "lua-runtime-diagnostics"',
            'const val FILE_NAME = "last-abnormal-termination.v1"',
            "ByteArray(LuaCrashDiagnosticCodec.ENCODED_BYTES + 1)",
            "atomicFile.startWrite()",
            "atomicFile.finishWrite(output)",
            "atomicFile.failWrite(output)",
        ),
        "Private fixed-shape crash diagnostic record",
    )
    require(
        "sourceUtf8" not in record
        and "sourceText" not in record
        and "stackTrace" not in record
        and "Throwable.printStackTrace" not in record,
        "Crash diagnostic storage can retain source content or a stack trace",
    )

    coordinator = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/diagnostic/"
        "LuaRuntimeCrashDiagnostics.kt"
    ).read_text("utf-8")
    require_tokens(
        coordinator,
        (
            "internal class LuaCrashDiagnosticCoordinator",
            "sourceSha256.toByteArray().copyOf(LuaCrashDiagnostic.SOURCE_HASH_PREFIX_BYTES)",
            "execution?.provisionalNativeCrashWritten == true && !execution.terminationCommitted",
            "execution.token !== token",
            "failureKind = reason.toCrashFailureKind()",
            "execution.terminationCommitted = true",
            "failureKind = LuaCrashFailureKind.NATIVE_CRASH",
            "if (!execution.terminationCommitted && execution.provisionalNativeCrashWritten)",
            "store.clear()",
            "LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED",
            "LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED",
            "LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE",
            "withLastAbnormalTerminationFlag(executionCapabilities, current().hasReportableDiagnostic())",
            "if (present && LAST_ABNORMAL_TERMINATION_FLAG !in capabilities)",
        ),
        "Token-bound crash diagnostic lifecycle",
    )

    metadata = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaProviderMetadata.kt"
    ).read_text("utf-8")
    require_tokens(
        metadata,
        (
            "private val EXECUTION_CAPABILITIES = listOf(",
            "capabilities = LuaRuntimeCrashDiagnostics.reportedCapabilities(EXECUTION_CAPABILITIES)",
        ),
        "Runtime-info abnormal-termination marker",
    )

    service = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/"
        "LuaRuntimeService.kt"
    ).read_text("utf-8")
    debug_service = (
        ROOT
        / "app/src/faultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/"
        "LuaRuntimeFaultService.kt"
    ).read_text("utf-8")
    for label, service_text in (("Provider", service), ("fault harness", debug_service)):
        initialize_at = service_text.index("LuaRuntimeCrashDiagnostics.initialize(this)")
        manager_at = service_text.index("LuaRuntimeExecutionManager(")
        require(
            initialize_at < manager_at,
            f"{label} service does not initialize crash diagnostics before its execution manager",
        )
    require(
        "LuaRuntimeCrashDiagnostics.reportedCapabilities(" in debug_service,
        "Fault harness runtime-info does not report persisted crash state",
    )

    manager = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/"
        "LuaRuntimeExecutionManager.kt"
    ).read_text("utf-8")
    require_tokens(
        manager,
        (
            "LuaRuntimeCrashDiagnostics.requireInitialized()",
            "LuaRuntimeCrashDiagnostics.acquire(token, request.sourceSha256)",
            "crashDiagnosticLease = LuaExecutionCrashDiagnosticLease.NONE",
            "crashDiagnostics = crashDiagnosticLease",
            "terminationObserver = LuaRuntimeCrashDiagnostics",
        ),
        "Execution-manager crash diagnostic ownership",
    )
    require(
        manager.index("LuaRuntimeCrashDiagnostics.acquire(token, request.sourceSha256)")
        < manager.index("ProcessExecutionResources.watchdog.tryAcquire("),
        "Crash diagnostic token is acquired after its watchdog token",
    )

    controller = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/"
        "LuaExecutionSessionController.kt"
    ).read_text("utf-8")
    require_tokens(
        controller,
        (
            "crashDiagnostics.sourceValidationStarted()",
            "crashDiagnostics.nativeExecutionStarted()",
            "crashDiagnostics.nativeExecutionReturned()",
            "crashDiagnostics.closeQuietly()",
        ),
        "Execution-phase crash diagnostic lifecycle",
    )
    native_started_at = controller.index("crashDiagnostics.nativeExecutionStarted()")
    runner_at = controller.index("runner.execute(", native_started_at)
    native_returned_at = controller.index("crashDiagnostics.nativeExecutionReturned()", runner_at)
    require(
        native_started_at < runner_at < native_returned_at
        and "} finally {" in controller[runner_at:native_returned_at],
        "Native crash provisional record no longer brackets every runner return",
    )

    watchdog = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/"
        "LuaExecutionWatchdog.kt"
    ).read_text("utf-8")
    observer_at = watchdog.index("observeTerminationLocked(token, reason)")
    terminator_at = watchdog.index("if (shouldTerminate) terminateProcess(reason)", observer_at)
    require(
        observer_at < terminator_at,
        "Watchdog termination can run before its diagnostic observer",
    )

    unit_tests = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/diagnostic/"
        "LuaCrashDiagnosticTest.kt"
    ).read_text("utf-8")
    require_tokens(
        unit_tests,
        (
            "fixedRecordRoundTripsWithoutExposingMutableHashBytes",
            "malformedOrOversizedPrivateRecordIsRejectedAndDeleted",
            "provisionalNativeCrashIsHiddenAndClearedAfterManagedReturn",
            "watchdogCommitOverwritesProvisionalRecordAndSurvivesWorkerReturn",
            "runtimeInfoFlagIsConditionalContentFreeAndNeverDuplicated",
        ),
        "Crash diagnostic JVM coverage",
    )
    controller_tests = (
        ROOT
        / "app/src/test/java/io/github/supermonster003/autojs6/plugin/lua/runtime/execution/"
        "LuaExecutionSessionControllerTest.kt"
    ).read_text("utf-8")
    require(
        "crashDiagnosticLeaseBracketsOnlyVerifiedRunnerExecution" in controller_tests,
        "Controller crash diagnostic phase coverage is missing",
    )

    instrumentation = (
        ROOT
        / "app/src/androidTestFaultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/"
        "LuaRuntimeFaultRecoveryInstrumentationTest.kt"
    ).read_text("utf-8")
    require_tokens(
        instrumentation,
        (
            "resetCrashDiagnostic(context)",
            "assertPersistedCrashDiagnostic(",
            "LAST_ABNORMAL_TERMINATION_FLAG in runtimeInfo.capabilities",
            "LuaCrashDiagnosticStore.forContext(context).read()",
            "expectedSourceSha256.toByteArray().copyOf(8)",
            "assertCrashDiagnosticCleared(context, provider)",
            "LuaCrashFailureKind.NATIVE_CRASH",
            "LuaCrashFailureKind.DEADLINE_CLEANUP_EXPIRED",
            "LuaCrashPhase.NATIVE_EXECUTION",
            "LuaCrashPhase.SOURCE_VALIDATION",
        ),
        "Crash/restart/runtime-info instrumentation evidence",
    )

    native = (ROOT / "app/src/main/cpp/lua_runtime_jni.cpp").read_text("utf-8")
    kotlin_boundary = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt"
    ).read_text("utf-8")
    require(
        "diagnostic.last-abnormal-termination.v1" not in native
        and "diagnostic.last-abnormal-termination.v1" not in kotlin_boundary,
        "Observational crash marker entered the executable Host-capability bridge",
    )

    crash_doc = (ROOT / "docs/crash-diagnostic-v1.md").read_text("utf-8")
    require_tokens(
        crash_doc,
        (
            "Status: **IMPLEMENTED — DEVICE VERIFIED**",
            "exactly 20 bytes",
            "noBackupFilesDir",
            "first eight digest bytes, never source bytes",
            "`NATIVE_CRASH/NATIVE_EXECUTION`",
            "provisional marker",
            "poisons its owned token first",
            "Observer exceptions",
            "storage failure cannot suppress mandatory fail-stop",
            "Reads are non-consuming",
            "next verified native execution",
            "content-free marker",
            "Repository static checks reject source/stack retention",
            "d882dea986dd95f1f0e6aeb484618ca99f703dfb",
            "44/44 Python hostile/static tests and 58/58",
            "appVersionCode=40 testVersionCode=0 tests=4",
            "1552e47be94542b0fab5fa28624691f2c7c3ee3e493c14ece9dc9816c5d75e8f",
            "a105d92e2fe476d246e0b57d1109bb5a22a1e7d82f65a6e96f92c9901035167f",
            "2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8",
            "CRASH_DIAGNOSTIC_INSTRUMENTATION_PASS serial=emulator-5554",
            "nativeCrash=pass nativeWedge=pass blockedSource=pass healthyClear=pass",
            "physicalDevicesUntouched=true",
        ),
        "Crash diagnostic lifecycle documentation",
    )

    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- [x] **崩溃诊断落盘**",
            "R4-D 崩溃诊断证据 (2026-08-25)",
            "d882dea986dd95f1f0e6aeb484618ca99f703dfb",
            "44/44 Python、58/58 JVM",
            "完整 fault instrumentation 4/4 (7.984 秒)",
            "`NATIVE_CRASH/NATIVE_EXECUTION`",
            "`DEADLINE_CLEANUP_EXPIRED/SOURCE_VALIDATION`",
            "最终私有目录为空",
            "未运行 `connectedAndroidTest`，未操作物理设备",
        ),
        "R4 crash diagnostic completion evidence",
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
        / "app/src/faultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt"
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
            "It never retries a dispatched host call.",
            "descriptorLedger.acquire(LuaFileDescriptorKind.HOST_CALLBACK_PAYLOAD)",
            "descriptors.forEachIndexed { index, descriptor ->",
            "runCatching { descriptor?.close() }",
            "ownerships[index]?.close()",
        ),
        "Host callback PFD ownership",
    )
    invoke_start = host_invoker.index("override fun invoke(")
    invoke_end = host_invoker.index("override fun close()", invoke_start)
    invoke_boundary = host_invoker[invoke_start:invoke_end]
    broker_dispatch = invoke_boundary.index("broker.invoke(")
    callback_wait = invoke_boundary.index("while (terminal.get() == null)")
    require(
        invoke_boundary.count("broker.invoke(") == 1
        and broker_dispatch < callback_wait
        and "broker.invoke(" not in invoke_boundary[callback_wait:],
        "Binder Host capability invoker can retry a dispatched call",
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
            'create("nativeTest")',
            'applicationIdSuffix = ".native_test"',
        ),
        "Provider-disabled native Android test configuration",
    )

    test = (
        ROOT
        / "app/src/androidTestNativeTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntimeInstrumentationTest.kt"
    ).read_text("utf-8")
    require_tokens(
        test,
        (
            'context.packageName.endsWith(".native_test")',
            "PackageManager.NameNotFoundException::class.java",
            "providerServicesAreAbsentDuringNativeTests",
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
            "nativeRunnerMapsTheFixedToastCapabilityWithoutAResultOrRetry",
            "assert(autojs.ui.toast('保存完成') == nil)",
            "assert(autojs.ui.toast('A' .. string.char(0) .. 'B') == nil)",
            "assertEquals(1, malformedCalls)",
            "nativeRunnerEnforcesToastTextAndExecutionQuotasBeforeHostDispatch",
            "for index = 1, 4 do toast('accepted-' .. index) end",
            "local child = coroutine.create(function() toast('fifth') end)",
            "toast('sixth')",
            "string.rep('x', 1025)",
            "string.char(0xc3, 0x28)",
            "string.char(0xc0, 0x80)",
            "string.char(0xed, 0xa0, 0x80)",
            "string.char(0xf4, 0x90, 0x80, 0x80)",
            "string.char(0xf0, 0x90)",
            "string.rep('x', 1024)",
            "assertEquals(4, calls)",
            "assertEquals(5, calls)",
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
            'create("provider")',
            'create("nativeTest")',
            'create("faultTest")',
            'applicationIdSuffix = ".fault_test"',
            '"-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=ON"',
            '"-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=OFF"',
            'beforeVariants(selector().withBuildType("release"))',
            'if (runtimeMode != "provider")',
            "task.name in releaseArtifactTaskNames",
        ),
        "Explicit faultTest build variant",
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
            "assembleProviderRelease",
            "bundleProviderRelease",
            "packageProviderRelease",
            "packageProviderReleaseBundle",
            "packageProviderReleaseUniversalApk",
        },
        "Release artifact task gate either misses an artifact or blocks audit intermediates",
    )

    fault_manifest_path = ROOT / "app/src/faultTest/AndroidManifest.xml"
    fault_manifest = ET.parse(fault_manifest_path).getroot()
    fault_application = fault_manifest.find("application")
    require(fault_application is not None, "faultTest manifest has no application node")
    fault_services = fault_application.findall("service")
    expected_fault_services = {
        ".debug.LuaRuntimeFaultService": ":lua_runtime",
        ".debug.LuaRuntimeFaultPeerService": ":lua_fault_peer",
    }
    removed_provider_services = {
        ".service.LuaPluginInfoService",
        ".service.LuaRuntimeService",
    }
    services_by_name = {service.get(ANDROID + "name"): service for service in fault_services}
    require(
        set(services_by_name) == set(expected_fault_services) | removed_provider_services,
        "faultTest service/remove inventory drift",
    )
    for name in removed_provider_services:
        service = services_by_name[name]
        require(
            service.get(TOOLS + "node") == "remove" and len(service.attrib) == 2,
            f"faultTest does not physically remove production service: {name}",
        )
    for name, process in expected_fault_services.items():
        service = services_by_name[name]
        require(
            service.get(ANDROID + "enabled") is None
            and service.get(ANDROID + "exported") == "false"
            and service.get(ANDROID + "process") == process
            and not service.findall("intent-filter"),
            f"faultTest service isolation or explicit-only binding drift: {name}",
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
        / "app/src/faultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultService.kt"
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
        / "app/src/faultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/LuaRuntimeFaultPeerService.kt"
    ).read_text("utf-8")
    require_tokens(
        peer_service_text,
        (
            'check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".fault_test"))',
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
        / "app/src/faultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/debug/NativeLuaFaults.kt"
    ).read_text("utf-8")
    require_tokens(
        native_wrapper,
        (
            "check(BuildConfig.DEBUG)",
            'BuildConfig.APPLICATION_ID.endsWith(".fault_test")',
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
        / "app/src/androidTestFaultTest/java/io/github/supermonster003/autojs6/plugin/lua/runtime/LuaRuntimeFaultRecoveryInstrumentationTest.kt"
    ).read_text("utf-8")
    require_tokens(
        instrumentation,
        (
            "nativeCrashCausesBinderDeathAndRecoversInANewProcess",
            "nativeWedgeIsKilledByWatchdogAndRecoversInANewProcess",
            "osFileDescriptorsReturnToBaselineAcrossTerminalAndPeerDeathPaths",
            "hangingPipeSourceIsFailStoppedAndRecoversInANewProcess",
            'BuildConfig.APPLICATION_ID.endsWith(".fault_test")',
            "PackageManager.NameNotFoundException::class.java",
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
            "assertEquals(7L, executeReturnSeven(context, provider))",
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
            "merged_manifest/providerRelease",
            "merged_manifest/faultTestDebug",
            "faultTest/debug",
            "provider/release",
            'DEBUG = Boolean.parseBoolean("true");',
            "DEBUG = false;",
            "LuaRuntimeService.class",
            'Legacy Lua build switches entered $($entry.Label) BuildConfig',
            "Production Provider services entered faultTestDebug",
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
    readme = read_localized_readme("en")
    checklist_start = readme.index("#### Pre-release fault-harness checklist")
    checklist_end = readme.index("\n******", checklist_start)
    fault_checklist = readme[checklist_start:checklist_end]
    require_tokens(
        fault_checklist,
        (
            "#### Pre-release fault-harness checklist",
            ":app:clean",
            ":app:assembleFaultTestDebug",
            ":app:compileProviderReleaseKotlin",
            ":app:processProviderReleaseMainManifest",
            ":app:externalNativeBuildProviderRelease",
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
            'MODE_SMOKE -> smoke(runId)',
            'MODE_INCOMPATIBLE -> incompatible(runId)',
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
            'requiredVersionArgument(ARG_EXPECTED_HOST_VERSION_CODE)',
            'requiredVersionArgument(ARG_EXPECTED_PROVIDER_VERSION_CODE)',
            'singleEnabledService(context, LuaPluginActions.INFO)',
            'singleEnabledService(context, LuaRuntimeContract.SERVICE_ACTION)',
            'packageManager.checkSignatures(HOST_PACKAGE, PROVIDER_PACKAGE)',
            'context.classLoader.loadClass(HOST_ENGINE_CLASS)',
            'method.parameterTypes.size == 1',
            'requireInt64Result(firstResult, 7L, "return 7")',
            'requireInt64Result(deviceResult, Build.VERSION.SDK_INT.toLong(), "device.info")',
            'awaitConsoleMarker(context, stdout)',
            '"$SMOKE_MARKER runId=$runId',
            'runCatching { invokeReflective(engineClass.getMethod("init"), engine) }',
            'failure.javaClass.name == HOST_EXCEPTION_CLASS',
            'hostFailure.javaClass.getMethod("getCode")',
            'hostFailure.javaClass.getMethod("getEvaluations")',
            'value.javaClass.getMethod("getRejection")',
            'EXPECTED_HOST_FAILURE_CODE = "LUA_RUNTIME_UNAVAILABLE"',
            'EXPECTED_PROVIDER_REJECTION = "HOST_VERSION_UNSUPPORTED"',
            '"$INCOMPATIBLE_MARKER runId=$runId',
            '"dispatch", "not-entered"',
            'val INFINITE_SOURCE = "while true do end"',
            'val RETURN_SEVEN_SOURCE = "return 7"',
        ),
        "Real-Host lifecycle instrumentation matrix",
    )
    require(
        instrumentation.count("executeReturnSeven(targetContext, binding.provider") == 2,
        "Host lifecycle verification must execute both before and after the stale-watchdog window",
    )
    require(
        "method.parameterCount" not in instrumentation,
        "Host smoke reflection must remain callable on API 24",
    )
    incompatible_start = instrumentation.index("private fun incompatible(runId: String)")
    incompatible_end = instrumentation.index("private fun requiredVersionArgument", incompatible_start)
    incompatible = instrumentation[incompatible_start:incompatible_end]
    require(
        "executeHostLua(" not in incompatible and 'getMethod("execute")' not in incompatible,
        "Older-Host rejection proof must not enter script dispatch",
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
            "'LuaPluginInfoService'",
            "'LuaRuntimeService'",
            "'android:enabled=\"false\"'",
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

    release_orchestrator = (ROOT / "tools/verify_release_upgrade_matrix.ps1").read_text("utf-8")
    require_tokens(
        release_orchestrator,
        (
            "$Serial -notmatch '^emulator-[0-9]+$'",
            "'ro.kernel.qemu'",
            "$isQemu -ne '1'",
            "$ExpectedCurrentHostVersionCode = 5276L",
            "$ExpectedRc1VersionName = '0.1.0-rc.1'",
            "$ExpectedRc2VersionName = '0.1.0-rc.2'",
            "$ExpectedOuterCode = 'LUA_RUNTIME_UNAVAILABLE'",
            "$ExpectedRejection = 'HOST_VERSION_UNSUPPORTED'",
            "$signers.Count -ne 1",
            "foreach ($provider in @($rc1Provider, $rc2Provider))",
            "'LuaPluginInfoService'",
            "'LuaRuntimeService'",
            "'android:enabled=\"false\"'",
            "Install-Apk $rc1Provider",
            "Install-Apk $rc2Provider -Replace",
            "(?:userId|appId)=([0-9]+)",
            "$rc2Upgraded.Uid -ne $rc1Installed.Uid",
            "$rc2Upgraded.FirstInstallTime -ne $rc1Installed.FirstInstallTime",
            "$uninstallOutput = (Invoke-Adb @('uninstall', $ProviderPackage))",
            "$runtimeAbsent = (Read-OptionalPid $RuntimeProcess) -eq $null",
            "Install-Apk $olderHost -Replace -AllowDowngrade",
            "-Mode 'incompatible'",
            '"outerCode=$ExpectedOuterCode"',
            '"rejection=$ExpectedRejection"',
            "'dispatch=not-entered'",
            "Install-Apk $currentHost -Replace",
            "& $script:Adb -s $Serial install -r $rc2Provider",
            "RELEASE_UPGRADE_CASE_PASS case=rc1-to-rc2",
            "RELEASE_UPGRADE_CASE_PASS case=rc2-uninstall-reinstall",
            "RELEASE_UPGRADE_CASE_PASS case=older-host-rejection",
            "RELEASE_UPGRADE_MATRIX_PASS",
            "upgrade=pass uninstallReinstall=pass olderHostRejection=pass currentHostRestore=pass",
        ),
        "Emulator-only release upgrade and rollback matrix",
    )
    for physical_serial in ("968e9f18", "BH900ASK9E", "QV710AF65F"):
        require(
            physical_serial not in release_orchestrator,
            "Release upgrade orchestrator contains a physical-device serial",
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
    release_design = (ROOT / "docs/release-upgrade-matrix.md").read_text("utf-8")
    require_tokens(
        release_design,
        (
            "accepts only an online",
            "`emulator-*` serial",
            "rc.1 to rc.2 package replacement",
            "firstInstallTime` must remain unchanged",
            "rc.2 uninstall and clean reinstall",
            "`:lua_runtime` PID to be absent",
            "`LUA_RUNTIME_UNAVAILABLE`",
            "`HOST_VERSION_UNSUPPORTED`",
            "records `dispatch=not-entered`",
            "real runtime-info probe",
            "5276 restoration",
            "RELEASE_UPGRADE_MATRIX_PASS",
            "It is not physical-device or public-release evidence",
            "b3cae39f63561cea81392051a7e8b4361cac9129",
            "9a738fa91973643b7c3b0a2ecd30612237decacb65fd5b15e337c14e45580e48",
            "813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e",
            "2bdd0f37bdc9a8ed365f55def12e500c37423364cd5c16916d8bb9a325e9ce28",
            "0fc069ccfa42a0d18ec57fee535515abf6b8f1c1409847371c56652543bb9c75",
            "rc2-older-host-ccec32723f8b40d1ad4acc5c9490eb97",
            "outerCode=LUA_RUNTIME_UNAVAILABLE rejection=HOST_VERSION_UNSUPPORTED dispatch=not-entered",
            "upgrade=pass uninstallReinstall=pass olderHostRejection=pass currentHostRestore=pass",
            "No physical Android device was addressed or mutated",
        ),
        "Published release upgrade and rollback boundary",
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
            "- [x] **升级/回滚路径证据**",
            "R4-E 升级/回滚路径证据 (2026-08-25)",
            "b3cae39f63561cea81392051a7e8b4361cac9129",
            "UID 10229 与 `firstInstallTime` 保持不变",
            "`LUA_RUNTIME_UNAVAILABLE`、唯一 rejection",
            "`HOST_VERSION_UNSUPPORTED` 与 `dispatch=not-entered`",
            "`RELEASE_UPGRADE_MATRIX_PASS`",
            "[`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md)",
        ),
        "R4-A Host lifecycle evidence ledger",
    )


def verify_public_release_materials() -> None:
    protocol_lock = require_exact_keys(
        load_json_strict(ROOT / "protocol/protocol-artifacts.lock.json"),
        PROTOCOL_LOCK_KEYS,
        "Protocol lock",
    )
    require(protocol_lock["status"] == "staged", "Public materials require staged protocol AARs")

    snapshot_root = ROOT / "third_party/autojs6-protocol-source"
    require(
        snapshot_root.is_dir() and not snapshot_root.is_symlink(),
        "Missing regular AutoJs6 corresponding-source directory",
    )
    require(
        {path.name for path in snapshot_root.iterdir()}
        == {"LICENSE", "README.md", "SOURCE_PROVENANCE.json", "plugin-api"},
        "AutoJs6 corresponding-source top-level inventory drift",
    )
    provenance = require_exact_keys(
        load_json_strict(snapshot_root / "SOURCE_PROVENANCE.json"),
        PROTOCOL_SOURCE_PROVENANCE_KEYS,
        "AutoJs6 corresponding-source provenance",
    )
    require_schema_one(provenance["schemaVersion"], "AutoJs6 corresponding-source provenance")
    require(
        provenance["sourceRepository"] == "https://github.com/SuperMonster003/AutoJs6",
        "AutoJs6 corresponding-source repository drift",
    )
    require(
        provenance["sourceRevision"] == protocol_lock["sourceRevision"]
        == "3b7378758c5a4f68e8680a78cf2c541c23628489",
        "AutoJs6 corresponding-source revision drift",
    )
    require(provenance["license"] == "MPL-2.0", "Protocol source license drift")
    require(provenance["licenseFile"] == "LICENSE", "Protocol source license path drift")
    require(provenance["sourceTreeRoot"] == "plugin-api", "Protocol source-tree root drift")
    require(
        provenance["sourceModules"]
        == [
            ":plugin-api:common-plugin-api",
            ":plugin-api:protocol-wire-api",
            ":plugin-api:lua-runtime-api",
        ],
        "Protocol corresponding-source module inventory drift",
    )
    require(
        provenance["artifacts"] == protocol_lock["artifacts"],
        "Protocol AAR and corresponding-source bindings disagree",
    )
    source_tree = snapshot_root / str(provenance["sourceTreeRoot"])
    require(
        source_tree.resolve().parent == snapshot_root.resolve(),
        "Protocol corresponding-source tree escaped its snapshot",
    )
    source_count, source_digest = source_tree_fingerprint(source_tree)
    require(
        type(provenance["sourceFileCount"]) is int
        and provenance["sourceFileCount"] == source_count == 35,
        "Protocol corresponding-source file count drift",
    )
    require(
        provenance["sourceTreeSha256"]
        == source_digest
        == PROTOCOL_SOURCE_TREE_SHA256,
        "Protocol corresponding-source tree digest drift",
    )

    frozen_texts = {
        snapshot_root / "LICENSE": MPL_2_LICENSE_SHA256,
        ROOT / "third_party/apache-2.0/LICENSE.txt": APACHE_2_LICENSE_SHA256,
        ROOT / "third_party/lua-5.4/LICENSE.txt": LUA_LICENSE_SHA256,
        ROOT / "third_party/android-ndk-r28c/NOTICE.toolchain.txt": NDK_R28C_NOTICE_SHA256,
    }
    for path, expected_digest in frozen_texts.items():
        require(path.is_file() and not path.is_symlink(), f"Missing regular license/notice: {path}")
        require(sha256(path) == expected_digest, f"License/notice digest drift: {path}")

    notices = (ROOT / "THIRD_PARTY_NOTICES.md").read_text("utf-8")
    require_tokens(
        notices,
        (
            "## AutoJs6 protocol APIs",
            "3b7378758c5a4f68e8680a78cf2c541c23628489",
            "Mozilla Public License 2.0 (MPL-2.0)",
            "third_party/autojs6-protocol-source",
            "## Kotlin Standard Library 2.3.20",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
            "## JetBrains Annotations 13.0",
            "org.jetbrains:annotations:13.0",
            "## Android NDK r28c LLVM runtimes",
            "-static-libstdc++",
            "97a699bf4812a18fb657c2779f5296a4ab2694d2",
            "f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1",
            "## PUC Lua 5.4.8",
            "copyright 1994-2025 Lua.org, PUC-Rio",
        ),
        "Complete third-party notice inventory",
    )

    app_build = (ROOT / "app/build.gradle.kts").read_text("utf-8")
    require_tokens(
        app_build,
        (
            '"-DANDROID_STL=c++_static"',
            'implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")',
            'resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")',
        ),
        "Release dependency and native-license boundary",
    )
    artifact_gate = (ROOT / "tools/verify_release_candidate_artifacts.ps1").read_text("utf-8")
    require_tokens(
        artifact_gate,
        (
            "$allNativeEntries.Count -ne $nativeEntries.Count",
            "Unexpected packaged native library",
            "$readelf --dynamic $destination",
            "$expectedNeeded = @('libc.so', 'libdl.so', 'liblog.so', 'libm.so')",
            "ELF dependency drift",
        ),
        "Release native dependency inventory gate",
    )

    policy = (ROOT / "docs/public-release-policy.md").read_text("utf-8")
    require_tokens(
        policy,
        (
            "MATERIAL PREPARED — PUBLICATION NOT AUTHORIZED",
            "No Git remote is configured",
            "`v0.1.0-rc.N`",
            "stable release: `v0.1.0`",
            "Tags are annotated and immutable",
            "never force-update, delete",
            "tag target must be the clean source revision named by the final runnable",
            "arm64-v8a physical-device",
            "production soak standard and first complete run",
            "A human explicitly authorizes",
            "NDK r28c toolchain notice",
        ),
        "Public release and tag policy",
    )

    draft = (ROOT / "docs/release-v0.1.0-rc.2-draft.md").read_text("utf-8")
    require_tokens(
        draft,
        (
            "DRAFT — DO NOT PUBLISH",
            "tag: `v0.1.0-rc.2`",
            "versionCode **5276**",
            "API **24+**",
            "arm64-v8a` and `x86_64",
            "93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd",
            "257f4c4a9dceed4fc58e089651370abaaa1384cf09c5f69e6fb21f244d409210",
            "c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12",
            "31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213",
            "deviceVerified=false",
            "runtimeVerified=false",
            "arm64-v8a physical-device",
            "first complete production soak",
            "third_party/autojs6-protocol-source",
            "Android NDK r28c",
        ),
        "Blocked GitHub Release draft",
    )

    for relative in ("README.md", ".readme/README-en.md"):
        readme = (ROOT / relative).read_text("utf-8")
        require_tokens(
            readme,
            ("THIRD_PARTY_NOTICES.md", "Apache-2.0", "MPL-2.0", "Android NDK LLVM"),
            f"Published license summary in {relative}",
        )

    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- [x] **公开发布物料**",
            "R4-E 公开发布物料证据 (2026-08-25)",
            "40a727f346376ae5827e63a2915d21cdd0ac8d62",
            "三个模块共 35 个逐 Git blob 一致",
            "0f845025cc46041a138de869fefcbcdbcc742e7e0d2f2c08eeffe1f375b97a69",
            "`DRAFT — DO NOT PUBLISH`",
            "候选 `v0.1.0-rc.N`、稳定版",
            "46/46 Python 敌意测试、59/59 JVM",
            "未创建/推送 tag",
            "arm64-v8a 真机与首轮 soak 仍未完成",
            "[`docs/public-release-policy.md`](docs/public-release-policy.md)",
            "[`docs/release-v0.1.0-rc.2-draft.md`](docs/release-v0.1.0-rc.2-draft.md)",
        ),
        "R4-E public release material evidence ledger",
    )


def verify_production_soak_boundary() -> None:
    executor = (ROOT / "tools/run_production_soak.ps1").read_text("utf-8")
    require_tokens(
        executor,
        (
            "[ValidatePattern('^emulator-[0-9]+$')]",
            "$RequiredDays = 7",
            "$ProductionIterations = 250",
            "$QualificationIterations = 10",
            "$WarmupIterations = 10",
            "$ExecutionsPerIteration = 2",
            "$ExpectedApi = 36",
            "$ExpectedAbi = 'x86_64'",
            "$ExpectedAvd = 'DEX_R1_API36_X64'",
            "$ExpectedHostVersionCode = 5276L",
            "$ExpectedProviderVersionCode = 43L",
            "if ($VersionCode -gt 0 -and $Identity.versionCode -ne $VersionCode)",
            "Assert-ArtifactIdentity $lifecycleIdentity 'Lifecycle test' $LifecyclePackage 0 $null",
            "[int] $Expected = -1",
            "Wait-StableFdCount $runtimePid -1",
            "b39872e2f1ccc940afcb74a6b95b5458e2fee594",
            "a0ae189ac8cba042848412a671c91b0b8a7c44e1",
            "813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e",
            "c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12",
            "31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213",
            "return Invoke-Captured $script:Adb (@('-s', $Serial) + $Arguments)",
            "Invoke-Captured $script:Adb @('-s', $Serial, 'root')",
            "Invoke-Captured $script:Adb @('-s', $Serial, 'wait-for-device')",
            "getprop', 'ro.kernel.qemu'",
            '"/proc/$RuntimePidValue/fd"',
            "$productionState.bootId -ne $bootId",
            "$runtimePid -ne $productionState.runtimePid",
            "Wait-StableFdCount $runtimePid $baselineFd",
            "LUA_HOST_OFFICIAL_SMOKE_PASS",
            "event=lua_runtime_fail_stop",
            "am_anr",
            "am_crash",
            "Qualification cannot replace APKs while a production soak round is in progress",
            "qualification cannot create or advance production state",
            "missed required date $expectedDate and is now invalid",
            "$productionState.status = 'complete'",
            "PRODUCTION_SOAK_QUALIFICATION_PASS",
            "PRODUCTION_SOAK_DAY_PASS",
            "[IO.File]::Move($temporary, $Path, $true)",
            "retained failed day 1",
            "use a new RoundId",
            '"$dayLabel-failure-logcat-all.txt"',
            "captured = $true",
            "logcat = $failureLogEvidence",
        ),
        "Fail-closed production soak executor",
    )
    require(
        "connectedAndroidTest" not in executor,
        "Production soak must not use the broad connectedAndroidTest target",
    )
    require(
        executor.count("Wait-StableFdCount $runtimePid $baselineFd") == 2,
        "Production soak must enforce the frozen FD baseline both before resumed days and at day end",
    )
    require(
        "[Nullable[" not in executor and "$Expected.Value" not in executor,
        "Production soak must not use PowerShell nullable wrappers for scalar comparisons",
    )
    require(
        "[IO.File]::Replace(" not in executor,
        "Production soak must use the portable same-directory overwrite path",
    )
    plan = (ROOT / "docs/production-soak-plan.md").read_text("utf-8")
    require_tokens(
        plan,
        (
            "STANDARD RETIRED — PRODUCTION SOAK DESCOPED ON 2026-08-26 (NO ROUND COMPLETED)",
            "fix-on-report",
            "future voluntary round",
            "7 consecutive Asia/Shanghai calendar days",
            "250",
            "500 executions per day and 3,500 executions",
            "one continuously booted, dedicated API 36 x86_64 AVD",
            "exclusive host-side deployment window",
            "`connectedAndroidTest`",
            "emulator boot ID and the Provider `:lua_runtime` PID never change",
            "same `/proc/<runtime-pid>/fd` count",
            "zero `event=lua_runtime_fail_stop`",
            "A missed date invalidates it",
            "A separate ADB server port is not treated as isolation",
            "unexpected package replacement is external",
            "Qualification installs the same exact",
            "artifacts and runs 10 measured iterations",
            "cannot create or advance production state",
            "No production soak checkbox may be closed until `state.json` says `complete`",
            "No day is complete",
            "until its 250-iteration receipt has been written successfully",
            "arm64-v8a physical smoke item",
        ),
        "Frozen production soak standard",
    )

    round_record = (ROOT / "docs/production-soak-round-1.md").read_text("utf-8")
    require_tokens(
        round_record,
        (
            "ROUND 1 INVALID — EXTERNAL HOST PACKAGE REPLACEMENT",
            "Qualification is explicitly",
            "not a production day",
            "R4-E checkbox remains open",
            "115 passed; attempt 116 interrupted",
            "230 verified (+20 qualification executions)",
            "a8eaab0ed4e82e3802ee6088a76a79eef1dc36aca9f769aed5c7055ce3afda4b",
            "1c4d0e341e7489640d885a1afd9b7e8789f6badb624611199c341a7f2971d570",
            "397f85aef1030233c3382314cb76674914141d6ead044d8aefd23397c5d49d6a",
            "b6176e0fcb2c8f125e3eb55c66cc284e08426ddef37392fa4a297b31481a0272",
            "due to installPackageLI",
            "Host versionCode 5278",
            "Provider package",
            "PID remained",
            "zero `event=lua_runtime_fail_stop`",
            "File.Move(..., overwrite=true)",
            "No pass",
            "is claimed for round 1",
        ),
        "Honest invalid production soak round-1 ledger",
    )

    round_two = (ROOT / "docs/production-soak-round-2.md").read_text("utf-8")
    require_tokens(
        round_two,
        (
            "ROUND 2 CLOSED AFTER DAY 2/7 — PRODUCTION SOAK DESCOPED (2026-08-26)",
            "external Host package replacement",
            "explicitly reserved host-side deployment window",
            "Qualification is not a production day",
            "the descoped R4-E item stays permanently unchecked",
            "No complete-round pass is claimed for round 2",
            "must use a new RoundId and begin again at day 1",
            "| 1 | 2026-08-25 | 250 | 500 | 8080 | 83 → 83 | **PASS** |",
            "| 2 | 2026-08-26 | 250 | 500 | 8080 | 83 → 83 | **PASS** |",
            "not run (was due 2026-08-27)",
            "| 7 | not run — descoped | — | — |",
            "7f5bc019367e46aa4533bb6ae71ec7f339cea20d1efb2a988f7f2060d3e07604",
            "9d40666b8f6fedcb2bd5a77dc647fe6d63d4cdb098f24ffdfeccd970fa2246f3",
            "47e07ea5-0187-4553-871a-6c713deab71c",
            "exactly 260",
            "Every periodic FD sample",
            "initial",
            "final stable observations were `83,83,83`",
            "6b7ce09b221c3165428adff234cede20b7bb61b5aad215a3e491c180ebe3d4d8",
            "f4fe337b65942d98749451f4e30f39fc2cb89995f0b23bdde690dc35db11c4fa",
            "dcc98a84f346ade21dbf8a6c7c0afd01814c15bf1ea55c4fa50cf1c2e1c518b9",
            "f76d02dad57ce8516910b7908c0fcade041b94599bc2ebee2e7e7180f45fd736",
            "exactly 250 `LUA_HOST_OFFICIAL_SMOKE_PASS` markers",
            "9b89cb129ddb2e619b98954042e8fb18fc942867563cc25278c7819ad1ccbc61",
            "0311b92263c3ad137d3be84ef8b6813ecf3b47ff8d7f44100e50f97cd7951f02",
            "2c9c9ba576d9e97b0f55a2c86fb7e8bc48a0ee5682e40d7a654f1aba09b811af",
            "0e686356990f4c83189bfc53acdc584ce12f939916df4fc03f8fc5f04f7347bf",
            "Pre-measurement audit note",
            "aborted launch created no instrumentation",
            "transcript of its own",
            "triggered none of the frozen invalidation conditions",
            "`in_progress`",
            "exactly two completed daily records",
            "These are two",
            "valid days, not a complete production soak",
        ),
        "Honest closed production soak round-2 ledger",
    )

    roadmap = (ROOT / "ROADMAP-R4.md").read_text("utf-8")
    require_tokens(
        roadmap,
        (
            "- **生产 soak 计划 — 已裁撤 (2026-08-26)**",
            "R4-E 长时测试裁撤记录 (2026-08-26)",
            "裁撤不是完成",
            "fix-on-report",
            "不被伪造为 complete",
            "重启需按原",
            "规则使用新 RoundId 从 Day 1 开始",
            "R4-E 生产 soak 标准冻结 (2026-08-25)",
            "`N=7` 个 Asia/Shanghai 连续自然日",
            "每日 250 次真实 Host smoke",
            "整轮 3,500 次",
            "资格运行不能创建或推进生产 state",
            "首轮尚未完成，本项",
            "保持未勾选",
            "R4-E 生产 soak Round 1 失效记录 (2026-08-25)",
            "第 116 次",
            "`installPackageLI` 杀死 Host",
            "versionCode 5278",
            "Round 2 使用",
            "新 ID 与独占部署窗口",
            "R4-E 生产 soak Round 2 Day 1 证据 (2026-08-25)",
            "250 次",
            "500 次 Lua 执行",
            "共 260 个确定性 PASS marker",
            "completedDays=1",
            "Day 2 只能在 2026-08-26 Asia/Shanghai 运行",
            "R4-E 生产 soak Round 2 Day 2 证据 (2026-08-26)",
            "250 个 run header、PASS marker",
            "Day-2 receipt SHA-256",
            "计量前中止不计为 partial day",
            "completedDays=2",
            "Day 3 只能在 2026-08-27",
            "[`docs/production-soak-plan.md`](docs/production-soak-plan.md)",
            "[`docs/production-soak-round-1.md`](docs/production-soak-round-1.md)",
            "[`docs/production-soak-round-2.md`](docs/production-soak-round-2.md)",
        ),
        "Descoped R4-E production soak ledger",
    )
    require(
        "- [ ] **生产 soak 计划**" not in roadmap and "- [x] **生产 soak 计划**" not in roadmap,
        "The descoped production soak item must not regain a checkbox",
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
    verify_build_modes()
    verify_ci_resilience()
    verify_localization_workflow()
    verify_input_workflows()
    verify_wrapper()
    verify_r4_design_records()
    verify_native_boundary()
    verify_watchdog_boundary()
    verify_crash_diagnostic_boundary()
    verify_descriptor_boundary()
    verify_native_android_test_boundary()
    verify_fault_harness_boundary()
    verify_host_lifecycle_boundary()
    verify_public_release_materials()
    verify_production_soak_boundary()
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
