// Pure geometry helpers for the editor: rotation-aware transforms, bounding
// boxes, and resize math. Everything works in canvas (page) coordinates and
// stays free of DOM concerns so it can be unit-tested and reused by export.

export const DEG = Math.PI / 180;

export function rotatePoint(px, py, cx, cy, deg) {
  const a = deg * DEG;
  const cos = Math.cos(a), sin = Math.sin(a);
  const dx = px - cx, dy = py - cy;
  return { x: cx + dx * cos - dy * sin, y: cy + dx * sin + dy * cos };
}

export function elementCenter(el) {
  return { x: el.x + el.w / 2, y: el.y + el.h / 2 };
}

// The four corners of an element, rotation applied, in canvas coords.
export function elementCorners(el) {
  const c = elementCenter(el);
  const pts = [
    { x: el.x, y: el.y },
    { x: el.x + el.w, y: el.y },
    { x: el.x + el.w, y: el.y + el.h },
    { x: el.x, y: el.y + el.h },
  ];
  if (!el.rotation) return pts;
  return pts.map(p => rotatePoint(p.x, p.y, c.x, c.y, el.rotation));
}

// Axis-aligned bounding box of a (possibly rotated) element.
export function elementAABB(el) {
  const pts = elementCorners(el);
  const xs = pts.map(p => p.x), ys = pts.map(p => p.y);
  const minX = Math.min(...xs), minY = Math.min(...ys);
  return { x: minX, y: minY, w: Math.max(...xs) - minX, h: Math.max(...ys) - minY };
}

export function unionBounds(boxes) {
  if (!boxes.length) return { x: 0, y: 0, w: 0, h: 0 };
  const minX = Math.min(...boxes.map(b => b.x));
  const minY = Math.min(...boxes.map(b => b.y));
  const maxX = Math.max(...boxes.map(b => b.x + b.w));
  const maxY = Math.max(...boxes.map(b => b.y + b.h));
  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
}

export function pointInRect(px, py, r) {
  return px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h;
}

// Hit test against a rotated element: un-rotate the point into local space.
export function pointInElement(px, py, el) {
  const c = elementCenter(el);
  const p = el.rotation ? rotatePoint(px, py, c.x, c.y, -el.rotation) : { x: px, y: py };
  return pointInRect(p.x, p.y, el);
}

// Rect-vs-rotated-element intersection (for marquee selection). Uses the
// separating axis theorem on the two rectangles' edge normals.
export function rectIntersectsElement(rect, el) {
  const a = [
    { x: rect.x, y: rect.y },
    { x: rect.x + rect.w, y: rect.y },
    { x: rect.x + rect.w, y: rect.y + rect.h },
    { x: rect.x, y: rect.y + rect.h },
  ];
  const b = elementCorners(el);
  return polygonsIntersect(a, b);
}

function polygonsIntersect(a, b) {
  for (const poly of [a, b]) {
    for (let i = 0; i < poly.length; i++) {
      const p1 = poly[i], p2 = poly[(i + 1) % poly.length];
      const axis = { x: -(p2.y - p1.y), y: p2.x - p1.x };
      let minA = Infinity, maxA = -Infinity, minB = Infinity, maxB = -Infinity;
      for (const p of a) { const d = p.x * axis.x + p.y * axis.y; minA = Math.min(minA, d); maxA = Math.max(maxA, d); }
      for (const p of b) { const d = p.x * axis.x + p.y * axis.y; minB = Math.min(minB, d); maxB = Math.max(maxB, d); }
      if (maxA < minB || maxB < minA) return false;
    }
  }
  return true;
}

// --- Resize with rotation -------------------------------------------------
// Dragging a handle of a rotated element must keep the opposite anchor point
// visually fixed. Strategy: express the anchor and the mouse in canvas
// coords, un-rotate the anchor->mouse vector into the element's local frame,
// derive the new size from it, then recompute x/y so that the anchor maps
// back to the same canvas position.
//
// handle: one of 'nw','n','ne','e','se','s','sw','w'
// Returns {x, y, w, h} (rotation unchanged).

const HANDLE_VECTORS = {
  nw: { hx: 0, hy: 0 }, n: { hx: 0.5, hy: 0 }, ne: { hx: 1, hy: 0 },
  e: { hx: 1, hy: 0.5 }, se: { hx: 1, hy: 1 }, s: { hx: 0.5, hy: 1 },
  sw: { hx: 0, hy: 1 }, w: { hx: 0, hy: 0.5 },
};

export function oppositeHandle(handle) {
  const map = { nw: 'se', n: 's', ne: 'sw', e: 'w', se: 'nw', s: 'n', sw: 'ne', w: 'e' };
  return map[handle];
}

// Canvas-space position of a named handle on an element.
export function handlePoint(el, handle) {
  const { hx, hy } = HANDLE_VECTORS[handle];
  const local = { x: el.x + el.w * hx, y: el.y + el.h * hy };
  const c = elementCenter(el);
  return el.rotation ? rotatePoint(local.x, local.y, c.x, c.y, el.rotation) : local;
}

export function resizeElement(el, handle, mouseX, mouseY, opts = {}) {
  const { proportional = false, minSize = 8 } = opts;
  const anchorName = oppositeHandle(handle);
  const anchor = handlePoint(el, anchorName); // stays fixed in canvas space
  const { hx, hy } = HANDLE_VECTORS[handle];
  const { hx: ax, hy: ay } = HANDLE_VECTORS[anchorName];

  // Vector anchor -> mouse, un-rotated into the element's local orientation.
  const v = rotatePoint(mouseX, mouseY, anchor.x, anchor.y, -(el.rotation || 0));
  let dx = v.x - anchor.x;
  let dy = v.y - anchor.y;

  // Direction signs: which way the handle grows relative to the anchor.
  const sx = hx === ax ? 0 : (hx > ax ? 1 : -1);
  const sy = hy === ay ? 0 : (hy > ay ? 1 : -1);

  let w = sx === 0 ? el.w : Math.max(minSize, dx * sx);
  let h = sy === 0 ? el.h : Math.max(minSize, dy * sy);

  if (proportional && sx !== 0 && sy !== 0) {
    const ratio = el.w / el.h;
    if (w / h > ratio) w = h * ratio; else h = w / ratio;
    w = Math.max(minSize, w); h = Math.max(minSize, h);
    if (w / h > ratio) h = w / ratio; else w = h * ratio;
  }

  // Rebuild the element so the anchor point stays put: place the anchor at
  // its local fractional position, then solve for the new top-left given the
  // new center rotates around itself.
  // Local (unrotated) coords: anchor sits at (ax*w, ay*h) from top-left.
  // Let center C' = topLeft + (w/2, h/2). The anchor's canvas position is
  // rotate(topLeft + (ax*w, ay*h), C', rotation) and must equal `anchor`.
  // Solve: let u = anchor_local_offset_from_center = ((ax-0.5)*w, (ay-0.5)*h).
  // anchor = C' + R(u)  =>  C' = anchor - R(u).
  const ux = (ax - 0.5) * w;
  const uy = (ay - 0.5) * h;
  const rot = el.rotation || 0;
  const ru = rotatePoint(ux, uy, 0, 0, rot);
  const cx = anchor.x - ru.x;
  const cy = anchor.y - ru.y;

  return { x: cx - w / 2, y: cy - h / 2, w, h };
}

// Angle (deg) of the mouse around a center, offset so 0 = pointing up.
export function angleFromCenter(cx, cy, mx, my) {
  return (Math.atan2(my - cy, mx - cx) / DEG + 90 + 360) % 360;
}

export function snapAngle(deg, step = 45, threshold = 5) {
  const nearest = Math.round(deg / step) * step;
  return Math.abs(deg - nearest) <= threshold ? (nearest % 360 + 360) % 360 : deg;
}

export function clamp(v, min, max) {
  return Math.min(max, Math.max(min, v));
}

// --- Snapping -------------------------------------------------------------
// Given a moving box and arrays of candidate x/y lines (canvas coords),
// return the snap adjustment and which guide lines matched.
// Candidates for the moving box: left, centerX, right / top, centerY, bottom.

export function computeSnap(box, xLines, yLines, threshold) {
  const movingX = [box.x, box.x + box.w / 2, box.x + box.w];
  const movingY = [box.y, box.y + box.h / 2, box.y + box.h];
  let bestDx = null, bestDy = null, guideX = null, guideY = null;
  for (const line of xLines) {
    for (const mx of movingX) {
      const d = line - mx;
      if (Math.abs(d) <= threshold && (bestDx === null || Math.abs(d) < Math.abs(bestDx))) {
        bestDx = d; guideX = line;
      }
    }
  }
  for (const line of yLines) {
    for (const my of movingY) {
      const d = line - my;
      if (Math.abs(d) <= threshold && (bestDy === null || Math.abs(d) < Math.abs(bestDy))) {
        bestDy = d; guideY = line;
      }
    }
  }
  return { dx: bestDx || 0, dy: bestDy || 0, guideX, guideY };
}

let idCounter = 0;
export function uid(prefix = 'el') {
  idCounter += 1;
  return `${prefix}_${Date.now().toString(36)}_${idCounter.toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}
