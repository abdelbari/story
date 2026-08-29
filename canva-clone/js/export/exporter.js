// Raster export: redraws a page onto an HTML canvas at arbitrary scale,
// mirroring the DOM renderer element by element (shapes, wrapped text with
// effects, images with cover-crop + filters, stickers, lines, backgrounds).

import { SHAPE_MAP } from '../assets/shapes.js';
import { fontStack, TEXT_EFFECTS, withAlpha, highlightColor } from '../assets/typography.js';
import { filterCss } from '../assets/filters.js';
import { paintToCanvas } from '../core/paint.js';
import { resolveImageSrc } from '../assets/photos.js';

const imageCache = new Map();

function loadImage(src) {
  if (imageCache.has(src)) return imageCache.get(src);
  const p = new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
  imageCache.set(src, p);
  return p;
}

async function preloadPageImages(page) {
  const jobs = [];
  if (page.background?.type === 'image') jobs.push(loadImage(resolveImageSrc(page.background.value)));
  for (const el of page.elements) {
    if (el.type === 'image' && el.src) jobs.push(loadImage(resolveImageSrc(el.src)));
  }
  await Promise.all(jobs);
}

export async function renderPageToCanvas(doc, page, scale = 1) {
  await preloadPageImages(page);
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(doc.width * scale));
  canvas.height = Math.max(1, Math.round(doc.height * scale));
  const ctx = canvas.getContext('2d');
  ctx.scale(scale, scale);

  await drawBackground(ctx, doc, page);
  for (const el of page.elements) {
    ctx.save();
    ctx.globalAlpha = typeof el.opacity === 'number' ? el.opacity : 1;
    // Position + rotation + flip around the element center.
    const cx = el.x + el.w / 2;
    const cy = el.y + el.h / 2;
    ctx.translate(cx, cy);
    if (el.rotation) ctx.rotate((el.rotation * Math.PI) / 180);
    ctx.scale(el.flipH ? -1 : 1, el.flipV ? -1 : 1);
    ctx.translate(-el.w / 2, -el.h / 2);
    try {
      switch (el.type) {
        case 'shape': drawShape(ctx, el); break;
        case 'text': drawText(ctx, el); break;
        case 'image': await drawImage(ctx, el); break;
        case 'sticker': drawSticker(ctx, el); break;
        case 'line': drawLine(ctx, el); break;
      }
    } finally {
      ctx.restore();
    }
  }
  return canvas;
}

async function drawBackground(ctx, doc, page) {
  const bg = page.background || { type: 'color', value: '#ffffff' };
  if (bg.type === 'image') {
    const img = await loadImage(resolveImageSrc(bg.value));
    if (img) {
      drawCover(ctx, img, 0, 0, doc.width, doc.height);
      return;
    }
  }
  ctx.fillStyle = bg.type === 'gradient'
    ? paintToCanvas(ctx, bg.value, doc.width, doc.height)
    : (bg.value || '#ffffff');
  ctx.fillRect(0, 0, doc.width, doc.height);
}

// ---- shapes --------------------------------------------------------------
export function shapePath(el) {
  const def = SHAPE_MAP[el.shapeId] || SHAPE_MAP.rect;
  let d;
  if (def.rectLike && el.radius > 0) {
    const rx = Math.min(el.radius, el.w / 2) * (100 / el.w);
    const ry = Math.min(el.radius, el.h / 2) * (100 / el.h);
    d = `M${rx},0H${100 - rx}A${rx},${ry} 0 0 1 100,${ry}V${100 - ry}A${rx},${ry} 0 0 1 ${100 - rx},100H${rx}A${rx},${ry} 0 0 1 0,${100 - ry}V${ry}A${rx},${ry} 0 0 1 ${rx},0Z`;
  } else {
    d = def.path;
  }
  // Scale the normalized 100x100 path to element size with a matrix so
  // stroke widths stay uniform when we stroke in element space.
  const path = new Path2D();
  const m = new DOMMatrix();
  m.a = el.w / 100; m.d = el.h / 100;
  path.addPath(new Path2D(d), m);
  return path;
}

function drawShape(ctx, el) {
  const path = shapePath(el);
  ctx.fillStyle = el.fill?.kind === 'gradient'
    ? paintToCanvas(ctx, el.fill, el.w, el.h)
    : (el.fill?.color || 'transparent');
  ctx.fill(path);
  if (el.stroke && el.strokeWidth > 0) {
    ctx.strokeStyle = el.stroke;
    ctx.lineWidth = el.strokeWidth;
    ctx.lineJoin = 'round';
    ctx.stroke(path);
  }
}

// ---- text ----------------------------------------------------------------
// Mirrors the DOM: white-space pre-wrap, word wrapping at el.w, alignment,
// line height, letter spacing, effects. Uses canvas letterSpacing (Chromium).
function fontString(el) {
  return `${el.italic ? 'italic ' : ''}${el.fontWeight} ${el.fontSize}px ${fontStack(el.fontFamily)}`;
}

export function wrapTextLines(ctx, el) {
  ctx.font = fontString(el);
  if ('letterSpacing' in ctx) ctx.letterSpacing = `${el.letterSpacing || 0}px`;
  const rawLines = (el.listStyle === 'bullet'
    ? el.text.split('\n').map(l => (l.trim() ? `•  ${l}` : l))
    : el.text.split('\n'));
  const lines = [];
  for (const raw of rawLines) {
    if (!raw) { lines.push(''); continue; }
    const words = raw.split(/(\s+)/).filter(w => w.length);
    let line = '';
    for (const word of words) {
      const candidate = line + word;
      if (line && ctx.measureText(candidate).width > el.w + 1) {
        lines.push(line.trimEnd());
        line = word.trimStart();
        // Hard-break single words wider than the box.
        while (ctx.measureText(line).width > el.w + 1 && line.length > 1) {
          let cut = line.length - 1;
          while (cut > 1 && ctx.measureText(line.slice(0, cut)).width > el.w + 1) cut--;
          lines.push(line.slice(0, cut));
          line = line.slice(cut);
        }
      } else {
        line = candidate;
      }
    }
    lines.push(line.trimEnd());
  }
  return lines;
}

function drawText(ctx, el) {
  const lines = wrapTextLines(ctx, el);
  const lineHeight = el.fontSize * el.lineHeight;
  const effect = el.effect?.type || 'none';
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = el.align === 'justify' ? 'left' : (el.align || 'left');

  const xFor = () => (el.align === 'center' ? el.w / 2 : el.align === 'right' ? el.w : 0);
  // Approximate the browser's line box: baseline sits ~80% into the line.
  const baselineOffset = lineHeight / 2 + el.fontSize * 0.32;

  lines.forEach((line, i) => {
    const y = i * lineHeight + baselineOffset;
    const x = xFor();

    if (effect === 'highlight' && line.trim()) {
      const w = ctx.measureText(line).width;
      const pad = el.fontSize * 0.18;
      const bx = el.align === 'center' ? (el.w - w) / 2 : el.align === 'right' ? el.w - w : 0;
      ctx.save();
      ctx.fillStyle = highlightColor(el.color);
      ctx.fillRect(bx - pad, i * lineHeight, w + pad * 2, lineHeight);
      ctx.restore();
    }

    ctx.save();
    applyTextEffect(ctx, el, effect);
    if (effect === 'outline' || effect === 'splice') {
      ctx.lineWidth = Math.max(1.5, el.fontSize * 0.035);
      ctx.strokeStyle = el.color;
      if (effect === 'splice') {
        ctx.save();
        ctx.shadowColor = 'transparent';
        ctx.fillStyle = withAlpha(el.color, 0.45);
        ctx.fillText(line, x + el.fontSize * 0.08, y + el.fontSize * 0.08);
        ctx.restore();
      }
      ctx.strokeText(line, x, y);
    } else if (effect === 'glitch') {
      ctx.fillStyle = withAlpha('#00e5ff', 0.85);
      ctx.fillText(line, x + el.fontSize * 0.06, y);
      ctx.fillStyle = withAlpha('#ff2d78', 0.85);
      ctx.fillText(line, x - el.fontSize * 0.06, y);
      ctx.fillStyle = el.color;
      ctx.fillText(line, x, y);
    } else if (effect === 'neon') {
      ctx.fillStyle = el.color;
      ctx.shadowColor = withAlpha(el.color, 0.9);
      for (const blur of [el.fontSize * 0.12, el.fontSize * 0.45, el.fontSize]) {
        ctx.shadowBlur = blur;
        ctx.fillText(line, x, y);
      }
    } else {
      ctx.fillStyle = el.color;
      ctx.fillText(line, x, y);
    }
    ctx.restore();

    if (el.underline && line) {
      const w = ctx.measureText(line).width;
      const ux = el.align === 'center' ? (el.w - w) / 2 : el.align === 'right' ? el.w - w : 0;
      ctx.save();
      ctx.strokeStyle = effect === 'outline' ? el.color : el.color;
      ctx.lineWidth = Math.max(1, el.fontSize * 0.06);
      ctx.beginPath();
      ctx.moveTo(ux, y + el.fontSize * 0.15);
      ctx.lineTo(ux + w, y + el.fontSize * 0.15);
      ctx.stroke();
      ctx.restore();
    }
  });
}

function applyTextEffect(ctx, el, effect) {
  if (effect === 'shadow') {
    ctx.shadowColor = 'rgba(0,0,0,0.55)';
    ctx.shadowOffsetX = el.fontSize * 0.06;
    ctx.shadowOffsetY = el.fontSize * 0.06;
    ctx.shadowBlur = el.fontSize * 0.12;
  } else if (effect === 'lift') {
    ctx.shadowColor = 'rgba(0,0,0,0.35)';
    ctx.shadowOffsetY = el.fontSize * 0.18;
    ctx.shadowBlur = el.fontSize * 0.5;
  }
}

// ---- images --------------------------------------------------------------
function roundedRectPath(w, h, r) {
  const path = new Path2D();
  const radius = Math.min(r, w / 2, h / 2);
  path.moveTo(radius, 0);
  path.arcTo(w, 0, w, h, radius);
  path.arcTo(w, h, 0, h, radius);
  path.arcTo(0, h, 0, 0, radius);
  path.arcTo(0, 0, w, 0, radius);
  path.closePath();
  return path;
}

function drawCover(ctx, img, x, y, w, h) {
  const scale = Math.max(w / img.naturalWidth, h / img.naturalHeight);
  const sw = w / scale, sh = h / scale;
  const sx = (img.naturalWidth - sw) / 2;
  const sy = (img.naturalHeight - sh) / 2;
  ctx.drawImage(img, sx, sy, sw, sh, x, y, w, h);
}

async function drawImage(ctx, el) {
  const img = await loadImage(resolveImageSrc(el.src));
  if (!img) return;
  ctx.save();
  if (el.radius > 0) ctx.clip(roundedRectPath(el.w, el.h, el.radius));
  const css = filterCss(el);
  if (css && css !== 'none') ctx.filter = css;
  drawCover(ctx, img, 0, 0, el.w, el.h);
  ctx.filter = 'none';
  ctx.restore();
  if (el.stroke && el.strokeWidth > 0) {
    ctx.strokeStyle = el.stroke;
    ctx.lineWidth = el.strokeWidth;
    ctx.stroke(roundedRectPath(el.w, el.h, el.radius || 0));
  }
}

// ---- stickers ------------------------------------------------------------
function drawSticker(ctx, el) {
  const size = Math.min(el.w, el.h) * 0.86;
  ctx.font = `${size}px 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(el.glyph, el.w / 2, el.h / 2 + size * 0.04);
}

// ---- lines ---------------------------------------------------------------
function drawLine(ctx, el) {
  const y = el.h / 2;
  const t = el.thickness;
  const capSize = Math.max(t * 3, 10);
  let x1 = 0, x2 = el.w;
  if (el.startCap === 'arrow') x1 += capSize * 0.9;
  if (el.endCap === 'arrow') x2 -= capSize * 0.9;
  ctx.strokeStyle = el.color;
  ctx.lineWidth = t;
  ctx.lineCap = 'round';
  if (el.dash === 'dashed') ctx.setLineDash([t * 3, t * 2]);
  else if (el.dash === 'dotted') ctx.setLineDash([0.01, t * 2.2]);
  ctx.beginPath();
  ctx.moveTo(x1, y);
  ctx.lineTo(x2, y);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = el.color;
  const cap = (atStart, kind) => {
    if (kind === 'arrow') {
      const tip = atStart ? 0 : el.w;
      const dir = atStart ? 1 : -1;
      ctx.beginPath();
      ctx.moveTo(tip, y);
      ctx.lineTo(tip + dir * capSize, y - capSize * 0.6);
      ctx.lineTo(tip + dir * capSize, y + capSize * 0.6);
      ctx.closePath();
      ctx.fill();
    } else if (kind === 'dot') {
      ctx.beginPath();
      ctx.arc(atStart ? t : el.w - t, y, Math.max(t * 1.4, 5), 0, Math.PI * 2);
      ctx.fill();
    }
  };
  cap(true, el.startCap);
  cap(false, el.endCap);
}

// ---- public export API ---------------------------------------------------

export async function exportPNG(doc, pageIndexes, scale = 2) {
  const blobs = [];
  for (const i of pageIndexes) {
    const canvas = await renderPageToCanvas(doc, doc.pages[i], scale);
    const blob = await new Promise(res => canvas.toBlob(res, 'image/png'));
    blobs.push(blob);
  }
  return blobs;
}

export async function exportJPEG(doc, pageIndexes, scale = 2, quality = 0.92) {
  const blobs = [];
  for (const i of pageIndexes) {
    const canvas = await renderPageToCanvas(doc, doc.pages[i], scale);
    const blob = await new Promise(res => canvas.toBlob(res, 'image/jpeg', quality));
    blobs.push(blob);
  }
  return blobs;
}

export async function makeThumbnail(doc, page, maxWidth = 320) {
  const scale = Math.min(1, maxWidth / doc.width);
  const canvas = await renderPageToCanvas(doc, page, scale);
  return canvas.toDataURL('image/jpeg', 0.7);
}
