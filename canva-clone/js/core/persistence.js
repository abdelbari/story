// Persistence: autosave to localStorage, a recent-designs index for the home
// screen, and JSON file download/upload. All storage access is wrapped so a
// blocked or full localStorage degrades gracefully instead of crashing.

import { migrateDoc } from './doc.js';

const INDEX_KEY = 'canvaclone.index';
const DOC_PREFIX = 'canvaclone.doc.';
const MAX_RECENTS = 24;

function readJSON(key) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

function writeJSON(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch { return false; }
}

export function listRecentDesigns() {
  const index = readJSON(INDEX_KEY) || [];
  return index
    .filter(entry => entry && entry.id)
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

export function saveDoc(doc, thumbnail) {
  if (!doc) return false;
  const ok = writeJSON(DOC_PREFIX + doc.id, doc);
  if (!ok) return false;
  let index = readJSON(INDEX_KEY) || [];
  index = index.filter(e => e && e.id !== doc.id);
  index.unshift({
    id: doc.id,
    title: doc.title,
    width: doc.width,
    height: doc.height,
    pages: doc.pages.length,
    updatedAt: doc.updatedAt || Date.now(),
    thumbnail: thumbnail || null,
  });
  // Trim old designs beyond the cap (and drop their stored docs).
  for (const evicted of index.slice(MAX_RECENTS)) {
    try { localStorage.removeItem(DOC_PREFIX + evicted.id); } catch { /* ignore */ }
  }
  index = index.slice(0, MAX_RECENTS);
  writeJSON(INDEX_KEY, index);
  return true;
}

export function loadDocById(id) {
  return migrateDoc(readJSON(DOC_PREFIX + id));
}

export function deleteDocById(id) {
  try { localStorage.removeItem(DOC_PREFIX + id); } catch { /* ignore */ }
  let index = readJSON(INDEX_KEY) || [];
  index = index.filter(e => e && e.id !== id);
  writeJSON(INDEX_KEY, index);
}

export function updateThumbnail(id, thumbnail) {
  const index = readJSON(INDEX_KEY) || [];
  const entry = index.find(e => e && e.id === id);
  if (entry) {
    entry.thumbnail = thumbnail;
    writeJSON(INDEX_KEY, index);
  }
}

// ---- file download / upload ----

export function downloadJSON(doc) {
  const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' });
  triggerDownload(blob, `${sanitizeFilename(doc.title)}.canva.json`);
}

export function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

export function sanitizeFilename(name) {
  return (name || 'design').replace(/[^\w\d-_ ]+/g, '').trim().replace(/\s+/g, '-').slice(0, 60) || 'design';
}

export function readFileAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file);
  });
}

export function readFileAsDataURL(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}
