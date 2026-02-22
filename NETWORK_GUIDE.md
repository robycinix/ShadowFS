# Guida di Rete P2P: Protobuf e Connettività Remota (Tailscale)

ShadowFS non è un semplice cloud: è un sistema distribuito **P2P (Peer-to-Peer)** progettato per farti riprendere il controllo dei tuoi file. Per farlo funzionare fuori da casa tua senza impazzire con configurazioni di rete instabili, utilizziamo la tecnologia VPN Mesh.

---

## 1. Il Protocollo Dati: Protobuf (Protocol Buffers)

Nel file `proto/shadow.proto` è definito il contratto di comunicazione tra il Raspberry (Go) e l'Android (Kotlin).
I **Protobuf** di Google inviano byte grezzi compressi ("binary wire format"), consumando pochissima batteria e banda rispetto al pesante JSON.

**Sul Raspberry (per il demone Go):**
```bash
sudo apt install protobuf-compiler
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
protoc --go_out=./shadow_daemon proto/shadow.proto
```

**In Android Studio (per il client Kotlin):**
Al primo avvio dell'app in Android Studio, il plugin `grpc-protobuf-lite` compilerà automaticamente le classi Java/Kotlin per `ShadowMessageProto`.

---

## 2. La Scelta Architetturale per la Rete Esterna: Tailscale (VPN Mesh Zero-Config)

Affinché il demone Android possa svuotare o idratare i file (QUIC mTLS) quando sei in 4G o in un hotel, **deve poter "vedere" il tuo Raspberry Pi**, che di norma è bloccato dietro il router di casa (NAT/Firewall).

Anche se l'UDP Hole Punching puro è un concetto affascinante, nel mondo reale le reti 4G/5G usano spesso "Carrier-Grade NAT" (NAT simmetrico a livello operatore). Questo blocca il 90% dei tentativi di Hole Punching puro, costringendoti a noleggiare un server Cloud (TURN) come "ponte", vanificando l'idea di un sistema 100% gratuito e self-hosted.

**Per questo motivo, la tecnologia Ufficiale scelta per ShadowFS è Tailscale.**

Tailscale è basato su **WireGuard**, ed è riconosciuto a livello globale come lo standard di settore per colmare queste lacune. Crea una rete P2P invisibile tra i tuoi dispositivi.

### I Vantaggi per ShadowFS:
1. **Nessun Port Forwarding:** Non devi toccare il modem di casa. Zero porte aperte, zero hacker in grado di scansionare il tuo Raspberry.
2. **IP Statico Magico:** Il tuo Raspberry otterrà un indirizzo IP fisso (es. `100.80.33.12`). Sul client Android dovrai semplicemente impostare questo IP. Funzionerà SEMPRE, sia in casa col Wi-Fi, sia all'estero.
3. **Batteria Intatta:** WireGuard dentro Tailscale entra "in letargo" quando non ci sono trasferimenti attivi, perfetto per la batteria dell'Android.
4. **Hole Punching Integrato:** Tailscale applica già nativamente l'Hole Punching (e offre i suoi server Relay gratuiti se fallisce). Usa esattamente le tecniche STUN di cui parlavamo, ma le gestisce per te.

### Come Installare l'Infrastruttura di Rete:

**Sul Raspberry Pi:**
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```
*(Ti darà un link da aprire nel browser per associare il Raspberry al tuo account gratuito. Qui vedrai il suo nuovo magico IP 100.x.x.x).*

**Sullo Smartphone Android:**
1. Scarica l'App "Tailscale" dal Play Store.
2. Accedi con lo stesso account.
3. Attiva lo switch VPN.

**Fatto.**
Ora il tuo Android e il tuo Raspberry Pi comunicano in P2P diretto come se fossero collegati con un cavo di rete lungo mille chilometri.
Il nostro demone QUIC (mTLS) ci passa sopra, scambiando i file fantasma (Ghosting/Hydrating) con una sicurezza crittografica assoluta e doppia (Wireguard + mTLS).
