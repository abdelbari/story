// Left rail + slide-in panels: Design (templates), Elements (shapes, lines,
// stickers), Text (inserts + pairings), Uploads, Photos, Background, Layers.

import { h, toast } from './widgets.js';
import * as commands from '../editor/commands.js';
import { createText as createTextElement } from '../core/doc.js';
import { SHAPES, shapeCategories } from '../assets/shapes.js';
import { STICKER_GROUPS } from '../assets/stickers.js';
import { listPhotos, photoURI } from '../assets/photos.js';
import { FONT_STACKS, fontStack } from '../assets/typography.js';
import { FONT_PAIRINGS } from '../assets/content.js';
import { TEMPLATES, templateCategories, instantiatePage } from '../assets/templates.js';
import { DEFAULT_SWATCHES, GRADIENT_PRESETS, documentColors } from '../assets/palettes.js';
import { paintToCss } from '../core/paint.js';
import { readFileAsDataURL } from '../core/persistence.js';
import { makeThumbnail } from '../export/exporter.js';
import { renderLayersPanel } from './layers.js';
import { openColorPicker } from './colorpicker.js';

const TABS = [
  { id: 'design', icon: '🎨', label: 'Design' },
  { id: 'elements', icon: '⬡', label: 'Elements' },
  { id: 'text', icon: 'T', label: 'Text' },
  { id: 'uploads', icon: '⇪', label: 'Uploads' },
  { id: 'photos', icon: '🖼', label: 'Photos' },
  { id: 'background', icon: '▦', label: 'Background' },
  { id: 'layers', icon: '≣', label: 'Layers' },
];

const sessionUploads = [];
let replaceMode = false;
const templateThumbs = new Map();

// Single listener; the uploads panel swaps in its current re-render (a
// per-render closure would leak one listener per panel open).
let uploadsRerender = null;
window.addEventListener('canvia:uploads-changed', () => uploadsRerender?.());

export function initSidebar({ store, railEl, panelEl, app }) {
  let activeTab = null;

  window.addEventListener('canvia:replace-image', () => { replaceMode = true; });

  const renderRail = () => {
    railEl.innerHTML = '';
    for (const tab of TABS) {
      railEl.appendChild(h('button', {
        class: 'rail-tab' + (tab.id === activeTab ? ' active' : ''),
        'data-testid': `tab-${tab.id}`,
        onclick: () => {
          activeTab = activeTab === tab.id ? null : tab.id;
          renderRail();
          renderPanel();
        },
      }, h('span', { class: 'r-icon' }, tab.icon), tab.label));
    }
  };

  const renderPanel = () => {
    if (!activeTab) { panelEl.hidden = true; return; }
    panelEl.hidden = false;
    panelEl.innerHTML = '';
    const tab = TABS.find(t => t.id === activeTab);
    panelEl.append(
      h('div', { class: 'panel-head' }, tab.label,
        h('button', { class: 'icon-btn', 'data-testid': 'panel-collapse', onclick: () => { activeTab = null; renderRail(); renderPanel(); } }, '«')),
    );
    const body = h('div', { class: 'panel-body' });
    panelEl.appendChild(body);
    switch (activeTab) {
      case 'design': designPanel(body, store, app); break;
      case 'elements': elementsPanel(body, store); break;
      case 'text': textPanel(body, store); break;
      case 'uploads': uploadsPanel(body, store, app); break;
      case 'photos': photosPanel(body, store); break;
      case 'background': backgroundPanel(body, store); break;
      case 'layers': renderLayersPanel(body, store); break;
    }
  };

  // Layers panel needs live refresh.
  store.on('doc', () => { if (activeTab === 'layers') renderPanel(); });
  store.on('selection', () => { if (activeTab === 'layers') renderPanel(); });
  store.on('pages', () => { if (activeTab === 'layers') renderPanel(); });

  renderRail();
  renderPanel();
  return {
    open(tabId) { activeTab = tabId; renderRail(); renderPanel(); },
  };
}

// ---- Design (templates) ----
function designPanel(body, store, app) {
  const search = searchBox(body, 'Search templates', 'design-search');
  const cats = templateCategories();
  let activeCat = 'All';
  const chipRow = h('div', { class: 'row', style: { flexWrap: 'wrap', marginBottom: '10px' } });
  const grid = h('div', { style: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' } });

  const renderGrid = async () => {
    const q = search.value.trim().toLowerCase();
    grid.innerHTML = '';
    const matches = TEMPLATES.filter(t =>
      (activeCat === 'All' || t.category === activeCat) &&
      (!q || t.name.toLowerCase().includes(q) || t.category.toLowerCase().includes(q)));
    if (!matches.length) {
      grid.appendChild(h('div', { class: 'empty-state' }, 'No templates match'));
      return;
    }
    for (const template of matches) {
      const img = h('img', { alt: template.name, style: { aspectRatio: `${template.width} / ${template.height}` } });
      thumbnailFor(template).then(uri => { img.src = uri; });
      grid.appendChild(h('button', {
        class: 'template-tile', 'data-testid': `template-${template.id}`,
        title: `${template.name} — click to apply to this page`,
        onclick: () => applyTemplate(store, template),
      }, img, h('div', { class: 'tt-name' }, template.name)));
    }
  };

  for (const cat of cats) {
    const chip = h('button', {
      class: 'chip' + (cat === activeCat ? ' active' : ''),
      onclick: () => {
        activeCat = cat;
        chipRow.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        renderGrid();
      },
    }, cat);
    chipRow.appendChild(chip);
  }
  search.addEventListener('input', renderGrid);
  body.append(chipRow, grid);
  renderGrid();
}

async function thumbnailFor(template) {
  if (!templateThumbs.has(template.id)) {
    const doc = { width: template.width, height: template.height, pages: [] };
    const page = instantiatePage(template);
    templateThumbs.set(template.id, makeThumbnail(doc, page, 300));
  }
  return templateThumbs.get(template.id);
}

// Apply a template into the CURRENT page, uniformly scaled to the doc size.
function applyTemplate(store, template) {
  const doc = store.doc;
  const scale = doc.width / template.width;
  const dy = (doc.height - template.height * scale) / 2;
  const page = instantiatePage(template);
  for (const el of page.elements) {
    el.x *= scale;
    el.y = el.y * scale + dy;
    el.w *= scale;
    el.h *= scale;
    if (el.type === 'text') el.fontSize *= scale;
    if (el.type === 'line') el.thickness = Math.max(1, el.thickness * scale);
  }
  store.apply(d => {
    const current = d.pages[store.pageIndex];
    current.background = page.background;
    current.elements = page.elements;
  });
  store.clearSelection();
  toast(`Applied “${template.name}”`);
}

// ---- Elements ----
function elementsPanel(body, store) {
  const search = searchBox(body, 'Search shapes & stickers', 'elements-search');
  const content = h('div');
  body.appendChild(content);

  const render = () => {
    const q = search.value.trim().toLowerCase();
    content.innerHTML = '';

    // Lines section
    if (!q || 'line arrow divider'.includes(q)) {
      content.appendChild(h('h4', {}, 'Lines'));
      const lineGrid = h('div', { class: 'tile-grid' });
      const lineDefs = [
        ['Line', {}], ['Arrow', { endCap: 'arrow' }],
        ['Double arrow', { startCap: 'arrow', endCap: 'arrow' }], ['Dashed', { dash: 'dashed' }],
      ];
      for (const [name, props] of lineDefs) {
        lineGrid.appendChild(h('button', {
          class: 'tile', title: name, 'data-testid': `el-line-${name.toLowerCase().replace(/\s/g, '-')}`,
          onclick: () => commands.createAndAdd(store, 'line', { ...props, w: Math.round(store.doc.width * 0.3) }),
        }, lineIcon(props)));
      }
      content.appendChild(lineGrid);
    }

    // Shapes by category
    for (const cat of shapeCategories()) {
      const shapes = SHAPES.filter(s => s.category === cat &&
        (!q || s.name.toLowerCase().includes(q) || cat.toLowerCase().includes(q)));
      if (!shapes.length) continue;
      content.appendChild(h('h4', {}, cat));
      const grid = h('div', { class: 'tile-grid' });
      for (const shape of shapes) {
        grid.appendChild(h('button', {
          class: 'tile', title: shape.name, 'data-testid': `el-shape-${shape.id}`,
          onclick: () => {
            const size = Math.round(Math.min(store.doc.width, store.doc.height) * 0.28);
            commands.createAndAdd(store, 'shape', { shapeId: shape.id, w: size, h: size });
          },
        }, shapeIcon(shape)));
      }
      content.appendChild(grid);
    }

    // Stickers
    for (const group of STICKER_GROUPS) {
      const emoji = group.emoji.filter(e => !q || group.name.toLowerCase().includes(q));
      if (!emoji.length) continue;
      content.appendChild(h('h4', {}, group.name));
      const grid = h('div', { class: 'tile-grid' });
      for (const glyph of emoji) {
        grid.appendChild(h('button', {
          class: 'tile', title: glyph, 'data-testid': `el-sticker-${glyph}`,
          onclick: () => {
            const size = Math.round(Math.min(store.doc.width, store.doc.height) * 0.18);
            commands.createAndAdd(store, 'sticker', { glyph, w: size, h: size });
          },
        }, h('span', { class: 'sticker-glyph' }, glyph)));
      }
      content.appendChild(grid);
    }
  };
  search.addEventListener('input', render);
  render();
}

function shapeIcon(shape) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '-4 -4 108 108');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', shape.path);
  path.setAttribute('fill', '#545d6b');
  svg.appendChild(path);
  return svg;
}

function lineIcon(props) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 100 100');
  svg.innerHTML = `<line x1="12" y1="50" x2="88" y2="50" stroke="#545d6b" stroke-width="6" stroke-linecap="round" ${props.dash === 'dashed' ? 'stroke-dasharray="14 10"' : ''}/>` +
    (props.endCap === 'arrow' ? '<path d="M78,38L96,50L78,62Z" fill="#545d6b"/>' : '') +
    (props.startCap === 'arrow' ? '<path d="M22,38L4,50L22,62Z" fill="#545d6b"/>' : '');
  return svg;
}

// ---- Text ----
function textPanel(body, store) {
  const docW = () => store.doc.width;
  const inserts = [
    ['Add a heading', 'add-heading', { text: 'Add a heading', fontSize: 0.08, fontWeight: 700 }],
    ['Add a subheading', 'add-subheading', { text: 'Add a subheading', fontSize: 0.045, fontWeight: 600 }],
    ['Add body text', 'add-body', { text: 'Add a little bit of body text', fontSize: 0.028, fontWeight: 400 }],
  ];
  for (const [label, testid, spec] of inserts) {
    const size = Math.round(docW() * spec.fontSize);
    body.appendChild(h('button', {
      class: 'text-insert', 'data-testid': testid,
      style: { fontSize: Math.min(26, 10 + spec.fontSize * 220) + 'px', fontWeight: spec.fontWeight },
      onclick: () => commands.createAndAdd(store, 'text', {
        text: spec.text, fontSize: size, fontWeight: spec.fontWeight,
        w: Math.round(docW() * 0.72), align: 'center',
      }),
    }, label));
  }

  if (FONT_PAIRINGS.length) {
    body.appendChild(h('h4', {}, 'Font pairings'));
    for (const pairing of FONT_PAIRINGS) {
      body.appendChild(h('button', {
        class: 'pairing-card', 'data-testid': `pairing-${pairing.name.toLowerCase()}`,
        onclick: () => insertPairing(store, pairing),
      },
        h('div', { class: 'pc-name' }, pairing.name),
        h('div', { style: { fontFamily: fontStack(pairing.heading.fontFamily), fontWeight: pairing.heading.fontWeight, fontSize: '19px', letterSpacing: (pairing.heading.letterSpacing || 0) / 4 + 'px' } }, pairing.heading.text),
        h('div', { style: { fontFamily: fontStack(pairing.body.fontFamily), fontWeight: pairing.body.fontWeight, fontSize: '12.5px', color: 'var(--text-dim)', marginTop: '2px' } }, pairing.body.text),
      ));
    }
  }

  body.appendChild(h('h4', {}, 'Font personalities'));
  for (const [key, font] of Object.entries(FONT_STACKS)) {
    body.appendChild(h('button', {
      class: 'text-insert', style: { fontFamily: font.stack, fontSize: '16px', padding: '9px 14px' },
      title: `Apply ${font.name} to selected text (or insert a sample)`,
      'data-testid': `font-${key}`,
      onclick: () => {
        const selectedText = store.selectedElements().filter(el => el.type === 'text');
        if (selectedText.length) {
          commands.updateSelected(store, { fontFamily: key });
        } else {
          commands.createAndAdd(store, 'text', {
            text: font.name, fontFamily: key,
            fontSize: Math.round(docW() * 0.055), w: Math.round(docW() * 0.6),
          });
        }
      },
    }, font.name));
  }
}

function insertPairing(store, pairing) {
  const docW = store.doc.width;
  const scale = docW / 1080;
  const x = Math.round(docW * 0.14);
  const w = Math.round(docW * 0.72);
  const headingSize = Math.round((pairing.heading.fontSize || 72) * scale);
  const bodySize = Math.round((pairing.body.fontSize || 28) * scale);
  const y0 = Math.round(store.doc.height * 0.38);
  const heading = createText(pairing.heading, headingSize, x, y0);
  const body = createText(pairing.body, bodySize, x, y0 + Math.round(headingSize * 1.45));
  store.apply(doc => {
    doc.pages[store.pageIndex].elements.push(heading, body);
  });
  store.select([heading.id, body.id]);

  function createText(spec, fontSize, tx, ty) {
    return createTextElement({
      text: spec.text, fontFamily: spec.fontFamily, fontWeight: spec.fontWeight,
      fontSize, letterSpacing: (spec.letterSpacing || 0) * scale,
      x: tx, y: ty, w, align: 'center',
    });
  }
}

// ---- Uploads ----
function uploadsPanel(body, store, app) {
  body.appendChild(h('button', {
    class: 'btn btn-primary', style: { width: '100%' }, 'data-testid': 'btn-upload',
    onclick: () => document.getElementById('upload-file').click(),
  }, '⇪ Upload image'));
  body.appendChild(h('div', { class: 'upload-drop' },
    'Drag & drop images anywhere on the canvas, or paste (Ctrl+V) a screenshot. Images are embedded into the design file.'));

  const grid = h('div', { class: 'tile-grid wide', style: { marginTop: '12px' } });
  body.appendChild(grid);

  uploadsRerender = () => {
    grid.innerHTML = '';
    const seen = new Set(sessionUploads);
    for (const page of store.doc.pages) {
      for (const el of page.elements) {
        if (el.type === 'image' && el.src && el.src.startsWith('data:')) seen.add(el.src);
      }
    }
    for (const src of seen) {
      grid.appendChild(h('button', {
        class: 'photo-tile', title: 'Insert (or Replace if active)',
        onclick: () => insertOrReplaceImage(store, src),
      }, h('img', { src })));
    }
    if (!seen.size) grid.appendChild(h('div', { class: 'empty-state', style: { gridColumn: '1 / -1' } }, 'Your uploads will appear here'));
  };
  uploadsRerender();
}

export async function handleUploadFiles(store, files, dropPoint) {
  for (const file of files) {
    if (!file.type.startsWith('image/')) continue;
    const dataUri = await downscaleImage(await readFileAsDataURL(file));
    sessionUploads.unshift(dataUri);
    insertOrReplaceImage(store, dataUri, dropPoint);
  }
  window.dispatchEvent(new CustomEvent('canvia:uploads-changed'));
}

export function insertOrReplaceImage(store, src, dropPoint) {
  const selected = store.selectedElements();
  if (replaceMode && selected.length === 1 && selected[0].type === 'image') {
    commands.updateSelected(store, { src, cropScale: 1, cropX: 0.5, cropY: 0.5 });
    replaceMode = false;
    toast('Image replaced');
    return;
  }
  replaceMode = false;
  const doc = store.doc;
  const w = Math.round(doc.width * 0.45);
  const el = commands.createAndAdd(store, 'image', {
    src, w, h: Math.round(w * 0.75),
    ...(dropPoint ? { x: Math.round(dropPoint.x - w / 2), y: Math.round(dropPoint.y - w * 0.375) } : {}),
  }, { center: !dropPoint });
  return el;
}

async function downscaleImage(dataUri, maxEdge = 1600) {
  const img = await new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = dataUri;
  });
  if (Math.max(img.naturalWidth, img.naturalHeight) <= maxEdge) return dataUri;
  const scale = maxEdge / Math.max(img.naturalWidth, img.naturalHeight);
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(img.naturalWidth * scale);
  canvas.height = Math.round(img.naturalHeight * scale);
  canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.85);
}

// ---- Photos ----
function photosPanel(body, store) {
  const search = searchBox(body, 'Search photos', 'photos-search');
  const grid = h('div', { class: 'tile-grid wide' });
  body.appendChild(grid);
  const render = () => {
    const q = search.value.trim().toLowerCase();
    grid.innerHTML = '';
    for (const photo of listPhotos()) {
      if (q && !photo.name.toLowerCase().includes(q) && !photo.category.toLowerCase().includes(q)) continue;
      grid.appendChild(h('button', {
        class: 'photo-tile', title: photo.name, 'data-testid': `photo-${photo.id}`,
        onclick: () => insertOrReplaceImage(store, `asset:${photo.id}`),
      }, h('img', { src: photoURI(photo.id), loading: 'lazy' })));
    }
  };
  search.addEventListener('input', render);
  render();
}

// ---- Background ----
function backgroundPanel(body, store) {
  body.appendChild(h('h4', {}, 'Document colors'));
  const docRow = h('div', { class: 'swatch-grid' });
  for (const color of documentColors(store.doc)) {
    docRow.appendChild(swatch(color, () => commands.setBackground(store, { type: 'color', value: color })));
  }
  body.appendChild(docRow);

  body.appendChild(h('h4', {}, 'Solid colors'));
  const solidRow = h('div', { class: 'swatch-grid' });
  for (const color of DEFAULT_SWATCHES) {
    solidRow.appendChild(swatch(color, () => commands.setBackground(store, { type: 'color', value: color }), `bg-${color.replace('#', '')}`));
  }
  const customBtn = h('button', { class: 'swatch', style: { background: 'conic-gradient(red, yellow, lime, cyan, blue, magenta, red)' }, title: 'Custom color', 'data-testid': 'bg-custom' });
  customBtn.addEventListener('click', () => openColorPicker(customBtn, {
    store,
    current: store.page.background?.type === 'color' ? store.page.background.value : '#ffffff',
    onPick: c => commands.setBackground(store, { type: 'color', value: c }),
    onPickGradient: g => commands.setBackground(store, { type: 'gradient', value: g }),
  }));
  solidRow.appendChild(customBtn);
  body.appendChild(solidRow);

  body.appendChild(h('h4', {}, 'Gradients'));
  const gradRow = h('div', { class: 'swatch-grid' });
  for (const g of GRADIENT_PRESETS) {
    const paint = { kind: 'gradient', angle: g.angle, stops: g.stops };
    gradRow.appendChild(swatch(paintToCss(paint), () =>
      commands.setBackground(store, { type: 'gradient', value: JSON.parse(JSON.stringify(paint)) }), `bg-grad-${g.id}`));
  }
  body.appendChild(gradRow);

  body.appendChild(h('h4', {}, 'Photo backgrounds'));
  const photoGrid = h('div', { class: 'tile-grid wide' });
  for (const photo of listPhotos()) {
    photoGrid.appendChild(h('button', {
      class: 'photo-tile', title: photo.name, 'data-testid': `bg-photo-${photo.id}`,
      onclick: () => commands.setBackground(store, { type: 'image', value: `asset:${photo.id}` }),
    }, h('img', { src: photoURI(photo.id), loading: 'lazy' })));
  }
  body.appendChild(photoGrid);

  function swatch(background, onclick, testid) {
    const el = h('button', { class: 'swatch', title: 'Set background', 'data-testid': testid || false });
    el.style.background = background;
    el.addEventListener('click', onclick);
    return el;
  }
}

function searchBox(body, placeholder, testid) {
  const input = h('input', { class: 'panel-search', placeholder, 'data-testid': testid });
  body.appendChild(input);
  return input;
}
