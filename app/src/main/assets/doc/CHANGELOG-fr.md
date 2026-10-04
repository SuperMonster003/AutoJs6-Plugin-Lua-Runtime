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

# v0.1.0-rc.2

###### 2026/08/27

* `Note` Il s'agit toujours d'un candidat d'empaquetage signé validé localement: aucun tag public ni GitHub Release, et le reçu immuable conserve `deviceVerified=false/runtimeVerified=false`
* `Note` Le smoke physique arm64-v8a et le soak de sept jours ont été retirés par décision du owner; les deux jours de preuves et le standard gelé restent archivés, puis la stabilité suit fix-on-report
* `Ajout` Ajout des coroutines contrôlées, `autojs.now()`, `console.info/warn`, du côté Provider de `ui.toast.v1`, des diagnostics de crash et des événements `AutoJs6LuaWatchdog`
* `Ajout` Implémentation négociée de `storage.kv.v1` avec persistance Host isolée par script fichier, formes get/put/remove/clear fixes, valeurs canoniques bornées et aucune nouvelle tentative, puis livraison Host de `ui.toast.v1`
* `Ajout` Ajout des snapshots de modules texte, des arguments en lecture seule et du pont d'informations appareil tout en propageant délai, annulation, mémoire et quotas de sortie
* `Ajout` Fourniture des bibliothèques arm64-v8a et x86_64 avec preuves x86_64 Host réel archivées sur API 24, 31, 36 et 37
* `Correction` Un délai minime expirant avant l'arrivée de `start()` produit maintenant un seul terminal `TIMEOUT/QUEUE`, sans session orpheline
* `Correction` Durcissement de `math.randomseed` et du rejet des capability Host afin qu'un appel non accordé finisse en `HOST_CAPABILITY`
* `Amélioration` Remplacement des quatre modes Boolean par `providerDebug/providerRelease/nativeTestDebug/faultTestDebug`, avec isolation physique de la découverte de production et du fault harness destructif
* `Amélioration` Migration vers la convention `.python/generate_markdown.py` + `.readme/` + `.changelog/` des plugins voisins, documentation complète en 10 langues et `zh-Hans` comme README racine
* `Amélioration` Séparation de R5 depuis `ROADMAP-R4.md` vers `ROADMAP-R5.md` et suppression de la tâche devenue inutile pour les créneaux chinois traditionnels
* `Amélioration` Ajout du suivi PFD logique et OS, du contrôle offline en une commande, d'une CI résiliente, de la validation release et des audits d'exclusion fault-harness
* `Dépendance` Verrouillage de PUC Lua 5.4.8, Android NDK 28.2.13676358 et CMake 3.22.1

# v0.1.0-rc.1

###### 2026/08/13

* `Note` Le premier candidat local Provider-enabled a été signé et testé sur appareil, sans tag ni release public
* `Ajout` Introduction du processus indépendant `:lua_runtime`, de l'exécution Lua texte, de la console, des résultats scalaires et de la découverte Binder AutoJs6
* `Correction` Rejet fail-closed des requêtes malformées ou excessives par validation du protocole, digest, UTF-8, délai, mémoire et sortie
* `Amélioration` Création de preuves vérifiables pour AAR, sources Lua, ABI, alignement 16 KiB, signature et matrices de retour arrière
* `Dépendance` Fondé sur PUC Lua 5.4.8 standard et le protocole Lua AutoJs6 1.0 gelé
