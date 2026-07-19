# ShadowFS - Guide d'installation

Ce guide garde l'app Android et le daemon Raspberry Pi sur la meme version.
Mettez a jour telephone et serveur ensemble pour eviter les incompatibilites de
protocole ou de certificats.

## Prerequis

- Raspberry Pi ou serveur Linux maison allume et joignable.
- Telephone Android avec ShadowFS installe.
- Meme Wi-Fi pour la premiere configuration.
- Tailscale recommande pour l'acces hors domicile.

## 1. Preparer le serveur

```bash
cd /home/<utilisateur>/ShadowFS/shadow_daemon
sudo chmod +x install_raspberry.sh
sudo ./install_raspberry.sh
```

Verifiez le service:

```bash
systemctl status shadowfs
```

Il doit afficher `active (running)`.

## 2. Associer le telephone

Sur le serveur, affichez le QR:

```bash
sudo systemctl restart shadowfs && journalctl -fu shadowfs
```

Dans l'app, touchez **Pair via QR Code** et scannez le code. Le QR expire en
quelques minutes et ne fonctionne qu'une fois.

## 3. Tester

1. Touchez **Test mTLS Connection**.
2. Confirmez l'etat connecte.
3. Touchez **Start Shadow Daemon**.

## Responsabilite

ShadowFS est fourni sans garantie. Avant de l'utiliser avec des fichiers
importants, testez l'upload, le remplacement et la restauration avec des fichiers
non critiques. Conservez toujours une copie separee des donnees importantes.

