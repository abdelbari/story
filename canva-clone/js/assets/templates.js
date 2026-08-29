// Template registry. Templates are stored as plain specs (page background +
// element property objects) and instantiated through the real element
// factories, so schema drift fails loudly instead of producing broken docs.

import { createDoc, createPage, FACTORIES } from '../core/doc.js';

export const TEMPLATES = [];

export function registerTemplates(templates) {
  if (!Array.isArray(templates)) return;
  for (const t of templates) {
    if (!TEMPLATES.some(x => x.id === t.id)) TEMPLATES.push(t);
  }
}

const LINE_MIN_H = 8;

export function normalizeElementSpec(spec) {
  const { type } = spec;
  const factory = FACTORIES[type];
  if (!factory) throw new Error(`Unknown element type in template: ${type}`);
  const { id, group, ...props } = spec; // never trust spec ids
  const el = factory(props);
  if (type === 'line') {
    el.h = Math.max(LINE_MIN_H, el.thickness);
  }
  if (type === 'text' && !('h' in props)) {
    // Rough pre-layout height; the renderer's auto-height corrects it.
    const lines = String(el.text).split('\n').length;
    el.h = Math.ceil(el.fontSize * el.lineHeight * lines);
  }
  return el;
}

export function instantiateTemplate(template) {
  const doc = createDoc({
    title: template.name,
    width: template.width,
    height: template.height,
  });
  doc.pages = [instantiatePage(template)];
  return doc;
}

export function instantiatePage(template) {
  const page = createPage(JSON.parse(JSON.stringify(template.background || { type: 'color', value: '#ffffff' })));
  page.elements = (template.elements || []).map(normalizeElementSpec);
  return page;
}

export function templateCategories() {
  const cats = ['All'];
  for (const t of TEMPLATES) if (!cats.includes(t.category)) cats.push(t.category);
  return cats;
}
