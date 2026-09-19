# AutoJs6-Plugin-Lua-Runtime

This repository owns its existing plugin implementation and contracts. Keep identity values in version.properties, Manifest and the current shared API aligned.

The provider packages the Lua native runtime. Derive release ABI splits and PluginInfo from the actual provider build and installed native payload; keep the owner-approved ABI scope explicit.


## Shared engineering baseline (2026-09-13)

- Apply the workspace AutoJs6 plugin conventions to code, resources, builds and tests. Preserve existing owner decisions and historical release evidence; a milestone or RC is not silently promoted to stable.
- Inspect status, branch, recent history and scoped diffs before editing. Preserve pre-existing work. Use Conventional Commits; before each commit set VERSION_BUILD to the next reachable Git commit count. The final count must match HEAD. Do not push or publish without the user's instruction.
- Root settings alone applies the public platform-versions 1.8.3 plugin before build-logic. Native alignment uses the same published version where applicable. Never use mavenLocal, sibling binary dependencies, gradle/data overrides or temporary migration backups.
- Keep signing resolution and the appendDigestToReleasedFiles task. It depends on assembleRelease and rejects incomplete signing, unexpected APK sets and unsigned APKs. Signing secrets and generated APKs remain ignored.
- All contract entry points use org.autojs.permission.PLUGIN. Preserve the no-display Wake Activity, its metadata, WAKE action and DEFAULT category, and test discovery and explicit Binder binding. OEM first-install activation must be reported separately from a manifest test.
- PluginInfo uses installed package versions, localized descriptions and accurate capabilities. Pure JVM providers explicitly return empty supportedAbis; native providers describe actual packaged ABIs.
- The app title is English and non-translatable. Maintain all ten languages, matching default/explicit English, sorted resource names and ASCII punctuation. Keep the real mipmap/ic_launcher.png.
- Edit JSON/templates rather than generated README/changelog. Keep the root README in Simplified Chinese, use the centered header, and omit IDE version badges. Changelog source stays in .changelog; generated language assets stay in app/src/main/assets/doc.
- Run py .python/generate_markdown.py and its read-only --check after document edits. Run relevant Python and JVM tests, debug and androidTest assembly, lint, and signed release collection. Platform changes additionally require the Temurin vendor simulation from the workspace conventions. Use actual application variant task names if the repository differs.
- Preserve native source/license locks, ABI inventory and 16 KB alignment checks. Existing device evidence is historical; never claim a new install, Binder or OEM test that was not executed.

- The current Lua RC and the owner-approved ABI/provider design remain unchanged. Respect the active work in app/build.gradle.kts, LuaProviderMetadata and release tools; do not reintroduce retired soak gates.

## Provider validation

Use `:app:assembleProviderDebug`, `:app:testProviderDebugUnitTest`, `:app:assembleProviderDebugAndroidTest`, and `:app:lintProviderDebug` for the real application variant. `:app:appendDigestToReleasedFiles` collects the signed provider release and preserves the existing RC release preconditions. Supply the ignored signing property file and keystore through the existing `autojs.lua.release.signingPropertiesFile` and `autojs.lua.release.signingStoreFile` Gradle properties (or their documented environment equivalents). Do not infer a new ABI or stable promotion from another runtime repository.

INFO `getInfo()` also enforces the configured AutoJs6 host UID. Plugin instrumentation verifies discovery, explicit binding, the descriptor and rejection of its own non-host UID. Positive INFO metadata and runtime Binder acceptance must run from the matching signed AutoJs6 host; do not weaken caller verification to make a plugin-local test pass.
