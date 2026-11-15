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
