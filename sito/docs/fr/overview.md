# ShadowFS - Vue d'ensemble

ShadowFS libere de l'espace sur Android en deplacant les fichiers inactifs vers
votre Raspberry Pi, NAS ou serveur Linux maison. Le telephone conserve un
marqueur leger et recupere le fichier complet quand vous en avez besoin.

L'objectif est d'offrir le confort du cloud avec un controle local: pas
d'abonnement ShadowFS, pas de fournisseur cloud obligatoire, et authentification
mTLS entre telephone et serveur.

## En bref

- Deplace photos, videos, PDF, ZIP et autres fichiers eligibles quand l'espace
  disponible baisse.
- Verifie chaque transfert avec SHA-256 avant toute modification locale.
- Conserve miniatures ou marqueurs pour garder les fichiers visibles.
- Restaure les fichiers complets a la demande depuis l'app.
- Fonctionne bien avec Tailscale pour l'acces hors domicile.
- Permet de proteger les dossiers sensibles.

## Securite et responsabilite

ShadowFS ne modifie pas le fichier local sans confirmation complete du serveur.
Il ne remplace toutefois pas une strategie de sauvegarde. Testez-le d'abord avec
des fichiers non critiques et conservez toujours une copie separee des donnees
importantes.
