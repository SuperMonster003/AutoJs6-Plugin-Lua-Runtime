<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>Plugin d'exécution Lua pour AutoJs6</h1>

  <p>Exécute des scripts PUC Lua 5.4.8 standard dans un processus dédié et isolé</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### Langues (Languages)

******

Le fichier README.md actuel est disponible dans les langues suivantes:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- Français [fr] # actuel
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### Introduction

******

AutoJs6 exécute normalement JavaScript. Ce plugin ajoute un second langage: créez un fichier `.lua` dans l'éditeur AutoJs6 et lancez-le; la source est remise à un Provider installé séparément, qui exécute PUC Lua 5.4.8 dans le processus dédié `:lua_runtime`. Les scripts disposent d'un sous-ensemble contrôlé de la bibliothèque Lua et d'un petit pont `autojs`, sans accès aux fichiers, processus, variables d'environnement ni modules natifs dynamiques.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

Les deux services de découverte sont protégés par la permission de signature `org.autojs.permission.PLUGIN`. L'hôte et le plugin doivent utiliser le même certificat; chaque capacité reste désactivée tant que la négociation du protocole et la validation ne l'ont pas admise. Aucun APK public n'est encore disponible; consultez les sections Construction et État du projet.

******

### Fonctionnalités

******

- Exécute les scripts Lua 5.4.8 (`.lua`) en texte brut depuis l'éditeur AutoJs6, diffuse la console en direct et renvoie un résultat scalaire à la fin.
- Fournit les bibliothèques contrôlées base, string, math, table, utf8 et coroutine; les coroutines héritent du délai, de l'annulation et du suivi mémoire.
- Expose `autojs.console`, `autojs.now()`, `autojs.arguments` en lecture seule, `autojs.device.info()`, le stockage Host `autojs.storage` et le pont complet `autojs.ui.toast()`.
- Charge des instantanés de modules adjacents: `job.lua` peut lire des modules texte UTF-8 de 64 KiB maximum dans `job.modules/name.lua`.
- Exécute une seule tâche par processus avec délai, budget mémoire, crédits de sortie et watchdog fail-stop définis par l'hôte.
- Revérifie la longueur exacte, SHA-256 et l'UTF-8 strict avant exécution; les chunks Lua précompilés ou binaires sont refusés.
- Fournit les bibliothèques natives `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` alignées sur 16 KiB, ainsi que README, CHANGELOG et textes Android en 10 langues.

******

### Démarrage rapide

******

**Comment l'installer?** Aucun APK public n'existe encore; construisez-le comme indiqué plus bas. `providerDebug` doit accompagner un AutoJs6 de débogage signé par le même certificat; un Provider release doit accompagner l'hôte release portant la même signature.

**Comment l'activer?** Aucun interrupteur n'est nécessaire. AutoJs6 découvre automatiquement un Provider de même signature; Lua n'utilise aucune propriété Boolean expérimentale.

**Comment lancer un script?** Créez dans l'éditeur AutoJs6 un fichier dont le nom finit par `.lua`, écrivez le code Lua et lancez-le. La console est diffusée en direct et l'hôte reçoit la valeur scalaire finale.

**Que vérifier en cas d'échec?** Un hôte antérieur au versionCode 5276 refuse l'envoi avec `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED`. Un dépassement de délai devient `TIMEOUT`, de mémoire `MEMORY_LIMIT`, et un pont Host non accordé `HOST_CAPABILITY`; ce sont des terminaisons de sécurité déterministes.

******

### Exemple d'utilisation

******

Cet exemple basé sur un fichier utilise le stockage R5 et Toast accordés par un Host compatible:

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

Pour réutiliser du code, créez `job.modules/` à côté du script d'entrée `job.lua`, ajoutez `helper.lua`, puis appelez `require("helper")`. Les noms V1 sont des identifiants ASCII plats de 64 caractères maximum; chemins, points, modules binaires et modules C sont refusés.

******

### API des scripts

******

`require("autojs")` renvoie la table du pont. `autojs.console.log/info(text)` écrit sur stdout et `error/warn(text)` sur stderr; les fonctions globales `print(...)` et `warn(text)` partagent ces flux contrôlés. `autojs.now()` renvoie les millisecondes Unix. `autojs.arguments` est l'instantané en lecture seule des arguments. `autojs.device.info()` renvoie brand, manufacturer, model, device, product et sdkInt. Pour un script fichier à identité stable, `autojs.storage.get/put/remove/clear` fournit des valeurs persistantes côté Host: clés ASCII de 1 à 64 bytes, valeur canonique de 252 KiB maximum, puis 256 clés et 2 MiB par principal; une exécution dispose de 64 opérations et 32 mutations. `autojs.ui.toast(text)` accepte au plus 4 appels et 1 à 1024 bytes UTF-8 stricts, envoie une fois et ne réessaie jamais. Un Host qui n'accorde pas l'une de ces capacités renvoie `HOST_CAPABILITY`, sans repli local ni nouvelle tentative.

Les bibliothèques disponibles sont base, string, math, table, utf8 et coroutine. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable` et `setmetatable` sont désactivés volontairement. Le seul chargeur est `luaL_loadbufferx(..., "t")` en mode texte, ce qui interdit les chunks binaires. Voir [`docs/native-execution-core.md`](docs/native-execution-core.md) et [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md).

******

### Limites et sécurité

******

- Un script s'exécute par processus, avec au plus deux sessions préparées et aucune file; les requêtes supplémentaires échouent immédiatement.
- L'hôte fixe un délai de bout en bout et un budget mémoire; un hook d'instructions applique annulation et timeout dans les boucles et coroutines.
- La sortie est limitée par ordre, crédit, chunks de 32 KiB et plafond total; une boucle d'impression infinie se termine sans saturer l'hôte.
- Une expiration du nettoyage empoisonne puis arrête le processus dédié avant une nouvelle liaison; chaque fail-stop émet un événement `AutoJs6LuaWatchdog` sans contenu.
- Avant un crash natif ou un arrêt watchdog, seul un diagnostic fixe de 20-byte est stocké: type d'échec, phase et préfixe source-hash de 8-byte, jamais le script.

******

### Compatibilité

******

Android 24+ (minSdk 24, targetSdk 37), les ABI `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, ainsi qu'AutoJs6 versionCode 5276 ou plus récent avec le même certificat sont requis. La matrice x86_64 d'installation, d'exécution avec l'hôte réel et de désinstallation est archivée pour API 24, 31 et 36, avec un smoke end-to-end API 37. arm64-v8a est construit et contrôlé comme artefact mais n'a pas été exécuté sur un appareil physique; les défauts réels sont corrigés sur signalement.

******

### Construction

******

Utilisez JDK 17+, Android SDK, NDK `28.2.13676358` et CMake `3.22.1`. Construisez le Provider de développement avec le Gradle Wrapper du dépôt:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### Variantes de construction

- `providerDebug`: construction de développement ordinaire avec les deux services de découverte de production.
- `providerRelease`: seule variante release; sans signature externe, elle produit uniquement un artefact unsigned non publiable.
- `nativeTestDebug` / `faultTestDebug`: variantes d'instrumentation avec application ID séparés et services de production retirés physiquement du manifest fusionné.
- Seul `faultTestDebug` compile le fault harness destructif; les anciens commutateurs Boolean `-Pautojs.lua.*.enabled` ont été supprimés.

#### Construction release signée

Utilisez le constructeur du dépôt avec les chemins absolus des éléments de signature externes et d'un APK AutoJs6 portant la même signature:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

Le script exige une revision propre avec commit count égal au versionCode, reconstruit entièrement hors ligne, compare les certificats Host/Provider et lance le contrôle strict des artefacts. Son reçu prouve seulement l'empaquetage signé, pas l'installation ou l'exécution sur appareil.

******

### État du projet

******

La version actuelle `0.1.3-rc.2` est un candidat d'empaquetage signé validé localement, pas une publication publique. La revision candidate est `a0ae189ac8cba042848412a671c91b0b8a7c44e1` et le SHA-256 de l'APK universal est `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`; le reçu immuable conserve `deviceVerified=false/runtimeVerified=false`. Le split x86_64 a ensuite réussi le smoke Host réel API 37 et la matrice API 24, 31, 36. Le test physique arm64 et le soak de sept jours ont été retirés le 2026-08-26; la stabilité suit désormais fix-on-report. Voir [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md) et [`docs/public-release-policy.md`](docs/public-release-policy.md). R3 se trouve dans [ROADMAP.md](ROADMAP.md), R4 dans [ROADMAP-R4.md](ROADMAP-R4.md) et R5 dans [ROADMAP-R5.md](ROADMAP-R5.md).

******

### Vérification et publication

******

Les contrôles de maintenance fonctionnent hors ligne par défaut pour réduire le bruit Cloudflare 502/524/529 du réseau de développement.

#### Documentation localisée

`.readme/` et `.changelog/` contiennent les modèles partagés et les sources JSON des 10 langues. `.python/generate_markdown.py` produit 10 README, les CHANGELOG intégrés et écrit `zh-Hans` dans le `README.md` racine. Les chaînes Android restent dans les répertoires `values-*`. Après modification du JSON, exécutez:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### Contrôle hors ligne

```powershell
.\tools\verify_local.ps1
```

Ce script enchaîne le verifier build-ready, la suite Python hostile et `:app:testProviderDebugUnitTest --offline`, puis compare les rapports XML au compte unique de `verification.properties`. CI reproduit ces limites.

#### Entrées immuables

Le protocole comprend exactement trois AAR locaux verrouillés par SHA-256. L'entrée native est PUC Lua 5.4.8, archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`; le verifier recalcule l'empreinte complète et CMake n'admet que les sources examinées. Voir [`protocol/README.md`](protocol/README.md) et [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### Validation des artefacts release

`providerRelease` peut être construit unsigned, mais un candidat publiable doit lire les signatures depuis des chemins absolus externes et lancer des tâches fresh hors ligne:

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

Exécutez ensuite le verifier avec le même certificat. Il contrôle le nombre JVM, le signer, les ABI, l'alignement ZIP/ELF 16 KiB, non-debuggable et l'exclusion du fault harness; cela reste une preuve d'empaquetage.

#### Liste de contrôle fault-harness avant publication

Avant de reconstruire un candidat signé, générez la variante fault et les intermédiaires release unsigned dans une seule invocation clean et canonique:

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

Le marqueur doit être `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`; `faultTestDebug` doit contenir les services isolés et symboles JNI fault, tandis que `providerRelease` doit les exclure. Exécutez aussi `LuaRuntimeFaultRecoveryInstrumentationTest` sur un appareil jetable; le reçu ne remplace pas cette preuve. Sinon, l'état est `UNVERIFIED_FAULT_HARNESS`.

******

### Pour aller plus loin

******

Limites d'exécution: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). Conceptions futures: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). Preuves de publication: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### Historique des versions

******

# v0.1.3

###### 2026/10/04

* `Amélioration` Les icônes du centre de plugins utilisent les tailles, positions, images claires et sombres et fonds circulaires réglés dans Icon Studio, avec les sources et paramètres permettant de les reproduire

# v0.1.2

###### 2026/09/19

* `Correction` Avertissements de lecture SDK XML v4 avec AGP 9.1 et contrôles d'alignement natif des APK déclenchés par erreur lors de l'assemblage des tests unitaires JVM, avec les plugins de compilation partagés 1.8.3
* `Amélioration` compileSdk et targetSdk passent à 37 (Android 17) ; le comportement du plugin ne dépend pas de la nouvelle cible

# v0.1.1

###### 2026/09/11

* `Amélioration` Vérification à la compilation de l'alignement des pages de 16 KB des bibliothèques natives 64 bits, avec contrôle du contrat manifest et rapports JSON
* `Amélioration` Ajouter une activation protégée, des métadonnées exactes et la collecte des versions signées; harmoniser les ressources et les contrôles documentaires en lecture seule
* `Amélioration` Étendre les ABI natives et les métadonnées du plugin à arm64-v8a, armeabi-v7a, x86 et x86_64, avec des APK universels et par ABI cohérents

##### Autres versions

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-fr.md)

******

### Licence

******

Le code propre au dépôt est sous MIT License. Les composants tiers comprennent PUC Lua sous MIT, Kotlin et JetBrains annotations sous Apache-2.0, des parties Android NDK LLVM runtime liées statiquement selon leurs conditions LLVM, et les API de protocole AutoJs6 sous MPL-2.0. Voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) et les textes complets.

******

### Organisation des ressources localisées

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` génère README et CHANGELOG intégré dans les 10 langues depuis JSON; modifiez les sources JSON et non le Markdown généré. Les chaînes Android restent dans leurs répertoires.

******

### Liens

******

- Projet AutoJs6: https://github.com/SuperMonster003/AutoJs6
- Projet Lua: https://www.lua.org
- Mentions tierces: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- Licence du projet: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
