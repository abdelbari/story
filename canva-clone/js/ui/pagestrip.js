// Bottom page strip: live page thumbnails, switch/add/duplicate/delete and
// reorder (move left/right). Thumbnails re-render lazily after commits.

import { h, iconBtn, confirmDialog } from './widgets.js';
import * as commands from '../editor/commands.js';
import { makeThumbnail } from '../export/exporter.js';

export function initPagestrip({ store, root }) {
  let thumbTimer = null;
  const thumbCache = new Map(); // page id -> {rev, uri}
  let rev = 0;

  const render = async () => {
    const doc = store.doc;
    if (!doc) return;
    root.innerHTML = '';

    doc.pages.forEach((page, i) => {
      const img = h('img', { alt: `Page ${i + 1}` });
      const cached = thumbCache.get(page.id);
      if (cached) img.src = cached.uri;
      const thumb = h('button', {
        class: 'page-thumb' + (i === store.pageIndex ? ' active' : ''),
        'data-testid': `page-thumb-${i}`,
        style: { aspectRatio: `${doc.width} / ${doc.height}` },
        onclick: () => store.setPage(i),
      }, img, h('span', { class: 'p-num' }, String(i + 1)));
      root.appendChild(thumb);
      refreshThumb(doc, page, img);
    });

    root.appendChild(h('button', {
      class: 'page-add', title: 'Add page', 'data-testid': 'btn-add-page',
      onclick: () => commands.addPage(store),
    }, '+'));

    root.appendChild(h('div', { class: 'page-actions' },
      iconBtn('⧉', 'Duplicate page', () => commands.duplicatePage(store), { 'data-testid': 'btn-duplicate-page' }),
      iconBtn('◀', 'Move page left', () => commands.movePage(store, store.pageIndex, store.pageIndex - 1), { 'data-testid': 'btn-page-left' }),
      iconBtn('▶', 'Move page right', () => commands.movePage(store, store.pageIndex, store.pageIndex + 1), { 'data-testid': 'btn-page-right' }),
      iconBtn('🗑', 'Delete page', async () => {
        if (store.doc.pages.length <= 1) return;
        const ok = await confirmDialog({ title: 'Delete page?', message: 'This page and its contents will be removed.' });
        if (ok) commands.deletePage(store);
      }, { 'data-testid': 'btn-delete-page', disabled: store.doc.pages.length <= 1 }),
    ));
  };

  async function refreshThumb(doc, page, img) {
    const cached = thumbCache.get(page.id);
    if (cached && cached.rev === rev) return;
    try {
      const uri = await makeThumbnail(doc, page, 200);
      thumbCache.set(page.id, { rev, uri });
      img.src = uri;
    } catch { /* ignore thumbnail failures */ }
  }

  store.on('pages', render);
  store.on('history', () => {
    // Debounce thumbnail regeneration after edits.
    rev += 1;
    clearTimeout(thumbTimer);
    thumbTimer = setTimeout(render, 500);
  });
  render();
}
