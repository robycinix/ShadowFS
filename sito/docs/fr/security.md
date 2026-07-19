# ShadowFS - Securite

ShadowFS repose sur une regle centrale: ne pas modifier le fichier local tant que
le serveur n'a pas confirme completement la copie distante.

## Garanties principales

- Le fichier du telephone est remplace seulement apres reception complete.
- Le serveur verifie le contenu avec SHA-256.
- Les transferts incomplets utilisent des fichiers temporaires.
- Chaque appareil dispose de certificats et d'un stockage isoles.
- La communication utilise TLS 1.3 avec authentification mTLS.

## Sauvegarde et responsabilite

ShadowFS protege l'integrite du transfert, mais ne remplace pas une sauvegarde.
Pour les fichiers importants ou irremplacables, conservez une copie separee.

Le logiciel est fourni sans garantie. Son utilisation et sa configuration restent
sous la responsabilite de l'utilisateur.

