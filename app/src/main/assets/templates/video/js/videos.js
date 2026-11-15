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
