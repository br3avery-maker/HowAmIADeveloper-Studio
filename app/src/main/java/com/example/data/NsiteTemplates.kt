package com.example.data

import android.content.Context
import org.json.JSONObject

enum class NsiteTemplateKind(
    val id: String,
    val assetDir: String,
    val label: String,
    val iconGlyph: String,
    val subtitle: String,
    val defaultSlug: String,
    val descriptionText: String
) {
    BLANK(
        id = "blank",
        assetDir = "blank",
        label = "Blank Shell",
        iconGlyph = "🌐",
        subtitle = "Minimal HTML / CSS / JS starter",
        defaultSlug = "my-nsite",
        descriptionText = "Blank multi-file NIP-5A Nsite starter built in SovereignShell."
    ),
    MUSIC(
        id = "music",
        assetDir = "music",
        label = "Music",
        iconGlyph = "🎵",
        subtitle = "Tracklist JSON + audio player slots",
        defaultSlug = "my-music",
        descriptionText = "Decentralized NIP-5A music & audio release template on Blossom."
    ),
    BOOKS(
        id = "books",
        assetDir = "books",
        label = "Books",
        iconGlyph = "📖",
        subtitle = "Chapters JSON + reader typography view",
        defaultSlug = "my-book",
        descriptionText = "Decentralized NIP-5A book & long-form chapter reader template."
    ),
    BLOGS(
        id = "blogs",
        assetDir = "blogs",
        label = "Blogs",
        iconGlyph = "✍️",
        subtitle = "Posts JSON, tag filter & article reader",
        defaultSlug = "my-blog",
        descriptionText = "Decentralized NIP-5A indie blog & zine publication template."
    ),
    VIDEOS(
        id = "videos",
        assetDir = "video",
        label = "Videos",
        iconGlyph = "🎬",
        subtitle = "Playlist JSON + responsive video player",
        defaultSlug = "my-videos",
        descriptionText = "Decentralized NIP-5A video channel template hosted on Blossom."
    )
}

data class NsiteAssetTemplateDescriptor(
    val id: String,
    val assetDir: String,
    val label: String,
    val icon: String,
    val entry: String,
    val dataFile: String,
    val description: String
)

object NsiteTemplates {

    fun listAvailableTemplates(context: Context): List<NsiteAssetTemplateDescriptor> {
        val assetManager = context.assets
        val rawEntries = runCatching { assetManager.list("templates")?.toList().orEmpty() }.getOrDefault(emptyList())
        val dirs = rawEntries
            .map { it.trim('/').substringBefore('/') }
            .filter { it.isNotBlank() }
            .distinct()
        return dirs.mapNotNull { dir ->
            val raw = runCatching {
                assetManager.open("templates/$dir/template.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            runCatching {
                val obj = JSONObject(raw)
                NsiteAssetTemplateDescriptor(
                    id = obj.optString("id", dir),
                    assetDir = dir,
                    label = obj.optString("label", dir.replaceFirstChar { it.uppercase() }),
                    icon = obj.optString("icon", "🌐"),
                    entry = obj.optString("entry", "/index.html"),
                    dataFile = obj.optString("dataFile", ""),
                    description = obj.optString("description", "")
                )
            }.getOrNull()
        }.sortedBy { desc ->
            NsiteTemplateKind.entries.indexOfFirst { it.assetDir == desc.assetDir }.let { if (it < 0) 99 else it }
        }
    }

    fun buildFiles(kind: NsiteTemplateKind, projectTitle: String): LinkedHashMap<String, String> =
        buildFiles(context = null, kind = kind, projectTitle = projectTitle)

    fun buildFiles(
        context: Context?,
        kind: NsiteTemplateKind,
        projectTitle: String
    ): LinkedHashMap<String, String> {
        val rawTitle = projectTitle.trim().ifBlank { kind.label }
        val safeTitleHtml = escapeHtml(rawTitle)
        val safeTitleJson = escapeJsonString(rawTitle)

        if (context != null) {
            val loadedFromAssets = loadTemplateFromAssets(
                context = context,
                assetRoot = "templates/${kind.assetDir}",
                safeTitleHtml = safeTitleHtml,
                safeTitleJson = safeTitleJson
            )
            if (loadedFromAssets.isNotEmpty()) {
                return loadedFromAssets
            }
        }

        return when (kind) {
            NsiteTemplateKind.BLANK -> buildBlankFiles(safeTitleHtml)
            NsiteTemplateKind.MUSIC -> buildMusicFiles(safeTitleHtml, safeTitleJson)
            NsiteTemplateKind.BOOKS -> buildBookFiles(safeTitleHtml, safeTitleJson)
            NsiteTemplateKind.BLOGS -> buildBlogFiles(safeTitleHtml, safeTitleJson)
            NsiteTemplateKind.VIDEOS -> buildVideoFiles(safeTitleHtml, safeTitleJson)
        }
    }

    private fun loadTemplateFromAssets(
        context: Context,
        assetRoot: String,
        safeTitleHtml: String,
        safeTitleJson: String
    ): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val assetManager = context.assets

        fun walk(assetDir: String, relativePrefix: String) {
            val entries = runCatching { assetManager.list(assetDir)?.toList().orEmpty() }.getOrDefault(emptyList())
            for (name in entries.sorted()) {
                val cleanName = name.trimStart('/')
                if (cleanName == "template.json" && relativePrefix.isEmpty()) continue
                val childAssetPath = "$assetDir/$cleanName"
                val relPath = ("$relativePrefix/$cleanName").let {
                    if (it.startsWith("/")) it else "/$it"
                }
                if (cleanName.contains('/')) {
                    // Robolectric AssetManager.list returns nested relative paths directly; read file if openable
                    val directContent = runCatching {
                        assetManager.open(childAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }.getOrNull()
                    if (directContent != null) {
                        result[relPath] = directContent
                            .replace("{{TITLE_JSON}}", safeTitleJson)
                            .replace("{{TITLE}}", safeTitleHtml)
                        continue
                    }
                }
                val subEntries = runCatching { assetManager.list(childAssetPath)?.toList().orEmpty() }.getOrDefault(emptyList())
                if (subEntries.isNotEmpty()) {
                    walk(childAssetPath, relPath)
                } else {
                    val content = runCatching {
                        assetManager.open(childAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }.getOrNull() ?: continue
                    val interpolated = content
                        .replace("{{TITLE_JSON}}", safeTitleJson)
                        .replace("{{TITLE}}", safeTitleHtml)
                    result[relPath] = interpolated
                }
            }
        }

        walk(assetRoot, "")
        return result
    }

    private fun escapeHtml(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun escapeJsonString(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }

    private fun buildBlankFiles(safeTitle: String): LinkedHashMap<String, String> = linkedMapOf(
        "/index.html" to """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$safeTitle</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <main class="shell-card">
    <span class="tag">NIP-5A NSITE</span>
    <h1 id="headline">$safeTitle</h1>
    <p>Multi-file Nostr website hosted on Blossom with cryptographic SHA-256 path verification.</p>
    <div class="row">
      <button id="counterBtn" type="button">Clicks: 0</button>
      <button id="nostrBtn" type="button" class="secondary">Verify NIP-07 Key</button>
    </div>
    <pre id="output">Ready.</pre>
  </main>
  <script type="module" src="./js/app.js"></script>
</body>
</html>
        """.trimIndent(),
        "/css/style.css" to COMMON_DARK_DEEPINK_CSS,
        "/js/app.js" to """
let count = 0;
const counterBtn = document.getElementById('counterBtn');
const nostrBtn = document.getElementById('nostrBtn');
const output = document.getElementById('output');

counterBtn?.addEventListener('click', () => {
  count += 1;
  counterBtn.textContent = 'Clicks: ' + count;
  output.textContent = 'Interactive state updated (' + count + ')';
});

nostrBtn?.addEventListener('click', async () => {
  if (!window.nostr) {
    output.textContent = 'NIP-07 disabled for this view.';
    return;
  }
  try {
    const pubkey = await window.nostr.getPublicKey();
    output.textContent = 'Active Nostr pubkey: ' + pubkey;
  } catch (err) {
    output.textContent = 'Signer response: ' + err.message;
  }
});
        """.trimIndent()
    )

    private fun buildMusicFiles(safeTitle: String, safeTitleJson: String): LinkedHashMap<String, String> = linkedMapOf(
        "/index.html" to """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$safeTitle</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <main class="container">
    <header class="hero">
      <span class="badge">AUDIO / MUSIC TEMPLATE</span>
      <h1>$safeTitle</h1>
      <p class="subtitle">Import audio files via Studio <strong>+ Asset</strong> (<code>/assets/track-01.mp3</code>) or paste Blossom URLs inside <code>/data/tracks.json</code>.</p>
    </header>

    <section class="panel player-panel" aria-label="Audio Player">
      <div class="now-playing">
        <div class="cover-slot" id="coverSlot">♪</div>
        <div class="track-meta">
          <div id="nowTitle" class="item-title">Select or preview a track</div>
          <div id="nowArtist" class="muted">No track loaded yet</div>
        </div>
      </div>
      <audio id="audioPlayer" controls preload="metadata"></audio>
      <div class="row">
        <label class="file-btn" for="localAudioPicker">Preview Local Audio File</label>
        <input id="localAudioPicker" type="file" accept="audio/*" hidden />
      </div>
    </section>

    <section class="panel">
      <div class="section-head">
        <h2>Tracklist</h2>
        <span id="trackCount" class="mono-pill">0 tracks</span>
      </div>
      <div id="trackList" class="stack-list"></div>
    </section>
  </main>
  <script type="module" src="./js/music.js"></script>
</body>
</html>
        """.trimIndent(),
        "/css/style.css" to SHARED_TEMPLATE_CSS,
        "/data/tracks.json" to """
{
  "releaseTitle": "$safeTitleJson",
  "artist": "",
  "releaseNote": "Add objects to 'tracks' using relative './assets/file.mp3' paths or Blossom URLs.",
  "schemaExample": {
    "title": "Track 01",
    "artist": "Creator Name",
    "src": "./assets/track-01.mp3",
    "duration": "03:42",
    "cover": "./assets/cover.jpg",
    "notes": ""
  },
  "tracks": []
}
        """.trimIndent(),
        "/js/music.js" to """
const audioEl = document.getElementById('audioPlayer');
const nowTitle = document.getElementById('nowTitle');
const nowArtist = document.getElementById('nowArtist');
const trackListEl = document.getElementById('trackList');
const trackCountEl = document.getElementById('trackCount');
const localPicker = document.getElementById('localAudioPicker');

let activeObjectUrl = null;

function setNowPlaying(track) {
  nowTitle.textContent = track.title || 'Untitled Track';
  nowArtist.textContent = [track.artist, track.duration].filter(Boolean).join(' • ') || 'Ready';
  if (track.src) {
    audioEl.src = track.src;
    audioEl.load();
  }
}

function renderEmpty(message) {
  trackListEl.replaceChildren();
  const box = document.createElement('div');
  box.className = 'empty-slot';
  const h = document.createElement('strong');
  h.textContent = 'Blank Audio Shelf';
  const p = document.createElement('p');
  p.textContent = message;
  box.append(h, p);
  trackListEl.appendChild(box);
}

function renderTracks(tracks) {
  trackCountEl.textContent = tracks.length + (tracks.length === 1 ? ' track' : ' tracks');
  if (!tracks.length) {
    renderEmpty('Edit /data/tracks.json to add track entries pointing to ./assets/*.mp3 or Blossom URLs.');
    return;
  }
  trackListEl.replaceChildren();
  tracks.forEach((track, idx) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'list-row';
    const left = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'item-title';
    title.textContent = (idx + 1) + '. ' + (track.title || 'Untitled Track');
    const sub = document.createElement('div');
    sub.className = 'muted';
    sub.textContent = [track.artist || 'Creator', track.duration || ''].filter(Boolean).join(' • ');
    left.append(title, sub);
    const playLabel = document.createElement('span');
    playLabel.className = 'mono-pill';
    playLabel.textContent = track.src ? 'LOAD' : 'NO SRC';
    row.append(left, playLabel);
    row.addEventListener('click', () => {
      setNowPlaying(track);
      audioEl.play().catch(() => {});
    });
    trackListEl.appendChild(row);
  });
}

localPicker?.addEventListener('change', (ev) => {
  const file = ev.target.files?.[0];
  if (!file) return;
  if (activeObjectUrl) URL.revokeObjectURL(activeObjectUrl);
  activeObjectUrl = URL.createObjectURL(file);
  setNowPlaying({ title: file.name, artist: 'Local File Preview', src: activeObjectUrl });
  audioEl.play().catch(() => {});
});

async function init() {
  try {
    const resp = await fetch('./data/tracks.json');
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const data = await resp.json();
    const tracks = Array.isArray(data.tracks) ? data.tracks : [];
    renderTracks(tracks);
    if (tracks.length > 0 && tracks[0].src) {
      setNowPlaying(tracks[0]);
    }
  } catch (err) {
    renderEmpty('Could not load /data/tracks.json: ' + err.message);
  }
}

init();
        """.trimIndent()
    )

    private fun buildBookFiles(safeTitle: String, safeTitleJson: String): LinkedHashMap<String, String> = linkedMapOf(
        "/index.html" to """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$safeTitle</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <main class="container">
    <header class="hero">
      <span class="badge">BOOK / READER TEMPLATE</span>
      <h1>$safeTitle</h1>
      <p class="subtitle">Add chapters inside <code>/data/chapters.json</code> or import standalone chapter <code>.txt</code>/<code>.html</code> files via <strong>+ Asset</strong>.</p>
      <div class="row">
        <button id="fontSmaller" type="button" class="secondary">A-</button>
        <button id="fontLarger" type="button" class="secondary">A+</button>
        <span id="chapterCount" class="mono-pill">0 chapters</span>
      </div>
    </header>

    <section class="panel">
      <h2>Table of Contents</h2>
      <div id="tocList" class="toc-row"></div>
    </section>

    <article class="panel reader-surface" id="readerSurface">
      <h2 id="chapterTitle">No chapter loaded</h2>
      <p id="chapterMeta" class="muted"></p>
      <div id="chapterBody" class="prose"></div>
    </article>
  </main>
  <script type="module" src="./js/reader.js"></script>
</body>
</html>
        """.trimIndent(),
        "/css/style.css" to SHARED_TEMPLATE_CSS,
        "/data/chapters.json" to """
{
  "bookTitle": "$safeTitleJson",
  "author": "",
  "schemaExample": {
    "id": "chapter-1",
    "title": "Chapter 1: Opening",
    "summary": "Optional short note",
    "paragraphs": [
      "Replace this schema example in 'chapters' with your own chapters."
    ],
    "textPath": ""
  },
  "chapters": []
}
        """.trimIndent(),
        "/js/reader.js" to """
const tocList = document.getElementById('tocList');
const chapterCount = document.getElementById('chapterCount');
const chapterTitle = document.getElementById('chapterTitle');
const chapterMeta = document.getElementById('chapterMeta');
const chapterBody = document.getElementById('chapterBody');
let fontSizePx = 17;

document.getElementById('fontSmaller')?.addEventListener('click', () => {
  fontSizePx = Math.max(14, fontSizePx - 1);
  chapterBody.style.fontSize = fontSizePx + 'px';
});
document.getElementById('fontLarger')?.addEventListener('click', () => {
  fontSizePx = Math.min(24, fontSizePx + 1);
  chapterBody.style.fontSize = fontSizePx + 'px';
});

function renderEmpty() {
  chapterCount.textContent = '0 chapters';
  tocList.replaceChildren();
  chapterTitle.textContent = 'Blank Book Shell';
  chapterMeta.textContent = 'Ready for manuscript';
  const box = document.createElement('div');
  box.className = 'empty-slot';
  const p = document.createElement('p');
  p.textContent = 'Open /data/chapters.json in Studio to add chapters with paragraphs or point textPath to an imported .txt asset.';
  box.appendChild(p);
  chapterBody.replaceChildren(box);
}

async function openChapter(ch, idx) {
  chapterTitle.textContent = ch.title || ('Chapter ' + (idx + 1));
  chapterMeta.textContent = ch.summary || '';
  chapterBody.replaceChildren();
  let paragraphs = Array.isArray(ch.paragraphs) ? ch.paragraphs : [];
  if (!paragraphs.length && ch.textPath) {
    try {
      const resp = await fetch(ch.textPath);
      if (resp.ok) {
        const raw = await resp.text();
        paragraphs = raw.split(/\n\s*\n/).map((s) => s.trim()).filter(Boolean);
      }
    } catch (_) {}
  }
  if (!paragraphs.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'This chapter slot is empty. Add paragraphs or a textPath in /data/chapters.json.';
    chapterBody.appendChild(p);
    return;
  }
  paragraphs.forEach((line) => {
    const p = document.createElement('p');
    p.textContent = line;
    chapterBody.appendChild(p);
  });
}

async function init() {
  try {
    const resp = await fetch('./data/chapters.json');
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const data = await resp.json();
    const chapters = Array.isArray(data.chapters) ? data.chapters : [];
    if (!chapters.length) {
      renderEmpty();
      return;
    }
    chapterCount.textContent = chapters.length + (chapters.length === 1 ? ' chapter' : ' chapters');
    tocList.replaceChildren();
    chapters.forEach((ch, idx) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'secondary';
      btn.textContent = ch.title || ('Chapter ' + (idx + 1));
      btn.addEventListener('click', () => openChapter(ch, idx));
      tocList.appendChild(btn);
    });
    await openChapter(chapters[0], 0);
  } catch (err) {
    renderEmpty();
  }
}

init();
        """.trimIndent()
    )

    private fun buildBlogFiles(safeTitle: String, safeTitleJson: String): LinkedHashMap<String, String> = linkedMapOf(
        "/index.html" to """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$safeTitle</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <main class="container">
    <header class="hero">
      <span class="badge">BLOG / ZINE TEMPLATE</span>
      <h1>$safeTitle</h1>
      <p class="subtitle">Write posts in <code>/data/posts.json</code> with titles, dates, tags, and body paragraphs, then publish cleanly over NIP-5A.</p>
    </header>

    <section class="panel">
      <div class="section-head">
        <h2>Tags</h2>
        <span id="postCount" class="mono-pill">0 posts</span>
      </div>
      <div id="tagBar" class="toc-row"></div>
    </section>

    <section id="postFeed" class="stack-list"></section>

    <article class="panel reader-surface" id="articlePanel" hidden>
      <div class="row">
        <button id="closeArticleBtn" type="button" class="secondary">Back to Posts</button>
      </div>
      <h2 id="articleTitle"></h2>
      <p id="articleMeta" class="muted"></p>
      <div id="articleBody" class="prose"></div>
    </article>
  </main>
  <script type="module" src="./js/blog.js"></script>
</body>
</html>
        """.trimIndent(),
        "/css/style.css" to SHARED_TEMPLATE_CSS,
        "/data/posts.json" to """
{
  "blogTitle": "$safeTitleJson",
  "schemaExample": {
    "slug": "first-dispatch",
    "title": "First Dispatch",
    "date": "2026-11-15",
    "tags": ["nostr", "nsite"],
    "summary": "Short summary shown on the post index.",
    "paragraphs": [
      "Paragraph 1 of your post.",
      "Paragraph 2 of your post."
    ]
  },
  "posts": []
}
        """.trimIndent(),
        "/js/blog.js" to """
const postCountEl = document.getElementById('postCount');
const tagBarEl = document.getElementById('tagBar');
const postFeedEl = document.getElementById('postFeed');
const articlePanelEl = document.getElementById('articlePanel');
const articleTitleEl = document.getElementById('articleTitle');
const articleMetaEl = document.getElementById('articleMeta');
const articleBodyEl = document.getElementById('articleBody');
let allPosts = [];
let selectedTag = 'ALL';

document.getElementById('closeArticleBtn')?.addEventListener('click', () => {
  articlePanelEl.hidden = true;
  postFeedEl.hidden = false;
});

function openPost(post) {
  postFeedEl.hidden = true;
  articlePanelEl.hidden = false;
  articleTitleEl.textContent = post.title || 'Untitled Post';
  articleMetaEl.textContent = [post.date, ...(Array.isArray(post.tags) ? post.tags.map((t) => '#' + t) : [])].filter(Boolean).join(' • ');
  articleBodyEl.replaceChildren();
  const paras = Array.isArray(post.paragraphs) ? post.paragraphs : (post.body ? [post.body] : []);
  if (!paras.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'No paragraphs written for this post yet.';
    articleBodyEl.appendChild(p);
    return;
  }
  paras.forEach((line) => {
    const p = document.createElement('p');
    p.textContent = line;
    articleBodyEl.appendChild(p);
  });
}

function renderPosts() {
  const visible = selectedTag === 'ALL'
    ? allPosts
    : allPosts.filter((p) => Array.isArray(p.tags) && p.tags.includes(selectedTag));
  postCountEl.textContent = visible.length + (visible.length === 1 ? ' post' : ' posts');
  postFeedEl.replaceChildren();
  if (!visible.length) {
    const box = document.createElement('div');
    box.className = 'empty-slot';
    const h = document.createElement('strong');
    h.textContent = 'Blank Blog Shell';
    const p = document.createElement('p');
    p.textContent = 'Add articles inside /data/posts.json. Tag filters and article reading mode activate automatically.';
    box.append(h, p);
    postFeedEl.appendChild(box);
    return;
  }
  visible.forEach((post) => {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'list-row';
    const info = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'item-title';
    title.textContent = post.title || 'Untitled Post';
    const summary = document.createElement('div');
    summary.className = 'muted';
    summary.textContent = post.summary || post.date || '';
    info.append(title, summary);
    const pill = document.createElement('span');
    pill.className = 'mono-pill';
    pill.textContent = post.date || 'READ';
    card.append(info, pill);
    card.addEventListener('click', () => openPost(post));
    postFeedEl.appendChild(card);
  });
}

function renderTags() {
  const tags = new Set(['ALL']);
  allPosts.forEach((p) => {
    if (Array.isArray(p.tags)) p.tags.forEach((t) => tags.add(String(t)));
  });
  tagBarEl.replaceChildren();
  tags.forEach((t) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = t === selectedTag ? '' : 'secondary';
    btn.textContent = t === 'ALL' ? 'All' : '#' + t;
    btn.addEventListener('click', () => {
      selectedTag = t;
      renderTags();
      renderPosts();
    });
    tagBarEl.appendChild(btn);
  });
}

async function init() {
  try {
    const resp = await fetch('./data/posts.json');
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const data = await resp.json();
    allPosts = Array.isArray(data.posts) ? data.posts : [];
  } catch (_) {
    allPosts = [];
  }
  renderTags();
  renderPosts();
}

init();
        """.trimIndent()
    )

    private fun buildVideoFiles(safeTitle: String, safeTitleJson: String): LinkedHashMap<String, String> = linkedMapOf(
        "/index.html" to """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$safeTitle</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <main class="container">
    <header class="hero">
      <span class="badge">VIDEO CHANNEL TEMPLATE</span>
      <h1>$safeTitle</h1>
      <p class="subtitle">Import <code>.mp4</code>/<code>.webm</code> assets via <strong>+ Asset</strong> or list Blossom video blobs in <code>/data/videos.json</code>.</p>
    </header>

    <section class="panel player-panel" aria-label="Video Player">
      <video id="videoPlayer" controls playsinline preload="metadata"></video>
      <div class="track-meta">
        <div id="videoTitle" class="item-title">No video loaded</div>
        <div id="videoDesc" class="muted">Pick a local video file or add entries in /data/videos.json</div>
      </div>
      <div class="row">
        <label class="file-btn" for="localVideoPicker">Preview Local Video File</label>
        <input id="localVideoPicker" type="file" accept="video/*" hidden />
      </div>
    </section>

    <section class="panel">
      <div class="section-head">
        <h2>Channel Playlist</h2>
        <span id="videoCount" class="mono-pill">0 videos</span>
      </div>
      <div id="videoList" class="stack-list"></div>
    </section>
  </main>
  <script type="module" src="./js/videos.js"></script>
</body>
</html>
        """.trimIndent(),
        "/css/style.css" to SHARED_TEMPLATE_CSS,
        "/data/videos.json" to """
{
  "channelTitle": "$safeTitleJson",
  "schemaExample": {
    "title": "Clip 01",
    "src": "./assets/clip-01.mp4",
    "poster": "./assets/poster.jpg",
    "duration": "02:15",
    "description": "Uploaded via Blossom SHA-256"
  },
  "videos": []
}
        """.trimIndent(),
        "/js/videos.js" to """
const videoEl = document.getElementById('videoPlayer');
const videoTitleEl = document.getElementById('videoTitle');
const videoDescEl = document.getElementById('videoDesc');
const videoListEl = document.getElementById('videoList');
const videoCountEl = document.getElementById('videoCount');
const localPicker = document.getElementById('localVideoPicker');

let activeObjectUrl = null;

function loadVideo(item) {
  videoTitleEl.textContent = item.title || 'Untitled Video';
  videoDescEl.textContent = [item.duration, item.description].filter(Boolean).join(' • ') || 'Ready';
  if (item.poster) videoEl.poster = item.poster;
  if (item.src) {
    videoEl.src = item.src;
    videoEl.load();
  }
}

function renderVideos(videos) {
  videoCountEl.textContent = videos.length + (videos.length === 1 ? ' video' : ' videos');
  videoListEl.replaceChildren();
  if (!videos.length) {
    const box = document.createElement('div');
    box.className = 'empty-slot';
    const h = document.createElement('strong');
    h.textContent = 'Blank Video Shelf';
    const p = document.createElement('p');
    p.textContent = 'Add video objects inside /data/videos.json pointing to ./assets/*.mp4 or Blossom URLs.';
    box.append(h, p);
    videoListEl.appendChild(box);
    return;
  }
  videos.forEach((vid, idx) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'list-row';
    const info = document.createElement('div');
    const t = document.createElement('div');
    t.className = 'item-title';
    t.textContent = (idx + 1) + '. ' + (vid.title || 'Untitled Video');
    const sub = document.createElement('div');
    sub.className = 'muted';
    sub.textContent = vid.description || vid.duration || '';
    info.append(t, sub);
    const pill = document.createElement('span');
    pill.className = 'mono-pill';
    pill.textContent = vid.duration || 'PLAY';
    row.append(info, pill);
    row.addEventListener('click', () => {
      loadVideo(vid);
      videoEl.play().catch(() => {});
    });
    videoListEl.appendChild(row);
  });
}

localPicker?.addEventListener('change', (ev) => {
  const file = ev.target.files?.[0];
  if (!file) return;
  if (activeObjectUrl) URL.revokeObjectURL(activeObjectUrl);
  activeObjectUrl = URL.createObjectURL(file);
  loadVideo({ title: file.name, description: 'Local File Preview', src: activeObjectUrl });
  videoEl.play().catch(() => {});
});

async function init() {
  try {
    const resp = await fetch('./data/videos.json');
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const data = await resp.json();
    const videos = Array.isArray(data.videos) ? data.videos : [];
    renderVideos(videos);
    if (videos.length > 0 && videos[0].src) {
      loadVideo(videos[0]);
    }
  } catch (_) {
    renderVideos([]);
  }
}

init();
        """.trimIndent()
    )

    private val COMMON_DARK_DEEPINK_CSS = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: #000000;
  color: #ffffff;
  font-family: system-ui, -apple-system, sans-serif;
}
.shell-card {
  width: min(100%, 480px);
  padding: 24px;
  border-radius: 20px;
  background: #111111;
  border: 1px solid #FF1493;
}
.tag {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 999px;
  background: #5B0633;
  color: #FFB6D9;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
h1 { margin: 12px 0 8px; font-size: 24px; }
p { margin: 0 0 18px; color: #B8B8B8; line-height: 1.5; font-size: 14px; }
.row { display: flex; gap: 10px; flex-wrap: wrap; }
button {
  min-height: 44px;
  border: 0;
  border-radius: 12px;
  padding: 12px 16px;
  background: #FF1493;
  color: #ffffff;
  font-weight: 700;
  cursor: pointer;
}
button.secondary {
  background: #1A1A1A;
  border: 1px solid #9A0957;
  color: #FFB6D9;
}
pre {
  margin: 16px 0 0;
  padding: 12px;
  border-radius: 12px;
  background: #080808;
  color: #FFB6D9;
  font-size: 12px;
  overflow-x: auto;
}
    """.trimIndent()

    private val SHARED_TEMPLATE_CSS = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body {
  margin: 0;
  min-height: 100vh;
  padding: 18px 14px 40px;
  background: #000000;
  color: #E8DCC8;
  font-family: system-ui, -apple-system, sans-serif;
}
.container {
  width: min(100%, 720px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.hero, .panel {
  padding: 16px;
  border-radius: 16px;
  background: #111111;
  border: 1px solid #2A1622;
}
.hero { border-color: #FF1493; }
.badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 999px;
  background: #5B0633;
  color: #FFB6D9;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}
h1 { margin: 10px 0 6px; font-size: 24px; color: #FFFFFF; }
h2 { margin: 0 0 10px; font-size: 16px; color: #FFFFFF; }
.subtitle, .muted { margin: 0; color: #B8B8B8; font-size: 13px; line-height: 1.45; }
code {
  font-family: ui-monospace, monospace;
  color: #FFB6D9;
  background: #1D0B14;
  padding: 1px 5px;
  border-radius: 4px;
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.section-head h2 { margin: 0; }
.row, .toc-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
button, .file-btn {
  min-height: 44px;
  border: 0;
  border-radius: 10px;
  padding: 10px 14px;
  background: #FF1493;
  color: #FFFFFF;
  font-weight: 700;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
}
button.secondary {
  background: #1A1A1A;
  border: 1px solid #9A0957;
  color: #FFB6D9;
}
.player-panel { display: flex; flex-direction: column; gap: 12px; }
audio, video {
  width: 100%;
  border-radius: 12px;
  background: #070707;
}
video { max-height: 360px; }
.now-playing { display: flex; gap: 12px; align-items: center; }
.cover-slot {
  width: 52px;
  height: 52px;
  border-radius: 12px;
  background: #1E0713;
  border: 1px solid #FF1493;
  color: #FF1493;
  display: grid;
  place-items: center;
  font-size: 22px;
  flex-shrink: 0;
}
.stack-list { display: flex; flex-direction: column; gap: 8px; }
.list-row {
  width: 100%;
  text-align: left;
  background: #161616;
  border: 1px solid #2A2A2A;
  border-radius: 12px;
  padding: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.item-title { font-weight: 700; font-size: 15px; color: #FFFFFF; }
.mono-pill {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  padding: 4px 8px;
  border-radius: 6px;
  background: #1F0A15;
  color: #FFB6D9;
  white-space: nowrap;
}
.empty-slot {
  padding: 16px;
  border-radius: 12px;
  border: 1px dashed #9A0957;
  background: #0C090B;
}
.empty-slot p { margin: 6px 0 0; color: #B8B8B8; font-size: 13px; line-height: 1.45; }
.prose { margin-top: 12px; font-size: 17px; line-height: 1.7; color: #E8DCC8; }
.prose p { margin: 0 0 14px; }
    """.trimIndent()
}
