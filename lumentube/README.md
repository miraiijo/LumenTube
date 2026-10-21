# LumenTube

YouTube sans pubs pour le Freebox Player, avec l'interface de Lumen. C'est un fork perso de
[SmartTube](https://github.com/yuliskov/SmartTube) (licence MIT, conservée dans `../LICENSE`).

## Principe
- Le dépôt est un fork de SmartTube. **Aucun fichier de SmartTube n'est modifié.** Tout est dans `lumentube/`, avec en plus `.github/workflows/lumentube.yml`.
- `lumentube/` est un build Gradle autonome (AGP 8.7, Kotlin 2, Compose TV, comme Lumen). Il compile depuis les dossiers parents le moteur de SmartTube, tel quel :
  - `MediaServiceCore` : API YouTube, compte, abonnements, historique ;
  - `SharedModules` ;
  - l'ExoPlayer modifié avec **SABR**.
- Les adaptations nécessaires au build se font dans `lumentube/build.gradle`, pas dans les fichiers amont :
  - namespace et manifeste ;
  - BuildConfig ;
  - Kotlin compilé pour Java 8.
- Fichiers recopiés depuis SmartTube, dans `app/src/main/java/.../player/upstream/` : la préparation de la lecture (`ExoMediaSourceFactory`, analyseur DASH live, gestion des erreurs). L'en-tête de chaque fichier indique le commit amont. À resynchroniser quand SmartTube les modifie :
  `git diff <commit> upstream/master -- common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/`

## Mise en place (une fois)
1. Sur GitHub, forke `yuliskov/SmartTube`, par exemple en `miraiijo/LumenTube`.
2. Clone-le avec ses sous-modules : `git clone --recurse-submodules git@github.com:miraiijo/LumenTube.git`
3. Copie dans le clone le dossier `lumentube/` et le fichier `.github/workflows/lumentube.yml` de l'archive, puis commit et push.
4. Optionnel : désactive les workflows de SmartTube (CI, VirusTotal…) dans l'onglet Actions du fork.
5. Pour des mises à jour qui s'installent par-dessus, ajoute une clé de signature dans les secrets du dépôt : `LUMENTUBE_KEYSTORE_B64`, `LUMENTUBE_KEYSTORE_PASSWORD`, `LUMENTUBE_KEY_ALIAS`, `LUMENTUBE_KEY_PASSWORD`. C'est le même principe que pour Lumen. Sans clé, l'APK est signé avec une clé de debug et n'est pas publié en release.

La CI produit `lumentube-<version>.apk`, en artefact et en release `lumentube-v<code>`.

## Mettre à jour depuis SmartTube
```
git remote add upstream https://github.com/yuliskov/SmartTube.git   # une fois
git fetch upstream
git merge upstream/master
git submodule update --init --recursive
git push
```
Ensuite, regarde si les fichiers de `player/upstream/` ont changé chez SmartTube (commande ci-dessus).

## Nouveautés
### 0.1.1
- Build : les versions partagées de SmartTube (`SharedModules/constants.gradle`) sont chargées sur le projet racine, comme dans SmartTube. Sans elles, les modules ExoPlayer n'avaient ni minSdk ni compileSdk.
- Build : seule la version inexistante « 1.8.10 » de kotlinx-coroutines est remplacée ; Compose et lifecycle gardent celle qu'ils demandent.
- Build : cronet-api fixé en 105, comme dans SmartTube, pour correspondre au Cronet embarqué.
- Build : le Kotlin amont (youtubeapi) est compilé avec le frontend K1 (langage 1.9) plutôt que K2.
- Correction : conflit de signature JVM entre les deux `collectAll` de `YouTube.kt`.
- Correction : les liens youtu.be ouvrent bien la vidéo (filtre d'intent séparé de /watch).

### 0.1.0
- Build et CI.
- Connexion au compte avec un code à saisir sur youtube.com/activate.
- Menu latéral façon Lumen : Accueil, Abonnements, Tendances, Musique, Gaming, Actus, Sport, Direct, Historique, Playlists.
- Rangées de vidéos 16:9, avec une fiche de la vidéo sélectionnée au-dessus et une page suivante chargée au bout de chaque rangée. Les chaînes et playlists s'ouvrent.
- Lecteur façon Nuvio :
  - titre et chaîne, barre de progression, boutons ronds, heure ;
  - OK = pause, Gauche/Droite = ±10 s ;
  - reprise à la position sauvegardée et historique YouTube mis à jour.
- Démarrage plus rapide : lecture dès 1 s de tampon au lieu de 2,5 s, pas de gestion du focus audio, et un chien de garde qui relance la lecture si elle reste bloquée en pause alors qu'elle devrait jouer.
- Erreur de lecture : nouvelle tentative avec un autre client YouTube, comme SmartTube.

## Prochaine étape
- Recherche.
- SponsorBlock.
- Choix de la qualité et sous-titres.
- Rangée ou tuile dans Lumen.
