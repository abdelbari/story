// Color system: curated palettes, gradient presets, default swatches,
// document-color extraction and the "shuffle colors" magic command.

import { parseColor } from './typography.js';

// Starter data — extended by generated content in palettes-data.js.
export const PALETTES = [
  { id: 'bold-primary', name: 'Bold & Bright', colors: ['#1a1a2e', '#e94560', '#0f3460', '#16c79a', '#f9ed69'] },
  { id: 'pastel-dream', name: 'Pastel Dream', colors: ['#6c5b7b', '#c06c84', '#f67280', '#f8b195', '#fdf6f0'] },
  { id: 'ocean-depth', name: 'Ocean Depth', colors: ['#03045e', '#0077b6', '#00b4d8', '#90e0ef', '#caf0f8'] },
];

export const GRADIENT_PRESETS = [
  { id: 'sunset', name: 'Sunset', angle: 135, stops: [{ offset: 0, color: '#ff9a8b' }, { offset: 0.55, color: '#ff6a88' }, { offset: 1, color: '#ff99ac' }] },
  { id: 'ocean', name: 'Ocean', angle: 120, stops: [{ offset: 0, color: '#00c6fb' }, { offset: 1, color: '#005bea' }] },
  { id: 'midnight', name: 'Midnight', angle: 160, stops: [{ offset: 0, color: '#0f2027' }, { offset: 0.5, color: '#203a43' }, { offset: 1, color: '#2c5364' }] },
];

export const DEFAULT_SWATCHES = [
  '#0d1216', '#545d6b', '#9aa4b2', '#e3e6ea', '#ffffff',
  '#e5484d', '#ff7b54', '#ffb02e', '#ffe066', '#8fce5f',
  '#16c79a', '#00b4d8', '#3e63dd', '#8b3dff', '#d6409f',
  '#f9d8e7', '#ffe8cc', '#fff8d6', '#d9f2e6', '#dbeafe',
];

export function registerPalettes(palettes, gradients) {
  if (Array.isArray(palettes)) {
    for (const p of palettes) {
      if (!PALETTES.some(x => x.id === p.id)) PALETTES.push(p);
    }
  }
  if (Array.isArray(gradients)) {
    for (const g of gradients) {
      if (!GRADIENT_PRESETS.some(x => x.id === g.id)) GRADIENT_PRESETS.push(g);
    }
  }
}

// ---- document colors ----
function collectPaint(counter, paint) {
  if (!paint) return;
  if (paint.kind === 'gradient') paint.stops.forEach(s => count(counter, s.color));
  else if (paint.color) count(counter, paint.color);
}

function count(counter, color) {
  if (typeof color !== 'string' || !color.startsWith('#')) return;
  const key = color.toLowerCase();
  counter.set(key, (counter.get(key) || 0) + 1);
}

export function documentColors(doc, limit = 10) {
  const counter = new Map();
  for (const page of doc.pages) {
    const bg = page.background;
    if (bg?.type === 'color') count(counter, bg.value);
    if (bg?.type === 'gradient') collectPaint(counter, bg.value);
    for (const el of page.elements) {
      if (el.type === 'shape') { collectPaint(counter, el.fill); count(counter, el.stroke); }
      if (el.type === 'text') count(counter, el.color);
      if (el.type === 'line') count(counter, el.color);
      if (el.type === 'image') count(counter, el.stroke);
    }
  }
  return [...counter.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit).map(([c]) => c);
}

// ---- shuffle colors ----
// Map the document's colors onto a target palette by luminance rank, so
// darks stay dark and lights stay light while the mood changes entirely.
function luminance(color) {
  const { r, g, b } = parseColor(color);
  return 0.299 * r + 0.587 * g + 0.114 * b;
}

export function buildColorMapping(docColors, paletteColors) {
  const sortedDoc = [...docColors].sort((a, b) => luminance(a) - luminance(b));
  const sortedPal = [...paletteColors].sort((a, b) => luminance(a) - luminance(b));
  const map = new Map();
  sortedDoc.forEach((color, i) => {
    const target = sortedPal[Math.round((i / Math.max(1, sortedDoc.length - 1)) * (sortedPal.length - 1))];
    map.set(color, target);
  });
  return map;
}

export function applyColorMapping(page, background, map) {
  const remap = c => (typeof c === 'string' && map.has(c.toLowerCase()) ? map.get(c.toLowerCase()) : c);
  const remapPaint = paint => {
    if (!paint) return;
    if (paint.kind === 'gradient') paint.stops.forEach(s => { s.color = remap(s.color); });
    else if (paint.color) paint.color = remap(paint.color);
  };
  if (background?.type === 'color') background.value = remap(background.value);
  if (background?.type === 'gradient') remapPaint(background.value);
  for (const el of page.elements) {
    if (el.type === 'shape') { remapPaint(el.fill); if (el.stroke) el.stroke = remap(el.stroke); }
    if (el.type === 'text') el.color = remap(el.color);
    if (el.type === 'line') el.color = remap(el.color);
    if (el.type === 'image' && el.stroke) el.stroke = remap(el.stroke);
  }
}
