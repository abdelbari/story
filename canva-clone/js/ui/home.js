// Home screen: size presets, recent designs (localStorage), template
// gallery and JSON import.

import { h, confirmDialog, promptDialog, relativeTime, toast } from './widgets.js';
import { SIZE_PRESETS } from '../core/doc.js';
import { listRecentDesigns, loadDocById, deleteDocById, saveDoc } from '../core/persistence.js';
import { TEMPLATES, instantiateTemplate, instantiatePage } from '../assets/templates.js';
import { makeThumbnail } from '../export/exporter.js';
import { openPopover, closePopover } from './widgets.js';

const templateThumbs = new Map();

export function renderHome(app) {
  renderPresets(app);
  renderRecents(app);
  renderTemplates(app);

  const importBtn = document.getElementById('btn-import-design');
  importBtn.onclick = () => app.importDesign();

  document.getElementById('btn-custom-create').onclick = () => {
    const w = clampSize(document.getElementById('custom-w').value);
    const h2 = clampSize(document.getElementById('custom-h').value);
    app.newDesign({ width: w, height: h2, title: 'Untitled design' });
  };
}

function clampSize(v) {
  return Math.min(4000, Math.max(40, Number(v) || 1080));
}

function renderPresets(app) {
  const row = document.getElementById('preset-row');
  row.innerHTML = '';
  for (const preset of SIZE_PRESETS) {
    row.appendChild(h('button', {
      class: 'preset-card', 'data-testid': `preset-${preset.id}`,
      onclick: () => app.newDesign({ width: preset.w, height: preset.h, title: `Untitled ${preset.name}` }),
    },
      h('div', { class: 'p-icon' }, preset.icon),
      h('div', { class: 'p-name' }, preset.name),
      h('div', { class: 'p-dims' }, `${preset.w} × ${preset.h}`),
    ));
  }
}

export function renderRecents(app) {
  const section = document.getElementById('recent-section');
  const grid = document.getElementById('recent-grid');
  const recents = listRecentDesigns();
  section.hidden = recents.length === 0;
  grid.innerHTML = '';
  for (const entry of recents) {
    const card = h('div', {
      class: 'design-card', 'data-testid': `recent-${entry.id}`,
      onclick: () => {
        const doc = loadDocById(entry.id);
        if (doc) app.openDoc(doc);
        else toast('Could not load this design', 'error');
      },
    },
      entry.thumbnail
        ? h('img', { class: 'thumb', src: entry.thumbnail, alt: entry.title })
        : h('div', { class: 'thumb' }),
      h('div', { class: 'meta' },
        h('div', { class: 't-name' }, entry.title),
        h('div', { class: 't-sub' }, `${entry.width} × ${entry.height} · ${entry.pages} page${entry.pages > 1 ? 's' : ''} · ${relativeTime(entry.updatedAt)}`),
      ),
    );
    const kebab = h('button', { class: 'kebab', title: 'More' }, '⋯');
    kebab.addEventListener('click', e => {
      e.stopPropagation();
      openPopover(kebab, pop => {
        pop.append(
          h('button', {
            class: 'menu-item', onclick: async () => {
              closePopover();
              const name = await promptDialog({ title: 'Rename design', value: entry.title });
              if (name && name.trim()) {
                const doc = loadDocById(entry.id);
                if (doc) { doc.title = name.trim(); doc.updatedAt = Date.now(); saveDoc(doc, entry.thumbnail); renderRecents(app); }
              }
            },
          }, 'Rename'),
          h('button', {
            class: 'menu-item', onclick: () => {
              closePopover();
              const doc = loadDocById(entry.id);
              if (doc) {
                doc.id = doc.id + '-copy' + Math.floor(Math.random() * 1e6).toString(36);
                doc.title = doc.title + ' (copy)';
                doc.updatedAt = Date.now();
                saveDoc(doc, entry.thumbnail);
                renderRecents(app);
              }
            },
          }, 'Duplicate'),
          h('button', {
            class: 'menu-item', 'data-testid': `recent-delete-${entry.id}`, onclick: async () => {
              closePopover();
              const ok = await confirmDialog({ title: 'Delete design?', message: `“${entry.title}” will be permanently removed.` });
              if (ok) { deleteDocById(entry.id); renderRecents(app); }
            },
          }, 'Delete'),
        );
      });
    });
    card.appendChild(kebab);
    grid.appendChild(card);
  }
}

function renderTemplates(app) {
  const gallery = document.getElementById('template-gallery');
  gallery.innerHTML = '';
  for (const template of TEMPLATES) {
    const img = h('img', { class: 'thumb', alt: template.name });
    thumbFor(template).then(uri => { img.src = uri; });
    gallery.appendChild(h('div', {
      class: 'design-card', 'data-testid': `home-template-${template.id}`,
      onclick: () => app.openDoc(instantiateTemplate(template)),
    },
      img,
      h('div', { class: 'meta' },
        h('div', { class: 't-name' }, template.name),
        h('div', { class: 't-sub' }, `${template.category} · ${template.width} × ${template.height}`),
      ),
    ));
  }
}

async function thumbFor(template) {
  if (!templateThumbs.has(template.id)) {
    const doc = { width: template.width, height: template.height, pages: [] };
    templateThumbs.set(template.id, makeThumbnail(doc, instantiatePage(template), 340));
  }
  return templateThumbs.get(template.id);
}
