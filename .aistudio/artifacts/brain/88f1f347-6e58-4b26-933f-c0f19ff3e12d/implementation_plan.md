# SovereignShell — Deepink UI & NIP-5A Architecture Plan

## 1. Core Principle: Hide the Dev Plumbing, Foreground the Experience

The 4 **Deepink / Deepink Arcade** reference mockups make the core problem clear: **protocol plumbing must work invisibly in the background without projecting raw developer internals onto every card and screen.**

### What Changes Immediately in the UI
1. **Zero "Dev Projection" on Cards & Headers**:
   - Remove `Kind 35128`, `d:slug`, `sha256:8f3a…`, `NIP-5A • NIP-07`, and raw Blossom URLs from Lobby cards, Discovery cards, and top bars.
   - Cards show **bold artwork/glyphs, human titles, `@creator` attribution, status pills (`LIVE`, `PINNED`, `×3 EDITIONS`), and a bold Deep Pink CTA (`LAUNCH >>>`)**.
   - Technical details (hashes, signed manifest JSON, Blossom mirrors, relay receipts) live inside an optional **"Under the Hood / Manifest Specs"** bottom sheet only when the user asks to inspect them.

2. **Full-Bleed Nsite / Game Runtime with Floating ID Medallion (`AppletRuntimeScreen`)**:
   - **Remove the bulky top developer toolbar** when an Nsite (like *2048 Link* or *Moji Survivor*) is running.
   - The WebView runs **100% full-bleed across the screen**, with a single **Floating Avatar / `≠` Medallion Button (`FAB WRAPPER / SETTINGS ID`)** in the top-left corner—matching the *Deepink Arcade* gameplay screen.
   - Tapping the floating medallion opens a compact **HUD Overlay Sheet** with:
     - **Exit to Lobby**
     - **Fork & Edit Site** (opens the site's verified files in Studio)
     - **Versions** (switch between signed releases)
     - **Signer Permission** (toggle `window.nostr` for this site)
     - **Open in Browser** / **Inspect Raw Manifest**

3. **Deepink Visual Language & Navigation (`MainActivity`, `LauncherHomeScreen`, `DiscoveryFeedScreen`, `CreatorStudioScreen`, `RelayManagementScreen`)**:
   - **Palette**: Pitch Black (`#000000`), Deep Pink (`#FF1493`), Distressed Bone Cream (`#E8DCC8`) for big headers, taped labels, and high-contrast highlight cards, and Dark Charcoal (`#141414`) card surfaces.
   - **Bottom Bar with Raised Glowing `≠` Center FAB**:
     - `Lobby` (Saved & Pinned Nsites + Spotlight Carousel + Direct URL/Address Launch)
     - `Explore` (`CHAOS FEED` — Curated & Live Nsites with search)
     - **Center Glowing `≠` FAB**: Opens **Launch / Studio** (Create, Import HTML / ZIP / Folder, or Fork an existing Nsite)
     - `Node` (`NODE ACTIVE` — Adaptive Relay + Blossom Engine with live pink waveform graph & 1-tap `ENGAGE` sync/probe)
     - `Identity` (`RISE UP` — Nostr ID switcher, Key Vault, NIP-55 / NIP-46 Signers & Profile)
   - **Left Slide-Out Drawer (`☰`)**: Quick access to Notes, Saved Studio Projects, Wallpaper/Theme, and Signer Audit Log.

---

## 2. Backend & Protocol Engine (Already Built & Preserved)

All 4 required core systems remain 100% real under the hood:
1. **Adaptive Relay + Blossom Engine (`AdaptiveNetworkEngine.kt`, `RelayPoolClient.kt`, `BlossomClient.kt`)**:
   - Capability probing (NIP-11, BUD-01/02), health scoring with 2-minute half-life failure decay, NIP-42 auth retry, 4-stage verification (`CONNECTED` → `ACCEPTED` → `PERSISTED` → `VERIFIED_RETRIEVAL`), and Blossom backup mirroring.
2. **Shell + `npub` + Signer (`ExternalSignerManager.kt`, `KeyVaultManager.kt`)**:
   - Hardware AES-256-GCM local vault, **NIP-55 Android Signer Intents (`nostrsigner:`)**, **NIP-46 Remote Bunkers (`bunker://`)**, read-only `npub`, and per-site signing approval prompts. Private keys never touch WebView JS.
3. **Nsite Launcher & Virtual-Origin WebView (`NativeSandboxRenderer.kt`, `NsiteAddressResolver.kt`)**:
   - Resolves `nsite.lol` URLs (including 50-char Base36 pubkey subdomains), `naddr1…`, `npub1…`, and NIP-05; verifies Blossom SHA-256 blobs; serves multi-file sites from `https://app-<hash>.nsite.local/` so HTML5 Canvas, ES modules, and relative assets run natively.
4. **Nsite Editor, Forker & Publisher (`NsiteProjectRepository.kt`, `CreatorStudioScreen.kt`)**:
   - Import **Single HTML**, **ZIP archive**, **Folder**, or **Fork any published Nsite**; edit files in a multi-file workspace; preview in the real WebView; publish & update signed NIP-5A manifests under the user's active identity without ever impersonating the original publisher.

---

## 3. Execution Steps Upon Approval

1. **Update `AppletRuntimeScreen.kt`**: Replace the top dev bar with the full-bleed WebView + top-left **Floating Avatar / `≠` Medallion (`FAB Wrapper / Settings ID`)** and HUD overlay sheet.
2. **Update `ShellViewModel.kt` & `MainActivity.kt`**: Wire the 5-slot Deepink bottom bar (with the center glowing `≠` Launch/Studio FAB), left slide-out drawer, NIP-55 intent launcher, and multi-file Studio state.
3. **Restyle `LauncherHomeScreen.kt`, `DiscoveryFeedScreen.kt`, `CreatorStudioScreen.kt`, `RelayManagementScreen.kt`, and `SignerAndSettingsSheets.kt`**:
   - Apply the **Deepink Arcade / Chaos Feed / Node Active / Launch** layout and typography (Bone Cream headers, taped labels, `PLAY / LAUNCH >>>` buttons, pink waveform telemetry on the Node screen, and zero uninvited dev-plumbing clutter).
4. **Compile & Run End-to-End Robolectric Integration Tests (`SovereignShellIntegrationTest.kt`)**:
   - Verify build and run all unit + integration tests covering interactive Nsite launch (2048 Link), HTML/ZIP/Folder import & publish, multi-file Fork + edit + republish, version updates, error recovery, and restart persistence.
