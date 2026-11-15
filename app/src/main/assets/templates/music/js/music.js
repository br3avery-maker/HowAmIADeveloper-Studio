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
