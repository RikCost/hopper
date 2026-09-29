# ɹǝddoH (Hopper)

<p align="center">
  <img src="screenshots/app-icon.png" width="128" alt="ɹǝddoH app icon">
</p>

iOS and Android VPN clients plus a Linux server stack for an **SSH overlay VPN**: traffic is tunneled as raw IP packets over SSH, routed through one or more hops, and NAT’d at the exit. Supports **one-hop** (single VPS) and **multi-hop chains**, including **reverse inter-hop links** when only one direction of SSH between hops works. One app, one server daemon (`hopperd`).


## Getting the app

| Platform | Status |
| -------- | ------ |
| **iOS (TestFlight)** | [Join the beta](https://testflight.apple.com/join/QCeqF71s) — install [TestFlight](https://apps.apple.com/app/testflight/id899247664) first, then open the link on your iPhone or iPad |
| **iOS / Android (developer-signed)** | Available in [GitHub Releases](https://github.com/ZonD80/hopper/releases) — install the IPA or APK from the latest release |
| **Apple App Store** | [Download on the App Store](https://apps.apple.com/us/app/%C9%B9%C7%9Dddoh/id6778506930) |
| **iOS (appdb)** | <a href="https://appdb.to/details/aa708e634598470eeec6ebd64cdd5fda9a6f5dc1"><img title="Get from appdb" src="https://s3cdn.dbservices.to/official_buttons/get_white.png" width="100" alt="Get from appdb" /></a> |
| **Google Play** | [Get it on Google Play](https://play.google.com/store/apps/details?id=com.aengix.hopper) |

For iOS, use the [App Store](https://apps.apple.com/us/app/%C9%B9%C7%9Dddoh/id6778506930), [appdb](https://appdb.to/details/aa708e634598470eeec6ebd64cdd5fda9a6f5dc1), or the TestFlight beta above. For Android, use Google Play. Alternatively, use the signed builds from Releases (you may need to trust the developer certificate on iOS or allow installs from unknown sources on Android).

## Setting up a server

Each hop in your chain runs `hopperd` on a Linux VPS. You need one VPS per hop (a single VPS works for a one-hop chain where entry = exit).

### Get a VPS

For the simplest setup, rent the **cheapest Ubuntu VPS** you can find (any provider — Hetzner, DigitalOcean, Vultr, etc.). Use a fresh machine with:

- **User:** `root`
- **Auth:** root password (SSH password login enabled)

The server must have TUN support (`/dev/net/tun`), `python3`, `ip`, and `iptables` (for exit NAT). Ubuntu images from major providers include these by default.

### Deploy from the app

You do **not** need a Mac or `deploy.sh` to get started. The app installs and configures the server for you:

1. Open **ɹǝddoH** → **Configure chains** → **Server library**.
2. Tap **Deploy**.
3. Enter the VPS **host/IP**, **user** (`root`), **SSH port** (`22`).
4. Authenticate with **Password** (root password) or **Saved key** from [Keys library](#keys-library).
5. Wait for the deploy log to finish — the server is added to your library automatically (no QR scan needed).

The app uploads `hopperd`, runs `configure_server.sh`, and saves the server profile locally. A password deploy also generates an ED25519 key, installs its public half in `authorized_keys`, and stores the private key in Keys library for the next deploy. Repeat for each hop.

**Alternative (developers):** deploy from your Mac with `./deploy.sh` — see [Deploy from the command line](#deploy-from-the-command-line).

You can also add an already-installed server with **Add manually** (same host/user/port auth as Deploy, without installing Hopper), or via **Scan QR**, **Import from file**, **Import from copy&paste**, or **Import remotely**. The same import path accepts a shared **key** payload into Keys library.


## Screenshots

### iPhone

<p align="center">
  <img src="screenshots/iphone/01-home.png" width="200" alt="Home — chain, connect, and route">
  <img src="screenshots/iphone/02-chain.png" width="200" alt="Chain detail — entry to exit hops">
  <img src="screenshots/iphone/03-servers.png" width="200" alt="Server library">
  <img src="screenshots/iphone/04-provisioning.png" width="200" alt="Provisioning chain on connect">
</p>

### iPad

<p align="center">
  <img src="screenshots/ipad/01-home.png" width="280" alt="Home — connected VPN">
  <img src="screenshots/ipad/02-servers.png" width="280" alt="Server library">
  <img src="screenshots/ipad/03-chain.png" width="280" alt="Chain detail">
  <img src="screenshots/ipad/04-chains.png" width="280" alt="Chains list">
</p>

### Android (phone)

<p align="center">
  <img src="screenshots/android/phone/01-home.png" width="200" alt="Home — chain, connect, and route">
  <img src="screenshots/android/phone/02-chains.png" width="200" alt="Chains list">
  <img src="screenshots/android/phone/03-chain-detail.png" width="200" alt="Chain detail — entry to exit hops">
  <img src="screenshots/android/phone/04-servers.png" width="200" alt="Server library">
  <img src="screenshots/android/phone/05-server-detail.png" width="200" alt="Server detail">
</p>

---

## How it works

```mermaid
flowchart LR
  subgraph clients [Mobile clients]
    App[iOS / Android app]
    Tunnel[Packet tunnel]
    App -->|verify + provision| Tunnel
    Tunnel -->|SSH + iptunnel| Entry
  end

  Entry[hopperd entry]
  Exit[hopperd exit]
  Tunnel --> Entry
  Entry -.->|reverse or forward hop link| Exit
  Exit -->|TUN + NAT| Internet[(Internet)]
```

| Layer | Role |
| ----- | ---- |
| **iOS** | L3 VPN (`NEPacketTunnelProvider`). Default IPv4 → leased overlay client address. |
| **Android** | L3 VPN (`VpnService`). Same overlay + iptunnel data plane as iOS. |
| **iptunnel** | Framed IP over a byte stream (SSH `direct-tcpip` to `127.0.0.1:<listen_port>`). |
| **hopperd** | Userspace routing between ingress (phone), reverse/downstream hop link, and TUN (internet on exit). |
| **SSH** | Phone → **entry only**. Inter-hop links use each hop’s `~/.hopper/id_ed25519` (forward or reverse). |

Chain order in the app: **first = entry**, **last = exit**.

### One-hop

A chain with a **single server** is valid: that host is both entry and exit.

```mermaid
flowchart LR
  Phone[Phone VPN] -->|SSH + iptunnel| Hop[hopperd entry+exit]
  Hop -->|TUN + NAT| Net[(Internet)]
```

- Phone opens one SSH session to that host and ports to local `hopperd`.
- `hopperd` assigns a client address from the chain pool, routes `0.0.0.0/0` to TUN, and applies NAT.
- No inter-hop SSH, no reverse dialer.

### Multi-hop and reverse links

With two or more hops, the phone still SSH-connects **only to the entry**. Traffic between hops is carried on an iptunnel session over SSH between `hopperd` processes.

**Who dials whom** is separate from logical order (entry → exit):

| Situation | Behavior |
| --------- | -------- |
| Entry can reach exit’s SSH | Entry (or previous hop) dials next with `provide=ingress`; next runs the hop session on that channel. Entry keeps a **spare** reverse link parked for the next phone session. |
| Only exit can reach entry (inbound blocked on exit) | Exit dials upstream with `provide=downstream`; entry **parks** the offer until a phone session claims it. |
| Sticky preference | After a successful dial, `~/.hopper/link-sticky/<peer>.json` records `outbound_to` / `inbound_from` so an **opposite** app chain (same two hosts, swapped order) reuses that dial direction instead of flipping. |

```mermaid
flowchart TB
  subgraph a_to_b [Logical A then B — sticky A dials B]
    Phone1[Phone] -->|SSH| A1[A entry]
    A1 -->|reverse dial provide=ingress| B1[B exit]
    B1 --> Net1[(Internet)]
  end

  subgraph b_to_a [Logical B then A — same sticky]
    Phone2[Phone] -->|SSH| B2[B entry]
    A2[A exit] -->|reverse dial provide=downstream| B2
    A2 --> Net2[(Internet)]
  end
```

Keepalives hold parked reverse links until claimed. When a phone session takes a spare, the dialer **refills** so the next connect does not wait on a cold dial.

### Connect sequence (verification chain)

On **Connect**, the app runs checks and setup **before** the VPN data plane. Failures stop early with a clear status.

1. **Version check (every hop)** — SSH to each hop (nested: phone → entry, then `direct-tcpip` jump to later hops). Read `hopperctl configure --version-json`. Compare `server_version` / `min_app_version` / `min_server_version` with the app. Prompt to update servers when required.
2. **Optional server update** — If the user confirms, `hopperctl update` on hops that are behind.
3. **Provision (exit → entry)** — Still over the nested SSH forward (phone only needs reachability to the entry):
   - Mutual **trust**: each hop’s `~/.hopper/id_ed25519.pub` is authorized on its peer (`--trust-pubkey`).
   - Write `~/.hopper/chains/<chain_id>/hopper.json` with role, overlay addr, `upstream` / `downstream` as needed, then start `hopperd`.
   - Exit first so reverse dialers can attach as soon as the peer listens.
4. **VPN start (entry only)** — Packet tunnel SSH to the entry, open iptunnel to `127.0.0.1:<listen_port>`, assign client IP, send traffic.

```mermaid
sequenceDiagram
  participant App
  participant Entry
  participant Exit
  App->>Entry: SSH (version / provision jump)
  Entry->>Exit: SSH jump (version / provision)
  App->>Exit: trust + hopperctl start (via jump)
  App->>Entry: trust + hopperctl start
  Note over Entry,Exit: reverse dial + sticky
  App->>Entry: VPN SSH + iptunnel
  Entry-->>Exit: hop link (reverse or forward)
  Exit-->>App: internet via TUN/NAT
```

### Multi-chain and multi-device

Hopper supports **multiple chains** and **multiple clients per chain** on the same servers.

**Multi-chain** — Each chain has its own UUID. That ID derives a dedicated overlay subnet (`10.64.{octet}.0/24`), local `hopperd` listen port (`7400 + octet`), and TUN interface (`hopper_*`) on every hop. State lives under `~/.hopper/chains/{chain_id}/`; active chains are tracked in `~/.hopper/registry.json`. The same VPS can serve several chains at once (as entry, relay, or exit in different chains). Sticky dial files are **per peer host**, shared across chains on that machine.

**Multi-device** — Several phones or tablets can connect to the **same chain** at the same time. Each app install gets a stable device ID; the entry hop assigns a unique client address from that chain's pool (`10.64.{octet}.2`–`.254`) via lease. Leases renew while connected and expire after idle timeout (default 1 hour). Entry keeps reverse **spares** so a second device does not wait for a cold dial.

**TUN limits** — Each active chain uses **one TUN interface per hop** where `hopperd` runs. How many chains you can run in parallel on a server depends on how many TUN devices the host allows (typically many on a stock Linux VPS, but the limit varies by kernel and provider).

### Overlay (per chain)

Each chain gets its own `/24` subnet: `10.64.{octet}.0/24`, where `{octet}` is derived from the chain UUID (1–254).

| Address | Use |
| ------- | --- |
| `10.64.{octet}.2`–`.254` | Mobile clients (leased per device) |
| `10.64.{octet}.10` + index | Hop *i* in chain (entry = `.10`) |
| `0.0.0.0/0` | Relay/entry with next → hop link; exit → TUN + NAT |

---

## Requirements

### Server (each hop)

- Linux with TUN (`/dev/net/tun`)
- `python3`, `ip`, `iptables` (exit NAT)
- Root or `cap_net_admin` on `hopperd` (set by `configure_server.sh` when run as root)
- SSH access for deploy and for inter-hop / client connections

### iOS

- Xcode 16+, iOS 17+
- Apple Developer account with **Network Extension** (Packet Tunnel) entitlement
- App Group: `group.com.aengix.hopper`

### Android

- Android Studio with SDK 35 (API 26+ devices)
- JDK 17 (bundled with Android Studio on macOS)
- Release signing: `~/googlePlayKeys.jks` + `app-android/keystore.properties` (see [Android build & run](#build--run))

### Dev machine

- Go 1.22+ (build server binaries)
- SSH key to target servers (`~/.ssh/id_rsa` by default)

---

## Quick start

1. [Get the app](#getting-the-app) and [set up one or more servers](#setting-up-a-server).
2. **New chain** → name it → **Add server…** in order **entry → exit** (one server = one-hop; two or more = multi-hop).
3. Swipe **Use** (iOS) or tap **Use** (Android), or pick the chain on the home screen → **Connect** (version check → provision → VPN).

### Remove a hop

```bash
./remove.sh YOUR_SERVER_IP
```

Stops `hopperd`, removes `~/hopper`, `~/.hopper`, TUN `hopper0`, hopper NAT rules, and hopper lines in `authorized_keys`.

---

## Server reference

### Deploy from the command line

From your Mac:

```bash
cd server
./deploy.sh YOUR_SERVER_IP
```

This will:

- Build `dist/hopperd-linux-{amd64,arm64}`
- Upload bundle to `~/hopper` on the server
- Run `configure_server.sh --json-only` and open a **local QR page** in the browser (deleted after 5 seconds)

Options (same as `remove.sh`):

| Flag         | Default         | Meaning              |
| ------------ | --------------- | -------------------- |
| `-u`         | `root`          | SSH user             |
| `-p`         | `22`            | SSH port             |
| `-i`         | `~/.ssh/id_rsa` | SSH private key      |
| `-P`         | `~/hopper`      | Remote install path  |
| `-y`         | —               | Skip confirmation    |
| `--no-build` | —               | Skip `build_dist.sh` |

Environment: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PORT`, `DEPLOY_KEY`, `DEPLOY_PATH`.

### Layout on the machine

```
~/hopper/
  hopperctl             # CLI: start / configure / update / remove
  install.sh
  VERSION.json
  dist/
    hopperd-linux-amd64
    hopperd-linux-arm64

~/.hopper/
  id_ed25519            # inter-hop + hopperd SSH identity
  registry.json         # active chains
  link-sticky/          # preferred dial direction per peer host
  chains/{chain_id}/
    hopper.json         # runtime config for this chain
    hopper-ready        # READY port line while running
    hopper-YYYY-MM-DD.log  # daily hopperd logs (default retain 2 days)
```

### Scripts

| Script                | Who runs it         | Purpose                                                                          |
| --------------------- | ------------------- | -------------------------------------------------------------------------------- |
| `hopperctl` / `install.sh` | Admin / `deploy.sh` / app | Install bundle, configure profile, start/stop/update `hopperd` per chain |
| `deploy.sh`           | Developer           | Build, upload local tree + binaries, configure                                   |
| `remove.sh`           | Developer           | Uninstall                                                                        |
| `build_dist.sh`       | Developer           | Cross-compile `hopperd`                                                          |

#### Provision via `hopperctl start`

The app (and CLI) drives hops with `hopperctl start` over SSH. Typical flags:

```bash
# Exit hop (index > 0): must declare upstream toward previous hop
./hopperctl start --chain-id <uuid> --role exit --addr 10.64.N.11 --index 1 \
  --upstream-host entry.example.com --upstream-port 22 --upstream-user root \
  --upstream-tunnel-port 75xx

# Entry / relay: downstream toward next hop (reverse dial when sticky says so)
./hopperctl start --chain-id <uuid> --role relay --addr 10.64.N.10 --index 0 \
  --downstream-host exit.example.com --downstream-port 22 --downstream-user root \
  --downstream-tunnel-port 75xx

./hopperctl start --chain-id <uuid> --trust-pubkey 'ssh-ed25519 AAAA…' --trust-only
./hopperctl start --chain-id <uuid> --stop-only
```

Stdout: one JSON line, e.g. `{"ready":true,"mode":"exit","addr":"10.64.N.11",…}`.

### `hopperd`

```bash
# typical chain paths (created by hopper start / the app)
CHAIN=~/.hopper/chains/<chain_id>
./dist/hopperd-linux-amd64 -verbose \
  --config "$CHAIN/hopper.json" \
  --ready-file "$CHAIN/hopper-ready" \
  --log-dir "$CHAIN" \
  --log-keep-days 2
```

| Flag | Default | Purpose |
| --- | --- | --- |
| `--config` | `~/.hopper/hopper.json` | Runtime JSON config |
| `--ready-file` | — | Write `READY <port>` when listening |
| `--log-dir` | — | Daily logs as `hopper-YYYY-MM-DD.log` in this directory; rotate at local midnight; prune on write and hourly |
| `--log-keep-days` | `2` | Calendar days of logs to keep (today counts as 1; `2` = today + yesterday) |
| `-verbose` | off | Debug logging |

When started via `hopper start` / the app, Python passes `--log-dir` and `--log-keep-days`. Override retention with env `HOPPER_LOG_KEEP_DAYS` (same default `2`).

Listens on `127.0.0.1:7400` (or `7400 + overlay octet` per chain) only — reached via SSH forwarding.

Example config: [`server/hopper.example.json`](server/hopper.example.json).

### Build server only

```bash
cd server
./build_dist.sh
```

Binaries land in `server/dist/` (gitignored).

---

## iOS app reference

### Screens

| Screen               | Purpose                                         |
| -------------------- | ----------------------------------------------- |
| **Home**             | Select chain, connect/disconnect, route preview, import/export |
| **Configure chains** | Create/delete chains, open server library and keys library |
| **Chain detail**     | Name, reorder hops, add/remove servers          |
| **Server library**   | **Deploy**, **Add manually**, scan QR, import `.hopperconf` / JSON, delete servers |
| **Keys library**     | List deploy keys and assigned `user@host`, generate ED25519, paste/validate PEM, scan QR, import/export `.hopperconf` |
| **Export**           | **Export as file…** and **Show QR code…** on one row (home, chain, server, key) |

Profiles persist in the App Group (`hopper-profiles.json`). Existing deploy keys from earlier app versions are picked up into Keys library on first launch after update.

### Keys library

Deploy keys are the identities the app uses to **SSH in and install** Hopper. They are separate from each server’s hop key (`HopNodeProfile.privateKey` / `~/.hopper/id_ed25519`), which is used for the VPN tunnel after install.

From **Configure chains** → **Keys library** you can:

- See every saved private key and the servers it has been used on (`user@host`, plus the library name when the host matches)
- **Generate** a new ED25519 key and copy the public and private halves
- **Paste** an OpenSSH ED25519 private key (validated immediately) and a name
- **Import** a key as a QR code (in-person) or an encrypted [`.hopperconf`](#hopperconf-share-files-v1) file. **Export as file…** / **Show QR code…** use the same password rules as servers and chains, including the default password
- Delete a key only when no library servers are still assigned to it

On deploy, choose **Saved key** instead of a password to reuse a library key. Password deploy still works: it creates a new library key named `Deploy user@host` and records that assignment.

Updating from an older app version keeps every key already stored in `deployKeys`. Auto-generated `Deploy user@host` names are matched to servers in the library so assignments appear without redeploying. Hop daemon keys on server profiles are **not** copied into Keys library.

### Server profile JSON (v2)

Scan QR, **Import**, and `./deploy.sh` all use the same **v2** hop wire format (`HopProfileCodec` on iOS/Android, `server/hopper/node_profile.py` on the server). Encrypted cross-device shares use [`.hopperconf`](#hopperconf-share-files-v1). The app stores servers in a library; chains reference server IDs in order.

**Example** (as emitted by `configure_server.sh --json-only`):

```json
{
  "v": 2,
  "name": "vps.example.com",
  "host": "203.0.113.10",
  "port": "22",
  "user": "root",
  "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\n...",
  "install_dir": "~/hopper",
  "server_version": "2.0.1",
  "min_app_version": "2.0.0",
  "host_key": ["ssh-ed25519 AAAA..."]
}
```

| Field | Required | Notes |
| ----- | -------- | ----- |
| `v` | No | Must be `2` when present; other values are rejected |
| `host` | Yes | SSH hostname or IP. Alias: `server` |
| `user` | Yes | SSH user. Alias: `username` |
| `private_key` | Yes | OpenSSH private key for this hop (`~/.hopper/id_ed25519` from configure). Alias: `privateKey` |
| `port` | No | SSH port as a string; default `22` |
| `name` | No | Display label; defaults to host or `Untitled`. Aliases: `title`, `remarks` |
| `install_dir` | No | Remote bundle path; default `~/hopper`. Aliases: `installDir`, `hopper_dir` |
| `server_version` | No | Server bundle version from `VERSION.json`; export uses `unknown` if empty. Alias: `serverVersion` |
| `min_app_version` | No | Minimum client version required by the server; export uses `unknown` if empty. Alias: `minAppVersion` |
| `host_key` | No | SSH host key pin(s) for first connect — string or array. Alias: `hostKeys` |

Treat exported JSON and QR codes as **secrets** (they contain the hop private key).

### `.hopperconf` share files (v1)

Servers, chains, and **deploy keys** are shared between devices as encrypted **`.hopperconf`** files (`application/x-hopperconf`). QR codes stay **unencrypted** for in-person scanning only — they are never written to a shareable file.

**Import remotely** is a live LAN session, not a file. The receiving device listens on `0.0.0.0` (ephemeral port), shows a `hopperconf://recv?ip=&port=&fp=&v=1` QR (`fp` is the SHA-256 of that session’s TLS certificate). Scan it with the **Camera** app on the other phone (in-app **Scan QR** also works). After TLS pinning succeeds, the sender can share several chains, servers, or keys until **Disconnect**. The listener accepts only one peer at a time.

#### Envelope (on disk)

Always JSON. Private keys live only inside the encrypted `data` blob.

```json
{
  "v": 1,
  "fmt": "hopperconf",
  "alg": "aes-256-gcm",
  "kdf": "pbkdf2-sha256",
  "iter": 210000,
  "salt": "<base64 16 bytes>",
  "nonce": "<base64 12 bytes>",
  "data": "<base64 ciphertext || 16-byte GCM tag>"
}
```

| Field | Notes |
| ----- | ----- |
| `fmt` | Must be `hopperconf` |
| `alg` | `aes-256-gcm` (AES-256-GCM, 128-bit tag appended to ciphertext) |
| `kdf` | `pbkdf2-sha256` — PBKDF2-HMAC-SHA256 over the **UTF-8** password |
| `iter` | Iteration count (apps use `210000`) |
| `salt` / `nonce` | Random per file (`16` / `12` bytes) |
| `data` | `AES-GCM(key, nonce, plaintext)` output: ciphertext ‖ tag |

**Password:** optional at export. Empty / omitted at export → default password `ɹǝddoH` (app display name). On import, apps try the **default password first**, then a user-provided password if that fails. Derived key length is 32 bytes.

#### Plaintext payload (after decrypt, or QR)

**Server** (file payload uses `kind`; QR for a single server may still be bare hop-profile v2):

```json
{
  "v": 1,
  "kind": "server",
  "server": { "v": 2, "name": "...", "host": "...", "port": "22", "user": "...", "private_key": "...", "...": "..." }
}
```

**Chain** (embeds full hop profiles in order; import mints new server IDs and a new chain):

```json
{
  "v": 1,
  "kind": "chain",
  "name": "My chain",
  "hops": [ { "v": 2, "...": "..." }, { "v": 2, "...": "..." } ]
}
```

**Key** (deploy identity only — name + private key; local server assignments are not shared). QR uses this same plaintext JSON; files wrap it in the encrypted envelope:

```json
{
  "v": 1,
  "kind": "key",
  "key": {
    "name": "Deploy root@203.0.113.10",
    "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\n..."
  }
}
```

Apps open `.hopperconf` via the share sheet / Files / “open with”. A `kind: key` payload is stored in Keys library (duplicate public-key fingerprints are merged). Interop tests (Python reference, Swift CryptoKit, Android `HopperConf`) live under `tests/hopperconf/` — run `./tests/hopperconf/run.sh`.

### Build & run

```bash
cd app
open Hopper.xcodeproj
```

- Target **Hopper** (app) + **HopperExtension** (packet tunnel)
- Citadel (vendored SSH) is linked to both app (provision) and extension (data plane)
- Signing: set your `DEVELOPMENT_TEAM`, enable Network Extension + App Groups

Regenerate Xcode project (optional):

```bash
ruby app/Scripts/generate_xcodeproj.rb
```

### Project layout

```
app/
  Hopper/              SwiftUI app, VPNController, ChainProvisioner
  HopperExtension/     PacketTunnelProvider
  TunnelCore/          SSHHopConnector, IPTunnelEngine, HopSSH
  Shared/              Models, HopConstants, ProfileStore, HopperConf, KeysLibraryMigration
  Vendor/Citadel/      SSH client library
app-android/
  app/                 Jetpack Compose UI, VpnController, HopperVpnService
  build-apk.sh         Signed release APK (bumps versionCode)
  build-aab.sh         Signed Play Store bundle (bumps versionCode)
  generate-keystore.sh Create hopper-upload signing key
server/
  cmd/hopperd/         Daemon entrypoint
  internal/hopper/     Config, session routing, reverse dial + sticky, NAT, SSH hop link
  internal/iptunnel/   Frame protocol + Linux TUN
```

---

## Android app reference

Same screens and flow as iOS: home (chain + connect), chain configurator, chain detail, server library (**Deploy**, **Add manually**, QR scan, import from file / paste / remotely), **Keys library** (generate / paste PEM / QR / import / export deploy keys), encrypted file share for servers, chains, and keys.

Profiles persist in app-private storage (`hopper-profiles.json`). Server profile JSON and `.hopperconf` formats are identical to iOS — see [Server profile JSON (v2)](#server-profile-json-v2), [Keys library](#keys-library), and [`.hopperconf` share files (v1)](#hopperconf-share-files-v1).

### Build & run

Open the project in Android Studio:

```bash
cd app-android
open -a "Android Studio" .
```

Or build a debug APK from the command line:

```bash
cd app-android
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install on a connected device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

On first **Connect**, Android prompts for VPN permission — required for the tunnel.

### Release signing

Release builds use the same keystore layout as other AENGIX Android apps. The keystore lives outside the repo:

```
~/googlePlayKeys.jks
```

Create `app-android/keystore.properties` (gitignored) before running the release scripts:

```properties
storePassword=your_store_password
keyPassword=your_key_password
keyAlias=hopper-upload
```

Hopper uses alias `hopper-upload` in the shared AENGIX keystore (`upload` is reserved for the TV browser app).

To generate a new Hopper signing key (adds to existing keystore or creates a fresh one):

```bash
cd app-android
./generate-keystore.sh
```

Or manually:

```bash
keytool -genkeypair -v \
  -keystore ~/googlePlayKeys.jks \
  -alias hopper-upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Hopper, OU=Mobile, O=AENGIX SL, L=Barcelona, ST=Barcelona, C=ES"
```

### Release build scripts

Both scripts auto-detect the Android Studio JDK, validate signing credentials, **bump `versionCode` by 1**, and produce a signed artifact:

```bash
cd app-android
./build-apk.sh    # app/build/outputs/apk/release/app-release.apk
./build-aab.sh    # app/build/outputs/bundle/release/app-release.aab
```

For manual Gradle release builds (without auto bump):

```bash
cd app-android
./gradlew assembleRelease   # APK
./gradlew bundleRelease     # AAB
```

### Android project layout

```
app-android/
  app/src/main/java/com/aengix/hopper/
    ui/                  Compose screens
    vpn/                 VpnController, HopperVpnService, TunnelCoordinator
    ssh/                 HopSSH, SSHHopConnector (SSHJ)
    tunnel/              IPTunnelFrame, IPTunnelEngine
    provision/           ChainProvisioner
    model/               AppState, HopNodeProfile, HopChain, DeploySSHKey
    data/                ProfileStore, HopQRParser, HopperConf, KeysLibraryMigration
```

---

## Troubleshooting

| Symptom                   | Things to check                                                                  |
| ------------------------- | -------------------------------------------------------------------------------- |
| VPN connects, no internet | Exit NAT: `iptables -t nat -L`; today’s `hopper-YYYY-MM-DD.log` on exit; re-connect to re-provision |
| Chain provision fails     | Nested SSH to later hops via entry; `hopperctl` on server; keys in `authorized_keys` |
| Reverse wait / stream closed | Entry log: `reverse linked` / `reverse spare claimed`; sticky under `~/.hopper/link-sticky/`; peer reachable on SSH; matching `chain_id` + `listen_port` |
| `hopperd` won’t start     | Root/`setcap cap_net_admin`; read `~/.hopper/chains/<chain_id>/hopper-YYYY-MM-DD.log` |
| Extension / VPN errors  | iOS: App Group + embedded extension; reinstall VPN profile. Android: revoke/re-grant VPN permission; check logcat `Hopper` |

**Logs**

- Server: `~/.hopper/chains/<chain_id>/hopper-YYYY-MM-DD.log` (daily; default keep 2 days via `--log-keep-days` / `HOPPER_LOG_KEEP_DAYS`)
- iOS: Xcode → Window → Devices → open console for device, filter `Hopper`
- Android: `adb logcat -s Hopper`

**Manual stop on server**

```bash
cd ~/hopper && ./hopperctl start --chain-id <uuid> --stop-only
```

---

## Security notes

- QR, deploy HTML, and `.hopperconf` files contain **private keys** — treat as secrets; deploy deletes local HTML after 5s.
- Keys library holds **deploy** identities (SSH install). Each hop still has its own `~/.hopper/id_ed25519` for tunnels; provision adds peer pubkeys to `authorized_keys` (both directions for reverse dial).
- Inter-hop sticky dial state is under `~/.hopper/link-sticky/` (host-scoped, not a secret key).
- `hopperd` binds to loopback; only SSH-forwarded clients reach iptunnel.
- Review `authorized_keys` after `remove.sh` if you added keys manually.

---

## License

See repository for license terms. Citadel is vendored under its own license in `app/Vendor/Citadel/`.
