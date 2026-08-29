// Selection overlay: outlines, transform handles, rotate grip, snap guides,
// marquee and size/angle badges. Lives inside the scaled page wrapper so it
// shares page coordinates; handle sizes multiply by 1/zoom (the --iz CSS
// var) to keep a constant screen size.

import { elementAABB, unionBounds, elementCenter } from '../core/geometry.js';

const HANDLES = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];

export class Overlay {
  constructor(overlayEl, store) {
    this.el = overlayEl;
    this.store = store;
    this.hoverId = null;
    this.guides = { x: null, y: null };
    this.marquee = null;
    this.badge = null; // { text, x, y }
  }

  setHover(id) {
    if (this.hoverId !== id) { this.hoverId = id; this.render(); }
  }

  setGuides(x, y) {
    this.guides = { x, y };
  }

  setMarquee(rect) {
    this.marquee = rect;
  }

  setBadge(badge) {
    this.badge = badge;
  }

  render() {
    const store = this.store;
    const el = this.el;
    el.innerHTML = '';
    const doc = store.doc;
    if (!doc) return;
    const iz = 1 / store.zoom;
    el.style.setProperty('--iz', iz);

    const selected = store.selectedElements();
    const editing = store.editingTextId;

    // Hover outline (not when element already selected).
    if (this.hoverId && !store.selection.includes(this.hoverId)) {
      const hovered = store.elementById(this.hoverId);
      if (hovered) el.appendChild(this.outlineBox(hovered, 'cc-hover-outline'));
    }

    if (selected.length === 1) {
      const target = selected[0];
      el.appendChild(this.outlineBox(target, 'cc-sel-outline'));
      if (!target.locked && editing !== target.id) {
        this.appendHandles(target);
      }
    } else if (selected.length > 1) {
      for (const t of selected) el.appendChild(this.outlineBox(t, 'cc-sel-outline cc-sel-multi'));
      const bounds = unionBounds(selected.map(elementAABB));
      const box = this.rectBox(bounds, 'cc-group-outline');
      el.appendChild(box);
      this.appendGroupHandles(bounds);
    }

    // Snap guides.
    if (this.guides.x !== null) {
      const g = document.createElement('div');
      g.className = 'cc-guide cc-guide-v';
      g.style.left = this.guides.x + 'px';
      el.appendChild(g);
    }
    if (this.guides.y !== null) {
      const g = document.createElement('div');
      g.className = 'cc-guide cc-guide-h';
      g.style.top = this.guides.y + 'px';
      el.appendChild(g);
    }

    // Marquee.
    if (this.marquee) {
      const m = this.rectBox(this.marquee, 'cc-marquee');
      el.appendChild(m);
    }

    // Badge (size / angle during gestures).
    if (this.badge) {
      const b = document.createElement('div');
      b.className = 'cc-badge';
      b.textContent = this.badge.text;
      b.style.left = this.badge.x + 'px';
      b.style.top = this.badge.y + 'px';
      el.appendChild(b);
    }
  }

  outlineBox(target, className) {
    const box = document.createElement('div');
    box.className = className;
    box.style.left = target.x + 'px';
    box.style.top = target.y + 'px';
    box.style.width = target.w + 'px';
    box.style.height = target.h + 'px';
    box.style.transform = `rotate(${target.rotation || 0}deg)`;
    return box;
  }

  rectBox(r, className) {
    const box = document.createElement('div');
    box.className = className;
    box.style.left = r.x + 'px';
    box.style.top = r.y + 'px';
    box.style.width = r.w + 'px';
    box.style.height = r.h + 'px';
    return box;
  }

  appendHandles(target) {
    const wrap = document.createElement('div');
    wrap.className = 'cc-handle-wrap';
    wrap.style.left = target.x + 'px';
    wrap.style.top = target.y + 'px';
    wrap.style.width = target.w + 'px';
    wrap.style.height = target.h + 'px';
    wrap.style.transform = `rotate(${target.rotation || 0}deg)`;

    // Text: corners scale font size, e/w change wrap width; n/s are
    // meaningless (height is automatic). Lines: e/w only.
    const handles = target.type === 'line' ? ['e', 'w']
      : target.type === 'text' ? ['nw', 'ne', 'se', 'sw', 'e', 'w']
      : HANDLES;
    for (const h of handles) {
      const dot = document.createElement('div');
      dot.className = `cc-handle cc-handle-${h.length === 1 ? 'edge' : 'corner'} cc-h-${h}`;
      dot.dataset.handle = h;
      dot.dataset.target = target.id;
      dot.style.cursor = cursorFor(h, target.rotation || 0);
      wrap.appendChild(dot);
    }
    const rot = document.createElement('div');
    rot.className = 'cc-rotate-handle';
    rot.dataset.rotate = target.id;
    wrap.appendChild(rot);
    this.el.appendChild(wrap);
  }

  appendGroupHandles(bounds) {
    const wrap = document.createElement('div');
    wrap.className = 'cc-handle-wrap';
    wrap.style.left = bounds.x + 'px';
    wrap.style.top = bounds.y + 'px';
    wrap.style.width = bounds.w + 'px';
    wrap.style.height = bounds.h + 'px';
    for (const h of ['nw', 'ne', 'se', 'sw']) {
      const dot = document.createElement('div');
      dot.className = `cc-handle cc-handle-corner cc-h-${h}`;
      dot.dataset.handle = h;
      dot.dataset.group = '1';
      dot.style.cursor = cursorFor(h, 0);
      wrap.appendChild(dot);
    }
    this.el.appendChild(wrap);
  }
}

// Rotation-aware resize cursors: pick the cursor whose direction best
// matches the handle's on-screen direction.
const CURSORS = ['ns-resize', 'nesw-resize', 'ew-resize', 'nwse-resize'];
const HANDLE_ANGLE = { n: 0, ne: 45, e: 90, se: 135, s: 180, sw: 225, w: 270, nw: 315 };

function cursorFor(handle, rotation) {
  const angle = ((HANDLE_ANGLE[handle] + rotation) % 360 + 360) % 360;
  const idx = Math.round(angle / 45) % 4;
  return CURSORS[idx];
}
