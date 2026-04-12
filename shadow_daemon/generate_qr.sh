#!/bin/bash
# Genera un QR Code per il pairing automatico dell'app Android.
# Il QR contiene: IP del server, porta, e i 3 certificati in base64.
# L'app Android scansiona il QR e si configura automaticamente.

CERTS_DIR="/opt/shadowfs/certs"
PORT=4243

# Rileva IP (preferisce Tailscale, poi LAN)
TAILSCALE_IP=$(tailscale ip -4 2>/dev/null || echo "")
LAN_IP=$(hostname -I | awk '{print $1}')
SERVER_IP="${TAILSCALE_IP:-$LAN_IP}"

if [ ! -f "$CERTS_DIR/ca.crt" ]; then
    echo "Errore: certificati non trovati in $CERTS_DIR"
    echo "Esegui prima: sudo ./install_raspberry.sh"
    exit 1
fi

# Codifica i certificati in base64 (senza newline)
CA_B64=$(base64 -w0 "$CERTS_DIR/ca.crt")
CLIENT_CRT_B64=$(base64 -w0 "$CERTS_DIR/client.crt")
CLIENT_KEY_B64=$(base64 -w0 "$CERTS_DIR/client.key")

# Crea il payload JSON
PAYLOAD=$(cat <<EOF
{"ip":"$SERVER_IP","port":$PORT,"ca":"$CA_B64","crt":"$CLIENT_CRT_B64","key":"$CLIENT_KEY_B64"}
EOF
)

# Installa qrencode se mancante
if ! command -v qrencode &> /dev/null; then
    echo "Installazione qrencode..."
    apt-get install -y -q qrencode
fi

echo ""
echo "══════════════════════════════════════════"
echo "  ShadowFS — QR Code di Pairing"
echo "  IP: $SERVER_IP : $PORT"
echo "══════════════════════════════════════════"
echo ""

# Mostra il QR nel terminale
echo "$PAYLOAD" | qrencode -t UTF8 -o -

echo ""
echo "Scansiona questo QR con l'app ShadowFS Android."
echo "L'app si configurerà automaticamente con IP e certificati."
echo ""
