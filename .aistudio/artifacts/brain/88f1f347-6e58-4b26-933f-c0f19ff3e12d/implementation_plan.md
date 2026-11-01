# SovereignShell — Curated App Gallery & Clone-Stacking Storefront

Transform SovereignShell's Discovery experience from a flat chronological stream into a **Home-screen-matched Curated App Gallery & Storefront** that automatically stacks clones, forks, and versions of NIP-5D Napplets and NIP-5A Nsites into layered tiles—while preserving a one-tap **Live Relay Feed** mode for real-time protocol power users.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The decisions below incorporate all preferences confirmed during interactive planning alongside existing SovereignShell capabilities (Built-in Signer, Spam Bot Shield, NIP-5D/NIP-5A runtime, and Creator Studio).

- **Confirmed Decision 1 — Gallery Default + Optional Live Feed**: Opening Search / Discovery launches the **Curated Gallery** by default, using the exact same custom wallpaper backdrop and **Icon Grid / Specimen List** layout toggle as the Home Screen, with a header toggle to switch into the chronological **Live Feed** at any time.
- **Confirmed Decision 2 — Multi-Signal Clone & Version Stacking with Picker Sheet**: Variants, forks, and re-uploads sharing a Blossom `SHA-256` bundle hash, canonical `d-tag` slug, or normalized title stem are collapsed into a single **layered stack tile/card** (`×N` badge) that opens a **Version & Clone Picker Bottom Sheet** to inspect authors, compare hashes, install, or launch any specific variant.
- **Confirmed Decision 3 — Featured Picks + Categories + NIP-51 Curation Lists**: Gallery curation combines built-in **Featured Picks**, smart **Category Chips** (*All*, *Featured*, *NIP-51 Curated*, *Napplets*, *Nsites*, *Social*, *Tools*, *Games & Art*, *Zines*), and live **NIP-51 (`Kind 30003` / `30001`) App Bookmark Lists** that users can both browse from relays and sign/publish with their own Built-in Signer.

---

### 1. Overview & Core Concept

- **What It Does**: Replaces the single-mode discovery feed with a cohesive Nostr App Store & Gallery that mirrors the Home Launcher's spatial grid/list ergonomics, groups duplicate clones and iterative versions into clean visual stacks, surfaces high-signal apps through curated shelves and NIP-51 lists, and keeps a toggleable raw relay stream one tap away.
- **Target Audience / Persona**: Sovereign Nostr builders and everyday users who want a clean, zero-clutter app store to browse, verify, pin, fork, and curate decentralized Napplets and Nsites without wading through duplicate forks or relay noise.
- **Key Value**:
  - **Visual & Spatial Continuity**: Discovery looks and behaves like an extension of the Home Launcher (same custom wallpaper + scrim, same 4/6-column Icon Grid vs. BreBre Specimen List toggle).
  - **Zero Clone Clutter**: Dozens of forks, re-deployments, or mirror uploads collapse into one tactile stacked icon with transparent provenance inspection.
  - **Decentralized Curation**: Native NIP-51 list ingestion and one-tap cryptographic bookmarking turn every user into a sovereign gallery curator.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Enter Curated Gallery from Home**: Tapping the Home search bar opens the Curated Gallery over the user's active wallpaper (`Cyber-Zine`, `BreBre Neon Orbs`, `Agent Circuit`, or custom photo).
  2. **Browse Featured Shelf & Category Grid/List**:
     - At the top of *Curated Gallery* mode (when no search query is active), a compact horizontal **Featured & NIP-51 Spotlight** strip highlights standout Napplets and Nsites.
     - Horizontal **Filter Chips** (*All*, *Featured*, *NIP-51 Lists*, *Napplets*, *Nsites*, *Social*, *Tools*, *Games & Art*, *Zines*) filter the main **Home-matched Icon Grid** (or **Description List**, toggled via the same top-bar Grid/List button used on Home).
  3. **Inspect Stacked Clones & Versions**:
     - Apps with a single edition open a sleek **App Store Sheet** (showing description, protocol badge, Blossom hash, `Install / Pin`, `Open`, `Curate to NIP-51`, and `Fork to Studio`).
     - Apps with `2+` clones, forks, or versions display **offset stacked backing plates** behind their 62dp launcher tile (or Specimen card) plus a neon `×N` stack counter pill. Tapping opens the **Version & Clone Picker Sheet**, listing all variants ranked by trust/freshness, flagging `Identical SHA-256 Clone` vs. `Modified Fork`, and allowing per-edition `Pin`, `Launch`, or `Fork`.
  4. **Curate & Publish NIP-51 App Lists**: Tapping the star/bookmark button on any app or variant adds it to the active identity's curated list and optionally signs & broadcasts a `Kind 30003` (`d:napplets`) NIP-51 Bookmark Set to connected relays via the Built-in Signer.
  5. **Switch to Optional Live Feed**: Tapping the `Gallery | Live Feed` pill in the top bar cross-fades to the chronological relay stream (showing individual unstacked manifest events as they arrive, protected by the toggleable **Spam Shield**).

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: **BreBre Neon Dark × Punk Cyber-Zine Storefront**—matching the Home Screen's exact glassmorphic launcher aesthetic rather than a plain black utility list.
  - *Color Palette & Mood*:
    - Backdrop: User's active `LauncherWallpaperLayer` + adjustable dark scrim (`#000000` at `0.45–0.75` alpha for crisp storefront legibility).
    - Primary Surfaces: `BreGray850` (`#1A1A1A` at `94%` alpha) tiles and Specimen cards with `BrePinkDark2` (`#9A0957`) borders for NIP-5D Napplets and `ZineBoneCream` (`#E8DCC8`) borders for NIP-5A Nsites.
    - Accents & Signals: `BreDeepPink` (`#FF1493`) active pills and `×N` stack badges, `BreLightPink` (`#FFB6D9`) secondary labels, and `CyberMint` (`#2EE59D`) `Pinned` / `Spam Shield` verification indicators.
  - *Typography & Hierarchy*:
    - Bold condensed single-line tile titles (`labelLarge`) and Specimen card headers (`titleSmall`), paired with non-wrapping `labelSmall` protocol & version badges (`NIP-5D`, `NSITE`, `×3`).
  - *Component Styling & Layout*:
    - **Adaptive Grid Parity**: Uses `BoxWithConstraints` matching `LauncherHomeScreen` (`4` columns under `600.dp`, `6` columns on tablets/foldables) in `ICON_GRID` mode, and compact Specimen cards in `DESCRIPTION_LIST` mode.
    - **Tactile Card Stacking**: Multi-version tiles render two subtle offset shadow frames (`+3.dp` and `+6.dp` top-end offset) behind the primary 62dp icon squircle so stacked editions are immediately recognizable at a glance.

- **Interactive Feedback & Motion**:
  - Spring-driven `AnimatedContent` transitions (`< 280ms`) when toggling between **Curated Gallery** and **Live Feed**, or between **Icon Grid** and **Description List**.
  - Interactive bottom sheet transitions for the **Storefront & Version Picker Sheet**, with instant optimistic `Pinned` badge updates and floating status banners when signing NIP-51 curation events.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1 — Shared Launcher Layout Engine Across Home & Gallery**
  - *Chosen Approach*: Render the Curated Gallery over `LauncherWallpaperLayer` using the same responsive 4/6-column Icon Grid and Specimen Description List modes as `LauncherHomeScreen`, with a persistent installed/pinned badge on tiles already on the user's Home screen.
  - *Why*: Eliminates visual jarring between Home and Discovery and directly fulfills the Home-screen layout parity goal while keeping touch targets >= `48.dp`.
  - *Alternatives Considered*: Separate multi-column store cards unrelated to Home tiles—rejected because it breaks spatial consistency with the Home launcher.

- **Decision 2 — Deterministic Union-Find Multi-Signal Clone & Version Stacker**
  - *Chosen Approach*: Group apps within the same protocol class (`Napplet` vs. `Nsite`) using a union-find pass across three signals: (1) identical non-empty `indexSha256` Blossom hash, (2) matching normalized `d-tag` slug (excluding generic `"root"`), and (3) matching normalized canonical title stem (stripping `"Remix"`, `"Fork"`, and version suffixes like `"v2"`).
  - *Why*: Catches exact byte-for-byte re-uploads by different `npub`s *and* intentional forks/new versions of the same app, while never falsely merging unrelated `"root"` Nsites.
  - *Alternatives Considered*: Grouping by `d-tag` alone—misses `"Remix"` forks with `-fork` slugs and misses byte-identical clones published under different `d-tag`s.

- **Decision 3 — Hybrid Curation (Featured + Heuristic Quality + NIP-51 Kind 30003/30001)**
  - *Chosen Approach*: Combine built-in Featured templates/apps, automatic category tagging, and real Nostr NIP-51 (`Kind 30003` Bookmark Sets & `Kind 30001` Categorized Lists referencing `35129:...` / `35128:...` `a`-tags) both fetched from relays and authored locally via the Built-in Signer.
  - *Why*: Guarantees the Curated Gallery feels rich and organized immediately on first launch while embracing authentic, decentralized Nostr curation standards.
  - *Alternatives Considered*: Hardcoded static lists only—rejected because users could not curate or sync real Nostr app lists across relays.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                          RelayPoolClient (WebSockets)                     │
│  • Split REQ Filters:                                                     │
│    1. NIP-5D Napplets (35129, 15129, 5129)                                │
│    2. NIP-5A Nsites   (35128, 15128)                                      │
│    3. NIP-51 Curation (30003, 30001 with #a / d="napplets"|"nsites")      │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │ Incoming NostrEvent Stream
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                  ShellRepository & Local Room Persistence                 │
│  ┌─────────────────────────┐  ┌────────────────────┐  ┌─────────────────┐ │
│  │ NsiteSpamFilter         │  │ InstalledApplets   │  │ CuratedBookmarks│ │
│  │ • 5 Bot Fingerprints    │─▶│ Table (Catalog +   │  │ (NIP-51 a-tag   │ │
│  │ • Cross-Pubkey Clusters │  │ Pinned Flag)       │  │ Coordinates)    │ │
│  └─────────────────────────┘  └─────────┬──────────┘  └────────┬────────┘ │
└─────────────────────────────────────────┼──────────────────────┼──────────┘
                                          │                      │
                                          ▼                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                     CatalogStacker & Gallery Engine                       │
│  • Union-Find Clustering: SHA-256 Blob Hash ∪ Canonical dTag ∪ Title Stem │
│  • Representative Ranking: Pinned > NIP-51 Curated > Local > Completeness │
│  • Category & Curation Classifier: Featured | NIP-51 | Social | Tools ... │
└─────────────────────────────────────────┬─────────────────────────────────┘
                                          │ StateFlow<GalleryUiModel>
                                          ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                DiscoveryFeedScreen (Storefront + Live Feed)               │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ Top Bar: Back • Search Pill • Grid/List Toggle • Gallery|Feed • +Pub│  │
│  └──────────────────┬───────────────────────────────────┬──────────────┘  │
│                     │ (Gallery Mode - Default)          │ (Live Feed Mode)│
│                     ▼                                   ▼                 │
│  ┌──────────────────────────────────────┐  ┌───────────────────────────┐  │
│  │ Home-Matched Wallpaper + Scrim       │  │ Chronological Relay Stream│  │
│  │ • Featured & NIP-51 Spotlight Strip  │  │ • Unstacked Live Cards    │  │
│  │ • Category Filter Chips + Spam Shield│  │ • Spam Shield Counter     │  │
│  │ • Stacked 4/6-Col Icon Grid OR       │  │ • Instant Install / Fork  │  │
│  │   Stacked BreBre Specimen List       │  └───────────────────────────┘  │
│  └──────────────────┬───────────────────┘                                 │
│                     │ Tap Stacked Tile / Card                             │
│                     ▼                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │          App Store & Stacked Version Picker Bottom Sheet            │  │
│  │  • Primary Summary + "Curate to NIP-51 (Sign Kind 30003)" Toggle    │  │
│  │  • Variant List: Publisher npub • Kind/dTag • Hash Diff Badge       │  │
│  │  • Per-Edition Actions: Install/Pin • Launch • Fork to Studio       │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - **`CuratedBookmarkEntity` (Room)**: Stores curated coordinate keys (`coordinate = "$kind:$authorPubkey:$dTag"`), source (`LOCAL_USER` vs. `REMOTE_NIP51`), curator `npub`, list `dTag`, and timestamp so NIP-51 curations persist offline and survive restarts.
  - **`GalleryCategory` Enum**: `ALL`, `FEATURED`, `NIP51_CURATED`, `NAPPLETS`, `NSITES`, `SOCIAL`, `TOOLS`, `GAMES_ART`, `ZINES`.
  - **`DiscoveryDisplayMode` Enum**: `CURATED_GALLERY` (default, stacked) and `LIVE_FEED` (optional unstacked chronological stream).
  - **`AppletVariantRelation` Enum**: `PRIMARY`, `IDENTICAL_CLONE` (same `indexSha256`), `VERSION_OR_FORK` (different `indexSha256`, shared slug/stem).
  - **`StackedAppletGroup`**: Holds `groupId`, `primary: InstalledAppletEntity`, `variants: List<StackedVariantItem>`, `isCuratedNip51: Boolean`, `isFeatured: Boolean`, `categories: Set<GalleryCategory>`, and `anyInstalled: Boolean`.

- **Interactive Component & State Mapping**:
  - **Mode Switch (`Gallery | Live Feed`)**: Mutates `discoveryDisplayMode` in `ShellViewModel` (persisted in `LauncherPreferencesRepository` so user preference sticks) and cross-fades between the stacked Gallery canvas and unstacked chronological Feed.
  - **Layout Toggle (`Grid | List`)**: Reuses `LauncherViewMode` (`ICON_GRID` vs. `DESCRIPTION_LIST`) so users switch seamlessly between 62dp stacked launcher icons and detailed Specimen cards.
  - **Category & Search Filtering**: Reactive `combine` pipeline filters `StackedAppletGroup` items by active `GalleryCategory` chip and search query (matching across *any* variant's title, `d-tag`, description, or publisher `npub` in the stack!).
  - **Tile / Card Tap -> Version Picker Sheet**: Sets `selectedStackGroup` state, opening a Material 3 `ModalBottomSheet` displaying the primary manifest header, NIP-51 star/publish button, and every stacked clone/fork/version with individual `Install / Unpin`, `Open`, and `Fork` buttons.
  - **Curate & Publish NIP-51 List**: Toggles local bookmark membership immediately and offers a one-tap `"Sign & Sync NIP-51 List"` action that routes through the Built-in Signer to sign and broadcast a `Kind 30003` event (`["d", "napplets"]`, plus `["a", "<kind>:<pubkey>:<dTag>"]` tags for all starred items) to connected relays.
