<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="{{ repo_url }}/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>{{ text_title }}</h1>

  <p>{{ text_plugin_synopsis }}</p>

  <p>
    <a href="{{ repo_url }}/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/{{ repo_slug }}?label=Release"/></a>
    <a href="{{ repo_url }}/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/{{ repo_slug }}?color=A24232&label=Issues"/></a>
    <a href="{{ license_url }}"><img alt="GitHub License" src="https://img.shields.io/github/license/{{ repo_slug }}?color=534BAE&label=License"/></a>
  </p>
</div>

******

### {{ h3_languages }}

******

{{ p_languages }}:

{{ placeholder_ul_languages_all_supported }}

******

### {{ h3_introduction }}

******

{{ p_introduction }}

```text
application ID: {{ application_id }}
plugin / engine / variant: {{ plugin_id }} / {{ plugin_engine }} / {{ plugin_variant }}
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: {{ runtime_process }}
minimum host build: {{ required_host_build }}
```

{{ p_identity_note }}

******

### {{ h3_features }}

******

{{ placeholder_features }}

******

### {{ h3_quick_start }}

******

{{ p_quick_start }}

******

### {{ h3_usage_example }}

******

{{ p_usage_example }}:

```lua
local autojs = require("autojs")

print("Hello from AutoJs6 Lua Runtime", _VERSION)
autojs.console.warn("stderr, ordered with console output")

local started = autojs.now()
local total = 0
for index = 1, 1000 do
    total = total + index
end
autojs.console.info(("sum=%d in %d ms"):format(total, autojs.now() - started))

local device = autojs.device.info()
autojs.console.log("running on: " .. device.manufacturer .. " " .. device.model)

local runCount = (autojs.storage.get("run_count") or 0) + 1
autojs.storage.put("run_count", runCount)
autojs.ui.toast(("Lua run #%d complete"):format(runCount))

return total
```

{{ p_module_example }}

******

### {{ h3_script_api }}

******

{{ p_script_api }}

******

### {{ h3_limits }}

******

{{ placeholder_limits }}

******

### {{ h3_compatibility }}

******

{{ p_compatibility }}

******

### {{ h3_build }}

******

{{ p_build }}:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### {{ h4_build_variants }}

{{ placeholder_build_variants }}

#### {{ h4_signed_release }}

{{ p_signed_release }}:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

{{ p_signed_release_note }}

******

### {{ h3_project_status }}

******

{{ p_project_status }}

******

### {{ h3_verification }}

******

{{ p_verification }}

#### {{ h4_localized_docs }}

{{ p_localized_docs }}:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### {{ h4_offline_gate }}

```powershell
.\tools\verify_local.ps1
```

{{ p_offline_gate }}

#### {{ h4_immutable_inputs }}

{{ p_immutable_inputs }}

#### {{ h4_release_validation }}

{{ p_release_validation }}:

```powershell
$releaseArgs = @(
    ':app:clean'
    ':app:testProviderDebugUnitTest'
    ':app:assembleProviderRelease'
    '-Pautojs.lua.release.signingPropertiesFile=<absolute sign.properties path>'
    '-Pautojs.lua.release.signingStoreFile=<absolute JKS path>'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @releaseArgs
```

```powershell
.\tools\verify_release_candidate_artifacts.ps1 `
    -InvocationStartedAtUtc '<UTC start of that Gradle invocation>' `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -SdkRoot '<Android SDK root>'
```

{{ p_release_validation_note }}

#### {{ h4_fault_checklist }}

{{ p_fault_checklist }}:

```powershell
$faultStarted = [DateTimeOffset]::UtcNow.ToString(
    'yyyy-MM-ddTHH:mm:ss.ffffffZ'
)
$faultArgs = @(
    ':app:clean'
    ':app:assembleFaultTestDebug'
    ':app:compileProviderReleaseKotlin'
    ':app:processProviderReleaseMainManifest'
    ':app:externalNativeBuildProviderRelease'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @faultArgs
.\tools\verify_fault_harness_artifacts.ps1 `
    -InvocationStartedAtUtc $faultStarted `
    -SdkRoot '<Android SDK root>'
```

{{ p_fault_checklist_note }}

******

### {{ h3_further_reading }}

******

{{ p_further_reading }}

******

### {{ h3_release_history }}

******

{{ placeholder_latest_release_history }}

##### {{ h5_more_release_history }}

* {{ placeholder_read_more_in_changelog_md }}

******

### {{ h3_license }}

******

{{ p_license }}

******

### {{ h3_resource_layout }}

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

{{ p_resource_layout }}.

******

### {{ h3_links }}

******

- {{ text_link_autojs6 }}: {{ autojs6_url }}
- {{ text_link_lua }}: {{ lua_url }}
- {{ text_link_notices }}: {{ third_party_notices_url }}
- {{ text_link_license }}: {{ license_url }}


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
