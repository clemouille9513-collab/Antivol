# Antivol – étape 1

Appli Android (Kotlin) pour Samsung S22.

## Ce qu'elle fait
- Surveille en Bluetooth les appareils que tu choisis (montre, AirPods...).
- Si plus aucun n'est connecté pendant 30 secondes (ou si le Bluetooth est coupé), le téléphone se verrouille.
- Après le déverrouillage Samsung, un écran plein écran demande tes codes dans l'ordre (code 1, code 2...). Il reste au-dessus de toutes les applis.
- Codes de secours à usage unique pour débloquer si tu oublies un code.
- Changer les codes ou couper la protection demande d'abord tous les codes.

## Installer sur ton S22
1. Installe Android Studio sur un ordinateur.
2. Ouvre le dossier `Antivol` (File > Open), attends la fin de la synchronisation Gradle.
3. Sur le S22 : Paramètres > À propos du téléphone > Informations sur le logiciel, appuie 7 fois sur "Numéro de build", puis active "Débogage USB" dans les options pour les développeurs.
4. Branche le S22 en USB et clique sur le bouton Run (triangle vert).

Pour un fichier APK : Build > Build Bundle(s) / APK(s) > Build APK(s).

## Premier réglage (dans l'appli)
Fais les étapes 1 à 7 dans l'ordre. Appaire d'abord ta montre ou tes AirPods dans les réglages Bluetooth du téléphone.
Si l'étape 7 refuse, l'appli te dit ce qui manque.
Utilise "Tester" pour voir le verrouillage sans éloigner ta montre.

## Sur Samsung, pour que ça tourne toujours
Paramètres > Batterie > Limites d'utilisation en arrière-plan : mets Antivol dans "Applis jamais en veille".

## À venir
- Étape 2 : position du téléphone dans une appli, avec un serveur gratuit.
- Étape 3 : détection de course, visage et voix.
- Code de secours par e-mail (demande le serveur de l'étape 2).
