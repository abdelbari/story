// Paint model shared by shape fills and page backgrounds.
// { kind: 'solid', color } or
// { kind: 'gradient', angle, stops: [{ offset, color }, ...] }

export function solid(color) {
  return { kind: 'solid', color };
}

export function gradient(angle, stops) {
  return { kind: 'gradient', angle, stops };
}

export function paintToCss(paint) {
  if (!paint) return 'transparent';
  if (paint.kind === 'gradient') {
    const stops = paint.stops.map(s => `${s.color} ${Math.round(s.offset * 100)}%`).join(', ');
    return `linear-gradient(${paint.angle}deg, ${stops})`;
  }
  return paint.color || 'transparent';
}

// A representative single color for a paint (used for swatch chips and for
// deriving contrasting UI colors).
export function paintPrimaryColor(paint) {
  if (!paint) return '#000000';
  if (paint.kind === 'gradient') return paint.stops[0]?.color || '#000000';
  return paint.color || '#000000';
}

// Build a canvas gradient matching CSS linear-gradient semantics for a box
// of size w x h: CSS angle 0deg points up, 90deg points right, and the
// gradient line length is the box's projection onto the gradient direction.
export function paintToCanvas(ctx, paint, w, h) {
  if (!paint) return 'transparent';
  if (paint.kind !== 'gradient') return paint.color || 'transparent';
  const rad = ((paint.angle - 90) * Math.PI) / 180; // canvas: 0rad points right
  const dx = Math.cos(rad), dy = Math.sin(rad);
  const halfLen = (Math.abs(w * dx) + Math.abs(h * dy)) / 2;
  const cx = w / 2, cy = h / 2;
  const g = ctx.createLinearGradient(cx - dx * halfLen, cy - dy * halfLen, cx + dx * halfLen, cy + dy * halfLen);
  for (const s of paint.stops) g.addColorStop(Math.min(1, Math.max(0, s.offset)), s.color);
  return g;
}

export function clonePaint(paint) {
  return paint ? JSON.parse(JSON.stringify(paint)) : null;
}
