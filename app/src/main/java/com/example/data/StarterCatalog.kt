package com.example.data

import com.example.nostr.NostrCrypto

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

    /**
     * Known legacy fake demo slugs from earlier builds — kept strictly so existing databases
     * purge all synthetic bundled demo entries on startup while preserving user-published apps.
     */
    val LEGACY_FAKE_SEED_DTAGS: Set<String> = setOf(
        "pulse-notes",
        "puter-crt",
        "agent-zone",
        "cipher-pad",
        "relay-sonar",
        "pretend-zine",
        "pulse-notes-classic",
        "zap-calc",
        "nostr-polls",
        "pixel-synth"
    )

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
            val sha = app.indexSha256.ifBlank { NostrCrypto.sha256Hex(app.inlineHtml) }.take(12)
            """{"id":"${app.dTag}","title":"$t","desc":"$d","icon":"${app.iconGlyph}","nsite":${app.isNsite},"sha":"$sha"}"""
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
}
