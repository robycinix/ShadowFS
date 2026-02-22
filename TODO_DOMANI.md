# Stato Avanzamento ShadowFS e Prossimi Passi 🚀

## ✅ Progressi Ottenuti Oggi (Android Studio)
Siamo riusciti a configurare correttamente e compilare l'app client Android da zero. Nello specifico:

1. **Fix Ambiente di Build:**
   - Creato e configurato `settings.gradle.kts` per il corretto download dei plugin.
   - Creato `gradle.properties` per abilitare `AndroidX` e il `Jetifier`.
   - Effettuato il downgrade indolore della versione di Gradle alla 8.4 nel `gradle-wrapper.properties` (per mantenere compatibilità con AGP 8.2.2).
2. **Fix del Codice Kotlin / XML:**
   - Rimossi temporaneamente i riferimenti (mancanti) alle icone predefinite di Android dall'`AndroidManifest.xml` (e adeguato `HydrationManager.kt`).
   - Risolti i classici errori di *Unresolved Reference* definendo le variabili a livello di classe in `ForegroundService.kt` (`vfsManager`, `hydrationManager`, `scanTimer`).
3. **Compilazione Completata con Successo:**
   - Lanciata la build `assembleDebug`. BUILD SUCCESSFUL. (L'emulatore confermava il funzionamento dell'UI).
   - Generato il vero file `app-debug.apk` pronto per l'installazione su smartphone fisico.

---

## 🎯 Obiettivi per Domani (Fase 3: Il Test P2P Logico)

Domani riprenderemo da qui. Questo è il piano d'azione:

### 1. Installazione e Test "Simulato" su Smartphone
- Passare il file `app-debug.apk` generato oggi sullo smartphone Android e installarlo.
- Avviare l'app e concedere i **Permessi "Accesso a tutti i file"** (`MANAGE_EXTERNAL_STORAGE`).
- Verificare la corretta logica "stand-alone" dello svuotamento file (Ghosting):
  - Creare un video "pesante" (es. >512KB) nella cartella Download/ o equivalente.
  - Cliccare su **"Libera Spazio Adesso"** dall'app.
  - Usare il File Manager di Android per controllare che il nostro file pesante sia diventato di **0 Byte** e che ci sia un marcatore `.shadow`.
- Verificare il "Re-Ghosting" (Idratazione simulata):
  - Cliccare sul file svuotato e controllare la notifica dell'HydrationManager; verificare che torni al suo stato normale col nuovo marker `.reghost`.

### 2. Il Passaggio alla "Realtà" (Network VERO)
Una volta accertato che il motore VFS sul telefonino non "buca" o distrugge irrimediabilmente i dati (e funziona la simulazione):
- Discuteremo su come sostituire l'oggetto `QuicClientMock` in `ForegroundService.kt` con i veri file auto-generati dal protocollo Protobuf.
- Analizzeremo l'aggiunta di `ca.crt, client.crt, client.key` al telefono, che saranno fondamentali per parlare col Raspberry Pi blindato da Tailscale.
