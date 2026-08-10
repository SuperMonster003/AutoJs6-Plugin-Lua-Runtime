#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
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


def parse_default_off_flags(properties: str) -> dict[str, str]:
    required_flags = {"autojs.lua.native.enabled", "autojs.lua.provider.enabled"}
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
    service = (ROOT / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/service/LuaRuntimeService.kt").read_text("utf-8")
    require(
        "override fun onBind(intent: Intent?): IBinder = binder" in service,
        "Runtime service no longer supports actionless explicit binding",
    )


def require_tokens(text: str, tokens: tuple[str, ...], label: str) -> None:
    for token in tokens:
        require(token in text, f"{label} drift: {token}")


def verify_input_workflows() -> None:
    ignore_lines = [
        line.strip()
        for line in (ROOT / ".gitignore").read_text("utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    exceptions = ("!protocol/*.aar", "!gradle/wrapper/gradle-wrapper.jar")
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
            "$lock.vendoredAt =",
            "tools/verify_repository.py",
        ),
        "Lua source intake",
    )
    archive_verifier = (ROOT / "tools/verify_lua_archive.ps1").read_text("utf-8")
    require_tokens(
        archive_verifier,
        (LUA_SHA256, "Get-FileHash -LiteralPath $resolvedArchive -Algorithm SHA256"),
        "Lua archive verifier",
    )


def verify_native_boundary() -> None:
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
        "return opened && !budget.accounting_failed && budget.used == 0U" in native,
        "Native probe ignores allocator accounting failure",
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
        'lua_setfield(state, -2, "dump")': "Lua string.dump is still exposed",
    }
    for token, message in required_execution_tokens.items():
        require(token in native, message)
    load_at = native.index("status = luaL_loadbufferx(")
    hook_at = native.index("lua_sethook(state, execution_hook, LUA_MASKCOUNT")
    protected_call_at = native.index("status = lua_pcall(state, 0, LUA_MULTRET, 0)")
    clear_hook_at = native.index("lua_sethook(state, nullptr, 0, 0)")
    result_at = native.index("jobject result = box_lua_result(environment, state)")
    require(
        load_at < hook_at < protected_call_at < clear_hook_at < result_at,
        "Native load/hook/protected-call/result ordering drift",
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
            "luaopen_math",
            "luaopen_string",
            "luaopen_table",
            "luaopen_utf8",
        },
        f"Native Lua library allowlist drift: {sorted(opened_libraries)}",
    )
    for forbidden_open in (
        "luaopen_coroutine",
        "luaopen_debug",
        "luaopen_io",
        "luaopen_os",
        "luaopen_package",
    ):
        require(forbidden_open not in native, f"Forbidden Lua library is opened: {forbidden_open}")

    kotlin_boundary = (
        ROOT
        / "app/src/main/java/io/github/supermonster003/autojs6/plugin/lua/runtime/NativeLuaRuntime.kt"
    ).read_text("utf-8")
    for token in (
        "fun execute(request: NativeLuaExecutionRequest): NativeLuaExecutionValue",
        "internal typealias NativeLuaCancellationProbe = BooleanSupplier",
        "    DEADLINE_EXCEEDED,",
        "NativeLuaFailureKind.UNSUPPORTED_RESULT",
        "LuaRuntimeContract.MAX_VALUE_STRING_OR_BYTES",
        "internal object NativeLuaExecutionRunner : LuaExecutionRunner",
        "LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS",
        "fun NativeLuaExecutionValue.toProtocolValue(): LuaValue",
    ):
        require(token in kotlin_boundary, f"Native Kotlin execution boundary drift: {token}")
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
        "runner = DisabledLuaExecutionRunner" in runtime_service,
        "The source-only native adapter must not be injected into the runtime service",
    )
    require(
        "NativeLuaExecutionRunner" not in runtime_service,
        "The uncompiled native adapter leaked into the runtime service",
    )

    native_doc = (ROOT / "docs/native-execution-core.md").read_text("utf-8")
    require("SOURCE ONLY / NOT COMPILED / NOT RUN" in native_doc, "Native evidence boundary is missing")
    require("There is no stdout/stderr implementation" in native_doc, "Native output limitation is missing")
    require("There is no module loader" in native_doc, "Native capability limitation is missing")
    require("There is no coroutine library" in native_doc, "Native coroutine limitation is missing")
    require("infinite `__gc` or `__close` handler" in native_doc, "Native teardown limitation is missing")
    require("process-level cleanup watchdog" in native_doc, "Native cleanup watchdog gate is missing")
    require("The adapter is not injected into the service" in native_doc, "Native adapter status is ambiguous")

    inventory = (ROOT / "app/src/main/cpp/cmake/lua54-sources.cmake").read_text("utf-8")
    for forbidden in (
        "src/linit.c",
        "src/lcorolib.c",
        "src/ldblib.c",
        "src/liolib.c",
        "src/loslib.c",
        "src/loadlib.c",
    ):
        require(
            not any(line.strip() == forbidden for line in inventory.splitlines()),
            f"Forbidden Lua library entered the native inventory: {forbidden}",
        )
    require(
        any(line.strip() == "src/lundump.c" for line in inventory.splitlines()),
        "Lua core inventory unexpectedly lost lundump.c",
    )
    readme = (ROOT / "README.md").read_text("utf-8")
    require(
        'luaL_loadbufferx(..., "t")' in readme,
        "The required text-only execution loader gate is not documented",
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
    verify_input_workflows()
    verify_native_boundary()
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
