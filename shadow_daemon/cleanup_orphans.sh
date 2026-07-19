#!/bin/bash
# ==============================================================================
# ShadowFS — Pulizia file orfani sul Raspberry Pi
# Un file è "orfano" se è nel DB ma il suo dispositivo non si connette da N giorni.
# Esegui manualmente quando vuoi liberare spazio sul Raspberry.
# ==============================================================================

STORAGE_DIR="/storage/shadow_root"
DB_PATH="/opt/shadowfs/shadowfs.db"
DAYS_THRESHOLD=${1:-30}  # giorni di inattività (default: 30)

# Escaping SQL: raddoppia gli apostrofi. Senza, un nome file come
# "Foto dell'anno.jpg" (comunissimo in italiano) rompe la query sqlite3:
# il COUNT fallisce in silenzio e l'esito del controllo orfani è inaffidabile.
sql_escape() {
    printf '%s' "$1" | sed "s/'/''/g"
}

# Un file è considerato orfano SOLO se la query è riuscita E ha restituito 0.
# Output vuoto = errore sqlite → non toccare il file.
is_orphan() {
    local device_esc file_esc count
    device_esc=$(sql_escape "$1")
    file_esc=$(sql_escape "$2")
    count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM files WHERE device_id='$device_esc' AND rel_path='$file_esc';" 2>/dev/null)
    [ "$count" = "0" ]
}

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}"
echo "  ╔══════════════════════════════════════════╗"
echo "  ║     ShadowFS — Pulizia File Orfani       ║"
echo "  ╚══════════════════════════════════════════╝"
echo -e "${NC}"

if [ ! -f "$DB_PATH" ]; then
    echo -e "${RED}Errore: database non trovato in $DB_PATH${NC}"
    exit 1
fi

if [ ! -d "$STORAGE_DIR" ]; then
    echo -e "${RED}Errore: storage non trovato in $STORAGE_DIR${NC}"
    exit 1
fi

echo -e "Soglia inattività: ${YELLOW}$DAYS_THRESHOLD giorni${NC}"
echo ""

# ── 1. Mostra spazio totale usato per dispositivo ─────────────────────────────
echo -e "${BLUE}── Spazio usato per dispositivo ──────────────────────────${NC}"
for device_dir in "$STORAGE_DIR"/*/; do
    device=$(basename "$device_dir")
    size=$(du -sh "$device_dir" 2>/dev/null | cut -f1)
    file_count=$(find "$device_dir" -type f 2>/dev/null | wc -l)
    last_access=$(sqlite3 "$DB_PATH" "SELECT MAX(last_access) FROM files WHERE device_id='$(sql_escape "$device")';" 2>/dev/null)
    echo -e "  📱 ${GREEN}$device${NC} — $size ($file_count file) — ultimo accesso: ${last_access:-mai}"
done
echo ""

# ── 2. Trova file orfani (nel filesystem ma non nel DB) ───────────────────────
# I .part sono upload in corso/riprendibili, non orfani: vanno esclusi,
# altrimenti cancellarli azzererebbe il resume (o peggio, durante un upload).
echo -e "${BLUE}── File nel filesystem senza record nel DB ───────────────${NC}"
ORPHAN_FS=0
while IFS= read -r -d '' filepath; do
    relpath="${filepath#$STORAGE_DIR/}"
    device_id=$(echo "$relpath" | cut -d'/' -f1)
    file_relpath=$(echo "$relpath" | cut -d'/' -f2-)

    if is_orphan "$device_id" "$file_relpath"; then
        size=$(du -sh "$filepath" 2>/dev/null | cut -f1)
        echo -e "  ${YELLOW}⚠️  $relpath${NC} ($size) — non nel DB"
        ORPHAN_FS=$((ORPHAN_FS + 1))
    fi
done < <(find "$STORAGE_DIR" -type f ! -name '*.part' -print0 2>/dev/null)

if [ "$ORPHAN_FS" = "0" ]; then
    echo -e "  ${GREEN}Nessun file orfano nel filesystem.${NC}"
fi
echo ""

# ── 3. Trova dispositivi inattivi da più di N giorni ─────────────────────────
echo -e "${BLUE}── Dispositivi inattivi da più di $DAYS_THRESHOLD giorni ───────────────${NC}"
INACTIVE_DEVICES=()
for device_dir in "$STORAGE_DIR"/*/; do
    device=$(basename "$device_dir")
    last_access=$(sqlite3 "$DB_PATH" "SELECT MAX(last_access) FROM files WHERE device_id='$(sql_escape "$device")';" 2>/dev/null)

    if [ -z "$last_access" ] || [ "$last_access" = "NULL" ]; then
        echo -e "  ${RED}📱 $device — nessun accesso registrato${NC}"
        INACTIVE_DEVICES+=("$device")
    else
        days_ago=$(( ( $(date +%s) - $(date -d "$last_access" +%s 2>/dev/null || echo $(date +%s)) ) / 86400 ))
        if [ "$days_ago" -gt "$DAYS_THRESHOLD" ]; then
            size=$(du -sh "$device_dir" 2>/dev/null | cut -f1)
            echo -e "  ${RED}📱 $device — inattivo da $days_ago giorni ($size)${NC}"
            INACTIVE_DEVICES+=("$device")
        fi
    fi
done

if [ "${#INACTIVE_DEVICES[@]}" = "0" ]; then
    echo -e "  ${GREEN}Tutti i dispositivi sono stati attivi di recente.${NC}"
fi
echo ""

# ── 4. Azioni disponibili ─────────────────────────────────────────────────────
if [ "$ORPHAN_FS" = "0" ] && [ "${#INACTIVE_DEVICES[@]}" = "0" ]; then
    echo -e "${GREEN}✅ Nessuna pulizia necessaria.${NC}"
    exit 0
fi

echo -e "${BLUE}── Azioni disponibili ────────────────────────────────────${NC}"
echo "  1) Elimina file orfani dal filesystem (non nel DB)"
echo "  2) Elimina tutti i file di un dispositivo inattivo"
echo "  3) Elimina tutto quanto sopra"
echo "  0) Esci senza fare nulla"
echo ""
read -p "Scelta: " choice

case "$choice" in
    1)
        echo -e "${YELLOW}Eliminazione file orfani...${NC}"
        while IFS= read -r -d '' filepath; do
            relpath="${filepath#$STORAGE_DIR/}"
            device_id=$(echo "$relpath" | cut -d'/' -f1)
            file_relpath=$(echo "$relpath" | cut -d'/' -f2-)
            if is_orphan "$device_id" "$file_relpath"; then
                rm "$filepath"
                echo -e "  ${RED}🗑  Eliminato: $relpath${NC}"
            fi
        done < <(find "$STORAGE_DIR" -type f ! -name '*.part' -print0 2>/dev/null)
        echo -e "${GREEN}✅ Fatto.${NC}"
        ;;
    2)
        if [ "${#INACTIVE_DEVICES[@]}" = "0" ]; then
            echo "Nessun dispositivo inattivo trovato."
            exit 0
        fi
        echo "Dispositivi inattivi:"
        for i in "${!INACTIVE_DEVICES[@]}"; do
            echo "  $((i+1))) ${INACTIVE_DEVICES[$i]}"
        done
        read -p "Quale eliminare? (numero): " dev_choice
        idx=$((dev_choice - 1))
        device="${INACTIVE_DEVICES[$idx]}"
        if [ -z "$device" ]; then echo "Scelta non valida."; exit 1; fi
        read -p "Sei sicuro di voler eliminare TUTTI i file di '$device'? (sì/no): " confirm
        if [ "$confirm" = "sì" ] || [ "$confirm" = "si" ]; then
            rm -rf "${STORAGE_DIR:?}/$device"
            sqlite3 "$DB_PATH" "DELETE FROM files WHERE device_id='$(sql_escape "$device")';"
            echo -e "${GREEN}✅ Eliminati tutti i file di '$device'.${NC}"
        else
            echo "Operazione annullata."
        fi
        ;;
    3)
        read -p "Sei sicuro di voler eliminare tutto? (sì/no): " confirm
        if [ "$confirm" = "sì" ] || [ "$confirm" = "si" ]; then
            # Orfani filesystem
            while IFS= read -r -d '' filepath; do
                relpath="${filepath#$STORAGE_DIR/}"
                device_id=$(echo "$relpath" | cut -d'/' -f1)
                file_relpath=$(echo "$relpath" | cut -d'/' -f2-)
                if is_orphan "$device_id" "$file_relpath"; then
                    rm "$filepath"
                    echo -e "  ${RED}🗑  $relpath${NC}"
                fi
            done < <(find "$STORAGE_DIR" -type f ! -name '*.part' -print0 2>/dev/null)
            # Dispositivi inattivi
            for device in "${INACTIVE_DEVICES[@]}"; do
                rm -rf "${STORAGE_DIR:?}/$device"
                sqlite3 "$DB_PATH" "DELETE FROM files WHERE device_id='$(sql_escape "$device")';"
                echo -e "  ${RED}🗑  Dispositivo '$device' eliminato${NC}"
            done
            echo -e "${GREEN}✅ Pulizia completata.${NC}"
        else
            echo "Operazione annullata."
        fi
        ;;
    *)
        echo "Uscita senza modifiche."
        ;;
esac
