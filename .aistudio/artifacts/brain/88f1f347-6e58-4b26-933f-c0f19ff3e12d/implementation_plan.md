# SovereignShell — Self-Publishing Nostr Napplet & Nsite Launcher

SovereignShell is an Android home-screen-style launcher and sandboxed runtime for **Nostr Applets (NIP-5D)** and **static Nsites (NIP-5A)**, complete with a built-in multi-account cryptographic signer vault, an in-app napplet/nsite authoring studio, and one-tap **self-publishing** that deploys both user-built applets and a standalone web replica of the shell itself to Blossom servers and Nostr relays.

### User Review & Critical Decisions

> [!IMPORTANT]
> **Answering Your UX Question — Should "Publish" live in the Discovery Feed or in the Account Menu Drawer?**
> **Recommended Choice: Primary home in the Account Menu Drawer, plus a contextual `+ Publish` pill inside the `Napplets | Nsites` Discovery Feed (Zero clutter on the Home Launcher).**
> - **Why the Account Menu Drawer is Primary**: Publishing (`Napplet & Nsite Studio` + `Self-Publish Shell`) is an authoring and identity-bound operation tied to your active `npub`, Blossom servers, and relay set. Keeping it in the upper-right Account Menu Drawer preserves a pure, distraction-free OS launcher feel on your custom-wallpaper home screen—and puts **Self-Publish Shell** right next to your identity, relays, and Blossom settings.
> - **Why the Discovery Feed gets a Secondary `+ Publish` Shortcut**: When browsing the full-page `Napplets | Nsites` feed to install apps, having a compact `+ Publish` header button (and a `"Fork to Studio"` action on existing open manifests) creates a seamless creator loop from discovering community apps to publishing your own into that exact feed.
> *(If you prefer Publish exclusively in one place, click Revise or reply before proceeding and we will lock it to that single location.)*

- **Confirmed Bridge Architecture**: Sandboxed **NIP-5D `postMessage` NAPs** (`NAP-SIGNER`, `NAP-RELAY`, `NAP-STORAGE`, `NAP-INTENT`) by default, with an explicit per-applet toggle to grant **`NIP-07` (`window.nostr`)** injection for classic Nostr web apps and nsites.
- **Confirmed Self-Publishing Scope**: Includes both an **in-app code/manifest studio** to build and publish napplets/nsites AND a **Self-Publish Shell** engine that compiles a working web version of the launcher (preloaded with your public pinned apps and NIP-5D host bridge) and publishes it as your own NIP-5A `nsite`.
- **Confirmed Built-in Signer**: Hardware-encrypted **multi-account `nsec` vault** (`npub`/`nsec` generation, import, and account switching) with **granular per-applet permission prompts** (Allow Once, Always Allow for Event Kind, Reject) and an audit log.
- **Confirmed Shell UX & Layout**: Home-screen style launcher with customizable wallpaper background, instant **Icon Grid vs. Detailed List** view toggle, a top **Search Bar** that expands into a full-page **`Napplets | Nsites`** discovery and install feed, and an **upper-right Account Avatar** that opens the right-hand slide-over **Menu Drawer**.

---

### 1. Overview & Core Concept

- **What It Does**: Turns Android into a sovereign, decentralized mini-app OS. Users pin Napplets (NIP-5D kinds `35129` / `15129`) and Nsites (NIP-5A kinds `35128` / `15128`) onto a personalized wallpaper launcher, run them inside isolated WebView containers backed by a native Nostr relay pool and BIP-340 Schnorr signer, author or fork applets on-device, and publish static blobs to Blossom (`BUD-02` / `kind 24242`) with signed manifest events to Nostr relays.
- **Target Audience / Persona**: Nostr power users, indie sovereign-web creators, and privacy-conscious users who want composable micro-apps without centralized app stores, leaked private keys, or single-server hosting dependencies.
- **Key Value**: Eliminates both key-exposure risk (no pasting `nsec` into web apps) and hosting lock-in—every installed applet is verifiable by SHA-256 Blossom hashes, and the shell itself can reproduce and publish itself to any Blossom + relay set as a live web `nsite`.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **First Launch & Identity Setup**: Users land immediately on a curated atmospheric launcher pre-stocked with working starter Napplets (e.g., *Micro-Notes*, *Relay Pulse*, *Profile Card*) so the shell is tactile right away. Tapping the upper-right Account icon opens the Drawer to generate a fresh Nostr keypair (`nsec`/`npub`) or import an existing `nsec` into the encrypted multi-account vault.
  2. **Home Launcher Customization & Switching**: On the Home Screen, users tap the **Grid / List toggle** beside the search bar to switch between a crisp OS-style **Icon Grid** (squircle glass badges + short titles over the custom wallpaper) and a **Detailed List** (icon, title, author `npub`, protocol badge `NIP-5D` / `NSITE`, and full description). Via the Account Drawer, users pick any photo via the system Photo Picker (or choose built-in procedural dark mesh wallpapers) with adjustable scrim contrast.
  3. **Full-Page Discovery & Install (`Napplets | Nsites`)**: Tapping the Search Bar smoothly expands into a full-screen discovery surface with segmented **`Napplets` | `Nsites`** tabs, live relay search/filtering (by title, `d`-tag, `npub`, or URL), protocol inspection (Blossom hashes & requested capabilities), one-tap **Install to Home**, and a contextual **`+ Publish`** shortcut.
  4. **Running an Applet & Signer Approval Sheet**: Tapping a launcher item opens the Runtime Container with a minimal floating shell pill (showing applet identity, active `npub`, NIP-5D/NIP-07 badge, and close/minimize controls). Whenever an applet requests `signEvent`, `nip04/nip44` encryption, or relay publishing, a native Material 3 **Signer Permission Bottom Sheet** slides up showing the exact event `kind`, JSON payload preview, active account, and *Allow Once / Always Allow Kind / Deny* actions.
  5. **In-App Studio & Self-Publishing the Shell**: Opening **Creator Studio** (from the Account Drawer or the Discovery Feed's `+ Publish` button) lets users write or edit multi-file HTML/CSS/JS applets, preview them live with the NIP-5D bridge, upload all assets to configured Blossom servers (`PUT /upload` authorized with signed `kind 24242` tokens), and broadcast NIP-5D (`35129`) or NIP-5A (`15128` / `35128`) manifests. Selecting **Self-Publish Shell** in the Drawer packages a complete standalone HTML/JS web shell (implementing the same launcher layout, your pinned public app directory, and NIP-5D iframe host broker) and deploys it as your own live `nsite`, outputting shareable gateway URLs.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: **Translucent Glass Launcher & Sovereign Cipher Deck** — clean OS-grade spatial clarity over user-selected photography, paired with high-contrast dark frosted glass (`SurfaceContainer` with alpha + subtle 1dp hairline borders) so icons and descriptions stay legible over any custom background.
  - *Color Palette & Mood*: Deep obsidian base (`#0B0E14`), frosted slate glass surfaces (`#151A24` at 82%–92% opacity), electric Nostr violet primary (`#9B72FF`), cyber-mint verification accent (`#2EE59D` for verified SHA-256 Blossom blobs and approved signer rules), and amber caution (`#FFB020` for unsigned/NIP-07 raw permissions).
  - *Typography & Hierarchy*: Bundled local Google Fonts via `font-util`: **Space Grotesk** for expressive launcher headers, sheet titles, and tab labels; **Plus Jakarta Sans** for crisp descriptions and UI controls; **JetBrains Mono** for `npub`/`nsec` keys, SHA-256 Blossom hashes, event kinds, and the in-app code editor.
  - *Component Styling & Layout*: Edge-to-edge full-bleed wallpaper canvas respecting status bar and gesture navigation insets; top bar housing the pill Search trigger, compact Grid/List view toggle, and upper-right circular Account Avatar (with active-account ring indicator) that opens a right-aligned `ModalNavigationDrawer` / slide-over sheet. Adaptive layout switches from 4-column icon grid on phones to wider multi-column / split Studio preview on tablets and foldables.
- **Interactive Feedback & Motion**: Shared-bounds / smooth vertical expansion when tapping the Search Bar into the full-page `Napplets | Nsites` feed; spring-animated layout morphing when toggling Icon Grid ↔ Description List; live step-by-step cryptographic progress pills during Blossom SHA-256 hashing, `kind 24242` upload, and multi-relay `OK` acknowledgments.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Hybrid Placement for Publishing (Account Drawer Primary + Feed Shortcut)**
  - *Chosen Approach*: Place **Creator Studio** and **Self-Publish Shell** prominently inside the upper-right Account Menu Drawer, plus a compact `+ Publish` action inside the full-page `Napplets | Nsites` discovery header (and `"Fork to Studio"` on feed items).
  - *Why*: Keeps the Home Launcher calm and personal (dedicated strictly to launching pinned apps and searching), while giving creators one-tap access whether they are managing their identity in the Drawer or browsing the community catalog.
  - *Alternatives Considered*: Putting a permanent floating action button on the Home Launcher was rejected because it obscures custom wallpapers and makes the home screen feel like a document app rather than an OS shell.

- **Decision 2: True NIP-5D Sandbox Isolation + Explicit Per-Applet NIP-07 Opt-In**
  - *Chosen Approach*: Load Napplets inside a sandboxed iframe/WebView context without `window.nostr`, brokering all capabilities via JSON `postMessage` (`NAP-SIGNER`, `NAP-RELAY`, `NAP-STORAGE`, `NAP-INTENT`). Provide an explicit per-applet toggle ("Enable Legacy NIP-07 `window.nostr`") for standard Nsites that expect browser-extension signing.
  - *Why*: Strict NIP-5D compliance protects users from rogue scripts scraping storage or spamming signatures, while optional NIP-07 ensures 100% compatibility with the broader ecosystem of existing Nostr web apps and nsites.
  - *Alternatives Considered*: Injecting `window.nostr` everywhere was rejected because NIP-5D explicitly forbids exposing `window.nostr` inside untrusted napplet sandboxes.

- **Decision 3: Hardware-Backed Local Key Vault + Decentralized Relay/Blossom Persistence**
  - *Chosen Approach*: Encrypt `nsec` private keys on-device using **Android Keystore (`AES/GCM/NoPadding`)**, persist launcher pins, per-applet permissions, studio projects, and wallpaper URI locally via **Room + DataStore**, and publish/sync public manifests and blobs directly to **Nostr Relays (OkHttp WebSockets)** and **Blossom HTTP servers**.
  - *Why*: In Nostr, private keys (`nsec`) must never leave the device or touch centralized cloud databases, while Nostr relays and Blossom servers natively serve as the user's decentralized cloud backend.
  - *Alternatives Considered*: Cloud-storing keys or requiring Google Sign-In was rejected as incompatible with sovereign Nostr key custody.

- **Decision 4: Pure-Kotlin BIP-340 Schnorr, NIP-19 Bech32 & NIP-04/NIP-44 Crypto Engine**
  - *Chosen Approach*: Implement deterministic BIP-340 `secp256k1` Schnorr signing/verification, SHA-256 content hashing, Bech32 (`npub`, `nsec`, `naddr`), and NIP-04/NIP-44 payload encryption in clean, audited Kotlin without native `.so` JNI blobs.
  - *Why*: Guarantees zero ABI crashes across Android architectures, full Google Play policy compliance, and fast local verification.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                        JETPACK COMPOSE UI SHELL                            │
│                                                                            │
│  ┌──────────────────────────────┐    ┌──────────────────────────────────┐  │
│  │     Home Screen Launcher     │    │  Right-Side Account Menu Drawer  │  │
│  │ • Custom Wallpaper + Scrim   │    │ • Multi-Account Switcher & Vault │  │
│  │ • Top Search Pill Trigger    │◄──►│ • Creator Studio (New / Drafts)  │  │
│  │ • Icon Grid ↔ List Toggle    │    │ • Self-Publish Shell to Nsite    │  │
│  │ • Upper-Right Account Avatar │    │ • Signer Permissions & Audit Log │  │
│  └──────────────┬───────────────┘    │ • Relays, Blossom & Wallpaper    │  │
│                 │                    └─────────────────┬────────────────┘  │
│       ┌─────────┴──────────┐                           │                   │
│       ▼                    ▼                           ▼                   │
│  ┌───────────────────┐  ┌────────────────────┐  ┌───────────────────────┐  │
│  │ Full-Page Search  │  │  Applet WebView    │  │  Creator Studio &     │  │
│  │ & Discovery Feed  │  │  Runtime Host      │  │  Publisher Sheet      │  │
│  │ • Napplets Tab    │  │ • NIP-5D Sandbox   │  │ • HTML/CSS/JS Editor  │  │
│  │ • Nsites Tab      │  │ • Optional NIP-07  │  │ • Live Bridge Preview │  │
│  │ • Install / Fork  │  │ • Signer Approval  │  │ • Blossom + Manifest  │  │
│  │ • + Publish Pill  │  │   Bottom Sheet     │  │   Deployment Pipeline │  │
│  └────────┬──────────┘  └─────────┬──────────┘  └───────────┬───────────┘  │
└───────────┼───────────────────────┼─────────────────────────┼──────────────┘
            │                       │                         │
            ▼                       ▼                         ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                     DOMAIN BROKERS & VIEWMODEL LAYER                       │
│                                                                            │
│  ┌─────────────────────┐  ┌───────────────────────┐  ┌──────────────────┐  │
│  │  NAP & NIP-07       │  │ Built-in Signer &     │  │ Nsite & Shell    │  │
│  │  Message Broker     │  │ Permission Engine     │  │ Self-Publisher   │  │
│  │ • NAP-SIGNER        │  │ • BIP-340 Schnorr     │  │ • SHA-256 Hasher │  │
│  │ • NAP-RELAY         │  │ • Bech32 (NIP-19)     │  │ • Kind 24242 Auth│  │
│  │ • NAP-STORAGE       │  │ • NIP-04 / NIP-44     │  │ • Kind 15128 /   │  │
│  │ • NAP-INTENT        │  │ • Per-App Policy Gate │  │   35128 / 35129  │  │
│  └──────────┬──────────┘  └───────────┬───────────┘  └────────┬─────────┘  │
└─────────────┼─────────────────────────┼───────────────────────┼────────────┘
              │                         │                       │
              ▼                         ▼                       ▼
┌──────────────────────────┐ ┌──────────────────────┐ ┌──────────────────────┐
│ Nostr & Blossom Network  │ │ Android Keystore     │ │ Room DB + DataStore  │
│ • OkHttp WSS Relay Pool  │ │ • AES-GCM Encrypted  │ │ • Installed Apps     │
│ • Blossom HTTP PUT/GET   │ │   nsec Account Vault │ │ • Applet NAP Storage │
│ • Blob SHA-256 Verifier  │ │                      │ │ • Policies & Drafts  │
└──────────────────────────┘ └──────────────────────┘ └──────────────────────┘
```

- **Data Model & State**:
  - **VaultAccount**: `npub`, display alias, avatar color/metadata, hardware-wrapped `nsec` ciphertext + IV, active flag.
  - **InstalledApplet**: Unique coordinate (`kind:pubkey:d-tag` or root `pubkey`), type (`NAPPLET` vs `NSITE`), title, summary description, icon path/emoji, bridge mode (`NIP_5D` or `NIP_07`), Blossom server hints, path-to-SHA256 manifest map, pin order, and cached offline bundle.
  - **SignerPolicy & AuditEntry**: Scoped to `(accountPubkey, appletId, capability, eventKind)` storing `ALLOW_ALWAYS` or `DENY`, plus timestamped history of signed/rejected requests.
  - **StudioProject**: Local editable multi-file map (`/index.html`, `/style.css`, `/app.js`), target deployment type (`NAPPLET_35129`, `NSITE_35128`, `ROOT_NSITE_15128`), `d`-tag identifier, title, description, and last published manifest event ID.
  - **LauncherPreferences**: View mode (`ICON_GRID` vs `DESCRIPTION_LIST`), custom background image URI or preset theme ID, wallpaper dim scrim intensity, configured Nostr relay URLs, and Blossom server URLs.

- **Interactive Component & State Mapping**:
  - **Launcher Top Bar & Canvas**: Tapping the Search Pill transitions `LauncherUiState` to the full-screen `DiscoveryOverlay` with instant keyboard focus; tapping the Grid/List icon flips `LauncherViewMode` in DataStore and animates item cards; tapping the upper-right Account Avatar opens the right-side Drawer; long-pressing any pinned applet opens quick actions (*Applet Permissions, Toggle NIP-07, View Manifest, Fork to Studio, Unpin*).
  - **Discovery Feed (`Napplets | Nsites`)**: Switching tabs updates live OkHttp WebSocket `REQ` filters (`kinds: [35129, 15129]` for Napplets; `kinds: [35128, 15128]` for Nsites) merged with curated featured applets so discovery works even on slow relays. Tapping **Install** verifies manifest structure, caches entry metadata in Room, and pins it to the Home Launcher.
  - **Applet Runtime & Signer Prompt**: Incoming `postMessage` envelopes (`{ id, type: "signer.signEvent" | "relay.req" | "storage.set" ... }`) or `window.nostr` calls suspend on a `CompletableDeferred` channel. If no `ALLOW_ALWAYS` policy matches, `PendingSignRequest` triggers the native Signer Bottom Sheet; user approval signs the payload with the active vault key and resolves the exact message ID back to the WebView.
  - **Creator Studio & Self-Publish Engine**: Editing files updates an in-memory/Room draft with instant live preview. Tapping **Publish** hashes each file (`SHA-256`), mints short-lived Blossom `kind 24242` upload tokens (`["t", "upload"]`, `["x", sha256]`, `["expiration", ...]`), uploads blobs via `PUT /upload`, constructs the NIP-5A (`["path", "/index.html", sha256]`) or NIP-5D manifest event, signs it, broadcasts to all active relays, and returns verifiable gateway links. **Self-Publish Shell** generates a standalone single-page HTML/CSS/JS launcher bundle containing the user's public pinned catalog and a browser-side NIP-5D host runtime, then runs the exact same Blossom + NIP-5A `nsite` publishing pipeline.
