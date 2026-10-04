******

### リリース履歴

******

# v0.1.3

###### 2026/10/04

* `改善` プラグインセンターのアイコンに Icon Studio で調整したサイズ, 位置, 明暗の図稿と円形背景を適用し, 再生成可能な原稿とパラメーターを保持

# v0.1.2

###### 2026/09/19

* `修正` 共有ビルドプラグイン 1.8.3 により, AGP 9.1 での SDK XML v4 解析警告と, JVM 単体テストの組み立て時に APK ネイティブライブラリのアラインメント検証が誤って実行される問題
* `改善` compileSdk と targetSdk を 37 (Android 17) に引き上げ, プラグインの動作は新しいターゲットの影響を受けない

# v0.1.1

###### 2026/09/11

* `改善` 64 ビットのネイティブライブラリの 16 KB ページアラインメントをビルド時に検証, manifest 契約の検査と JSON レポートに対応
* `改善` 保護されたホスト起動, 正確なインストール情報, 署名済み配布収集を追加し, 多言語リソースと読み取り専用ドキュメント検証を統一
* `改善` ネイティブ ABI のパッケージ化とプラグインメタデータを arm64-v8a, armeabi-v7a, x86, x86_64 に拡張し, ユニバーサル APK と ABI 別 APK を同期

# v0.1.0-rc.2

###### 2026/08/27

* `注記` 現在もローカル検証済み署名 package 候補です. 公開 tag と GitHub Release はなく, immutable receipt は `deviceVerified=false/runtimeVerified=false` のままです
* `注記` owner の決定で arm64-v8a 実機 smoke と 7 日 production soak を対象外にしました. 完了した 2 日分と凍結 standard は保存し, 長期安定性は fix-on-report に移行します
* `追加` 制御 coroutine, `autojs.now()`, `console.info/warn`, Provider 側 `ui.toast.v1`, crash diagnostic, `AutoJs6LuaWatchdog` event を追加しました
* `追加` file script ごとに分離した Host 永続化, 固定 get/put/remove/clear shape, bounded canonical value, no retry を持つ negotiated `storage.kv.v1` と Host 配信 `ui.toast.v1` を実装しました
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
