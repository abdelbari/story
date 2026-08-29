// Pointer interaction engine: selection, dragging with snapping, 8-handle
// resize (rotation-aware), rotation, marquee, panning, zooming and inline
// text editing. One gesture = one undo step (store.beginGesture/commit).

import {
  resizeElement, angleFromCenter, snapAngle, computeSnap,
  elementAABB, unionBounds, rectIntersectsElement, clamp,
} from '../core/geometry.js';
import { expandToGroup } from './commands.js';

const DRAG_THRESHOLD = 3;   // screen px before a drag starts
const SNAP_THRESHOLD = 6;   // screen px

export class Interactions {
  constructor({ workspace, scaler, pageEl, overlay, store, onContextMenu }) {
    this.workspace = workspace;
    this.scaler = scaler;
    this.pageEl = pageEl;
    this.overlay = overlay;
    this.store = store;
    this.onContextMenu = onContextMenu;
    this.gesture = null;
    this.spaceHeld = false;

    workspace.addEventListener('pointerdown', e => this.onPointerDown(e));
    workspace.addEventListener('pointermove', e => this.onHover(e));
    workspace.addEventListener('dblclick', e => this.onDoubleClick(e));
    workspace.addEventListener('wheel', e => this.onWheel(e), { passive: false });
    workspace.addEventListener('contextmenu', e => this.onContext(e));
    window.addEventListener('keydown', e => {
      if (e.code === 'Space' && !isTypingTarget(e.target) && !this.store.editingTextId) {
        this.spaceHeld = true;
        this.workspace.classList.add('cc-panning');
        e.preventDefault();
      }
    });
    window.addEventListener('keyup', e => {
      if (e.code === 'Space') {
        this.spaceHeld = false;
        this.workspace.classList.remove('cc-panning');
      }
    });
  }

  toPage(clientX, clientY) {
    const rect = this.pageEl.getBoundingClientRect();
    const z = this.store.zoom;
    return { x: (clientX - rect.left) / z, y: (clientY - rect.top) / z };
  }

  // ---- pointer routing ----
  onPointerDown(e) {
    if (e.button === 1 || (e.button === 0 && this.spaceHeld)) {
      this.startPan(e);
      return;
    }
    if (e.button !== 0) return;

    const handleEl = e.target.closest?.('[data-handle]');
    if (handleEl) {
      if (handleEl.dataset.group) this.startGroupResize(e, handleEl.dataset.handle);
      else this.startResize(e, handleEl.dataset.handle, handleEl.dataset.target);
      e.preventDefault();
      return;
    }
    const rotateEl = e.target.closest?.('[data-rotate]');
    if (rotateEl) {
      this.startRotate(e, rotateEl.dataset.rotate);
      e.preventDefault();
      return;
    }

    const elNode = e.target.closest?.('.cc-el');
    if (elNode) {
      const id = elNode.dataset.id;
      if (this.store.editingTextId === id) return; // native text caret handling
      this.commitTextEditIfAny();
      this.startElementGesture(e, id);
      e.preventDefault();
      return;
    }

    // Empty space: marquee (click clears selection).
    this.commitTextEditIfAny();
    this.startMarquee(e);
  }

  onHover(e) {
    if (this.gesture) return;
    const elNode = e.target.closest?.('.cc-el');
    this.overlay.setHover(elNode ? elNode.dataset.id : null);
  }

  onDoubleClick(e) {
    const elNode = e.target.closest?.('.cc-el');
    if (!elNode) return;
    const el = this.store.elementById(elNode.dataset.id);
    if (el && el.type === 'text' && !el.locked) {
      this.enterTextEdit(el.id);
    }
  }

  onContext(e) {
    e.preventDefault();
    const elNode = e.target.closest?.('.cc-el');
    if (elNode && !this.store.selection.includes(elNode.dataset.id)) {
      this.store.select(expandToGroup(this.store, elNode.dataset.id));
    }
    this.onContextMenu?.(e);
  }

  // ---- element drag / select ----
  startElementGesture(e, id) {
    const store = this.store;
    const el = store.elementById(id);
    if (!el) return;

    const groupIds = expandToGroup(store, id);
    const additive = e.shiftKey;
    const alreadySelected = store.selection.includes(id);

    if (additive) {
      if (alreadySelected) {
        // Defer the toggle-off to pointerup so shift-drag of a selection works.
        this.pendingShiftToggle = id;
      } else {
        store.select(groupIds, { additive: true });
      }
    } else if (!alreadySelected) {
      store.select(groupIds);
    }

    if (el.locked) return; // selectable (to unlock) but not draggable

    const start = this.toPage(e.clientX, e.clientY);
    const movable = store.selectedElements().filter(s => !s.locked);
    this.beginGesture(e, {
      kind: 'drag',
      start,
      startClient: { x: e.clientX, y: e.clientY },
      originals: movable.map(s => ({ id: s.id, x: s.x, y: s.y })),
      moved: false,
      clickedId: id,
      wasSelected: alreadySelected,
    });
  }

  moveDrag(e, g) {
    const store = this.store;
    if (!g.moved) {
      const dist = Math.hypot(e.clientX - g.startClient.x, e.clientY - g.startClient.y);
      if (dist < DRAG_THRESHOLD) return;
      g.moved = true;
      store.beginGesture();
    }
    const p = this.toPage(e.clientX, e.clientY);
    let dx = p.x - g.start.x;
    let dy = p.y - g.start.y;

    // Snap the moving bounds against page + other elements.
    const store2 = this.store;
    const movingIds = new Set(g.originals.map(o => o.id));
    const boxes = g.originals.map(o => {
      const el = store2.elementById(o.id);
      return elementAABB({ ...el, x: o.x + dx, y: o.y + dy });
    });
    const moving = unionBounds(boxes);
    const { xLines, yLines } = this.snapLines(movingIds);
    const snap = e.altKey
      ? { dx: 0, dy: 0, guideX: null, guideY: null }
      : computeSnap(moving, xLines, yLines, SNAP_THRESHOLD / store2.zoom);
    dx += snap.dx;
    dy += snap.dy;

    store2.applyTransient(() => {
      for (const o of g.originals) {
        const el = store2.elementById(o.id);
        if (el) { el.x = o.x + dx; el.y = o.y + dy; }
      }
    });
    this.overlay.setGuides(snap.guideX, snap.guideY);
    const first = store2.elementById(g.originals[0]?.id);
    if (first) {
      this.overlay.setBadge({
        text: `${Math.round(first.x)}, ${Math.round(first.y)}`,
        x: moving.x + moving.w / 2,
        y: moving.y + moving.h + 12 / store2.zoom,
      });
    }
    this.overlay.render();
  }

  snapLines(excludeIds) {
    const doc = this.store.doc;
    const xLines = [0, doc.width / 2, doc.width];
    const yLines = [0, doc.height / 2, doc.height];
    for (const el of this.store.page.elements) {
      if (excludeIds.has(el.id)) continue;
      const b = elementAABB(el);
      xLines.push(b.x, b.x + b.w / 2, b.x + b.w);
      yLines.push(b.y, b.y + b.h / 2, b.y + b.h);
    }
    return { xLines, yLines };
  }

  endDrag(e, g) {
    const store = this.store;
    if (g.moved) {
      store.commit();
    } else {
      store.endGesture();
      if (this.pendingShiftToggle) {
        // Shift-click on an already-selected element without dragging: toggle off.
        for (const gid of expandToGroup(store, this.pendingShiftToggle)) store.toggleSelect(gid);
      } else if (g.wasSelected && !e.shiftKey) {
        // Click (no drag) on selected element: narrow multi-selection to it.
        if (store.selection.length > 1) store.select([g.clickedId]);
      }
    }
    this.pendingShiftToggle = null;
  }

  // ---- resize ----
  startResize(e, handle, targetId) {
    const el = this.store.elementById(targetId);
    if (!el || el.locked) return;
    this.store.beginGesture();
    this.beginGesture(e, {
      kind: 'resize',
      handle,
      original: JSON.parse(JSON.stringify(el)),
      id: targetId,
    });
  }

  moveResize(e, g) {
    const store = this.store;
    const el = store.elementById(g.id);
    if (!el) return;
    const p = this.toPage(e.clientX, e.clientY);
    const isCorner = g.handle.length === 2;
    const orig = g.original;
    // Corners preserve aspect by default; Shift unlocks. Lines never do.
    const proportional = el.type === 'line' ? false : (isCorner && !e.shiftKey);
    const minSize = el.type === 'text' ? 12 : 8;
    const next = resizeElement(orig, g.handle, p.x, p.y, { proportional, minSize });

    store.applyTransient(() => {
      el.x = next.x; el.y = next.y; el.w = next.w;
      if (el.type === 'text') {
        if (isCorner) {
          const scale = next.w / orig.w;
          el.fontSize = Math.max(6, orig.fontSize * scale);
          el.h = next.h;
        }
        // Side handles reflow: renderer recomputes auto height.
      } else {
        el.h = next.h;
      }
      if (el.type === 'line') el.h = orig.h; // only e/w handles exist; height fixed
    });
    this.overlay.setBadge({
      text: `${Math.round(el.w)} × ${Math.round(el.h)}`,
      x: el.x + el.w / 2,
      y: el.y + el.h + 14 / store.zoom,
    });
    this.overlay.render();
  }

  // ---- group resize (corner-only, proportional) ----
  startGroupResize(e, handle) {
    const store = this.store;
    const selected = store.selectedElements().filter(s => !s.locked);
    if (!selected.length) return;
    store.beginGesture();
    const bounds = unionBounds(selected.map(elementAABB));
    this.beginGesture(e, {
      kind: 'group-resize',
      handle,
      bounds,
      originals: selected.map(s => JSON.parse(JSON.stringify(s))),
    });
  }

  moveGroupResize(e, g) {
    const store = this.store;
    const p = this.toPage(e.clientX, e.clientY);
    const b = g.bounds;
    // Anchor = corner opposite the dragged one.
    const anchor = {
      nw: { x: b.x + b.w, y: b.y + b.h },
      ne: { x: b.x, y: b.y + b.h },
      se: { x: b.x, y: b.y },
      sw: { x: b.x + b.w, y: b.y },
    }[g.handle];
    const sx = Math.abs(p.x - anchor.x) / b.w;
    const sy = Math.abs(p.y - anchor.y) / b.h;
    const s = clamp(Math.max(sx, sy), 0.05, 40);

    store.applyTransient(() => {
      for (const o of g.originals) {
        const el = store.elementById(o.id);
        if (!el) continue;
        el.x = anchor.x + (o.x - anchor.x) * s;
        el.y = anchor.y + (o.y - anchor.y) * s;
        el.w = o.w * s;
        el.h = o.h * s;
        if (el.type === 'text') el.fontSize = Math.max(6, o.fontSize * s);
        if (el.type === 'line') el.thickness = Math.max(1, o.thickness * s);
      }
    });
    this.overlay.render();
  }

  // ---- rotate ----
  startRotate(e, targetId) {
    const el = this.store.elementById(targetId);
    if (!el || el.locked) return;
    this.store.beginGesture();
    const center = { x: el.x + el.w / 2, y: el.y + el.h / 2 };
    const p = this.toPage(e.clientX, e.clientY);
    this.beginGesture(e, {
      kind: 'rotate',
      id: targetId,
      center,
      offset: angleFromCenter(center.x, center.y, p.x, p.y) - (el.rotation || 0),
    });
  }

  moveRotate(e, g) {
    const store = this.store;
    const el = store.elementById(g.id);
    if (!el) return;
    const p = this.toPage(e.clientX, e.clientY);
    let angle = angleFromCenter(g.center.x, g.center.y, p.x, p.y) - g.offset;
    angle = ((angle % 360) + 360) % 360;
    if (!e.shiftKey) angle = snapAngle(angle, 45, 4);
    store.applyTransient(() => { el.rotation = Math.round(angle * 10) / 10; });
    this.overlay.setBadge({
      text: `${Math.round(angle)}°`,
      x: g.center.x,
      y: el.y - 28 / store.zoom,
    });
    this.overlay.render();
  }

  // ---- marquee ----
  startMarquee(e) {
    const start = this.toPage(e.clientX, e.clientY);
    if (!e.shiftKey) this.store.clearSelection();
    this.beginGesture(e, { kind: 'marquee', start, additiveBase: [...this.store.selection], moved: false });
  }

  moveMarquee(e, g) {
    g.moved = true;
    const p = this.toPage(e.clientX, e.clientY);
    const rect = {
      x: Math.min(g.start.x, p.x),
      y: Math.min(g.start.y, p.y),
      w: Math.abs(p.x - g.start.x),
      h: Math.abs(p.y - g.start.y),
    };
    const hits = this.store.page.elements
      .filter(el => !el.locked && rectIntersectsElement(rect, el))
      .map(el => el.id);
    this.store.select([...new Set([...g.additiveBase, ...hits])]);
    this.overlay.setMarquee(rect);
    this.overlay.render();
  }

  // ---- pan ----
  startPan(e) {
    this.beginGesture(e, {
      kind: 'pan',
      startClient: { x: e.clientX, y: e.clientY },
      scroll: { x: this.workspace.scrollLeft, y: this.workspace.scrollTop },
    });
    e.preventDefault();
  }

  movePan(e, g) {
    this.workspace.scrollLeft = g.scroll.x - (e.clientX - g.startClient.x);
    this.workspace.scrollTop = g.scroll.y - (e.clientY - g.startClient.y);
  }

  // ---- gesture plumbing ----
  beginGesture(e, gesture) {
    this.gesture = gesture;
    this.moveHandler = ev => this.onGestureMove(ev);
    this.upHandler = ev => this.onGestureEnd(ev);
    window.addEventListener('pointermove', this.moveHandler);
    window.addEventListener('pointerup', this.upHandler);
  }

  onGestureMove(e) {
    const g = this.gesture;
    if (!g) return;
    switch (g.kind) {
      case 'drag': this.moveDrag(e, g); break;
      case 'resize': this.moveResize(e, g); break;
      case 'group-resize': this.moveGroupResize(e, g); break;
      case 'rotate': this.moveRotate(e, g); break;
      case 'marquee': this.moveMarquee(e, g); break;
      case 'pan': this.movePan(e, g); break;
    }
  }

  onGestureEnd(e) {
    const g = this.gesture;
    window.removeEventListener('pointermove', this.moveHandler);
    window.removeEventListener('pointerup', this.upHandler);
    this.gesture = null;
    if (!g) return;
    if (g.kind === 'drag') this.endDrag(e, g);
    else if (g.kind === 'resize' || g.kind === 'group-resize' || g.kind === 'rotate') {
      this.store.commit();
    }
    this.overlay.setGuides(null, null);
    this.overlay.setMarquee(null);
    this.overlay.setBadge(null);
    this.overlay.render();
  }

  // ---- zoom ----
  onWheel(e) {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      const factor = Math.exp(-e.deltaY * 0.0015);
      this.zoomAt(e.clientX, e.clientY, this.store.zoom * factor);
    }
  }

  zoomAt(clientX, clientY, newZoom) {
    const store = this.store;
    const old = store.zoom;
    newZoom = clamp(newZoom, 0.05, 4);
    if (Math.abs(newZoom - old) < 1e-4) return;
    const wsRect = this.workspace.getBoundingClientRect();
    const ox = clientX - wsRect.left + this.workspace.scrollLeft;
    const oy = clientY - wsRect.top + this.workspace.scrollTop;
    store.setZoom(newZoom);
    const ratio = newZoom / old;
    this.workspace.scrollLeft = ox * ratio - (clientX - wsRect.left);
    this.workspace.scrollTop = oy * ratio - (clientY - wsRect.top);
  }

  zoomCentered(newZoom) {
    const r = this.workspace.getBoundingClientRect();
    this.zoomAt(r.left + r.width / 2, r.top + r.height / 2, newZoom);
  }

  fitToScreen() {
    const doc = this.store.doc;
    if (!doc) return;
    const pad = 90;
    const z = Math.min(
      (this.workspace.clientWidth - pad) / doc.width,
      (this.workspace.clientHeight - pad) / doc.height,
    );
    this.store.setZoom(clamp(z, 0.05, 2));
    // Center after layout settles.
    requestAnimationFrame(() => {
      this.workspace.scrollLeft = (this.workspace.scrollWidth - this.workspace.clientWidth) / 2;
      this.workspace.scrollTop = (this.workspace.scrollHeight - this.workspace.clientHeight) / 2;
    });
  }

  // ---- inline text editing ----
  enterTextEdit(id) {
    const store = this.store;
    store.beginGesture();
    store.editingTextId = id;
    store.select([id]);
    store.emit('doc');
    store.emit('selection');
    requestAnimationFrame(() => {
      const node = this.pageEl.querySelector(`[data-id="${id}"] .cc-text`);
      if (!node) return;
      node.focus();
      const range = document.createRange();
      range.selectNodeContents(node);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      const onInput = () => {
        const el = store.elementById(id);
        if (!el) return;
        store.applyTransient(() => { el.text = node.innerText.replace(/\n$/, ''); });
      };
      const onPaste = ev => {
        ev.preventDefault();
        const text = ev.clipboardData.getData('text/plain');
        document.execCommand('insertText', false, text);
      };
      node.addEventListener('input', onInput);
      node.addEventListener('paste', onPaste);
      node.dataset.editListeners = '1';
    });
  }

  commitTextEditIfAny() {
    const store = this.store;
    if (!store.editingTextId) return;
    const id = store.editingTextId;
    const node = this.pageEl.querySelector(`[data-id="${id}"] .cc-text`);
    const el = store.elementById(id);
    if (node && el) {
      el.text = node.innerText.replace(/\n$/, '');
      node.blur();
    }
    store.editingTextId = null;
    // Delete empty text elements on exit (Canva behavior).
    if (el && !el.text.trim()) {
      store.applyTransient(doc => {
        const page = doc.pages[store.pageIndex];
        page.elements = page.elements.filter(x => x.id !== id);
      });
      store.selection = store.selection.filter(s => s !== id);
    }
    store.commit();
    store.emit('doc');
    store.emit('selection');
  }
}

export function isTypingTarget(target) {
  if (!target) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}
