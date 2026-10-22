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
### 0.12.7
- **Rangées vraiment stables** : le titre (2 lignes) et la chaîne (1 ligne) des cartes ont maintenant une hauteur fixe. Un titre avec emoji ou dans une autre écriture rendait encore la carte quelques dp plus haute, et les rangées autour bougeaient quand on défilait de côté.

### 0.12.6
- **DualSense sans pilote Sony** (Freebox Android 10) : ses boutons arrivaient en BUTTON_1…16 génériques et ne faisaient rien. Ils sont traduits (manettes Sony uniquement, constructeur 054c) : Croix = OK, Rond = Retour, Triangle / Options = Menu, Carré = lecture / pause, L1 / R1 = −10 / +10 s, L2 / R2 = chapitre précédent / suivant, Create = Rechercher.
- **Micro de la télécommande** : ouvre Rechercher et lance la dictée directement (le clavier s'il n'y a pas de reconnaissance vocale sur la box).
- **Clavier de la recherche** : demandé une deuxième fois après la mise au point du champ, aussi par Android, car la première demande pouvait être ignorée par la box.

### 0.12.5
- **Rangées stables** : défiler une rangée de côté ne fait plus monter ou descendre les autres. Chaque carte réserve toujours 2 lignes de titre et la ligne de la chaîne, donc la rangée garde la même hauteur quelles que soient les cartes à l'écran.

### 0.12.3 – 0.12.4
- **Onglets de l'en-tête** : « Pour toi » prenait toute la largeur et cachait les autres sections (Abonnements, Historique…), elles sont de nouveau visibles.
- **Anneau de focus** : il entoure de nouveau les cartes, les avatars de « Qui regarde ? » et les boutons ronds (il était dessiné à l'intérieur de l'image).
- **Taille de l'interface** appliquée aussi à « Qui regarde ? », Réglages, menus de carte et connexion (ils restaient à 100 %).

### 0.12.2
- **Taille de l'interface réglable** : Réglages › Affichage › « Taille de l'interface » : Plus petit / Plus grand, de 60 % à 120 % par pas de 5 %, appliqué tout de suite (Par défaut : 80 %). Le nombre de cartes par rangée suit (6 à 60 %, 5 à 80 %, 4 à 100 %, 3 à 120 %). L'ancien choix « Grande » est repris en 100 %.

### 0.12.1
- **Interface plus compacte et aérée** : tout est dessiné 20 % plus petit (textes, boutons, cartes, panneaux, dialogues), et les rangées montrent 5 cartes au lieu de 4. Pensé pour les TV 4K où les éléments paraissaient énormes. Réglages › Affichage › « Taille de l'interface » : Compacte (par défaut) ou Grande (la taille d'avant).

### 0.12.0
- **Refonte graphique, sur le modèle de celle de Lumen** :
  - thèmes **Jour** et **Nuit**, et **Auto** (clair de 7 h 30 à 19 h 30) : Réglages › Affichage › Apparence ; fond aux deux halos (chaud en haut à droite, froid en bas à gauche) ;
  - **en-tête à onglets** à la place du menu latéral : nom de la section en grand, sections en onglets (OK pour ouvrir), boutons ronds Rechercher, compte (« Qui regarde ? ») et Réglages, heure, et « Lecture en cours » avec une vidéo en petite fenêtre ;
  - focus : aplat plein (blanc la nuit, noir le jour) sur les boutons et pastilles, anneau détaché autour des cartes et des avatars, zoom plus léger ;
  - cartes : titre et chaîne dessous, plus d'image de fond ni de fiche en haut de page ; largeur calculée d'après l'écran (4 cartes par ligne sur toute TV, box ou densité) ;
  - **Rechercher** : grande barre en pilule ; **chaîne** : en-tête façon fiche Lumen (« CHAÎNE », avatar, nom en grand, S'abonner, tris) ;
  - **« Qui regarde ? »** et **Réglages** en plein écran ; Réglages en deux colonnes (Lecture, Affichage, Téléphone, Compte) avec des choix en pastilles ;
  - lecteur : boutons ronds translucides, panneaux (réglages, commentaires) aux couleurs du thème.
- **Défilement plus calme** : les rangées ne bougent plus à chaque Haut / Bas ; la page ne défile que si la carte visée sortirait de l'écran (titre de rangée et légende gardés visibles).
- **Retour** : rangées → onglets (ou « Lecture en cours » avec une petite fenêtre) → Accueil → ferme la petite fenêtre → quitte. Depuis Rechercher, Retour revient à la section d'avant.
- **Manette (DualSense…)** : A = OK, B = Retour, X = lecture/pause (et petite fenêtre en plein écran), Y / Start = menu (menu de la carte, réglages du lecteur), Select = Rechercher, L1 / R1 = −10 s / +10 s, L2 / R2 = chapitre précédent / suivant (±60 s sans chapitres). Seuls les événements de la manette sont convertis, la télécommande ne change pas.
- **Télécommande** : la petite fenêtre ne capte plus Gauche / Droite (qui faisaient avancer la vidéo réduite) et OK la remet en plein écran du premier coup ; touches média (lecture, pause, retour/avance rapide) actives aussi commandes affichées ; Menu ouvre les réglages du lecteur ; maintenir Gauche / Droite sur la barre accélère au plus ×6 (au lieu de sans limite).

### 0.11.4
- Recherche : Bas pendant la saisie vers une suggestion (ou un filtre) laisse le focus dessus, au lieu de le renvoyer sur la barre. OK sur la suggestion lance la recherche comme la loupe du clavier : focus sur la barre, puis sur la première vidéo trouvée.

### 0.11.3
- Recherche : valider avec la loupe du clavier ne fait plus passer le focus par le menu latéral ; il revient sur la barre, puis va sur la première vidéo trouvée dès qu'elle arrive.

### 0.11.2
- Recherche : OK sur une suggestion ne fait plus passer le focus par le menu latéral (il va sur la barre, puis sur le premier résultat quand il arrive). OK sur un filtre garde le focus sur ce filtre (pour enchaîner les choix) ; les résultats se mettent à jour dessous, Bas pour y aller.

### 0.11.1
- Lecteur : Retour quitte la vidéo directement, même avec les commandes affichées (avant, un premier Retour les masquait seulement). Dans un panneau (Réglages, sous-titres, commentaires…), Retour ferme toujours d'abord le panneau.

### 0.11.0
- **HDR** : les pistes HDR de YouTube (VP9.2 et AV1 10 bits, HDR10 ou HLG) sont reconnues et proposées comme les autres qualités. Le menu Qualité les liste avec leur nom complet (« 2160p60 · 4K HDR », « 2160p60 · 4K », « 1080p60 »…), uniquement si le téléviseur affiche le HDR et que le décodeur de la box les prend en charge. En Auto, le HDR est choisi quand l'écran le gère (sinon SDR, jamais d'image HDR délavée sur un écran SDR), toujours avec la connexion et le plafond de charge.
- La qualité est désormais appliquée comme un choix explicite de pistes : Auto reste adaptatif entre les pistes retenues, une qualité choisie est tenue.
- Statistiques : type HDR de la piste jouée et formats HDR que l'écran annonce (HDR10, HLG, HDR10+, Dolby Vision) ; la ligne Vidéo indique HDR10 / HLG.
- Dolby Vision : YouTube ne diffuse pas de flux Dolby Vision ; sur un téléviseur Dolby Vision, les vidéos HDR sont lues en HDR10 / HLG.

### 0.10.4
- Recherche : Bas depuis la barre (ou « Dicter ») va toujours au premier filtre / à la première suggestion, et Haut depuis cette ligne revient à la barre (la recherche de focus automatique partait parfois vers le menu latéral).

### 0.10.3
- **Qualité Auto sans plafond fixe** : jusqu'à la résolution maximale que le décodeur accepte, selon la connexion (adaptatif d'ExoPlayer) et la charge de la box : si plus de 10 % des images sont perdues sur ~6 s (20 au moins), Auto descend d'un cran sous la qualité jouée, pour le reste de la session (« Auto : plafonné à …p » dans les statistiques, journalisé sous `LumenTube.Freeze`). Une qualité choisie à la main reste tenue. Remplace le plafond à 1080p de la 0.7.3.

### 0.10.2
- **Image figée, son qui continue** : le décodeur vidéo de la box peut se bloquer (vu sur Amlogic, surtout en VP9). Le chien de garde le détecte (aucune nouvelle image pendant ~4,5 s alors que la position avance) et relance le décodage sur place, à la même position. Chaque gel est compté dans les statistiques (« Gels relancés ») et journalisé avec le codec : `adb logcat -s LumenTube.Freeze`.

### 0.10.1
- Commandes du lecteur : le même espace (10 dp) entre tous les boutons, compteurs J'aime / Je n'aime pas compris (plus d'écarts plus larges entre les groupes).

### 0.10.0
- **Commandes du lecteur réorganisées** par usage, avec un espace entre les groupes : lecture (lecture/pause, sous-titres, chapitres) · la vidéo (J'aime, Je n'aime pas, commentaires, À propos) · fenêtre et réglages (petite fenêtre, Réglages toujours en dernier).
- **Réglages du lecteur en sections** : LECTURE (qualité, sous-titres, piste audio, vitesse, format de l'image) · VIDÉO ET CHAÎNE (à regarder plus tard, voir la chaîne, s'abonner) · OPTIONS (SponsorBlock, statistiques). À propos et Chapitres ne sont plus répétés (ils ont leur bouton) ; le focus s'ouvre sur la première ligne, pas sur un titre de section.
- **Perfs (audit)** :
  - déplacer le focus ne recompose plus toute la page (lu dans un flux), et la fiche / l'image de fond ne suivent qu'après 300 ms de pause sur une carte ; la fiche change sans fondu (un fondu rendait le texte hors écran à chaque image) ;
  - `contentType` sur les rangées et les cartes (réutilisation des compositions entre rangées) ; durées formatées sans `String.format`, expression des avatars compilée une seule fois ;
  - voile du menu latéral dessiné directement, sans calque plein écran ;
  - cache mémoire Glide de 32 Mo (≈ 6 écrans de miniatures, au lieu de ≈ 2) : revenir en arrière ne redécode plus depuis le disque ;
  - tampon vidéo plafonné à 40 Mo en plus des 30 s (la 4K pouvait occuper plus de 100 Mo de mémoire) ;
  - LoudnessEnhancer créé seulement quand la vidéo a besoin d'être remontée ;
  - lecteur : un appui de touche ne recompose plus tout l'écran (minuterie de masquage lue dans un flux).
- **Chaîne ouverte depuis une carte** (« Vos chaînes préférées »…) : le focus ne passe plus par le menu latéral pendant le chargement (il est tenu dans la page jusqu'aux vidéos).
- Le script d'installation compile l'appli en natif (AOT, `cmd package compile -m speed`) après l'installation.

### 0.9.2
- **Focus audio** : le lecteur demande `AUDIOFOCUS_GAIN` au démarrage de la lecture (et à chaque reprise) et se met en pause sur `AUDIOFOCUS_LOSS` (sans reprise automatique) ou `AUDIOFOCUS_LOSS_TRANSIENT` (reprise automatique au retour du focus, sauf si la lecture a été relancée à la main entre-temps). « Peut baisser le son » : le système baisse le volume lui-même, la lecture continue. Le focus est rendu à la fermeture du lecteur. Géré par LumenTube et non par ExoPlayer, dont la gestion laissait parfois la vidéo prête mais en pause sur la box : une demande refusée ou différée ne bloque jamais la lecture.
- Arrière-plan (bouton Accueil, autre appli) : inchangé, déjà en place : pause et sauvegarde de la position (`updateHistoryPosition`) dans `onStop`, et au retour la vidéo attend en pause, à la même position, commandes affichées.

### 0.9.1
- À propos : description en plus petit (13 sp au lieu de 15, interligne 19), plus de texte visible à la fois pour les longues descriptions.

### 0.9.0
- **À propos** : bouton rond (i) dans la rangée de commandes, qui ouvre le panneau de droite avec les chiffres (vues, date, j'aime) et la description (Haut/Bas pour la faire défiler). Renommé aussi dans Réglages (ex-« Infos sur la vidéo »).
- **Chapitres** : bouton rond (liste) quand la vidéo en a, aussi dans Réglages : chaque chapitre avec son heure de début, celui en cours coché ; OK saute au chapitre et ferme la liste.
- Retour depuis ces panneaux remet le focus sur le bouton qui les a ouverts.

### 0.8.3
- Lecteur : plus d'espace autour des compteurs J'aime / Je n'aime pas (10 dp après le cercle, 16 dp avant le bouton suivant) : le cercle agrandi au focus ne touche plus le texte.

### 0.8.2
- Lecteur : J'aime et Je n'aime pas sont des boutons ronds identiques aux autres (même taille, même hauteur, cercle blanc au focus), avec le compteur affiché à côté du cercle.

### 0.8.1
- Correctif de compilation de la 0.8.0 (panneau des commentaires : API de focus expérimentale de Compose déclarée). Aucun changement de fonctionnement.

### 0.8.0
- **Commandes du lecteur** : les boutons « reculer » et « avancer » disparaissent (Gauche/Droite commandes masquées, ou la barre de progression, font la même chose). À droite de lecture/pause : J'aime, Je n'aime pas, Commentaires, ronds façon Nuvio (blancs au focus), puis Sous-titres, Petite fenêtre et Réglages. Haut mène à la barre, Bas masque les commandes.
- **J'aime / Je n'aime pas** : nombre de « j'aime » (métadonnées YouTube) et nombre estimé de « je n'aime pas » (Return YouTube Dislike, via MediaServiceCore). OK met ou retire le vote ; le pouce se remplit quand il est actif, et les deux s'excluent. Sans compte connecté, seuls les compteurs sont affichés.
- **Commentaires** : panneau à droite (fond sombre, 40 % de la largeur) : avatar, auteur, date relative, texte, nombre de j'aime et de réponses. OK sur un commentaire déplie ou replie ses réponses (« Plus de réponses » pour la suite), la page suivante se charge en approchant du bas. Retour ferme le panneau et remet le focus sur le bouton Commentaires ; la vidéo continue derrière, commandes masquées.

### 0.7.3
- **Fluidité des pages** : les rangées ne se redessinent plus à chaque changement d'état (message, lecteur, dialogue) : la page n'est recalculée que si son contenu change, et les modèles (vidéos, rangées, pages) sont déclarés immuables pour Compose. L'image de fond se fond dans la page avec un dégradé simple au lieu d'un masque sur un calque hors écran (moins de travail pour le GPU de la box), et son fondu dure 300 ms au lieu de 500.
- **Fluidité de la lecture** : la barre de temps n'est rafraîchie que lorsqu'elle est affichée (commandes ou ligne de recherche), au lieu de redessiner l'écran du lecteur deux fois par seconde. La qualité « Auto » s'arrête à 1080p (la 4K, souvent à 60 i/s, perd des images sur la box) ; 1440p et 4K restent disponibles dans Qualité.
- **Petite fenêtre** : Retour depuis la page place le focus directement sur « Lecture en cours » dans le menu ; OK remet la vidéo en plein écran (Retour puis OK, quel que soit le nombre de rangées).

### 0.7.2
- **Flux Lumen, recherche** : `query content://dev.miraiijo.lumentube.feed/search?q=<texte>` (même contrôle de l'appelant, mêmes colonnes que `/continue`). Renvoie les 20 premiers résultats vidéo de YouTube, sans chaînes, playlists ni Shorts. `q` est coupé à 200 caractères ; une requête vide, une erreur ou plus de 8 s sans réponse renvoient un curseur vide. Les recherches tournent sur leurs propres threads (deux au plus en même temps), jamais derrière un rafraîchissement du flux.

### 0.7.1
- Lecteur : Retour depuis le panneau des sous-titres remet le focus sur le bouton Sous-titres (et non plus sur Réglages).

### 0.7.0
- **Regarder sur la TV** : Réglages › « Lier un téléphone » affiche un code TV à saisir dans l'app YouTube du téléphone (photo de profil › Paramètres › Regarder sur la TV › Saisir le code TV). Le bouton Caster du téléphone envoie alors la vidéo ici, et le téléphone sert de télécommande (lecture, pause, position, suivante, arrêt, volume). Écoute uniquement quand LumenTube est à l'écran, et désactivable (« Commande depuis le téléphone »). Désactivé tant qu'aucun téléphone n'a été lié.
- **Menu d'une carte** : appui long sur OK (ou touche Menu) : Lire, À regarder plus tard, Ouvrir la chaîne, Pas intéressé, Ne pas recommander cette chaîne. La carte disparaît des rangées tout de suite.
- **Pages de chaîne** : avatar, nom, bouton « S'abonner » / « Abonné », et les tris de la chaîne (Accueil, Plus récentes, Populaires…). Accessible depuis une carte de chaîne, le menu d'une carte, ou « Voir la chaîne » dans les réglages du lecteur (la vidéo passe en petite fenêtre).
- **Recherche** : suggestions de YouTube pendant la saisie (sous la barre, Bas pour y aller), et filtres Date, Durée, Type (vidéos, chaînes, playlists, en direct) et Tri (pertinence, date, vues, note) ; OK sur un filtre passe au choix suivant.
- **Statistiques pour les curieux** (réglages du lecteur) : résolution, i/s, codec, débits vidéo et audio, images perdues, tampon, débit de la connexion, volume, type de flux et client YouTube, temps de démarrage (formats et première image).

### 0.6.0
- **Flux Lumen, plusieurs comptes** (contrat figé, utilisé par Lumen 3.6.0) :
  - `query /accounts` : une ligne par compte lié, avec `account_id`, `name`, `avatar_url` (https ou null) et `selected` (`"1"` ou `"0"`). `account_id` est un HMAC-SHA256 (64 caractères hexadécimaux) de l'identité du compte, calculé avec une clé aléatoire propre au Player : il est stable d'un redémarrage à l'autre, et on ne peut pas remonter à l'e-mail. Aucun e-mail, jeton ou cookie n'est exposé ;
  - `call(content://dev.miraiijo.lumentube.feed/, "select_account", account_id)` sélectionne le compte, marque son flux comme périmé (son dernier contenu reste affiché tout de suite) et rafraîchit `/continue` et `/subscriptions` sur un thread à part. Il renvoie `ok` = true, ou false si le compte est inconnu, et `null` pour une méthode inconnue ;
  - un cache par compte (`lumen_feed_<account_id>.json`) : un changement de profil affiche tout de suite le bon contenu, et un chargement lancé avant un changement ne peut écrire que dans le cache de son compte ; la déconnexion supprime le cache du compte ;
  - `notifyChange` sur `content://dev.miraiijo.lumentube.feed/` et ses chemins à chaque rafraîchissement et à chaque changement de compte, y compris depuis LumenTube ;
  - LumenTube suit un changement de compte fait depuis Lumen : ses pages se rechargent pour le nouveau compte.
- **Lecteur réduit** : la petite fenêtre prend le focus comme une carte (Bas ou Droite depuis la page, cadre blanc) et OK la remet en plein écran. Indispensable pour les télécommandes sans touche lecture/pause.

### 0.5.3
- « À suivre » plus compact, en bas à droite (miniature 144 dp, titre sur deux lignes, boutons « Lire » et « Annuler ») : la fin de la vidéo reste visible.
- Audio : la langue originale est choisie par défaut sur les vidéos doublées, et elle est en tête de la liste « Piste audio ». Les types de piste sont traduits : originale, doublée, doublage automatique, audiodescription.
- Sous-titres centrés aussi sur la vidéo suivante (« À suivre ») : chaque vidéo a maintenant son propre écran de lecteur.
- Audit sécurité et performances :
  - liens entrants : seul un vrai identifiant de vidéo (11 caractères) est accepté, le moteur l'insérant tel quel dans ses requêtes connectées ; le paramètre `t` est plafonné à 24 h, plus de plantage sur une valeur énorme ;
  - flux Lumen : l'appelant est vérifié avant tout le reste, et le certificat par le système (`hasSigningCertificate`, rotation de clé comprise) ; un chargement lancé avant un changement de compte n'écrit plus dans le cache ; fichier écrit avec `AtomicFile`, qui résiste aux coupures de courant ;
  - CI : jeton en lecture seule, plus de release publique (l'APK reste un artefact du build), keystore limité à l'étape qui le restaure puis supprimé, jeton non conservé dans `.git`, actions épinglées par commit ;
  - lecture : la source vidéo (analyse du manifeste DASH/SABR, création de Cronet) est construite hors du thread principal ;
  - Accueil : les flux limités à deux rangées sont arrêtés au lieu de continuer à charger des pages inutiles ;
  - aperçus de la barre : chaque planche est décodée une seule fois (trois gardées en mémoire), et la vignette est découpée selon la taille réelle de l'image ;
  - cartes : miniatures en 640 px (`sddefault`), le 1280 px reste réservé à l'image en haut à droite et au lecteur ;
  - DeArrow : chaque vidéo n'est demandée qu'une fois ;
  - la préparation du moteur YouTube se fait à l'ouverture de l'écran, plus quand Lumen interroge le flux ;
  - sauvegarde de position dans une portée de coroutines unique, propre à l'app.

### 0.5.2
- Sous-titres centrés en bas de l'image : les positions envoyées par YouTube (souvent « à gauche, 0 % » pour les sous-titres automatiques) sont ignorées, comme dans SmartTube. Les lignes répétées des sous-titres automatiques défilants ne s'affichent plus qu'une fois.

### 0.5.1
- Flux pour Lumen : LumenTube garde sa propre clé de signature. La permission `READ_FEED` passe au niveau `normal` et ne verrouille plus rien ; c'est le fournisseur qui vérifie l'appelant : le paquet `dev.miraiijo.lumen`, fourni par le système, et l'empreinte SHA-256 de son certificat, réglée dans `gradle.properties` (`lumen.certSha256`, remplaçable par la variable d'environnement `LUMEN_CERT_SHA256`). Toute autre app reçoit une `SecurityException`. Si Lumen change de clé, il suffit de mettre à jour l'empreinte.
- Sous-titres plus petits (4,2 % de la hauteur de l'image au lieu de 6 %).

### 0.5.0
- **Flux pour Lumen** : un fournisseur de contenu en lecture seule, `dev.miraiijo.lumentube.feed`, protégé par la permission `dev.miraiijo.lumentube.permission.READ_FEED` (niveau signature : Lumen doit être signé avec la même clé de release) :
  - `/continue` : vidéos commencées et non finies (historique, progression de 1 à 94 %), de la plus récente à la plus ancienne ;
  - `/subscriptions` : dernières vidéos des abonnements, sans les Shorts ;
  - 20 lignes au plus ; colonnes `video_id`, `title`, `channel`, `thumbnail_url` (https), `duration_ms`, `watched_percent`, `position_s`, `published_ms` (0 si inconnu), `live` (0/1) ;
  - cache JSON dans `filesDir`, écrit à chaque chargement d'Abonnements ou de l'historique (Historique, rangée « Reprendre ») ; s'il a plus de 30 min et que l'utilisateur est connecté, il est rafraîchi sur un thread à part, 8 s au plus, sinon l'ancien est servi ; `notifyChange` à chaque mise à jour ;
  - aucun jeton, cookie ou identifiant de compte ; déconnecté : curseur vide ; cache effacé au changement de compte et à la déconnexion ;
  - insert, update et delete lèvent `UnsupportedOperationException`.
- **Liens** : le paramètre `t` des liens youtube.com/watch et youtu.be (`90`, `90s`, `1m30s`, `1h2m3s`) fixe la position de départ. Les liens youtube.com et m.youtube.com sont aussi acceptés.

### 0.4.5
- Rangées de chaînes : avatars plus petits (96 dp), plus d'espace au-dessus et zoom réduit, pour ne plus déborder sur le titre de la rangée ; la rangée active garde son titre entier visible. Avatar en haut à droite plus petit (120 dp), aligné sur la fiche.

### 0.4.4
- Sous-titres : français et anglais en tête, sous « Préférées » (ceux de la vidéo puis les traductions automatiques), puis « Autres langues ».
- Lecteur, commandes masquées : Gauche/Droite avancent ou reculent sans afficher les boutons ni leur donner le focus. Les appuis s'additionnent : 10 s par appui, puis 30 s et 60 s quand la touche reste enfoncée. La barre de temps montre la cible et son image, et le lecteur ne saute qu'une fois, 0,6 s après le dernier appui, au lieu de recharger la vidéo à chaque appui.
- Lecteur : le bouton ⚙ Réglages est le dernier de la rangée, après ▣.

### 0.4.3
- Lecteur : Retour (ou la fin du délai) qui masque les commandes ne fait plus perdre le focus ; la télécommande garde la main (OK, ±10 s).

### 0.4.2
- Compte et Réglages séparés :
  - le compte, en haut du menu, ouvre « Qui regarde ? » : une petite fenêtre centrée avec les avatars ronds et « Ajouter » ;
  - « Réglages », en bas du menu, ouvre une fenêtre qui ne touche ni le haut ni le bas de l'écran (sa liste défile si besoin), avec la déconnexion du compte actuel.
- Avatars des comptes en 256 px.
- Lecteur réduit : la touche lecture/pause de la télécommande le remet en plein écran (rappel « ⏯ Plein écran » dans la petite fenêtre).
- Chargement d'une vidéo : plus de titre sous les points, il est déjà dans les commandes.

### 0.4.1
- Chaînes (« Vos chaînes préférées », résultats de recherche…) : avatar rond et plus petit, demandé en 256 px au serveur d'images de Google au lieu de 88 px. En haut à droite, une chaîne sélectionnée s'affiche en rond, sans image 16:9 pixelisée.

### 0.4.0
- Lecteur réduit (image dans l'image, dans l'app) : le bouton ▣ du lecteur réduit la vidéo dans un coin, et la navigation continue. « Lecture en cours » en haut du menu, ou OK sur la même vidéo, la remet en plein écran. Au dernier Retour (menu, Accueil), la vidéo réduite s'arrête. En fin de vidéo, la suivante enchaîne directement dans la petite fenêtre.
- Démarrage des vidéos :
  - le moteur YouTube (script du lecteur, déchiffrement des signatures) se prépare au lancement de l'app, comme dans SmartTube, au lieu de pendant la première vidéo ;
  - SponsorBlock et les infos de la vidéo ne partent qu'une fois les formats reçus, pour ne plus concurrencer la requête dont l'image dépend ;
  - temps de démarrage dans les logs : `adb logcat -s LumenTube.Timing`.

### 0.3.2
- Lecteur : la touche Accueil de la télécommande (ou une autre app au premier plan) met la vidéo en pause et enregistre la position. Au retour, la vidéo attend en pause, commandes affichées.

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
