package com.example.data

import com.example.nostr.NostrCrypto
import com.example.nostr.NostrKinds

enum class GraphicTemplatePreset(
    val label: String,
    val subtitle: String,
    val badge: String
) {
    BREBRE_SPECIMEN(
        label = "BreBre UI Specimen",
        subtitle = "Pitch black #000000, #FF1493 neon pills & #9A0957 glowing borders",
        badge = "NEON"
    ),
    PRETEND_ZINE(
        label = "☆ Pretend Internet Zine ☆",
        subtitle = "Punk star header strip & bone-cream MICRO-SERIES world guide",
        badge = "ZINE"
    ),
    AGENT_ZONE(
        label = "Agent ≠ Zone Deck",
        subtitle = "Circular stamped identity medallion, circuit traces & 3-col spec bar",
        badge = "≠ ZONE"
    ),
    PUTER_CRT(
        label = "Puter CRT Terminal",
        subtitle = "Retro CRT pixel-eye monitor frame, scanlines & cyber-sticker vibe",
        badge = "CRT"
    )
}

object StarterCatalog {

    fun templateDraftFor(preset: GraphicTemplatePreset): Triple<String, String, String> {
        return when (preset) {
            GraphicTemplatePreset.BREBRE_SPECIMEN -> Triple(
                """
<div class="orb-top"></div>
<div class="card">
  <div class="eyebrow">BREBRE UI STANDARDS • NIP-5D</div>
  <h2>Bold. Simple. Expressive.</h2>
  <p>Sandboxed Nostr applet styled with #000000 Black, #1A1A1A Gray 850, and #FF1493 Deep Pink.</p>
  <button id="helloSign">Sign Note →</button>
  <button id="loadPk" class="secondary">Read Pubkey</button>
  <div id="resultBox" class="mono" style="margin-top:12px;"></div>
</div>
                """.trimIndent(),
                COMMON_APPLET_CSS.trimIndent(),
                DEFAULT_TEMPLATE_JS
            )

            GraphicTemplatePreset.PRETEND_ZINE -> Triple(
                """
<div class="zine-banner">
  <div class="zine-title">☆ PRETEND INTERNET ☆</div>
  <div class="zine-sub">M I C R O - S E R I E S &nbsp; W O R L D &nbsp; G U I D E</div>
</div>
<div class="card zine-card">
  <h2>Issue #01 — Sovereign Zine</h2>
  <p>Self-published to Blossom &amp; Nostr relays with zero gatekeepers.</p>
  <button id="helloSign">Stamp &amp; Sign Issue →</button>
  <div id="resultBox" class="mono" style="margin-top:12px;"></div>
</div>
                """.trimIndent(),
                COMMON_APPLET_CSS.trimIndent() + "\n" + """
.zine-banner { background:#000; border:2px solid #FF1493; padding:14px; text-align:center; margin-bottom:16px; box-shadow: 0 0 24px rgba(255,20,147,0.22); }
.zine-title { color:#FF1493; font-size:22px; font-weight:900; letter-spacing:0.06em; }
.zine-sub { color:#E8DCC8; font-size:11px; letter-spacing:0.28em; margin-top:6px; border-top:1px solid #5B0633; padding-top:6px; }
.zine-card { border-style: dashed; border-color: #E8DCC8; }
                """.trimIndent(),
                DEFAULT_TEMPLATE_JS
            )

            GraphicTemplatePreset.AGENT_ZONE -> Triple(
                """
<div class="agent-hero">
  <div class="neq-ring">≠</div>
  <div class="medallion">AGENT<br/>ZONE</div>
  <div class="tracked">M O V E &nbsp; D I F F E R E N T . &nbsp; I M P A C T &nbsp; E V E R Y T H I N G .</div>
</div>
<div class="card">
  <h2>Customizable Sovereign Deck</h2>
  <p>Built to stand out. Built to scale across Nostr relays.</p>
  <button id="helloSign">Authorize Agent Proof →</button>
  <div id="resultBox" class="mono" style="margin-top:12px;"></div>
</div>
                """.trimIndent(),
                COMMON_APPLET_CSS.trimIndent() + "\n" + """
.agent-hero { position:relative; text-align:center; padding:22px 14px; border:1px solid #9A0957; border-radius:20px; margin-bottom:16px; background:radial-gradient(circle, #2a0619 0%, #000000 72%); }
.neq-ring { width:92px; height:92px; margin:0 auto; border-radius:50%; border:3px solid #CD0D72; display:flex; align-items:center; justify-content:center; font-size:44px; font-weight:900; color:#FF1493; }
.medallion { display:inline-block; margin-top:-18px; background:#E8DCC8; color:#111; font-weight:900; font-size:10px; padding:8px 12px; border-radius:999px; border:2px dashed #111; letter-spacing:0.1em; }
.tracked { margin-top:12px; color:#E8DCC8; font-size:10px; letter-spacing:0.22em; }
                """.trimIndent(),
                DEFAULT_TEMPLATE_JS
            )

            GraphicTemplatePreset.PUTER_CRT -> Triple(
                """
<div class="crt-shell">
  <div class="crt-top"><span>PUTER // OS</span><span>☆ ♥ ××</span></div>
  <div class="crt-screen">
    <div class="eyes"><span></span><span></span></div>
    <div id="resultBox" class="mono" style="text-align:center;margin-top:14px;">READY FOR NAP SIGNAL_</div>
  </div>
  <div style="margin-top:12px;text-align:center;">
    <button id="helloSign">Transmit Note →</button>
    <button id="loadPk" class="secondary">Ping Identity</button>
  </div>
</div>
                """.trimIndent(),
                COMMON_APPLET_CSS.trimIndent() + "\n" + """
.crt-shell { background:#F0E6D2; color:#111; border:4px solid #FF1493; border-radius:24px; padding:14px; box-shadow:0 0 28px rgba(255,20,147,0.35); }
.crt-top { display:flex; justify-content:space-between; font-weight:900; font-size:13px; color:#111; margin-bottom:8px; }
.crt-screen { background:repeating-linear-gradient(0deg, #090909 0px, #090909 3px, #141414 4px); border:3px solid #111; border-radius:16px; padding:26px 14px; }
.eyes { display:flex; justify-content:center; gap:26px; }
.eyes span { width:26px; height:32px; background:#FFF8E7; box-shadow:0 0 12px #FF1493; display:inline-block; }
                """.trimIndent(),
                DEFAULT_TEMPLATE_JS
            )
        }
    }

    private val DEFAULT_TEMPLATE_JS = """
function nap(type, extra = {}) {
  return new Promise((resolve, reject) => {
    const id = 'tpl_' + Math.random().toString(36).slice(2, 9);
    window.addEventListener('message', function h(ev) {
      let d = ev.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e){ return; } }
      if (d && d.id === id && d.reply) { window.removeEventListener('message', h); d.error ? reject(d.error) : resolve(d.result); }
    });
    window.postMessage({ id, type, ...extra }, '*');
  });
}
const out = document.getElementById('resultBox');
const signBtn = document.getElementById('helloSign');
if (signBtn) signBtn.onclick = async () => {
  try {
    const ev = await nap('signer.signEvent', { event: { kind: 1, content: 'Published from SovereignShell custom graphic napplet! ☆ #nostr', tags: [] } });
    out.textContent = 'SIGNED ID: ' + ev.id.slice(0, 18) + '…';
  } catch (e) { out.textContent = 'DENIED: ' + e; }
};
const pkBtn = document.getElementById('loadPk');
if (pkBtn) pkBtn.onclick = async () => {
  try {
    const pk = await nap('signer.getPublicKey');
    out.textContent = 'PUBKEY: ' + pk.slice(0, 20) + '…';
  } catch (e) { out.textContent = 'ERR: ' + e; }
};
    """.trimIndent()

    fun defaultCatalog(hostPubkeyHex: String, hostNpub: String): List<InstalledAppletEntity> = listOf(
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:pulse-notes",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "pulse-notes",
            title = "PulseNotes",
            description = "Sandboxed NIP-5D micro-note client using NAP-RELAY and NAP-SIGNER without raw key exposure.",
            iconGlyph = "⚡",
            accentHex = "#FF1493",
            isNsite = false,
            enableNip07 = false,
            isInstalled = true,
            pinOrder = 1,
            inlineHtml = PULSE_NOTES_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = PULSE_NOTES_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:puter-crt",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "puter-crt",
            title = "Puter CRT",
            description = "Retro cyber-sticker CRT terminal napplet transmitting signed Nostr pings via NAP-SIGNER.",
            iconGlyph = "📺",
            accentHex = "#FFB6D9",
            isNsite = false,
            enableNip07 = false,
            isInstalled = true,
            pinOrder = 2,
            inlineHtml = templateDraftFor(GraphicTemplatePreset.PUTER_CRT).first,
            inlineCss = templateDraftFor(GraphicTemplatePreset.PUTER_CRT).second,
            inlineJs = DEFAULT_TEMPLATE_JS
        ),
        InstalledAppletEntity(
            appletId = "35128:$hostPubkeyHex:agent-zone",
            kind = NostrKinds.NSITE_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "agent-zone",
            title = "Agent ≠ Zone",
            description = "Customizable sovereign identity nsite with stamped medallion proof and NIP-07 verification.",
            iconGlyph = "≠",
            accentHex = "#E8DCC8",
            isNsite = true,
            enableNip07 = true,
            isInstalled = true,
            pinOrder = 3,
            inlineHtml = IDENTITY_NSITE_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = IDENTITY_NSITE_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:cipher-pad",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "cipher-pad",
            title = "CipherPad",
            description = "Encrypted personal scratchpad powered by isolated NAP-STORAGE and NIP-04 self-encryption.",
            iconGlyph = "🔐",
            accentHex = "#CD0D72",
            isNsite = false,
            enableNip07 = false,
            isInstalled = true,
            pinOrder = 4,
            inlineHtml = CIPHER_PAD_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = CIPHER_PAD_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:relay-sonar",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "relay-sonar",
            title = "Relay Sonar",
            description = "Inspect recent Nostr event kinds across your shell relay pool via NAP-RELAY queries.",
            iconGlyph = "📡",
            accentHex = "#FF1493",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            pinOrder = 5,
            inlineHtml = RELAY_SONAR_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = RELAY_SONAR_JS
        ),
        InstalledAppletEntity(
            appletId = "35128:$hostPubkeyHex:pretend-zine",
            kind = NostrKinds.NSITE_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "pretend-zine",
            title = "Pretend Web Zine",
            description = "☆ Micro-Series World Guide ☆ static nsite hosted via Blossom content-addressed SHA-256 blobs.",
            iconGlyph = "☆",
            accentHex = "#FF1493",
            isNsite = true,
            enableNip07 = false,
            isInstalled = false,
            pinOrder = 6,
            inlineHtml = templateDraftFor(GraphicTemplatePreset.PRETEND_ZINE).first,
            inlineCss = templateDraftFor(GraphicTemplatePreset.PRETEND_ZINE).second,
            inlineJs = DEFAULT_TEMPLATE_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:pulse-notes-classic",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "pulse-notes-classic",
            title = "PulseNotes v1",
            description = "Compact minimalist edition of PulseNotes for rapid single-tap Kind 1 dispatches.",
            iconGlyph = "⚡",
            accentHex = "#CD0D72",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            pinOrder = 7,
            updatedAt = System.currentTimeMillis() - 86_400_000L,
            inlineHtml = PULSE_NOTES_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = PULSE_NOTES_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:zap-calc",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "zap-calc",
            title = "Sats & Hash Lab",
            description = "Offline Nostr event commitment & sats split calculator with NAP-STORAGE presets.",
            iconGlyph = "🧮",
            accentHex = "#FF1493",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            pinOrder = 8,
            inlineHtml = SATS_LAB_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = SATS_LAB_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:nostr-polls",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "nostr-polls",
            title = "Zine Polls",
            description = "Publish signed Nostr community polls and tally live responses via NAP-SIGNER.",
            iconGlyph = "📊",
            accentHex = "#FFB6D9",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            pinOrder = 9,
            inlineHtml = ZINE_POLLS_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = ZINE_POLLS_JS
        ),
        InstalledAppletEntity(
            appletId = "35129:$hostPubkeyHex:pixel-synth",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = hostPubkeyHex,
            authorNpub = hostNpub,
            dTag = "pixel-synth",
            title = "Pixel Board",
            description = "Create cyber-zine ASCII/pixel stamps and broadcast signed art notes to relays.",
            iconGlyph = "🎨",
            accentHex = "#FF1493",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            pinOrder = 10,
            inlineHtml = PIXEL_BOARD_HTML,
            inlineCss = COMMON_APPLET_CSS,
            inlineJs = PIXEL_BOARD_JS
        )
    )

    data class GeneratedShellBundle(
        val indexHtml: String,
        val styleCss: String,
        val appJs: String
    )

    fun buildSelfPublishedWebShell(
        account: VaultAccountEntity,
        relays: List<String>,
        pinnedApplets: List<InstalledAppletEntity>,
        preset: GraphicTemplatePreset = GraphicTemplatePreset.BREBRE_SPECIMEN
    ): GeneratedShellBundle {
        val safeName = account.displayName.ifBlank { "Sovereign Nostr User" }
            .replace("<", "&lt;").replace(">", "&gt;")
        val safeBio = account.bio.ifBlank { "Move different. Self-published from SovereignShell." }
            .replace("<", "&lt;").replace(">", "&gt;")
        val safeGlyph = account.badgeGlyph.ifBlank { "≠" }
        val relaysJsonArray = relays.joinToString(",") { "\"$it\"" }
        val cardsJson = pinnedApplets.joinToString(",") { app ->
            val t = app.title.replace("\"", "\\\"")
            val d = app.description.replace("\"", "\\\"")
            """{"id":"${app.dTag}","title":"$t","desc":"$d","icon":"${app.iconGlyph}","nsite":${app.isNsite},"sha":"${NostrCrypto.sha256Hex(app.inlineHtml).take(12)}"}"""
        }

        val zineHeader = if (preset == GraphicTemplatePreset.PRETEND_ZINE) {
            """<div class="zine-strip">☆ $safeName • M I C R O - S E R I E S &nbsp; S H E L L ☆</div>"""
        } else ""

        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$safeName — SovereignShell Nsite</title>
  <link rel="stylesheet" href="/style.css" />
</head>
<body>
  <div class="glow-tr"></div>
  <div class="glow-bl"></div>
  <div class="shell-wrap">
    $zineHeader
    <header class="topbar">
      <div style="display:flex;gap:16px;align-items:center;">
        <div class="agent-medallion">$safeGlyph</div>
        <div>
          <span class="badge">NIP-5A + NIP-5D • ${preset.label.uppercase()}</span>
          <h1>$safeName</h1>
          <p class="bio">$safeBio</p>
          <code class="npub">${account.npub}</code>
        </div>
      </div>
      <button id="toggleViewBtn" class="secondary-btn">Grid / List</button>
    </header>
    <div class="circuit-rule">●────── M O V E &nbsp; D I F F E R E N T • S O V E R E I G N &nbsp; W E B ──────●</div>
    <section class="composer-box">
      <h3>Broadcast NIP-01 Note</h3>
      <div class="row">
        <input id="noteInput" placeholder="Write a signed Nostr note from your self-published shell…" />
        <button id="sendNoteBtn" class="primary-btn">Publish →</button>
      </div>
      <div id="statusMsg" class="status"></div>
    </section>
    <h2>Pinned Napplets &amp; Nsites</h2>
    <div id="catalogGrid" class="grid"></div>
    <footer>Relays: ${relays.joinToString(" • ")}</footer>
  </div>
  <script src="/app.js"></script>
</body>
</html>
        """.trimIndent()

        val css = """
* { box-sizing: border-box; font-family: system-ui, -apple-system, sans-serif; }
body { margin: 0; background: #000000; color: #FFFFFF; min-height: 100vh; position: relative; overflow-x: hidden; }
.glow-tr { position: fixed; top: -120px; right: -120px; width: 340px; height: 340px; background: radial-gradient(circle, rgba(255,20,147,0.42), rgba(91,6,51,0.18), transparent 70%); pointer-events: none; }
.glow-bl { position: fixed; bottom: -140px; left: -120px; width: 340px; height: 340px; background: radial-gradient(circle, rgba(205,13,114,0.35), transparent 70%); pointer-events: none; }
.shell-wrap { max-width: 860px; margin: 0 auto; padding: 28px 20px; position: relative; z-index: 1; }
.zine-strip { background: #000; border: 2px solid #FF1493; color: #FF1493; font-weight: 900; padding: 10px 14px; text-align: center; letter-spacing: 0.14em; margin-bottom: 16px; }
.topbar { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 16px; background: #111111; border: 1px solid #9A0957; border-radius: 20px; padding: 20px; }
.agent-medallion { width: 64px; height: 64px; border-radius: 50%; background: #E8DCC8; color: #111111; border: 2px dashed #FF1493; display: flex; align-items: center; justify-content: center; font-size: 26px; font-weight: 900; flex-shrink: 0; }
.badge { font-size: 11px; letter-spacing: 0.12em; color: #FFB6D9; font-weight: 700; }
h1 { margin: 6px 0; font-size: 28px; color: #FFFFFF; }
.bio { margin: 4px 0 10px; color: #B8B8B8; font-size: 14px; }
.npub { font-size: 11px; color: #FFB6D9; word-break: break-all; }
.circuit-rule { text-align: center; color: #E8DCC8; font-size: 10px; letter-spacing: 0.2em; margin: 18px 0; opacity: 0.85; }
.primary-btn { background: #FF1493; color: #FFFFFF; border: none; border-radius: 14px; padding: 11px 18px; cursor: pointer; font-weight: 800; }
.secondary-btn { background: #000000; color: #FFFFFF; border: 1px solid #CD0D72; border-radius: 14px; padding: 10px 16px; cursor: pointer; font-weight: 700; }
.composer-box { margin: 18px 0; padding: 18px; border-radius: 18px; background: #1A1A1A; border: 1px solid #9A0957; }
.row { display: flex; gap: 10px; }
input { flex: 1; background: #000000; border: 1px solid #5E5E5E; color: #FFFFFF; border-radius: 12px; padding: 10px 14px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 14px; }
.grid.list-mode { grid-template-columns: 1fr; }
.card { background: #1A1A1A; border: 1px solid #9A0957; border-radius: 18px; padding: 16px; }
.icon-box { width: 42px; height: 42px; border-radius: 10px; background: #5B0633; border: 1px solid #FF1493; display: flex; align-items: center; justify-content: center; font-size: 20px; margin-bottom: 10px; }
.desc { color: #B8B8B8; font-size: 13px; margin-top: 6px; line-height: 1.45; }
.cta-pill { display: inline-block; margin-top: 10px; background: #CD0D72; color: #FFF; font-size: 11px; font-weight: 700; padding: 6px 12px; border-radius: 999px; }
.status { margin-top: 8px; font-size: 12px; color: #FFB6D9; }
footer { margin-top: 32px; font-size: 12px; color: #5E5E5E; }
        """.trimIndent()

        val js = """
const RELAYS = [$relaysJsonArray];
const APPS = [$cardsJson];
let listMode = false;
const grid = document.getElementById('catalogGrid');
function render() {
  grid.className = listMode ? 'grid list-mode' : 'grid';
  grid.innerHTML = APPS.map(a => `<div class="card"><div class="icon-box">${'$'}{a.icon}</div><strong>${'$'}{a.title}</strong> <small style="color:#FFB6D9">[${'$'}{a.nsite ? 'NSITE' : 'NIP-5D'}]</small><div class="desc">${'$'}{a.desc}</div><span class="cta-pill">SHA ${'$'}{a.sha.slice(0,8)} →</span></div>`).join('');
}
document.getElementById('toggleViewBtn').onclick = () => { listMode = !listMode; render(); };
document.getElementById('sendNoteBtn').onclick = async () => {
  const status = document.getElementById('statusMsg');
  const text = document.getElementById('noteInput').value.trim();
  if (!text) return;
  if (!window.nostr) { status.textContent = 'Enable NIP-07 signer or open inside SovereignShell to sign.'; return; }
  try {
    const signed = await window.nostr.signEvent({ kind: 1, created_at: Math.floor(Date.now()/1000), tags: [], content: text });
    RELAYS.forEach(r => { try { const ws = new WebSocket(r); ws.onopen = () => ws.send(JSON.stringify(['EVENT', signed])); } catch(e){} });
    status.textContent = 'Signed & broadcast event ' + signed.id.slice(0, 12) + '…';
    document.getElementById('noteInput').value = '';
  } catch (err) { status.textContent = 'Denied: ' + err; }
};
render();
        """.trimIndent()

        return GeneratedShellBundle(indexHtml = html, styleCss = css, appJs = js)
    }

    const val COMMON_APPLET_CSS = """
* { box-sizing: border-box; font-family: system-ui, -apple-system, sans-serif; }
body { margin: 0; padding: 18px; background: #000000; color: #FFFFFF; }
.orb-top { position: fixed; top: -80px; right: -80px; width: 220px; height: 220px; background: radial-gradient(circle, rgba(255,20,147,0.35), transparent 70%); pointer-events: none; }
.eyebrow { font-size: 10px; letter-spacing: 0.18em; color: #FFB6D9; font-weight: 800; margin-bottom: 6px; }
.card { background: #1A1A1A; border: 1px solid #9A0957; border-radius: 18px; padding: 16px; margin-bottom: 14px; position: relative; }
h2 { margin: 0 0 6px 0; font-size: 19px; color: #FFFFFF; }
p { margin: 0 0 12px 0; color: #B8B8B8; font-size: 13px; line-height: 1.45; }
button { background: #FF1493; color: #FFFFFF; border: none; border-radius: 12px; padding: 10px 16px; font-weight: 800; cursor: pointer; margin-right: 8px; margin-top: 6px; }
button.secondary { background: #000000; color: #FFFFFF; border: 1px solid #CD0D72; }
textarea, input { width: 100%; background: #111111; color: #FFFFFF; border: 1px solid #5E5E5E; border-radius: 12px; padding: 10px 12px; font-size: 14px; margin-bottom: 8px; }
.mono { font-family: monospace; font-size: 12px; color: #FFB6D9; word-break: break-all; }
.item { border-top: 1px solid #2b2b2b; padding: 10px 0; font-size: 13px; }
    """

    private const val PULSE_NOTES_HTML = """
<div class="orb-top"></div>
<div class="card">
  <div class="eyebrow">☆ NIP-5D SANDBOXED NAPPLET ☆</div>
  <h2>⚡ PulseNotes</h2>
  <p>Communicates strictly via <code>postMessage</code> NAP-SIGNER &amp; NAP-RELAY.</p>
  <div id="ident" class="mono">Requesting pubkey from host shell…</div>
</div>
<div class="card">
  <textarea id="noteBox" rows="3" placeholder="Compose a Kind 1 note via NAP-SIGNER…"></textarea>
  <button id="pubBtn">Sign &amp; Publish →</button>
  <button id="refreshBtn" class="secondary">Query Relay Feed</button>
  <div id="out" class="mono" style="margin-top:10px;"></div>
</div>
<div class="card">
  <h2>Recent Kind 1 Notes</h2>
  <div id="feed">Tap Query Relay Feed to load live notes.</div>
</div>
    """

    private const val PULSE_NOTES_JS = """
function napCall(type, payload = {}) {
  return new Promise((resolve, reject) => {
    const id = 'req_' + Math.random().toString(36).slice(2, 10);
    function handler(ev) {
      let data = ev.data;
      if (typeof data === 'string') { try { data = JSON.parse(data); } catch(e) { return; } }
      if (data && data.id === id && data.reply === true) {
        window.removeEventListener('message', handler);
        if (data.error) reject(data.error); else resolve(data.result);
      }
    }
    window.addEventListener('message', handler);
    window.postMessage({ id, type, ...payload }, '*');
  });
}
(async () => {
  try {
    const pk = await napCall('signer.getPublicKey');
    document.getElementById('ident').textContent = 'Active Shell Pubkey: ' + pk.slice(0, 20) + '…';
  } catch (e) { document.getElementById('ident').textContent = 'Error: ' + e; }
})();
document.getElementById('pubBtn').onclick = async () => {
  const txt = document.getElementById('noteBox').value.trim();
  if (!txt) return;
  const out = document.getElementById('out');
  out.textContent = 'Awaiting Built-in Signer approval…';
  try {
    const signed = await napCall('signer.signEvent', { event: { kind: 1, content: txt, tags: [['client', 'PulseNotes-NIP5D']] } });
    const res = await napCall('relay.publish', { event: signed });
    out.textContent = 'Published ' + signed.id.slice(0, 14) + '… (' + (res.accepted || 0) + ' relays)';
    document.getElementById('noteBox').value = '';
  } catch (err) { out.textContent = 'Signer rejected: ' + err; }
};
document.getElementById('refreshBtn').onclick = async () => {
  const feed = document.getElementById('feed');
  feed.textContent = 'Querying host relay pool…';
  try {
    const events = await napCall('relay.query', { filter: { kinds: [1], limit: 10 } });
    feed.innerHTML = (events && events.length)
      ? events.map(e => '<div class="item"><div class="mono">' + e.pubkey.slice(0,12) + '…</div>' + e.content.replace(/</g,'&lt;') + '</div>').join('')
      : 'No cached/live events yet — publish your first note above!';
  } catch (err) { feed.textContent = 'Query error: ' + err; }
};
    """

    private const val CIPHER_PAD_HTML = """
<div class="card">
  <div class="eyebrow">ISOLATED NAP-STORAGE + NIP-04</div>
  <h2>🔐 CipherPad</h2>
  <p>Encrypts memos to your Nostr public key using the built-in signer and saves ciphertext inside scoped storage.</p>
  <textarea id="secretInput" rows="3" placeholder="Enter private memo to encrypt…"></textarea>
  <button id="saveEncBtn">Encrypt &amp; Save →</button>
  <button id="loadDecBtn" class="secondary">Load &amp; Decrypt</button>
  <div id="cipherStatus" class="mono" style="margin-top:10px;"></div>
</div>
    """

    private const val CIPHER_PAD_JS = """
function napCall(type, payload = {}) {
  return new Promise((resolve, reject) => {
    const id = 'cp_' + Math.random().toString(36).slice(2, 10);
    function onMsg(ev) {
      let d = ev.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e){ return; } }
      if (d && d.id === id && d.reply) { window.removeEventListener('message', onMsg); d.error ? reject(d.error) : resolve(d.result); }
    }
    window.addEventListener('message', onMsg);
    window.postMessage({ id, type, ...payload }, '*');
  });
}
document.getElementById('saveEncBtn').onclick = async () => {
  const st = document.getElementById('cipherStatus');
  const txt = document.getElementById('secretInput').value;
  if (!txt) return;
  try {
    const pk = await napCall('signer.getPublicKey');
    const ct = await napCall('signer.nip04Encrypt', { pubkey: pk, plaintext: txt });
    await napCall('storage.set', { key: 'vault_memo', value: ct });
    st.textContent = 'Stored NIP-04 ciphertext: ' + ct.slice(0, 34) + '…';
  } catch (e) { st.textContent = 'Error: ' + e; }
};
document.getElementById('loadDecBtn').onclick = async () => {
  const st = document.getElementById('cipherStatus');
  try {
    const ct = await napCall('storage.get', { key: 'vault_memo' });
    if (!ct) { st.textContent = 'No encrypted memo stored yet.'; return; }
    const pk = await napCall('signer.getPublicKey');
    const pt = await napCall('signer.nip04Decrypt', { pubkey: pk, ciphertext: ct });
    document.getElementById('secretInput').value = pt;
    st.textContent = 'Decrypted memo via Built-in Signer!';
  } catch (e) { st.textContent = 'Error: ' + e; }
};
    """

    private const val IDENTITY_NSITE_HTML = """
<div class="card" style="text-align:center;">
  <div style="width:74px;height:74px;margin:0 auto 10px;border-radius:50%;background:#E8DCC8;color:#111;border:2px dashed #FF1493;display:flex;align-items:center;justify-content:center;font-size:30px;font-weight:900;">≠</div>
  <div class="eyebrow">AGENT ≠ ZONE • NIP-5A NSITE</div>
  <h2>Sovereign Identity Medallion</h2>
  <p>Static profile nsite using optional <code>window.nostr</code> (NIP-07) mode.</p>
  <div id="pkView" class="mono">Checking NIP-07 bridge…</div>
  <button id="proveBtn">Sign Schnorr Proof →</button>
  <div id="proofOut" class="mono" style="margin-top:10px;"></div>
</div>
    """

    private const val IDENTITY_NSITE_JS = """
(async () => {
  const el = document.getElementById('pkView');
  if (!window.nostr) { el.textContent = 'NIP-07 disabled for this nsite. Toggle NIP-07 in the top runtime bar.'; return; }
  const pk = await window.nostr.getPublicKey();
  el.textContent = 'Verified Pubkey: ' + pk;
})();
document.getElementById('proveBtn').onclick = async () => {
  const out = document.getElementById('proofOut');
  if (!window.nostr) { out.textContent = 'Enable NIP-07 in the runtime bar first.'; return; }
  try {
    const ev = await window.nostr.signEvent({ kind: 27235, created_at: Math.floor(Date.now()/1000), tags: [], content: 'Agent Zone Identity Proof' });
    out.textContent = 'Schnorr Sig: ' + ev.sig.slice(0, 36) + '…';
  } catch (e) { out.textContent = 'Rejected: ' + e; }
};
    """

    private const val RELAY_SONAR_HTML = """
<div class="card">
  <div class="eyebrow">NAP-RELAY INSPECTOR</div>
  <h2>📡 Relay Sonar</h2>
  <p>Inspect live Nostr event kinds cached and streamed by your SovereignShell relay pool.</p>
  <button id="scanBtn">Scan Latest Events →</button>
  <div id="sonarOut" style="margin-top:10px;"></div>
</div>
    """

    private const val RELAY_SONAR_JS = """
document.getElementById('scanBtn').onclick = () => {
  const id = 'sonar_' + Date.now();
  window.addEventListener('message', function h(ev) {
    let d = ev.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e){ return; } }
    if (d && d.id === id && d.reply) {
      window.removeEventListener('message', h);
      const list = d.result || [];
      document.getElementById('sonarOut').innerHTML = list.length
        ? list.map(e => '<div class="item"><span class="mono">Kind ' + e.kind + '</span> • ' + e.id.slice(0,12) + '…</div>').join('')
        : '<div class="mono">No events returned yet.</div>';
    }
  });
  window.postMessage({ id, type: 'relay.query', filter: { kinds: [0, 1, 35128, 35129], limit: 12 } }, '*');
};
    """

    private const val SATS_LAB_HTML = """
<div class="card">
  <div class="eyebrow">NIP-5D UTILITY • TOOLS</div>
  <h2>🧮 Sats &amp; Split Lab</h2>
  <p>Save value-4-value split presets into isolated <code>NAP-STORAGE</code>.</p>
  <input id="satsInput" placeholder="Enter total sats (e.g. 21000)" value="21000" />
  <button id="calcBtn">Compute 60 / 30 / 10 Split →</button>
  <div id="calcOut" class="mono" style="margin-top:10px;">Ready.</div>
</div>
    """

    private const val SATS_LAB_JS = """
document.getElementById('calcBtn').onclick = () => {
  const raw = parseInt(document.getElementById('satsInput').value || '21000', 10) || 21000;
  const summary = 'Lead: ' + Math.round(raw*0.6) + ' • Relay: ' + Math.round(raw*0.3) + ' • Shell: ' + Math.round(raw*0.1) + ' sats';
  document.getElementById('calcOut').textContent = summary;
  window.postMessage({ id: 'sl_' + Date.now(), type: 'storage.set', key: 'last_split', value: summary }, '*');
};
    """

    private const val ZINE_POLLS_HTML = """
<div class="card">
  <div class="eyebrow">SOCIAL • SIGNED POLLS</div>
  <h2>📊 Zine Quick Poll</h2>
  <p>Draft a two-option poll question and sign it via your Built-in Signer.</p>
  <input id="pollQuestion" placeholder="Best Nostr app transport?" value="Favorite sovereign web stack?" />
  <button id="publishPollBtn">Sign &amp; Broadcast Poll →</button>
  <div id="pollOut" class="mono" style="margin-top:10px;"></div>
</div>
    """

    private const val ZINE_POLLS_JS = """
document.getElementById('publishPollBtn').onclick = () => {
  const q = document.getElementById('pollQuestion').value || 'Favorite sovereign web stack?';
  const id = 'poll_' + Date.now();
  window.addEventListener('message', function h(ev) {
    let d = ev.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e){ return; } }
    if (d && d.id === id && d.reply) {
      window.removeEventListener('message', h);
      document.getElementById('pollOut').textContent = d.error ? ('Cancelled: ' + d.error) : ('Signed Poll Event: ' + d.result.id.slice(0,16) + '…');
    }
  });
  window.postMessage({
    id,
    type: 'signer.signEvent',
    event: { kind: 1, content: '📊 POLL: ' + q + '\n1) NIP-5D Napplets\n2) NIP-5A Nsites\n#nostr #poll', tags: [['t','poll']] }
  }, '*');
};
    """

    private const val PIXEL_BOARD_HTML = """
<div class="card">
  <div class="eyebrow">GAMES &amp; ART • ZINE STAMP</div>
  <h2>🎨 Cyber-Zine Stamp Pad</h2>
  <p>Compose an ASCII badge and sign it onto your Nostr timeline.</p>
  <textarea id="artInput">[ ☆ SOVEREIGN ≠ SHELL ☆ ]</textarea>
  <button id="stampArtBtn">Sign Art Note →</button>
  <div id="artOut" class="mono" style="margin-top:10px;"></div>
</div>
    """

    private const val PIXEL_BOARD_JS = """
document.getElementById('stampArtBtn').onclick = () => {
  const art = document.getElementById('artInput').value || '[ ☆ ]';
  const id = 'art_' + Date.now();
  window.addEventListener('message', function h(ev) {
    let d = ev.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e){ return; } }
    if (d && d.id === id && d.reply) {
      window.removeEventListener('message', h);
      document.getElementById('artOut').textContent = d.error ? ('Rejected: ' + d.error) : ('Signed Stamp: ' + d.result.id.slice(0,16) + '…');
    }
  });
  window.postMessage({ id, type: 'signer.signEvent', event: { kind: 1, content: art + ' #zine #napplet', tags: [['t','art']] } }, '*');
};
    """
}
