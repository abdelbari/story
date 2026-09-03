// Layers panel: current page's elements top-first, with selection,
// drag-to-reorder, lock and delete.

import { h, iconBtn } from './widgets.js';
import * as commands from '../editor/commands.js';
import { SHAPE_MAP } from '../assets/shapes.js';
import { resolveImageSrc } from '../assets/photos.js';
import { paintToCss } from '../core/paint.js';

export function renderLayersPanel(body, store) {
  const page = store.page;
  if (!page) return;
  const list = h('div', { 'data-testid': 'layers-list' });
  body.appendChild(list);

  if (!page.elements.length) {
    list.appendChild(h('div', { class: 'empty-state' }, 'This page is empty — add elements from the sidebar'));
    return;
  }

  // Top-most first.
  const elements = [...page.elements].reverse();
  let dragIndex = null; // index within `elements` (reversed order)

  elements.forEach((el, i) => {
    const row = h('div', {
      class: 'layer-row' + (store.selection.includes(el.id) ? ' selected' : ''),
      draggable: true,
      'data-testid': `layer-${el.id}`,
      onclick: e => store.select([el.id], { additive: e.shiftKey }),
    },
      h('div', { class: 'l-thumb' }, layerThumb(el)),
      h('div', { class: 'l-name' }, layerName(el)),
      h('div', { class: 'l-actions' },
        iconBtn(el.locked ? '🔒' : '🔓', el.locked ? 'Unlock' : 'Lock', e => {
          e.stopPropagation();
          store.apply(() => { el.locked = !el.locked; });
        }),
        iconBtn('🗑', 'Delete', e => {
          e.stopPropagation();
          store.apply(doc => {
            const p = doc.pages[store.pageIndex];
            p.elements = p.elements.filter(x => x.id !== el.id);
          });
          store.selection = store.selection.filter(id => id !== el.id);
          store.emit('selection');
        }),
      ),
    );

    row.addEventListener('dragstart', e => {
      dragIndex = i;
      row.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
    });
    row.addEventListener('dragend', () => row.classList.remove('dragging'));
    row.addEventListener('dragover', e => e.preventDefault());
    row.addEventListener('drop', e => {
      e.preventDefault();
      if (dragIndex === null || dragIndex === i) return;
      // Convert reversed indexes back to array indexes and move.
      const from = page.elements.length - 1 - dragIndex;
      const to = page.elements.length - 1 - i;
      store.apply(doc => {
        const arr = doc.pages[store.pageIndex].elements;
        const [moved] = arr.splice(from, 1);
        arr.splice(to, 0, moved);
      });
      dragIndex = null;
    });

    list.appendChild(row);
  });
}

function layerName(el) {
  switch (el.type) {
    case 'text': return el.text.split('\n')[0].slice(0, 40) || 'Text';
    case 'shape': return SHAPE_MAP[el.shapeId]?.name || 'Shape';
    case 'image': return 'Image';
    case 'sticker': return `Sticker ${el.glyph}`;
    case 'line': return 'Line';
    default: return el.type;
  }
}

function layerThumb(el) {
  if (el.type === 'shape') {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '-4 -4 108 108');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', (SHAPE_MAP[el.shapeId] || SHAPE_MAP.rect).path);
    path.setAttribute('fill', el.fill?.kind === 'gradient' ? el.fill.stops[0].color : (el.fill?.color || '#888'));
    svg.appendChild(path);
    return svg;
  }
  if (el.type === 'image') {
    return h('img', { src: resolveImageSrc(el.src), style: { width: '100%', height: '100%', objectFit: 'cover' } });
  }
  if (el.type === 'sticker') return el.glyph;
  if (el.type === 'text') return h('span', { style: { fontWeight: 700, color: el.color } }, 'T');
  if (el.type === 'line') return h('span', { style: { color: el.color } }, '—');
  return '?';
}
