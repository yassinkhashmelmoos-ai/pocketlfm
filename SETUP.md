# Thunder AGI — Wallet / Mesh Payments: SETUP (final, tested)

This replaces ALL earlier setup files and ALL earlier code from previous sessions.
The `lfm25/` folder next to this file mirrors your repository: copy its contents into your `lfm25/` folder.

---------------------------------------------------------------------------------------------------

## 0. FOR THE ASSISTANT THAT APPLIES THIS (read first)

The user uploads (a) the delivered files, (b) their CURRENT project, (c) this file. The delivered files were
written against an OLDER snapshot of the project, so **adapt the integration anchors to the current code; never
downgrade or rewrite what the user already has.**

Rules:
1. Do NOT change the logic of the delivered files. They were compiled and tested (see section 8). Only integrate them.
   If the real Gradle build reports an error inside a delivered file, fix the minimum needed and tell the user exactly what.
2. First read the user's current `build.gradle.kts`, `libs.versions.toml`, `AndroidManifest.xml`, `MainActivity.kt`,
   `ChatScreen.kt` (search for `PeerSyncPanel`), `PeerSync.kt`, `UpdateNotificationListener.kt`, `SubscriptionManager.kt`.
3. Apply sections 3 to 7 in order. For each existing file show the user a short diff (before/after) of what you changed.
4. Package names: everything is under `com.thunder.agi.*` (`util, crypto, ledger, transfer, node, p2p, relay, wallet, ui`).
   If a package or class with the same name already exists, STOP and ask the user before overwriting.
5. Facts about the project at the time of writing (verify against the current files): Kotlin 1.9.22, KSP (NOT kapt) for Room 2.6.1,
   Compose BOM 2024.02 (Material3 1.2), minSdk 26, targetSdk 36, kotlinx-coroutines 1.7.3, `buildConfig = true`,
   `androidx.activity:activity-compose` present, existing `UpdateNotificationListener`, existing theme colors
   `ThunderBlack, ThunderDeepBlue, ThunderMidBlue, ThunderElectric, ThunderGray, ThunderGreen, ThunderRed, ThunderWhite`
   in `com.thunder.agi.ui.theme` (WalletScreen imports them; if a name changed, adjust the imports only).
6. An earlier setup file told the user to add `kapt` and to patch `SubscriptionManager`. **Both were wrong. Do not do either.**

Integration surface (everything the existing app needs to touch):

| Existing file | What changes | Section |
|---|---|---|
| `app/build.gradle.kts` | one dependency (+ optional packaging exclude) | 3 |
| `AndroidManifest.xml` | 2 permissions, 2 uses-feature, 1 service, 2 backup attributes | 4 |
| `MainActivity.kt` | swap ChatScreen/WalletScreen, restore background service | 5 |
| `ChatScreen.kt` (`PeerSyncPanel`) | one "Wallet" button | 5 |
| `UpdateNotificationListener.kt` | one line (inside the existing "Thunder AGI chat" filter) | 6 |
| `p2p/PeerSync.kt` | replaced by the delivered file (API kept) | 6 |
| `SubscriptionManager.kt` | **no change** | 7 |

---------------------------------------------------------------------------------------------------

## 1. What these files do (one paragraph each)

* **Ledger** (`ledger/`): every phone keeps the whole ledger (Room database). A transaction is ONE compact signed
  record of at most 226 bytes (`TxWire`). `LedgerEngine` is the only place that decides what is valid: sender key must hash to the
  `from` address, signature valid, strict per-sender nonce chain, balance sufficient, only the issuer key may create money.
  Two different valid transactions with the same sender+nonce = proven double-spend => that address is frozen on every node
  that sees the proof. `GossipProtocol` syncs ledgers over TCP (LAN, hotspot, WiFi Direct). `ThunderABI` is its wire protocol.
* **Connectionless radios** (`p2p/BleBeaconTransport`, `p2p/WifiAwareTransport`): a complete signed payment is broadcast in ONE Bluetooth-5
  extended advertisement or ONE WiFi-Aware message. No pairing, no password, no connection; the receiver verifies the signature itself.
  Phones without Bluetooth-5 send a 17-byte notice ("a payment for you exists") and the full transaction follows by gossip.
* **Text channel** (`ledger/Transaction.kt` `TxText`, `relay/WhatsApp.kt`): a whole payment as one short text `⚡TX1:<base64url>`.
  User taps "WhatsApp" in the app, WhatsApp opens with the text ready. The receiver's app imports it from the "Thunder AGI" chat
  notification (existing listener), from the clipboard (Paste button), or by sharing the text. Big bundles use `ChunkedTransferProtocol`
  (resumable, out-of-order, CRC, ACK of missing chunks, persisted sessions: the IDM/Feem technique).
* **Crypto** (`crypto/`): signing key = ECDSA-P256 inside Android Keystore (StrongBox/TEE, non-exportable). Kyber-768 (post-quantum KEM) +
  AES-256-GCM protect the PeerSync channel (`SecureChannel`, mutually authenticated). The wallet address commits to the signing key AND the Kyber key.
* **Node** (`node/`): `ThunderRuntime` is the single process-wide object that wires everything; `ThunderService` is an opt-in foreground service;
  `LanDiscovery` finds other Thunder phones on the same WiFi/hotspot (mDNS).
* **UI** (`ui/WalletScreen.kt`): balance, unverified-incoming, nearby-payments status, send/receive, WhatsApp share, paste import,
  issuer panel (only on the issuer phone). Every spend/mint asks for the phone PIN/pattern/fingerprint first.

---------------------------------------------------------------------------------------------------

## 2. Files to add, and files to delete

### 2.1 Copy these (from the delivered `lfm25/` folder; same relative paths)

```
app/src/main/java/com/thunder/agi/util/Bytes.kt
app/src/main/java/com/thunder/agi/crypto/{EcCodec,Kem,Aead,SecureChannel,QuantumKeyManager}.kt
app/src/main/java/com/thunder/agi/ledger/{WalletAddress,Transaction,MintAuthority,LedgerStore,LedgerEngine,ThunderABI,GossipProtocol,LedgerDatabase}.kt
app/src/main/java/com/thunder/agi/transfer/ChunkedTransferProtocol.kt
app/src/main/java/com/thunder/agi/node/{ThunderRuntime,ThunderService,LanDiscovery,NotificationText}.kt
app/src/main/java/com/thunder/agi/p2p/{BeaconCodec,BleBeaconTransport,WifiAwareTransport}.kt      (PeerSync.kt: see 6.2)
app/src/main/java/com/thunder/agi/relay/WhatsApp.kt
app/src/main/java/com/thunder/agi/wallet/WalletManager.kt
app/src/main/java/com/thunder/agi/ui/WalletScreen.kt
app/src/main/res/xml/thunder_backup_rules.xml
app/src/main/res/xml/thunder_data_extraction_rules.xml
app/src/test/java/com/thunder/agi/{TestSupport,CryptoTest,AddressAndWireTest,LedgerEngineTest,GossipTest,ChunkedTransferTest}.kt
```

### 2.2 Delete these if they exist (files from earlier sessions; they are replaced and some are unsafe)

```
p2p/MeshPacket.kt  p2p/MeshRouter.kt  p2p/BluetoothMeshNode.kt  p2p/TxBeacon.kt  p2p/QuantumKeyManager.kt (old one, package p2p)
relay/WhatsAppRelay.kt  relay/NotificationRelayService.kt  relay/TransportOrchestrator.kt
ledger/GossipProtocol.kt (old)  ledger/LedgerEngine.kt (old)  ledger/Transaction.kt (old)  ledger/LedgerDatabase.kt (old)  ledger/ThunderABI.kt (old)
wallet/WalletManager.kt (old)  ui/WalletScreen.kt (old)   transfer/ChunkedTransferProtocol.kt (old)
```
and remove from `AndroidManifest.xml` any `<service android:name=".relay.NotificationRelayService">` entry and any
`MeshForegroundService` / `TxReceivedReceiver` entry added earlier. Remove any code that referenced those classes.
(The delivered files overwrite same-named old files; just make sure no stale old file with a different name is left behind,
otherwise the build will fail with duplicate or unresolved classes.)

---------------------------------------------------------------------------------------------------

## 3. `app/build.gradle.kts`

Add ONE dependency (Room/KSP, coroutines, org.json, OkHttp are already there; do NOT add kapt):

```kotlin
dependencies {
    // Post-quantum KEM (Kyber-768). Used only through crypto/Kem.kt (one file to change when moving to ML-KEM).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
```

Only if the build complains about duplicate files in `META-INF`, add inside `android { }`:

```kotlin
packaging { resources { excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" } }
```

Unit tests need nothing new (`testImplementation(libs.junit)` already exists). If `android.util.Log` ever throws in tests, add
`testOptions { unitTests.isReturnDefaultValues = true }` (the code already guards against it).

If the project later enables R8 (`isMinifyEnabled = true`), add to `proguard-rules.pro`:

```
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.thunder.agi.ledger.** { *; }
```

---------------------------------------------------------------------------------------------------

## 4. `AndroidManifest.xml`

### 4.1 Permissions: make sure ALL of these exist (most already did; add what is missing)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<!-- NEW -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- NEW: do not hide the app from phones without these radios -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
<uses-feature android:name="android.hardware.wifi.aware" android:required="false" />
```
(If the existing file uses `tools:` attributes it already declares `xmlns:tools`; keep the existing style. Android 6-11 also need the
legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` permissions with `android:maxSdkVersion="30"`; keep them if present.)

### 4.2 Inside `<application>` add the service (next to the existing notification-listener service):

```xml
<service
    android:name=".node.ThunderService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

### 4.3 On the `<application>` tag add (merge with existing values if the attributes already exist):

```xml
android:fullBackupContent="@xml/thunder_backup_rules"
android:dataExtractionRules="@xml/thunder_data_extraction_rules"
```
Why: wallet secrets are tied to this phone's Keystore; letting Auto Backup copy the encrypted files would only produce a broken wallet
after a restore. If the project already has its own backup-rule XML files, merge the `<exclude>` lines of the two delivered files into them instead.

---------------------------------------------------------------------------------------------------

## 5. UI integration

### 5.1 `MainActivity.kt` (inside the `ThunderAGIApp()` composable; the activated/logged-in branch)

Add imports:
```kotlin
import com.thunder.agi.node.ThunderNav
import com.thunder.agi.node.ThunderService
import com.thunder.agi.ui.WalletScreen
```
Find the place where `ChatScreen( ... )` is composed for an activated user and wrap it (keep ALL existing ChatScreen arguments unchanged):

```kotlin
if (ThunderNav.showWallet.value) {
    WalletScreen(onDismiss = { ThunderNav.showWallet.value = false })
} else {
    ChatScreen(
        /* ... all existing arguments exactly as they are today ... */
    )
}
```
Also, once the user is activated (e.g. next to the existing `LaunchedEffect`/`DisposableEffect` that registers `peerSync`), add:
```kotlin
LaunchedEffect(Unit) { ThunderService.restoreIfEnabled(context) }   // use the existing Context variable name
```
Do NOT put the wallet check behind an early `return` that skips other composable calls; use the if/else above so the order of
composable calls stays stable.

### 5.2 `ChatScreen.kt`: entry button inside `PeerSyncPanel`

`PeerSyncPanel` lives in the settings sheet. At the END of its main `Column { ... }` add a button in the same visual style as the
panel's other buttons (this is the style used when it was written):

```kotlin
Spacer(Modifier.height(12.dp))
OutlinedButton(
    onClick = { com.thunder.agi.node.ThunderNav.showWallet.value = true },
    modifier = Modifier.fillMaxWidth(),
    border = BorderStroke(1.dp, ThunderElectric)
) { Text("⚡ المحفظة  Wallet", color = ThunderElectric) }
```
(`ThunderNav.showWallet` is a global Compose state, so no parameter has to be threaded through `ChatScreen`.
If the user prefers another place for the entry point, any composable may set `ThunderNav.showWallet.value = true`.)

---------------------------------------------------------------------------------------------------

## 6. Existing services

### 6.1 `notification/UpdateNotificationListener.kt` (WhatsApp payments inbox)

The existing listener already ignores every chat except the one whose title contains `Thunder AGI`. KEEP that filter exactly
(it is the privacy promise of the app). Inside the code path that runs AFTER the filter passes (where `[UPDATE]` / `[SYNC]`
messages are handled) add, using the notification's `extras` bundle (`sbn.notification.extras`):

```kotlin
// Thunder wallet: payments ("TX1:...") and bulk chunks ("⚡T:...") posted in the Thunder AGI chat
if (com.thunder.agi.node.NotificationText.importThunderPayments(applicationContext, extras)) return
```
Nothing else changes. `NotificationText` reads every message text carried by the notification, imports only text that
contains a payment/chunk, and stores/logs nothing else. Payments from other chats are imported by the user with the
wallet's Paste button (or "Copy" in WhatsApp then Paste).

### 6.2 `p2p/PeerSync.kt`

Replace the file with the delivered `PeerSync.kt` **after comparing with the user's current one**:
* The delivered version keeps the public API the UI uses: `SyncState(isDiscovering, peers, isConnected, connectedDevice, transferProgress, lastSyncResult, error)`,
  `state`, `register()`, `unregister()`, `discoverPeers()`, `connectToPeer(device)`, `disconnect()`, constructor `PeerSync(context)`.
  If the user's current file gained extra members or sync files since, port them into the delivered file (look at `SYNC_FILES` and `merge()`).
* What changed inside: the old handshake `THUNDER_AGI_SYNC_V1` had no authentication and sent files in clear text. The new session
  is `crypto/SecureChannel` (both phones prove their identity key, Kyber-768 key exchange signed by both, AES-256-GCM frames),
  files are capped at 2 MB, and the peer's IP is handed to ledger gossip. **Old app versions cannot sync with the new one**
  (different handshake); both phones must update.
* There are two `PeerSync` instances in the old code (one in `MainActivity`, one stray in `ChatViewModel`); only the registered one matters.

---------------------------------------------------------------------------------------------------

## 7. `SubscriptionManager.kt`: do NOT touch, and why

The receipt check there accepts an image if it is mostly green or red and a typed 8-digit number. It never reads the amount and anyone can
pass it with any green picture. That is acceptable for a 30-day license, but **it must never create money**. An earlier session
patched it to mint 1000 SDG per "verified" receipt: remove that patch if it was applied.

Money enters the wallet ONLY through the issuer (section 9). `MintAuthority` makes every node reject money that is not signed by the issuer key.

---------------------------------------------------------------------------------------------------

## 8. Build and test

```
./gradlew :app:testDebugUnitTest      # expect 49 tests, all green (about 15 s)
./gradlew :app:assembleDebug
```
What was verified in the authoring environment (so you know what is proven and what is not):
* 49 unit tests pass: address format, compact wire format, forged-sender attack rejected, unauthorized minting rejected, double-spend detection and
  proof propagation, out-of-order delivery converges, 150-operation randomized replication conserves money, gossip over real loopback sockets
  including hostile peers and a 900-transaction chain, secure channel impersonation/tamper/replay rejected, chunk transfer with corruption/zip-bomb/resume.
* All non-UI code compiles with Kotlin 1.9.22 against Android API 34 definitions.
* `WalletScreen.kt` could only be type-checked against a stand-in Compose API (the real Compose libraries were not available). It copies the Compose
  parameter names the project already uses, but **the first real Gradle build is its first real compile**; if it complains, the fix is a parameter-name
  adjustment, not a logic change.
* NOT testable without phones: BLE/WiFi-Aware radio behavior, background limits, notification parsing. Use the checklist below.

### Device checklist (two Android phones, debug build, both with Bluetooth + Location ON)
1. Open Wallet on both, tap "Enable", grant all permissions. Status line shows Bluetooth ✅.
2. Debug build: Wallet shows the Issuer panel on every phone (debug = anyone may mint). On phone A mint 100 SDG to A's own address (ⓘ shows it; use any receipt no.).
3. Same WiFi/hotspot: send 10 SDG A to B. B's balance changes within ~30 s (LAN gossip). Both show "peers: 1".
4. WiFi off on both, Bluetooth on, phones 2 m apart: send again. B should receive within seconds on phones with Bluetooth-5 (check `Bluetooth ✅`). On older phones only the "incoming" notice appears.
5. WhatsApp: after sending tap "WhatsApp", choose the Thunder AGI chat, send. On B the existing listener imports it (notification must be visible/not muted) or use Paste.
6. Kill the app on B, send again: with "Stay online in background" ON, B gets a notification "Payment received".
7. Try sending more than the balance: refused. Try sending to a mistyped address: refused (checksum).

---------------------------------------------------------------------------------------------------

## 9. First-time issuer setup (how real money becomes balance)

There must be exactly one trusted party that turns real bank money into balance. The network cannot verify a bank receipt by itself.

1. Install a build on the issuer's phone. Open Wallet, tap ⓘ, tap "Copy key" (66 hex characters = the issuer's PUBLIC signing key; not a secret).
2. Paste it into `ledger/MintAuthority.kt` → `AUTHORITY_KEYS_HEX`, e.g. `listOf("02ab...")`. Add a SECOND key from a spare phone in the same list
   (the issuer key lives inside that phone's hardware and cannot be exported; if the phone is lost you can only rotate keys by shipping an update).
3. Build and release. Until a key is listed, nobody can mint (release builds fail closed). Debug builds (`FLAG_DEBUGGABLE`) allow anyone to mint:
   **never distribute a debuggable APK to users.**
4. Deposit flow: the user sends the issuer (WhatsApp/in person) their wallet address (Wallet → Receive → Share), the amount, and the bank receipt.
   The issuer checks the transfer in the bank app, opens Wallet → Issuer panel, enters address, amount, and the bank's unique receipt number, taps Mint
   (phone PIN required). The receipt number is stored in the transaction, so the same receipt can never be minted twice. The share dialog then sends the
   signed deposit by WhatsApp; it is also broadcast to nearby phones automatically.

### WhatsApp as the long-distance backbone (works with the existing app design)
Create a WhatsApp group whose name contains **Thunder AGI** (that is what the existing listener filters on). People post payments there with the app's
WhatsApp button (choose the group). Every member's phone imports every payment through the notification listener, so far-away phones converge without any server.
Consequences to tell users: everyone in the group sees amounts and addresses (the ledger is public by design); notifications for that chat must not be muted;
a phone that was offline imports what it missed when WhatsApp delivers the old messages. `ThunderRuntime.exportLedgerChunks()` produces resumable "⚡T:" messages
that an operator can post to let new phones catch up.

### WhatsApp Cloud API: what is and is not possible (answer to "can the app post through the API?")
* The API sends FROM a business number TO users. A normal user's phone cannot post into it, and an access token inside the APK can be extracted by anyone
  (account abuse/ban). So user phones never carry a token: they open WhatsApp with the message prepared and the user taps Send.
* Free-text API messages are allowed only within 24 h after the user last wrote to the business number; otherwise approved templates (paid) are required.
* Incoming messages reach a business number by webhook (a public HTTPS endpoint), not by notification.
* `relay/WhatsApp.kt` contains `WhatsAppCloudSender` for the OPERATOR phone only: token entered once in the Issuer panel, stored encrypted by the Keystore.

---------------------------------------------------------------------------------------------------

## 10. Security model: what is guaranteed and what is not

Guaranteed (and covered by tests):
* Nobody can spend from an address without its hardware-held key; nobody can forge or alter a payment; nobody but the issuer can create money.
* A double-spend attempt is detected, produces a proof anyone can verify, and freezes the cheating address on every node that sees it.
* Malformed/hostile network input cannot crash a node or make it allocate unbounded memory.
* Key storage uses a non-exportable Keystore key (the old code derived its storage key from the public key, which is broadcast).

NOT guaranteed (be honest with users):
* **Offline double-spending cannot be prevented, only detected.** A payment received over Bluetooth/WiFi-Aware is provisional until the phone has
  synced with others. For large amounts wait for sync (and ideally the issuer's confirmation). Rollback of already-spent funds after a fork is not implemented.
* **ECDSA is not post-quantum.** Kyber-768 + AES-256-GCM protect confidentiality of the PeerSync channel against "harvest now, decrypt later";
  the address commits to a Kyber key. Post-quantum SIGNATURES (e.g. ML-DSA, hybrid with ECDSA) are not implemented. Kyber here is BouncyCastle's
  round-3 variant, not final FIPS-203 ML-KEM (see header of `crypto/Kem.kt` for the upgrade path).
* **The ledger is public**: amounts, addresses and notes are visible to every node. Do not put private information in the 48-byte note.
* **No wallet recovery**: the key cannot leave the phone's hardware. Lost/factory-reset phone = that address is unusable. Recovery must be an
  issuer-side process (identify the user, freeze the old address, mint the balance to a new one); not implemented in code.
* The issuer is a trusted party (can refuse to mint, can be coerced). That is inherent to bridging real money.
* Bluetooth company id `0xFFFF` is the SIG "testing" value; register an id before a public launch. Android background limits can delay beacons
  unless the opt-in foreground service is on. WiFi Aware exists on only some phones.
* **This handles real money.** Before real users: independent security review, legal advice on e-money / money-transfer rules where you operate, small
  limits at first. The 226-byte format is versioned (byte 0) so it can evolve.

---------------------------------------------------------------------------------------------------

## 11. What was wrong in the files from earlier sessions (all fixed in these files)

| # | Problem in earlier versions | Effect |
|---|---|---|
| 1 | BouncyCastle Kyber package name was wrong (`crystalskyber`; real: `crystals.kyber`) | none of the crypto compiled |
| 2 | Address validator demanded a leading `T`, but the version byte produced `a`/`b` | every address rejected, no transfer possible |
| 3 | Ledger verified the signature against a key SUPPLIED BY THE ATTACKER and never tied it to the `from` address | anyone could spend anyone's balance |
| 4 | Any device could create GENESIS money of any size; receipts verified by a color check | unlimited counterfeit money |
| 5 | Nonce window ±20, no prev-hash check, balance check and insert not atomic | double spends, races |
| 6 | Gossip: both sides read at the same time (deadlock); only "latest 500 ids" | stuck sessions; new phones could never fully sync |
| 7 | Storage key derived from the PUBLIC key; Kyber key silently regenerated on any error | secrets readable; address changed, funds orphaned |
| 8 | PeerSync handshake: attestation without freshness, unsigned Kyber exchange | man-in-the-middle / replay possible |
| 9 | Mesh discovery re-announced on every announcement | endless message storm |
| 10 | BLE/WiFi-Aware carried only a "notification", receivers were never started; 31 bytes cannot hold a 64-byte signature | beacons did nothing |
| 11 | WhatsApp design: API token in the APK, API cannot receive from users, listener read ALL chats | account abuse, privacy regression |
| 12 | Build instructions used kapt (project uses KSP); renamed PeerSync state fields; referenced MainActivity parameters that do not exist | build/compile failures |
| 13 | Retry of held payments scanned the whole pool (12 rounds, re-verifying signatures); released payments were not counted | stuck payments, CPU abuse |
| 14 | Signing/key creation could run on the UI thread | freezes on first launch |
