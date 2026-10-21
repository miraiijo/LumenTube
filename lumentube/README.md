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
### 0.3.1
- Sous-titres ouverts par leur bouton : Retour, ou le choix d'une langue, ferme le panneau au lieu d'afficher les réglages.

### 0.3.0
- **Accueil** : la rangée « Reprendre » (vidéos commencées) en tête, puis l'accueil YouTube, les nouveautés des abonnements et deux rangées de chaque rubrique (Musique, Gaming, Actus, Sport, Direct).
- **Rechercher** (en tête du menu) : au clavier de la télé, ou à la voix (« Dicter ») si le Player propose la reconnaissance vocale.
- **Comptes** :
  - connexion par QR code, avec le code déjà rempli ; le QR est dessiné sur le Player (bibliothèque ZXing), sans service web ;
  - plusieurs comptes : « Qui regarde ? » à l'ouverture dès que deux comptes sont liés ;
  - « Compte et réglages » en bas du menu : changer de compte, en ajouter un, se déconnecter.
- **Réglages** : Lecture automatique, SponsorBlock, Volume stable, DeArrow (désactivé par défaut).
- **Lecteur** :
  - « À suivre » : en fin de vidéo, la suivante (playlist ou suggestion YouTube) après 10 s ;
  - aperçu de l'image en déplaçant la barre ;
  - bouton J'aime ;
  - dans ⚙ : s'abonner, ajouter à « À regarder plus tard », infos sur la vidéo (description, vues, date), format de l'image (image entière, remplir l'écran, étirer) ;
  - volume stable : correction de volume par vidéo fournie par YouTube (+3,5 dB au plus) ;
  - OK affiche les commandes au lieu de mettre en pause ; la touche lecture/pause de la télécommande garde son rôle.
- **Menu latéral** : les rubriques défilent quand elles ne tiennent pas à l'écran.
- **Tendances** retiré : YouTube a supprimé sa page Tendances en 2025 et le flux ne répond plus.

### 0.2.0
- Lecteur : panneau Réglages façon Nuvio, ouvert par le bouton ⚙ et fermé par Retour :
  - **Qualité** : Auto, ou une qualité tenue (2160p, 1440p, 1080p…). Seules les qualités que le Player sait décoder sont proposées. Le choix est mémorisé.
  - **Sous-titres** : ceux de la vidéo, puis les traductions automatiques de YouTube. La langue choisie est réappliquée aux vidéos suivantes qui l'ont. Bouton direct à côté de ⚙. Texte blanc sur bande sombre, un peu plus grand.
  - **Vitesse** : de 0,5× à 2×.
  - **Piste audio** : affichée seulement pour les vidéos doublées.
  - **SponsorBlock** : activé ou désactivé (mémorisé).
- SponsorBlock : les sponsors, l'autopromo, les rappels d'abonnement, l'intro et le générique de fin sont sautés automatiquement, avec un bandeau « Passé : … ». Ces segments apparaissent en orange sur la barre de progression, et un segment n'est sauté qu'une fois : revenir en arrière dedans le lit.
- Chapitres : marqués sur la barre de progression, avec le chapitre en cours affiché à côté du nom de la chaîne, y compris pendant l'aperçu d'un déplacement.

### 0.1.3
- Accueil et les autres pages s'affichent dès la première partie reçue. YouTube envoie l'Accueil en plusieurs requêtes successives, et l'app attendait la dernière avant d'afficher quoi que ce soit.
- Reprise de lecture plus rapide : le lecteur se place à la position sauvegardée avant de préparer la vidéo, au lieu de charger le début puis de sauter.
- Les titres sans couleur explicite (rangées, fiche) sont blancs : ils étaient noirs sur fond noir.
- Miniatures en HD (1280×720, maxresdefault) sur les cartes, la fiche et l'écran de chargement du lecteur. Si une vidéo n'en a pas, la miniature du flux est utilisée.

### 0.1.2
- CI : la validation du wrapper Gradle ne porte plus que sur celui de LumenTube, comparé à la somme publiée par Gradle. Celle de setup-gradle analysait tout le dépôt et rejetait les wrappers de SmartTube (racine et ExoPlayer), que ce build n'exécute jamais.

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
- Rangée ou tuile dans Lumen.
- Signature avec la vraie clé (secrets de la CI).
