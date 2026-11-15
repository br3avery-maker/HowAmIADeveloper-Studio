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
