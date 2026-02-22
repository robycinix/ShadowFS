#!/bin/bash

# ==============================================================================
# ShadowDaemon Auto-Installer per Raspberry Pi (Debian/Ubuntu/Raspbian)
# ==============================================================================

set -e

# Colori per l'output log
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}=== Inizializzazione installazione ShadowDaemon ===${NC}"

# 1. Controllo permessi di root
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}Errore: Esegui questo script come root (usa sudo).${NC}"
  exit 1
fi

# 2. Aggiornamento e installazione dipendenze di sistema
echo -e "${GREEN}[1/5] Aggiornamento dei pacchetti di sistema...${NC}"
apt-get update -y
apt-get install -y curl wget git build-essential sqlite3 firewalld

# 3. Installazione di Golang (se non presente o troppo vecchio)
if ! command -v go &> /dev/null; then
    echo -e "${GREEN}[2/5] Installazione di Golang (v1.21)...${NC}"
    wget https://go.dev/dl/go1.21.0.linux-arm64.tar.gz -O /tmp/go.tar.gz
    rm -rf /usr/local/go && tar -C /usr/local -xzf /tmp/go.tar.gz
    echo "export PATH=$PATH:/usr/local/go/bin" >> /etc/profile
    export PATH=$PATH:/usr/local/go/bin
    rm /tmp/go.tar.gz
else
    echo -e "${GREEN}[2/5] Golang è già installato. Versione: $(go version)${NC}"
fi

# 4. Creazione struttura delle directory del progetto
PROJECT_DIR="/opt/shadowfs"
STORAGE_DIR="/storage/shadow_root"

echo -e "${GREEN}[3/5] Creazione directory del progetto in ${PROJECT_DIR}...${NC}"
mkdir -p "$PROJECT_DIR"
mkdir -p "$STORAGE_DIR"
chmod 777 "$STORAGE_DIR" # Permessi ampi temporanei per scrittura offload

# Copia i file sorgente attuali dalla directory locale al demone (assumendo l'esecuzione da radice del progetto)
if [ -d "./shadow_daemon" ]; then
    cp -r ./shadow_daemon/* "$PROJECT_DIR/"
else
    echo -e "${RED}Attenzione: Impossibile trovare i file sorgente in './shadow_daemon/'. Assicurati di essere nella cartella principale del progetto.${NC}"
fi

# 5. Compilazione e Setup
echo -e "${GREEN}[4/5] Compilazione del demone ShadowFS...${NC}"
cd "$PROJECT_DIR"
go mod tidy
go build -o shadowdaemon main.go certs.go server.go scanner.go db.go

# Generazione certificati mTLS iniziale
echo "Generazione certificati P2P mTLS..."
./shadowdaemon -generate-certs

# 6. Registrazione come Servizio Systemd persistence (per farlo avviare al boot)
echo -e "${GREEN}[5/5] Installazione Servizio SystemD...${NC}"

cat <<EOF > /etc/systemd/system/shadowfs.service
[Unit]
Description=ShadowFS Daemon (mTLS QUIC Server)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$PROJECT_DIR
ExecStart=$PROJECT_DIR/shadowdaemon -storage $STORAGE_DIR -addr 0.0.0.0:4242
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable shadowfs.service
systemctl start shadowfs.service

# 7. Configurazione Firewall per la porta QUIC (4242/UDP)
echo "Apertura porta UDP 4242 (QUIC) sul firewall..."
if command -v ufw &> /dev/null; then
    ufw allow 4242/udp
elif command -v firewall-cmd &> /dev/null; then
    firewall-cmd --zone=public --add-port=4242/udp --permanent
    firewall-cmd --reload
fi

echo -e "${BLUE}======================================================${NC}"
echo -e "${GREEN}Installazione Completata con Successo!${NC}"
echo -e "Demone in esecuzione come servizio systemd: 'systemctl status shadowfs'"
echo -e "Certificati Client per Android pronti in: $PROJECT_DIR/certs/"
echo -e "${BLUE}======================================================${NC}"
