// SVG export: serializes a page to standalone SVG markup. Shapes and lines
// export losslessly; text uses the same wrap algorithm as the raster
// exporter (via a measuring canvas) with effects reduced to fills/shadows;
// images embed as data URIs with cover-crop semantics.

import { SHAPE_MAP } from '../assets/shapes.js';
import { fontStack, withAlpha, highlightColor } from '../assets/typography.js';
import { filterCss } from '../assets/filters.js';
import { paintToCss } from '../core/paint.js';
import { resolveImageSrc } from '../assets/photos.js';
import { wrapTextLines } from './exporter.js';

const measureCtx = (() => {
  const c = document.createElement('canvas');
  return c.getContext('2d');
})();

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export function exportSVG(doc, page) {
  const parts = [];
  const defs = [];
  parts.push(bgMarkup(doc, page, defs));
  page.elements.forEach((el, i) => {
    const cx = el.x + el.w / 2, cy = el.y + el.h / 2;
    const transforms = [];
    if (el.rotation) transforms.push(`rotate(${el.rotation} ${num(cx)} ${num(cy)})`);
    if (el.flipH || el.flipV) {
      transforms.push(`translate(${num(cx)} ${num(cy)}) scale(${el.flipH ? -1 : 1} ${el.flipV ? -1 : 1}) translate(${num(-cx)} ${num(-cy)})`);
    }
    const open = `<g${transforms.length ? ` transform="${transforms.join(' ')}"` : ''}${el.opacity < 1 ? ` opacity="${el.opacity}"` : ''}>`;
    parts.push(open + elementMarkup(el, i, defs) + '</g>');
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${doc.width}" height="${doc.height}" viewBox="0 0 ${doc.width} ${doc.height}">` +
    `<defs>${defs.join('')}</defs>${parts.join('')}</svg>`;
}

function bgMarkup(doc, page, defs) {
  const bg = page.background || { type: 'color', value: '#ffffff' };
  if (bg.type === 'image') {
    return `<image href="${esc(resolveImageSrc(bg.value))}" x="0" y="0" width="${doc.width}" height="${doc.height}" preserveAspectRatio="xMidYMid slice"/>`;
  }
  if (bg.type === 'gradient') {
    const id = 'bg-grad';
    defs.push(gradientDef(id, bg.value));
    return `<rect width="${doc.width}" height="${doc.height}" fill="url(#${id})"/>`;
  }
  return `<rect width="${doc.width}" height="${doc.height}" fill="${esc(bg.value || '#ffffff')}"/>`;
}

function gradientDef(id, paint) {
  const rad = ((paint.angle - 90) * Math.PI) / 180;
  const dx = Math.cos(rad) / 2, dy = Math.sin(rad) / 2;
  const stops = paint.stops.map(s => `<stop offset="${s.offset}" stop-color="${esc(s.color)}"/>`).join('');
  return `<linearGradient id="${id}" x1="${num(0.5 - dx)}" y1="${num(0.5 - dy)}" x2="${num(0.5 + dx)}" y2="${num(0.5 + dy)}">${stops}</linearGradient>`;
}

function elementMarkup(el, i, defs) {
  switch (el.type) {
    case 'shape': return shapeMarkup(el, i, defs);
    case 'text': return textMarkup(el);
    case 'image': return imageMarkup(el, i, defs);
    case 'sticker': return `<text x="${num(el.x + el.w / 2)}" y="${num(el.y + el.h / 2)}" font-size="${num(Math.min(el.w, el.h) * 0.86)}" text-anchor="middle" dominant-baseline="central">${esc(el.glyph)}</text>`;
    case 'line': return lineMarkup(el);
    default: return '';
  }
}

function shapeMarkup(el, i, defs) {
  const def = SHAPE_MAP[el.shapeId] || SHAPE_MAP.rect;
  let d = def.path;
  if (def.rectLike && el.radius > 0) {
    const rx = Math.min(el.radius, el.w / 2) * (100 / el.w);
    const ry = Math.min(el.radius, el.h / 2) * (100 / el.h);
    d = `M${rx},0H${100 - rx}A${rx},${ry} 0 0 1 100,${ry}V${100 - ry}A${rx},${ry} 0 0 1 ${100 - rx},100H${rx}A${rx},${ry} 0 0 1 0,${100 - ry}V${ry}A${rx},${ry} 0 0 1 ${rx},0Z`;
  }
  let fill;
  if (el.fill?.kind === 'gradient') {
    const id = `el-grad-${i}`;
    defs.push(gradientDef(id, el.fill));
    fill = `url(#${id})`;
  } else {
    fill = el.fill?.color || 'none';
  }
  const stroke = el.stroke && el.strokeWidth > 0
    ? ` stroke="${esc(el.stroke)}" stroke-width="${el.strokeWidth}" vector-effect="non-scaling-stroke" stroke-linejoin="round"`
    : '';
  return `<g transform="translate(${num(el.x)} ${num(el.y)}) scale(${num(el.w / 100)} ${num(el.h / 100)})"><path d="${d}" fill="${esc(fill)}"${stroke}/></g>`;
}

function textMarkup(el) {
  const lines = wrapTextLines(measureCtx, el);
  const lineHeight = el.fontSize * el.lineHeight;
  const anchor = el.align === 'center' ? 'middle' : el.align === 'right' ? 'end' : 'start';
  const xOff = el.align === 'center' ? el.w / 2 : el.align === 'right' ? el.w : 0;
  const baselineOffset = lineHeight / 2 + el.fontSize * 0.32;
  const effect = el.effect?.type || 'none';

  let extra = '';
  if (effect === 'shadow' || effect === 'lift') {
    extra = ` style="filter: drop-shadow(0 ${num(el.fontSize * (effect === 'lift' ? 0.18 : 0.06))}px ${num(el.fontSize * (effect === 'lift' ? 0.5 : 0.12))}px rgba(0,0,0,0.45))"`;
  }
  let fill = esc(el.color);
  let strokeAttr = '';
  if (effect === 'outline' || effect === 'splice') {
    fill = 'none';
    strokeAttr = ` stroke="${esc(el.color)}" stroke-width="${num(Math.max(1.5, el.fontSize * 0.035))}"`;
  } else if (effect === 'neon') {
    extra = ` style="filter: drop-shadow(0 0 ${num(el.fontSize * 0.3)}px ${esc(withAlpha(el.color, 0.8))})"`;
  }

  let highlights = '';
  if (effect === 'highlight') {
    lines.forEach((line, idx) => {
      if (!line.trim()) return;
      measureCtx.font = `${el.italic ? 'italic ' : ''}${el.fontWeight} ${el.fontSize}px ${fontStack(el.fontFamily)}`;
      const w = measureCtx.measureText(line).width;
      const pad = el.fontSize * 0.18;
      const bx = el.align === 'center' ? (el.w - w) / 2 : el.align === 'right' ? el.w - w : 0;
      highlights += `<rect x="${num(el.x + bx - pad)}" y="${num(el.y + idx * lineHeight)}" width="${num(w + pad * 2)}" height="${num(lineHeight)}" fill="${esc(highlightColor(el.color))}"/>`;
    });
  }

  const tspans = lines.map((line, idx) =>
    `<tspan x="${num(el.x + xOff)}" y="${num(el.y + idx * lineHeight + baselineOffset)}">${esc(line) || ' '}</tspan>`
  ).join('');
  const style = [
    `font-family:${fontStack(el.fontFamily).replace(/"/g, "'")}`,
    `font-size:${el.fontSize}px`,
    `font-weight:${el.fontWeight}`,
    el.italic ? 'font-style:italic' : '',
    el.underline ? 'text-decoration:underline' : '',
    el.letterSpacing ? `letter-spacing:${el.letterSpacing}px` : '',
  ].filter(Boolean).join(';');
  return `${highlights}<text text-anchor="${anchor}" fill="${fill}"${strokeAttr} style="${esc(style)}"${extra}>${tspans}</text>`;
}

function imageMarkup(el, i, defs) {
  const clipId = `clip-${i}`;
  let clip = '';
  if (el.radius > 0) {
    defs.push(`<clipPath id="${clipId}"><rect x="${num(el.x)}" y="${num(el.y)}" width="${num(el.w)}" height="${num(el.h)}" rx="${num(el.radius)}"/></clipPath>`);
    clip = ` clip-path="url(#${clipId})"`;
  }
  const css = filterCss(el);
  const filter = css && css !== 'none' ? ` style="filter:${esc(css)}"` : '';
  const stroke = el.stroke && el.strokeWidth > 0
    ? `<rect x="${num(el.x)}" y="${num(el.y)}" width="${num(el.w)}" height="${num(el.h)}" rx="${num(el.radius || 0)}" fill="none" stroke="${esc(el.stroke)}" stroke-width="${el.strokeWidth}"/>`
    : '';
  return `<image href="${esc(resolveImageSrc(el.src))}" x="${num(el.x)}" y="${num(el.y)}" width="${num(el.w)}" height="${num(el.h)}" preserveAspectRatio="xMidYMid slice"${clip}${filter}/>${stroke}`;
}

function lineMarkup(el) {
  const y = el.y + el.h / 2;
  const t = el.thickness;
  const capSize = Math.max(t * 3, 10);
  let x1 = el.x, x2 = el.x + el.w;
  if (el.startCap === 'arrow') x1 += capSize * 0.9;
  if (el.endCap === 'arrow') x2 -= capSize * 0.9;
  let dash = '';
  if (el.dash === 'dashed') dash = ` stroke-dasharray="${t * 3} ${t * 2}"`;
  if (el.dash === 'dotted') dash = ` stroke-dasharray="0.01 ${t * 2.2}" stroke-linecap="round"`;
  let caps = '';
  const capMarkup = (atStart, kind) => {
    if (kind === 'arrow') {
      const tip = atStart ? el.x : el.x + el.w;
      const dir = atStart ? 1 : -1;
      return `<path d="M${num(tip)},${num(y)}L${num(tip + dir * capSize)},${num(y - capSize * 0.6)}L${num(tip + dir * capSize)},${num(y + capSize * 0.6)}Z" fill="${esc(el.color)}"/>`;
    }
    if (kind === 'dot') {
      return `<circle cx="${num(atStart ? el.x + t : el.x + el.w - t)}" cy="${num(y)}" r="${num(Math.max(t * 1.4, 5))}" fill="${esc(el.color)}"/>`;
    }
    return '';
  };
  caps += capMarkup(true, el.startCap) + capMarkup(false, el.endCap);
  return `<line x1="${num(x1)}" y1="${num(y)}" x2="${num(x2)}" y2="${num(y)}" stroke="${esc(el.color)}" stroke-width="${t}" stroke-linecap="round"${dash}/>${caps}`;
}

function num(v) {
  return Math.round(v * 100) / 100;
}
