// Pure-math unit tests for the geometry module (run: node tests/geometry.test.mjs).
// The key invariant: during a resize, the anchor (opposite handle) must not
// move in canvas space — for every handle, at any rotation.

import {
  resizeElement, handlePoint, oppositeHandle, rotatePoint,
  elementAABB, pointInElement, rectIntersectsElement, computeSnap, snapAngle,
} from '../js/core/geometry.js';

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok  ${name}`);
  else { failures++; console.log(`FAIL  ${name} ${extra}`); }
}
function near(a, b, eps = 0.001) { return Math.abs(a - b) < eps; }

// --- resize anchor invariant across handles and rotations ---
const HANDLES = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];
for (const rotation of [0, 17, 45, 90, 133, 270, 359]) {
  for (const handle of HANDLES) {
    const el = { x: 100, y: 150, w: 200, h: 120, rotation };
    const anchorBefore = handlePoint(el, oppositeHandle(handle));
    // Drag the handle outward along an arbitrary direction.
    const hp = handlePoint(el, handle);
    const next = resizeElement(el, handle, hp.x + 37, hp.y - 22, { proportional: false, minSize: 8 });
    const after = { ...el, ...next };
    const anchorAfter = handlePoint(after, oppositeHandle(handle));
    check(`anchor fixed r=${rotation} h=${handle}`,
      near(anchorBefore.x, anchorAfter.x, 0.01) && near(anchorBefore.y, anchorAfter.y, 0.01),
      `(${anchorBefore.x.toFixed(2)},${anchorBefore.y.toFixed(2)}) -> (${anchorAfter.x.toFixed(2)},${anchorAfter.y.toFixed(2)})`);
  }
}

// --- proportional corner resize keeps ratio ---
{
  const el = { x: 0, y: 0, w: 300, h: 150, rotation: 30 };
  const hp = handlePoint(el, 'se');
  const next = resizeElement(el, 'se', hp.x + 120, hp.y + 10, { proportional: true, minSize: 8 });
  check('proportional keeps ratio', near(next.w / next.h, 2, 0.01), `${(next.w / next.h).toFixed(3)}`);
}

// --- min-size clamp: dragging past the anchor never flips ---
{
  const el = { x: 100, y: 100, w: 200, h: 100, rotation: 0 };
  const anchor = handlePoint(el, 'w'); // dragging 'e' toward/past 'w'
  const next = resizeElement(el, 'e', anchor.x - 500, anchor.y, { proportional: false, minSize: 8 });
  check('clamp instead of flip', next.w === 8 && next.h === 100, JSON.stringify(next));
}

// --- edge handle only affects one axis ---
{
  const el = { x: 50, y: 60, w: 180, h: 90, rotation: 77 };
  const hp = handlePoint(el, 'n');
  const next = resizeElement(el, 'n', hp.x + 5, hp.y - 40, { proportional: false, minSize: 8 });
  check('edge handle keeps width', near(next.w, el.w, 0.01), `${next.w}`);
}

// --- rotatePoint round trip ---
{
  const p = rotatePoint(10, 20, 50, 50, 123);
  const back = rotatePoint(p.x, p.y, 50, 50, -123);
  check('rotatePoint round trip', near(back.x, 10) && near(back.y, 20));
}

// --- AABB of rotated square is bigger ---
{
  const box = elementAABB({ x: 0, y: 0, w: 100, h: 100, rotation: 45 });
  check('rotated AABB expands', near(box.w, Math.SQRT2 * 100, 0.1) && near(box.h, Math.SQRT2 * 100, 0.1));
}

// --- hit test respects rotation ---
{
  const el = { x: 0, y: 40, w: 100, h: 20, rotation: 90 };
  // Rotated 90°, the visual footprint is a vertical bar around (50, 50).
  check('hit inside rotated', pointInElement(50, 10, el));
  check('miss outside rotated', !pointInElement(95, 45, el));
}

// --- marquee SAT vs rotated element ---
{
  const el = { x: 100, y: 100, w: 80, h: 20, rotation: 45 };
  check('SAT intersects', rectIntersectsElement({ x: 120, y: 90, w: 30, h: 30 }, el));
  check('SAT rejects corner gap', !rectIntersectsElement({ x: 180, y: 96, w: 8, h: 8 }, el));
}

// --- snapping picks nearest line ---
{
  const snap = computeSnap({ x: 96, y: 200, w: 50, h: 50 }, [100], [], 6);
  check('snap dx to left edge', snap.dx === 4 && snap.guideX === 100, JSON.stringify(snap));
  // Box edges/center at 60, 85, 110 — all further than 6 from the line at 100.
  const noSnap = computeSnap({ x: 60, y: 200, w: 50, h: 50 }, [100], [], 6);
  check('no snap outside threshold', noSnap.dx === 0 && noSnap.guideX === null);
}

// --- angle snapping ---
{
  check('snaps 43 -> 45', snapAngle(43, 45, 5) === 45);
  check('keeps 30', snapAngle(30, 45, 5) === 30);
  check('snaps 357 -> 0', snapAngle(357, 45, 5) === 0);
}

console.log(failures === 0 ? '\nGEOMETRY: ALL PASS' : `\nGEOMETRY: ${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
