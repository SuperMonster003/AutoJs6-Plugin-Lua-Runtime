<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>AutoJs6 Lua ランタイムプラグイン</h1>

  <p>専用の分離プロセスで標準 PUC Lua 5.4.8 スクリプトを実行します</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### 言語 (Languages)

******

現在の README.md は次の言語に対応しています:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- 日本語 [ja] # 現在
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### 概要

******

AutoJs6 自体は JavaScript を実行します. このプラグインは第 2 のスクリプト言語を追加します. AutoJs6 エディターで `.lua` ファイルを作成して実行すると, ソースは別途インストールされた Provider に渡され, 専用 `:lua_runtime` プロセス内の PUC Lua 5.4.8 で実行されます. スクリプトはレビュー済み Lua 標準ライブラリのサブセットと小さな `autojs` ブリッジを利用できますが, ファイル, プロセス, 環境変数, 動的ネイティブモジュールにはアクセスできません.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

2 つの検出サービスは `org.autojs.permission.PLUGIN` 署名権限で保護されています. Host とプラグインは同じ証明書を使う必要があります. 各 capability はプロトコル交渉と検証で許可されるまで無効です. 公開 APK はまだないため, ビルドとプロジェクト状態を参照してください.

******

### 機能

******

- AutoJs6 エディターからプレーンテキストの Lua 5.4.8 (`.lua`) を実行し, console をリアルタイム配信して, 完了時に 1 個のスカラー結果を返します.
- 制御された base, string, math, table, utf8, coroutine を提供します. coroutine も deadline, cancel, メモリ計測を継承します.
- `autojs.console`, `autojs.now()`, 読み取り専用 `autojs.arguments`, `autojs.device.info()`, Provider 側 `autojs.ui.toast()` を公開します.
- 隣接モジュール snapshot に対応します. `job.lua` は `job.modules/name.lua` から最大 64 KiB の UTF-8 テキストモジュールを読み込めます.
- 各ランタイムプロセスで 1 実行のみを動かし, Host 指定の deadline, メモリ予算, 出力 credit, fail-stop watchdog を適用します.
- 実行前に正確な長さ, SHA-256, 厳格な UTF-8 を再検証します. プリコンパイル済みまたはバイナリ Lua chunk は常に拒否します.
- 16 KiB alignment の arm64-v8a と x86_64 ネイティブライブラリ, および 10 言語の README, CHANGELOG, Android テキストを提供します.

******

### クイックスタート

******

**インストール方法は?** 公開 APK はまだないため, 下記の手順でビルドしてください. `providerDebug` は同じデバッグ証明書の AutoJs6 と組み合わせ, release Provider は同じ署名の release Host と組み合わせます.

**有効化方法は?** スイッチはありません. AutoJs6 は同じ署名の Provider を自動検出し, Lua に実験的 Boolean プロパティはありません.

**実行方法は?** AutoJs6 エディターで名前が `.lua` で終わるファイルを作成し, Lua ソースを書いて実行します. console はリアルタイム表示され, 完了時に Host がスカラー戻り値を受け取ります.

**失敗時は何を確認する?** versionCode 5276 未満の Host は `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED` で dispatch を拒否します. deadline は `TIMEOUT`, メモリ超過は `MEMORY_LIMIT`, 未許可 Host ブリッジは `HOST_CAPABILITY` で終了します. いずれも決定的な安全終端です.

******

### 使用例

******

このスクリプトは現在の Host が常に許可する capability だけを使います:

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

return total
```

コードを再利用する場合は, エントリースクリプト `job.lua` の横に `job.modules/` を作り, `helper.lua` を置いて `require("helper")` を呼びます. V1 モジュール名は最大 64 文字のフラットな ASCII 識別子です. パス, ドット, バイナリモジュール, C モジュールは拒否されます.

******

### スクリプト API

******

`require("autojs")` はブリッジテーブルを返します. `autojs.console.log/info(text)` は stdout, `error/warn(text)` は stderr に書き込み, グローバル `print(...)` と `warn(text)` も同じ制御済みストリームを使います. `autojs.now()` は Unix epoch ミリ秒を返します. `autojs.arguments` は実行引数の読み取り専用 snapshot です. `autojs.device.info()` は brand, manufacturer, model, device, product, sdkInt を返します. `autojs.ui.toast(text)` は 1 実行あたり最大 4 回, 厳格な UTF-8 で 1 から 1024 bytes, 1 回だけ dispatch して retry しません. 現在の Host はまだ許可しないため `HOST_CAPABILITY` で終了します.

利用可能なライブラリは base, string, math, table, utf8, coroutine です. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable`, `setmetatable` は意図的に無効です. ソースローダーはテキストモードの `luaL_loadbufferx(..., "t")` だけなので, バイナリ chunk は入りません. 詳細は [`docs/native-execution-core.md`](docs/native-execution-core.md) と [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md) を参照してください.

******

### 制限と安全性

******

- 1 プロセスで 1 スクリプトを実行し, 準備済み session は最大 2 件, queue はありません. 追加 request は即座に失敗します.
- Host は各実行の end-to-end deadline とメモリ予算を設定し, instruction hook が tight loop と coroutine 内でも cancel と timeout を適用します.
- 出力は順序, credit, 32 KiB chunk, 合計上限で制限されます. 無限 print loop は Host を圧迫せず決定的に終了します.
- cleanup 期限切れでは専用プロセスを poison して終了し, Host が新しいプロセスへ再接続します. 各 fail-stop は内容を含まない `AutoJs6LuaWatchdog` event を出します.
- native crash または watchdog kill の前に保存するのは固定 20-byte 診断だけです. failure kind, phase, 8-byte source-hash prefix を含み, スクリプト本文は含みません.

******

### 互換性

******

Android 24+ (minSdk 24, targetSdk 36), ABI `arm64-v8a` または `x86_64`, 同じ証明書の AutoJs6 versionCode 5276 以降が必要です. x86_64 の install, 実 Host 実行, uninstall matrix は API 24, 31, 36 で保存され, API 37 end-to-end smoke もあります. arm64-v8a はビルドと artifact gate を通過していますが実機未検証です. 実利用で報告された不具合は修正します.

******

### ビルド

******

JDK 17+, Android SDK, NDK `28.2.13676358`, CMake `3.22.1` を使用します. リポジトリの Gradle Wrapper で通常の開発 Provider をビルドします:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### ビルド variant

- `providerDebug`: 2 つの本番検出サービスを含む通常の開発ビルドです.
- `providerRelease`: 唯一の release variant です. 外部署名情報がなければ公開不能な unsigned artifact のみ生成します.
- `nativeTestDebug` / `faultTestDebug`: 独立 application ID を持ち, merged manifest から本番検出を物理的に削除した instrumentation variant です.
- 破壊的 fault harness をコンパイルするのは `faultTestDebug` だけです. 旧 `-Pautojs.lua.*.enabled` Boolean switch は削除済みです.

#### 署名付き release ビルド

外部署名情報と同一署名 AutoJs6 APK の絶対パスをリポジトリの builder に渡します:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

スクリプトは commit count と versionCode が一致する clean revision を要求し, offline で全面再ビルドし, Host/Provider 証明書を比較して厳格な artifact gate を実行します. receipt は署名付き package の証拠だけで, 実機 install や runtime 証拠ではありません.

******

### プロジェクト状態

******

現在の `0.1.0-rc.2` はローカル検証済みの署名 package 候補で, 公開 release ではありません. 候補 revision は `a0ae189ac8cba042848412a671c91b0b8a7c44e1`, universal APK SHA-256 は `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd` で, immutable receipt は `deviceVerified=false/runtimeVerified=false` のままです. x86_64 split はその後 API 37 実 Host smoke と API 24, 31, 36 matrix を通過しました. arm64 実機と 7 日 soak は 2026-08-26 に対象外となり, 今後は fix-on-report です. [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md) を参照してください. R3 は [ROADMAP.md](ROADMAP.md), R4 は [ROADMAP-R4.md](ROADMAP-R4.md), R5 は [ROADMAP-R5.md](ROADMAP-R5.md) です.

******

### 検証と release engineering

******

開発ネットワークの Cloudflare 502/524/529 ノイズを減らすため, maintainer gate は既定で offline 実行します.

#### ローカライズ文書

`.readme/` と `.changelog/` に 10 言語の JSON ソースと共通 template があります. `.python/generate_markdown.py` が 10 個の README と APK 内 CHANGELOG を生成し, `zh-Hans` をルート `README.md` に書きます. Android UI 文字列は各 `values-*` ディレクトリで管理します. JSON 変更後に実行します:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### オフライン検証 gate

```powershell
.\tools\verify_local.ps1
```

build-ready verifier, hostile Python suite, `:app:testProviderDebugUnitTest --offline` を連結し, `verification.properties` の単一 test count と XML report を照合します. CI も同じ境界です.

#### 不変入力

protocol 入力は SHA-256 lock 済みのリポジトリ内 AAR 3 個だけです. native 入力は archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae` の PUC Lua 5.4.8 です. verifier は source tree 全体を再計算し, CMake はレビュー済み source だけを許可します. [`protocol/README.md`](protocol/README.md) と [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json) を参照してください.

#### Release artifact 検証

`providerRelease` は unsigned でビルドできますが, 公開候補は外部絶対パスの署名情報を読み, fresh offline task を実行します:

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

続いて同じ証明書で artifact verifier を実行します. JVM count, signer, ABI, 16 KiB ZIP/ELF alignment, non-debuggable, fault-harness 除外を確認しますが, これは package 証拠だけです.

#### 公開前 fault-harness checklist

署名候補の再ビルド前に, 1 回の clean canonical invocation で fault variant と監査可能な unsigned release intermediate を生成します:

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

成功 marker は `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS` です. `faultTestDebug` は隔離サービスと fault JNI symbol を含み, `providerRelease` はそれらを除外する必要があります. 破棄可能な device で `LuaRuntimeFaultRecoveryInstrumentationTest` も実行してください. receipt は代替にならず, 未実行は `UNVERIFIED_FAULT_HARNESS` です.

******

### 詳細資料

******

実行境界: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). 将来設計: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). release 証拠: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### リリース履歴

******

# v0.1.0-rc.2

###### 2026/08/27

* `注記` 現在もローカル検証済み署名 package 候補です. 公開 tag と GitHub Release はなく, immutable receipt は `deviceVerified=false/runtimeVerified=false` のままです
* `注記` owner の決定で arm64-v8a 実機 smoke と 7 日 production soak を対象外にしました. 完了した 2 日分と凍結 standard は保存し, 長期安定性は fix-on-report に移行します
* `追加` 制御 coroutine, `autojs.now()`, `console.info/warn`, Provider 側 `ui.toast.v1`, crash diagnostic, `AutoJs6LuaWatchdog` event を追加しました
* `追加` テキスト module snapshot, 読み取り専用実行引数, device 情報ブリッジを追加し, deadline, cancel, memory, output quota を全経路で維持します
* `追加` arm64-v8a と x86_64 native library を提供し, API 24, 31, 36, 37 の x86_64 実 Host 証拠を保存しました
* `修正` `start()` 到着前に小さな deadline が切れた場合, 終端なし session ではなく 1 個の決定的な `TIMEOUT/QUEUE` を返します
* `修正` `math.randomseed` と Host capability 拒否 mapping を強化し, 未許可 call は `HOST_CAPABILITY` で終了します
* `改善` 4 個の Boolean build mode を `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` に置き換え, 本番 discovery と破壊的 fault harness を物理分離しました
* `改善` 兄弟 plugin の `.python/generate_markdown.py` + `.readme/` + `.changelog/` 規約へ移行し, 10 言語文書と `zh-Hans` 既定 root README を提供します
* `改善` R5 を `ROADMAP-R4.md` から `ROADMAP-R5.md` へ分離し, 完全な 10 言語化で不要になった繁体字 slot task を削除しました
* `改善` PFD 論理/OS 計測, 1 command offline gate, resilient CI, release artifact 検証, fault-harness 除外 audit を追加しました
* `依存関係` PUC Lua 5.4.8, Android NDK 28.2.13676358, CMake 3.22.1 を固定します

# v0.1.0-rc.1

###### 2026/08/13

* `注記` 最初の Provider-enabled ローカル候補を署名し device test しましたが, 公開 tag や release は作成していません
* `追加` 独立 `:lua_runtime` process, テキスト Lua 実行, console, scalar result, AutoJs6 Binder Provider discovery を導入しました
* `修正` protocol, digest, UTF-8, deadline, memory, output の fail-closed 検証で不正または過大 request を拒否しました
* `改善` AAR, Lua source, ABI, 16 KiB alignment, 署名, rollback matrix の検証可能な証拠を確立しました
* `依存関係` 標準 PUC Lua 5.4.8 と凍結済み AutoJs6 Lua protocol 1.0 を基盤にします

##### その他のリリース

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-ja.md)

******

### ライセンス

******

リポジトリ固有コードは MIT License です. 第三者コンポーネントには MIT の PUC Lua, Apache-2.0 の Kotlin と JetBrains annotations, 記録済み LLVM 条件の Android NDK LLVM runtime 静的リンク部分, MPL-2.0 の AutoJs6 protocol API が含まれます. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) と完全な条文を参照してください.

******

### ローカライズ resource 構成

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` は JSON から全 10 言語の README と APK 内 CHANGELOG を生成します. 生成 Markdown ではなく JSON source を編集してください. Android UI 文字列は各 resource directory で管理します.

******

### リンク

******

- AutoJs6 プロジェクト: https://github.com/SuperMonster003/AutoJs6
- Lua プロジェクト: https://www.lua.org
- 第三者通知: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- プロジェクトライセンス: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE
