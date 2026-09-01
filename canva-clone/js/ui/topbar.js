// Top bar: home/logo, File menu, undo/redo, resize, title, autosave badge,
// and the Export popover (PNG/JPEG at 1-3x, SVG, PDF, ZIP, JSON).

import { h, iconBtn, openPopover, closePopover, toast, promptDialog } from './widgets.js';
import { exportPNG, exportJPEG } from '../export/exporter.js';
import { exportSVG } from '../export/svg.js';
import { exportPDF } from '../export/pdf.js';
import { makeZip } from '../export/zip.js';
import { downloadJSON, triggerDownload, sanitizeFilename } from '../core/persistence.js';
import { SIZE_PRESETS } from '../core/doc.js';
import { celebrate } from './confetti.js';
import { PALETTES, documentColors, buildColorMapping, applyColorMapping } from '../assets/palettes.js';

export function initTopbar({ store, root, app }) {
  let saveBadge;

  const render = () => {
    root.innerHTML = '';
    root.append(
      h('button', { class: 'logo-sm', 'data-testid': 'btn-home', title: 'Back to home', onclick: () => app.goHome() }, 'Canvia'),

      fileMenuButton(store, app),

      iconBtn('↩', 'Undo (Ctrl+Z)', () => store.undo(),
        { 'data-testid': 'btn-undo', disabled: !store.canUndo() }),
      iconBtn('↪', 'Redo (Ctrl+Shift+Z)', () => store.redo(),
        { 'data-testid': 'btn-redo', disabled: !store.canRedo() }),

      resizeButton(store, app),
      shuffleButton(store),

      h('div', { class: 'topbar-spacer' }),
      titleInput(store),
      h('div', { class: 'topbar-spacer' }),

      saveBadge = h('span', { class: 'save-status', 'data-testid': 'save-status' }, 'Saved'),
      exportButton(store, app),
    );
  };

  store.on('history', render);
  store.on('doc-loaded', render);
  app.onSaveState = state => {
    if (saveBadge) saveBadge.textContent = state;
  };
  render();
}

function titleInput(store) {
  const input = h('input', {
    class: 'title-input', value: store.doc?.title || '', placeholder: 'Untitled design',
    maxlength: 80, 'data-testid': 'design-title',
  });
  input.addEventListener('change', () => {
    store.apply(doc => { doc.title = input.value.trim() || 'Untitled design'; });
  });
  input.addEventListener('keydown', e => { if (e.key === 'Enter') input.blur(); });
  return input;
}

function fileMenuButton(store, app) {
  const btn = h('button', { class: 'btn btn-ghost', 'data-testid': 'menu-file' }, 'File');
  btn.addEventListener('click', () => openPopover(btn, pop => {
    const item = (label, testid, onclick, kbd) => h('button', {
      class: 'menu-item', 'data-testid': testid,
      onclick: () => { closePopover(); onclick(); },
    }, label, kbd ? h('span', { class: 'mi-kbd' }, kbd) : null);
    pop.append(
      item('New design…', 'file-new', () => app.goHome()),
      item('Import design (JSON)', 'file-import', () => app.importDesign()),
      h('div', { class: 'menu-sep' }),
      item('Save now', 'file-save', () => { app.saveNow(); toast('Design saved'); }, 'auto'),
      item('Download as JSON', 'file-json', () => downloadJSON(store.doc)),
      h('div', { class: 'menu-sep' }),
      item('Design settings…', 'file-settings', () => openResizeDialog(store)),
    );
  }));
  return btn;
}

function resizeButton(store, app) {
  const btn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-resize' }, 'Resize');
  btn.addEventListener('click', () => openPopover(btn, pop => {
    pop.append(h('h5', {}, 'Resize design'));
    for (const preset of SIZE_PRESETS.slice(0, 8)) {
      pop.append(h('button', {
        class: 'menu-item', 'data-testid': `resize-${preset.id}`,
        onclick: () => { closePopover(); resizeDoc(store, preset.w, preset.h); },
      }, `${preset.name}`, h('span', { class: 'mi-kbd' }, `${preset.w}×${preset.h}`)));
    }
    const wIn = h('input', { class: 'num-input', type: 'number', value: store.doc.width, min: 40, max: 4000, 'data-testid': 'resize-w' });
    const hIn = h('input', { class: 'num-input', type: 'number', value: store.doc.height, min: 40, max: 4000, 'data-testid': 'resize-h' });
    pop.append(h('div', { class: 'menu-sep' }), h('div', { class: 'row' },
      wIn, h('span', {}, '×'), hIn,
      h('button', {
        class: 'btn btn-primary', 'data-testid': 'resize-apply',
        onclick: () => {
          const w = Math.min(4000, Math.max(40, Number(wIn.value) || store.doc.width));
          const hh = Math.min(4000, Math.max(40, Number(hIn.value) || store.doc.height));
          closePopover();
          resizeDoc(store, w, hh);
        },
      }, 'Apply')));
  }));
  return btn;
}

// Scale all pages' content proportionally to the new canvas (Canva's
// "magic resize", simplified): scale to fit the new box and centre on both
// axes, so an aspect-ratio change never strands content off the page.
function resizeDoc(store, w, hNew) {
  const doc = store.doc;
  if (w === doc.width && hNew === doc.height) return;
  // Scale to fit and centre, so an aspect-ratio change never strands content
  // off the bottom of the new canvas.
  const scale = Math.min(w / doc.width, hNew / doc.height);
  const dx = (w - doc.width * scale) / 2;
  const dy = (hNew - doc.height * scale) / 2;
  store.apply(d => {
    d.width = w;
    d.height = hNew;
    for (const page of d.pages) {
      for (const el of page.elements) {
        el.x = el.x * scale + dx;
        el.y = el.y * scale + dy;
        el.w *= scale;
        el.h *= scale;
        if (el.type === 'text') el.fontSize *= scale;
        if (el.type === 'line') el.thickness = Math.max(1, el.thickness * scale);
      }
    }
  });
  store.emit('fit-request');
  toast(`Resized to ${w} × ${hNew}`);
}

function shuffleButton(store) {
  let paletteIndex = 0;
  const btn = h('button', {
    class: 'btn btn-ghost', 'data-testid': 'btn-shuffle', title: 'Shuffle colors — remap the page onto a curated palette',
  }, '✨ Shuffle');
  btn.addEventListener('click', () => {
    const palette = PALETTES[paletteIndex % PALETTES.length];
    paletteIndex += 1;
    const colors = documentColors(store.doc, 24);
    if (!colors.length) return;
    const mapping = buildColorMapping(colors, palette.colors);
    store.apply(doc => {
      const page = doc.pages[store.pageIndex];
      applyColorMapping(page, page.background, mapping);
    });
    toast(`Shuffled onto “${palette.name}”`);
  });
  return btn;
}

function exportButton(store, app) {
  const btn = h('button', { class: 'btn btn-primary', 'data-testid': 'btn-export' }, 'Export ⤓');
  btn.addEventListener('click', () => openPopover(btn, pop => {
    pop.dataset.testid = 'export-popover';
    pop.style.width = '330px';
    const doc = store.doc;
    const multi = doc.pages.length > 1;
    const name = sanitizeFilename(doc.title);

    let scale = 2;
    pop.append(h('h5', {}, 'Quality'));
    const scaleRow = h('div', { class: 'row' });
    for (const s of [1, 2, 3]) {
      const chip = h('button', {
        class: 'chip' + (s === scale ? ' active' : ''), 'data-testid': `export-scale-${s}`,
        onclick: () => {
          scale = s;
          scaleRow.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
          chip.classList.add('active');
        },
      }, `${s}×`);
      scaleRow.appendChild(chip);
    }
    pop.appendChild(scaleRow);

    const busy = fn => async () => {
      btn.disabled = true;
      try {
        await fn();
        closePopover();
        celebrate();
      } catch (err) {
        console.error(err);
        toast('Export failed: ' + err.message, 'error');
      } finally {
        btn.disabled = false;
      }
    };

    const fmt = (label, sub, testid, onclick) => h('button', {
      class: 'export-format', 'data-testid': testid, onclick: busy(onclick),
    }, h('div', { class: 'ef-name' }, label), h('div', { class: 'ef-sub' }, sub));

    pop.append(h('h5', {}, 'Download'), h('div', { class: 'export-format-grid' },
      fmt('PNG', multi ? 'Current page, transparent-safe' : 'Best for sharing', 'export-png', async () => {
        const [blob] = await exportPNG(doc, [store.pageIndex], scale);
        triggerDownload(blob, `${name}.png`);
      }),
      fmt('JPEG', 'Smaller file', 'export-jpg', async () => {
        const [blob] = await exportJPEG(doc, [store.pageIndex], scale);
        triggerDownload(blob, `${name}.jpg`);
      }),
      fmt('SVG', 'Vector, current page', 'export-svg', async () => {
        const svg = exportSVG(doc, doc.pages[store.pageIndex]);
        triggerDownload(new Blob([svg], { type: 'image/svg+xml' }), `${name}.svg`);
      }),
      fmt('PDF', multi ? `All ${doc.pages.length} pages` : 'Print-ready', 'export-pdf', async () => {
        const blob = await exportPDF(doc, doc.pages.map((_, i) => i), scale);
        triggerDownload(blob, `${name}.pdf`);
      }),
      multi ? fmt('ZIP', `All pages as PNG`, 'export-zip', async () => {
        const blobs = await exportPNG(doc, doc.pages.map((_, i) => i), scale);
        const files = await Promise.all(blobs.map(async (b, i) => ({
          name: `${name}-page-${i + 1}.png`,
          data: new Uint8Array(await b.arrayBuffer()),
        })));
        triggerDownload(makeZip(files), `${name}.zip`);
      }) : null,
      fmt('JSON', 'Editable source', 'export-json', async () => {
        downloadJSON(doc);
      }),
    ));
  }, { align: 'right' }));
  return btn;
}

async function openResizeDialog(store) {
  const value = await promptDialog({
    title: 'Design settings',
    message: 'Design name',
    value: store.doc.title,
    okLabel: 'Save',
  });
  if (value !== null && value.trim()) {
    store.apply(doc => { doc.title = value.trim(); });
  }
}
