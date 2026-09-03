// Canvia boot + app controller: routing between home and editor, editor
// wiring (renderer/overlay/interactions/panels), autosave, uploads,
// OS-clipboard paste, drag-drop and the Playwright test hook.

import { store } from './core/store.js';
import { createDoc, migrateDoc } from './core/doc.js';
import { saveDoc, loadDocById, updateThumbnail, readFileAsText } from './core/persistence.js';
import { PageRenderer } from './editor/renderer.js';
import { Overlay } from './editor/overlay.js';
import { Interactions, isTypingTarget } from './editor/interactions.js';
import { installShortcuts } from './editor/shortcuts.js';
import * as commands from './editor/commands.js';
import { loadContent } from './assets/content.js';
import { initTopbar } from './ui/topbar.js';
import { initToolbar } from './ui/toolbar.js';
import { initSidebar, handleUploadFiles } from './ui/sidebar.js';
import { initPagestrip } from './ui/pagestrip.js';
import { openContextMenu, closeContextMenu } from './ui/contextmenu.js';
import { renderHome, renderRecents } from './ui/home.js';
import { h, iconBtn, toast, closePopover } from './ui/widgets.js';
import { makeThumbnail } from './export/exporter.js';

loadContent();

if (new URLSearchParams(location.search).has('nomotion')) {
  document.body.classList.add('nomotion');
}

const homeView = document.getElementById('home-view');
const editorView = document.getElementById('editor-view');

let editor = null; // { renderer, overlay, interactions }
let saveTimer = null;
let sidebarApi = null;

const app = {
  onSaveState: null,

  newDesign({ width, height, title }) {
    this.openDoc(createDoc({ width, height, title }));
  },

  openDoc(doc) {
    doc.createdAt = doc.createdAt || Date.now();
    store.loadDoc(doc);
    location.hash = '#/edit';
    showEditor();
    this.saveNow();
  },

  goHome() {
    this.saveNow();
    location.hash = '#/home';
    showHome();
  },

  saveNow() {
    const doc = store.doc;
    if (!doc) return;
    if (editor) editor.interactions.commitTextEditIfAny();
    doc.updatedAt = doc.updatedAt || Date.now();
    const ok = saveDoc(doc);
    app.onSaveState?.(ok ? 'Saved ✓' : 'Save failed — storage full');
    if (!ok) toast('Storage is full — use File → Download as JSON to back up', 'error');
    // Thumbnail asynchronously (not blocking the save).
    makeThumbnail(doc, doc.pages[0], 300)
      .then(uri => updateThumbnail(doc.id, uri))
      .catch(() => { /* thumbnail is best-effort */ });
  },

  async importDesign() {
    const input = document.getElementById('import-file');
    input.onchange = async () => {
      const file = input.files[0];
      input.value = '';
      if (!file) return;
      try {
        const doc = migrateDoc(JSON.parse(await readFileAsText(file)));
        if (!doc) throw new Error('not a Canvia design');
        doc.id = doc.id + '-i' + Math.floor(Math.random() * 1e6).toString(36);
        app.openDoc(doc);
        toast('Design imported');
      } catch (err) {
        toast('Import failed: ' + err.message, 'error');
      }
    };
    input.click();
  },
};

function showHome() {
  editorView.hidden = true;
  homeView.hidden = false;
  closePopover();
  closeContextMenu();
  renderHome(app);
  renderRecents(app);
}

function showEditor() {
  homeView.hidden = true;
  editorView.hidden = false;
  initEditorOnce();
  editor.renderer.render();
  editor.overlay.render();
  store.emit('pages');
  store.emit('history');
  requestAnimationFrame(() => editor.interactions.fitToScreen());
}

function initEditorOnce() {
  if (editor) return;
  const workspace = document.getElementById('workspace');
  const sizer = document.getElementById('sizer');
  const scaler = document.getElementById('scaler');
  const pageEl = document.getElementById('page');
  const overlayEl = document.getElementById('overlay');

  const renderer = new PageRenderer(pageEl, store);
  const overlay = new Overlay(overlayEl, store);
  const interactions = new Interactions({
    workspace, scaler, pageEl, overlay, store,
    onContextMenu: e => openContextMenu(e, store),
  });
  editor = { renderer, overlay, interactions };

  // Render loop: renderer first (it fixes text auto-heights), then overlay.
  store.on('doc', () => { renderer.render(); overlay.render(); });
  store.on('selection', () => { renderer.render(); overlay.render(); });

  const applyZoom = () => {
    const doc = store.doc;
    if (!doc) return;
    scaler.style.transform = `scale(${store.zoom})`;
    scaler.style.width = doc.width + 'px';
    scaler.style.height = doc.height + 'px';
    sizer.style.width = doc.width * store.zoom + 96 + 'px';
    sizer.style.height = doc.height * store.zoom + 96 + 'px';
    overlay.render();
    updateZoomLabel();
  };
  store.on('zoom', applyZoom);
  store.on('doc', applyZoom);
  store.on('fit-request', () => interactions.fitToScreen());

  installShortcuts({ store, interactions });
  initTopbar({ store, root: document.getElementById('topbar'), app });
  initToolbar({ store, root: document.getElementById('ctxbar') });
  sidebarApi = initSidebar({
    store,
    railEl: document.getElementById('rail'),
    panelEl: document.getElementById('panel'),
    app,
  });
  initPagestrip({ store, root: document.getElementById('pagestrip') });
  initZoombar(interactions);

  // Autosave: debounce commits.
  store.on('dirty', () => {
    app.onSaveState?.('Saving…');
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => app.saveNow(), 900);
  });

  // Uploads via the hidden input.
  document.getElementById('upload-file').addEventListener('change', async e => {
    await handleUploadFiles(store, [...e.target.files]);
    e.target.value = '';
  });

  // Drag & drop images onto the canvas.
  workspace.addEventListener('dragover', e => e.preventDefault());
  workspace.addEventListener('drop', async e => {
    e.preventDefault();
    const files = [...(e.dataTransfer?.files || [])].filter(f => f.type.startsWith('image/'));
    if (files.length) {
      await handleUploadFiles(store, files, interactions.toPage(e.clientX, e.clientY));
    }
  });

  // OS clipboard paste: screenshots and plain text from outside the app.
  document.addEventListener('paste', async e => {
    if (editorView.hidden || store.editingTextId || isTypingTarget(e.target)) return;
    const files = [...(e.clipboardData?.files || [])].filter(f => f.type.startsWith('image/'));
    if (files.length) {
      e.preventDefault();
      await handleUploadFiles(store, files);
      return;
    }
    const text = e.clipboardData?.getData('text/plain');
    if (text && text.trim()) {
      e.preventDefault();
      commands.createAndAdd(store, 'text', {
        text: text.trim().slice(0, 2000),
        w: Math.round(store.doc.width * 0.6),
        fontSize: Math.round(store.doc.width * 0.033),
      });
    }
  });

  // Flush in-progress edits before the tab goes away.
  window.addEventListener('beforeunload', () => {
    if (!editorView.hidden) app.saveNow();
  });

  // First-run hint.
  try {
    if (!localStorage.getItem('canvaclone.hint')) {
      const hint = document.getElementById('hint');
      hint.hidden = false;
      hint.append(
        h('span', {}, 'Double-click text to edit · drag to move · Ctrl+wheel to zoom'),
        h('button', {
          onclick: () => {
            hint.hidden = true;
            try { localStorage.setItem('canvaclone.hint', '1'); } catch { /* ignore */ }
          },
        }, 'Got it'),
      );
    }
  } catch { /* storage blocked */ }
}

function initZoombar(interactions) {
  const bar = document.getElementById('zoombar');
  bar.append(
    iconBtn('−', 'Zoom out', () => interactions.zoomCentered(store.zoom / 1.25), { 'data-testid': 'zoom-out' }),
    h('span', { class: 'zoom-label', 'data-testid': 'zoom-label', title: 'Zoom to 100%', onclick: () => interactions.zoomCentered(1) }, '100%'),
    iconBtn('+', 'Zoom in', () => interactions.zoomCentered(store.zoom * 1.25), { 'data-testid': 'zoom-in' }),
    iconBtn('⤢', 'Fit to screen (Ctrl+0)', () => interactions.fitToScreen(), { 'data-testid': 'zoom-fit' }),
  );
}

function updateZoomLabel() {
  const label = document.querySelector('#zoombar .zoom-label');
  if (label) label.textContent = Math.round(store.zoom * 100) + '%';
}

// ---- routing ----
function route() {
  if (location.hash.startsWith('#/edit') && store.doc) showEditor();
  else showHome();
}
window.addEventListener('hashchange', route);
route();

// Test hook for Playwright.
window.__canvia = { store, app, commands };
