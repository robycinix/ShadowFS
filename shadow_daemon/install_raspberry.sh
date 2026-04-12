#!/bin/bash

# ==============================================================================
# ShadowDaemon Auto-Installer per Raspberry Pi (Debian/Ubuntu/Raspbian)
# v2.0 — Supporto TCP+mTLS per Android
# ==============================================================================

set -e

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}"
echo "  ╔══════════════════════════════════════════╗"
echo "  ║       ShadowDaemon Installer v2.0        ║"
echo "  ║   TCP+mTLS — File Ghosting per Android   ║"
echo "  ╚══════════════════════════════════════════╝"
echo -e "${NC}"

# 1. Controllo root
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}Errore: Esegui come root (usa sudo).${NC}"
  exit 1
fi

# Rileva l'IP principale di questo Raspberry (usa il primo IP non-loopback)
DETECTED_IP=$(hostname -I | awk '{print $1}' 2>/dev/null || echo "")
TAILSCALE_IP=$(tailscale ip -4 2>/dev/null || echo "")

echo -e "${YELLOW}IP rilevato sulla LAN:     ${DETECTED_IP:-non rilevato}${NC}"
echo -e "${YELLOW}IP Tailscale (se attivo):  ${TAILSCALE_IP:-non attivo}${NC}"
echo ""
echo "Questi IP verranno aggiunti al certificato TLS del server."
echo "Così il tuo Android potrà connettersi sia in LAN che tramite Tailscale."
echo ""

# Costruisce la lista IP per il certificato
CERT_IPS="127.0.0.1"
if [ -n "$DETECTED_IP" ]; then
    CERT_IPS="$CERT_IPS,$DETECTED_IP"
fi
if [ -n "$TAILSCALE_IP" ]; then
    CERT_IPS="$CERT_IPS,$TAILSCALE_IP"
fi

# 2. Dipendenze di sistema
echo -e "${GREEN}[1/5] Aggiornamento pacchetti...${NC}"
apt-get update -y -q
apt-get install -y -q curl wget git build-essential sqlite3

# 3. Golang
if ! command -v go &> /dev/null; then
    echo -e "${GREEN}[2/5] Installazione Golang v1.21...${NC}"

    # Rileva architettura (arm64 per Raspberry Pi 4, armv6l per Pi Zero/1/2)
    ARCH=$(uname -m)
    case $ARCH in
        aarch64) GO_ARCH="arm64" ;;
        armv7l)  GO_ARCH="armv6l" ;;
        armv6l)  GO_ARCH="armv6l" ;;
        x86_64)  GO_ARCH="amd64" ;;
        *)       GO_ARCH="arm64" ;;
    esac

    GO_URL="https://go.dev/dl/go1.21.0.linux-${GO_ARCH}.tar.gz"
    echo "   Download Go per ${ARCH} (${GO_ARCH})..."
    wget -q "$GO_URL" -O /tmp/go.tar.gz
    rm -rf /usr/local/go && tar -C /usr/local -xzf /tmp/go.tar.gz
    echo "export PATH=\$PATH:/usr/local/go/bin" >> /etc/profile
    export PATH=$PATH:/usr/local/go/bin
    rm /tmp/go.tar.gz
    echo "   Go installato: $(go version)"
else
    echo -e "${GREEN}[2/5] Go già installato: $(go version)${NC}"
    export PATH=$PATH:/usr/local/go/bin
fi

# 4. Directory del progetto
PROJECT_DIR="/opt/shadowfs"
STORAGE_DIR="/storage/shadow_root"
CERTS_CLIENT_DIR="/opt/shadowfs/certs_for_android"

echo -e "${GREEN}[3/5] Creazione directory...${NC}"
mkdir -p "$PROJECT_DIR"
mkdir -p "$STORAGE_DIR"
mkdir -p "$CERTS_CLIENT_DIR"
chmod 755 "$STORAGE_DIR"

# Copia sorgenti
if [ -f "./main.go" ] && [ -f "./go.mod" ]; then
    echo "   Copia sorgenti dalla cartella corrente..."
    cp ./*.go "$PROJECT_DIR/"
    cp ./go.mod "$PROJECT_DIR/"
    [ -f ./go.sum ] && cp ./go.sum "$PROJECT_DIR/"
elif [ -d "./shadow_daemon" ]; then
    echo "   Copia sorgenti da ./shadow_daemon/..."
    cp ./shadow_daemon/*.go "$PROJECT_DIR/"
    cp ./shadow_daemon/go.mod "$PROJECT_DIR/"
    [ -f ./shadow_daemon/go.sum ] && cp ./shadow_daemon/go.sum "$PROJECT_DIR/"
else
    echo -e "${RED}Sorgenti non trovati. Esegui dalla cartella del progetto.${NC}"
    exit 1
fi

# 5. Compilazione
echo -e "${GREEN}[4/5] Compilazione ShadowDaemon...${NC}"
cd "$PROJECT_DIR"
go mod tidy
go build -o shadowdaemon .

echo -e "${GREEN}   Compilazione completata.${NC}"

# Genera certificati con gli IP del Raspberry
echo ""
echo "   Generazione certificati mTLS con IP: $CERT_IPS"
./shadowdaemon --generate-certs --server-ip="$CERT_IPS"

# Copia certificati client in cartella dedicata
cp certs/ca.crt     "$CERTS_CLIENT_DIR/ca.crt"
cp certs/client.crt "$CERTS_CLIENT_DIR/client.crt"
cp certs/client.key "$CERTS_CLIENT_DIR/client.key"
chmod 644 "$CERTS_CLIENT_DIR/"*

# 6. Servizio SystemD (TCP su porta 4243)
echo -e "${GREEN}[5/5] Installazione servizio SystemD...${NC}"

cat <<EOF > /etc/systemd/system/shadowfs.service
[Unit]
Description=ShadowFS Daemon — TCP+mTLS File Ghosting Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=$PROJECT_DIR
ExecStart=$PROJECT_DIR/shadowdaemon -storage $STORAGE_DIR -tcp-addr 0.0.0.0:4243
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable shadowfs.service
systemctl start shadowfs.service

# 7. Avahi mDNS — annuncia il servizio sulla rete locale
echo -e "${GREEN}[5b] Configurazione mDNS (avahi-daemon)...${NC}"
apt-get install -y -q avahi-daemon

cat <<EOF > /etc/avahi/services/shadowfs.service
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">ShadowFS su %h</name>
  <service>
    <type>_shadowfs._tcp</type>
    <port>4243</port>
  </service>
</service-group>
EOF

systemctl enable avahi-daemon
systemctl restart avahi-daemon
echo "   mDNS attivo — il telefono Android può trovare questo Raspberry automaticamente."

# 8. Firewall — apri porta TCP 4243
echo "   Apertura porta TCP 4243 sul firewall..."
if command -v ufw &> /dev/null; then
    ufw allow 4243/tcp 2>/dev/null || true
elif command -v firewall-cmd &> /dev/null; then
    firewall-cmd --zone=public --add-port=4243/tcp --permanent 2>/dev/null || true
    firewall-cmd --reload 2>/dev/null || true
fi

# 8. Report finale
echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}  ✅ Installazione Completata!${NC}"
echo -e "${BLUE}╠════════════════════════════════════════════════════════╣${NC}"
echo -e "  Servizio:   systemctl status shadowfs"
echo -e "  Log:        journalctl -fu shadowfs"
echo -e "  Storage:    $STORAGE_DIR"
echo -e "  Porta TCP:  4243 (mTLS)"
echo ""
echo -e "${YELLOW}  ── CERTIFICATI PER ANDROID ─────────────────────────────${NC}"
echo -e "  I 3 file per l'app si trovano in: $CERTS_CLIENT_DIR"
echo ""
echo -e "  Trasferiscili sul telefono Android:"
echo -e "    adb push $CERTS_CLIENT_DIR/ca.crt     /sdcard/Download/shadowfs_certs/ca.crt"
echo -e "    adb push $CERTS_CLIENT_DIR/client.crt /sdcard/Download/shadowfs_certs/client.crt"
echo -e "    adb push $CERTS_CLIENT_DIR/client.key /sdcard/Download/shadowfs_certs/client.key"
echo ""
echo -e "  Oppure copia manualmente con FileZilla/WinSCP nella stessa cartella."
echo ""
echo -e "${YELLOW}  ── IP DA INSERIRE NELL'APP ANDROID ──────────────────────${NC}"
if [ -n "$TAILSCALE_IP" ]; then
    echo -e "  Usa Tailscale: ${GREEN}$TAILSCALE_IP${NC} : 4243"
elif [ -n "$DETECTED_IP" ]; then
    echo -e "  Usa LAN:       ${GREEN}$DETECTED_IP${NC} : 4243"
fi
echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
