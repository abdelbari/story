// Editor commands: every mutation the toolbar, keyboard shortcuts, context
// menu and panels share. Each command routes through store.apply so it lands
// as exactly one undo step.

import { cloneElement, clonePage, createPage, FACTORIES } from '../core/doc.js';
import { elementAABB, unionBounds, uid, clamp } from '../core/geometry.js';

let clipboard = []; // deep-cloned elements
let pasteCount = 0;

export function addElement(store, el, { select = true, center = true } = {}) {
  if (center && el.x === 0 && el.y === 0) {
    el.x = Math.round((store.doc.width - el.w) / 2);
    el.y = Math.round((store.doc.height - el.h) / 2);
  }
  store.apply(doc => {
    doc.pages[store.pageIndex].elements.push(el);
  });
  if (select) store.select([el.id]);
  return el;
}

export function createAndAdd(store, type, props = {}) {
  return addElement(store, FACTORIES[type](props));
}

export function updateSelected(store, updates) {
  store.apply(() => {
    for (const el of store.selectedElements()) {
      if (el.locked && !('locked' in updates)) continue;
      Object.assign(el, typeof updates === 'function' ? updates(el) : updates);
    }
  });
}

// Transient variant for continuous controls (sliders); commit on release.
export function updateSelectedTransient(store, updates) {
  store.applyTransient(() => {
    for (const el of store.selectedElements()) {
      if (el.locked) continue;
      Object.assign(el, typeof updates === 'function' ? updates(el) : updates);
    }
  });
}

export function deleteSelected(store) {
  const ids = new Set(store.selection);
  if (!ids.size) return;
  store.apply(doc => {
    const page = doc.pages[store.pageIndex];
    page.elements = page.elements.filter(el => !ids.has(el.id) || el.locked);
  });
  store.clearSelection();
}

export function duplicateSelected(store) {
  const selected = store.selectedElements().filter(el => !el.locked);
  if (!selected.length) return;
  const copies = selected.map(el => cloneElement(el, 24));
  store.apply(doc => {
    doc.pages[store.pageIndex].elements.push(...copies);
  });
  store.select(copies.map(c => c.id));
}

export function copySelected(store) {
  const selected = store.selectedElements();
  if (!selected.length) return false;
  clipboard = selected.map(el => JSON.parse(JSON.stringify(el)));
  pasteCount = 0;
  return true;
}

export function cutSelected(store) {
  if (copySelected(store)) deleteSelected(store);
}

export function paste(store) {
  if (!clipboard.length) return;
  pasteCount += 1;
  const copies = clipboard.map(el => cloneElement(el, 24 * pasteCount));
  store.apply(doc => {
    doc.pages[store.pageIndex].elements.push(...copies);
  });
  store.select(copies.map(c => c.id));
}

export function selectAll(store) {
  store.select(store.page.elements.filter(el => !el.locked).map(el => el.id));
}

export function nudgeSelected(store, dx, dy, { commit = true } = {}) {
  const fn = doc => {
    for (const el of store.selectedElements()) {
      if (el.locked) continue;
      el.x += dx;
      el.y += dy;
    }
  };
  if (commit) store.apply(fn); else store.applyTransient(fn);
}

// ---- z-order -------------------------------------------------------------
function reorder(store, mover) {
  const ids = new Set(store.selection);
  if (!ids.size) return;
  store.apply(doc => {
    const page = doc.pages[store.pageIndex];
    mover(page, ids);
  });
}

export function bringToFront(store) {
  reorder(store, (page, ids) => {
    const rest = page.elements.filter(el => !ids.has(el.id));
    const moved = page.elements.filter(el => ids.has(el.id));
    page.elements = [...rest, ...moved];
  });
}

export function sendToBack(store) {
  reorder(store, (page, ids) => {
    const rest = page.elements.filter(el => !ids.has(el.id));
    const moved = page.elements.filter(el => ids.has(el.id));
    page.elements = [...moved, ...rest];
  });
}

export function bringForward(store) {
  reorder(store, (page, ids) => {
    for (let i = page.elements.length - 2; i >= 0; i--) {
      if (ids.has(page.elements[i].id) && !ids.has(page.elements[i + 1].id)) {
        [page.elements[i], page.elements[i + 1]] = [page.elements[i + 1], page.elements[i]];
      }
    }
  });
}

export function sendBackward(store) {
  reorder(store, (page, ids) => {
    for (let i = 1; i < page.elements.length; i++) {
      if (ids.has(page.elements[i].id) && !ids.has(page.elements[i - 1].id)) {
        [page.elements[i], page.elements[i - 1]] = [page.elements[i - 1], page.elements[i]];
      }
    }
  });
}

// ---- align / distribute --------------------------------------------------
// Single selection aligns to the page; multi-selection aligns to the
// combined bounds (Canva behavior).
export function alignSelected(store, mode) {
  const selected = store.selectedElements().filter(el => !el.locked);
  if (!selected.length) return;
  const single = selected.length === 1;
  const bounds = single
    ? { x: 0, y: 0, w: store.doc.width, h: store.doc.height }
    : unionBounds(selected.map(elementAABB));
  store.apply(() => {
    for (const el of selected) {
      const box = elementAABB(el);
      let dx = 0, dy = 0;
      if (mode === 'left') dx = bounds.x - box.x;
      if (mode === 'centerX') dx = bounds.x + bounds.w / 2 - (box.x + box.w / 2);
      if (mode === 'right') dx = bounds.x + bounds.w - (box.x + box.w);
      if (mode === 'top') dy = bounds.y - box.y;
      if (mode === 'centerY') dy = bounds.y + bounds.h / 2 - (box.y + box.h / 2);
      if (mode === 'bottom') dy = bounds.y + bounds.h - (box.y + box.h);
      el.x += dx;
      el.y += dy;
    }
  });
}

export function distributeSelected(store, axis) {
  const selected = store.selectedElements().filter(el => !el.locked);
  if (selected.length < 3) return;
  const boxes = selected.map(el => ({ el, box: elementAABB(el) }));
  const key = axis === 'x' ? 'x' : 'y';
  const size = axis === 'x' ? 'w' : 'h';
  boxes.sort((a, b) => a.box[key] - b.box[key]);
  const first = boxes[0], last = boxes[boxes.length - 1];
  const span = (last.box[key] + last.box[size]) - first.box[key];
  const total = boxes.reduce((sum, b) => sum + b.box[size], 0);
  const gap = (span - total) / (boxes.length - 1);
  store.apply(() => {
    let cursor = first.box[key];
    for (const { el, box } of boxes) {
      const delta = cursor - box[key];
      if (axis === 'x') el.x += delta; else el.y += delta;
      cursor += box[size] + gap;
    }
  });
}

// ---- grouping ------------------------------------------------------------
export function groupSelected(store) {
  const selected = store.selectedElements().filter(el => !el.locked);
  if (selected.length < 2) return;
  const gid = uid('grp');
  store.apply(() => {
    for (const el of selected) el.group = gid;
  });
}

export function ungroupSelected(store) {
  store.apply(() => {
    for (const el of store.selectedElements()) delete el.group;
  });
}

// Expand a clicked element to its whole group.
export function expandToGroup(store, id) {
  const el = store.elementById(id);
  if (!el || !el.group) return [id];
  return store.page.elements.filter(e => e.group === el.group).map(e => e.id);
}

// ---- misc element ops ----------------------------------------------------
export function toggleLockSelected(store) {
  const selected = store.selectedElements();
  const anyUnlocked = selected.some(el => !el.locked);
  store.apply(() => {
    for (const el of selected) el.locked = anyUnlocked;
  });
}

export function flipSelected(store, axis) {
  updateSelected(store, el => (axis === 'h' ? { flipH: !el.flipH } : { flipV: !el.flipV }));
}

export function setOpacitySelected(store, opacity, { commit = true } = {}) {
  const value = clamp(opacity, 0.02, 1);
  if (commit) updateSelected(store, { opacity: value });
  else updateSelectedTransient(store, { opacity: value });
}

// ---- pages ---------------------------------------------------------------
export function addPage(store, afterIndex = store.pageIndex) {
  const page = createPage(JSON.parse(JSON.stringify(store.page.background)));
  store.apply(doc => {
    doc.pages.splice(afterIndex + 1, 0, page);
  });
  store.setPage(afterIndex + 1);
  store.emit('pages');
}

export function duplicatePage(store, index = store.pageIndex) {
  store.apply(doc => {
    doc.pages.splice(index + 1, 0, clonePage(doc.pages[index]));
  });
  store.setPage(index + 1);
  store.emit('pages');
}

export function deletePage(store, index = store.pageIndex) {
  if (store.doc.pages.length <= 1) return;
  store.apply(doc => {
    doc.pages.splice(index, 1);
  });
  store.pageIndex = Math.min(store.pageIndex, store.doc.pages.length - 1);
  store.clearSelection();
  store.emit('pages');
  store.emit('doc');
}

export function movePage(store, from, to) {
  if (to < 0 || to >= store.doc.pages.length || from === to) return;
  const currentId = store.page.id;
  store.apply(doc => {
    const [page] = doc.pages.splice(from, 1);
    doc.pages.splice(to, 0, page);
  });
  store.pageIndex = store.doc.pages.findIndex(p => p.id === currentId);
  store.emit('pages');
  store.emit('doc');
}

export function setBackground(store, background) {
  store.apply(doc => {
    doc.pages[store.pageIndex].background = background;
  });
}

export function hasClipboard() {
  return clipboard.length > 0;
}
