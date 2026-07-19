# ShadowFS - Manuel utilisateur

ShadowFS deplace les fichiers inactifs de votre telephone Android vers votre
Raspberry Pi ou serveur maison, puis les restaure quand vous en avez besoin.

## Premier demarrage

1. Installez le daemon sur le serveur avec `install_raspberry.sh`.
2. Ouvrez l'app Android.
3. Associez l'app avec le QR affiche par le serveur.
4. Accordez notifications, acces aux fichiers et exclusion batterie.
5. Testez la connexion mTLS et demarrez le service.

## Usage quotidien

Quand l'espace baisse, ShadowFS envoie les fichiers eligibles au serveur,
verifie le checksum et laisse un marqueur leger sur le telephone. Vous pouvez
restaurer un fichier depuis **View Ghosted Files** avec **Restore**.

Utilisez **Protected Folders** pour exclure documents de travail, chats ou
dossiers synchronises par d'autres services.

## Apps cloud

Evitez que deux apps gerent le meme dossier. Si Google Photos, OneDrive, Dropbox
ou Nextcloud gere un dossier, protegez-le ou desactivez le backup cloud pour ce
chemin.

## Sauvegarde

ShadowFS verifie les transferts avant de modifier les fichiers locaux, mais ce
n'est pas une sauvegarde. Testez-le d'abord avec des fichiers non critiques et
conservez une copie separee des donnees importantes.

