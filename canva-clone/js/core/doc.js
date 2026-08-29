// Document model: factories for designs, pages and elements, plus the
// design-size presets offered on the home screen. The schema is plain JSON
// so documents serialize losslessly for save/load and export.
//
// Element common props: id, type, x, y, w, h, rotation, opacity, locked,
// flipH, flipV. Type-specific props documented per factory below.

import { uid } from './geometry.js';

export const DOC_VERSION = 1;

export const SIZE_PRESETS = [
  { id: 'insta-post', name: 'Instagram Post', w: 1080, h: 1080, icon: '⬜', category: 'Social' },
  { id: 'insta-story', name: 'Instagram Story', w: 1080, h: 1920, icon: '📱', category: 'Social' },
  { id: 'presentation', name: 'Presentation 16:9', w: 1920, h: 1080, icon: '🖥️', category: 'Business' },
  { id: 'youtube-thumb', name: 'YouTube Thumbnail', w: 1280, h: 720, icon: '▶️', category: 'Social' },
  { id: 'poster', name: 'Poster', w: 1587, h: 2245, icon: '🪧', category: 'Marketing' },
  { id: 'flyer', name: 'Flyer A5', w: 1240, h: 1748, icon: '📄', category: 'Marketing' },
  { id: 'a4', name: 'A4 Document', w: 1240, h: 1754, icon: '📃', category: 'Business' },
  { id: 'business-card', name: 'Business Card', w: 1050, h: 600, icon: '💼', category: 'Business' },
  { id: 'facebook-cover', name: 'Facebook Cover', w: 1640, h: 924, icon: '🖼️', category: 'Social' },
  { id: 'invitation', name: 'Invitation', w: 1400, h: 2000, icon: '💌', category: 'Events' },
  { id: 'quote-card', name: 'Quote Card', w: 1440, h: 1080, icon: '❝', category: 'Social' },
  { id: 'logo', name: 'Logo', w: 800, h: 800, icon: '✴️', category: 'Branding' },
];

export function createDoc({ title = 'Untitled design', width = 1080, height = 1080 } = {}) {
  return {
    version: DOC_VERSION,
    id: uid('doc'),
    title,
    width,
    height,
    createdAt: 0,
    updatedAt: 0,
    pages: [createPage()],
  };
}

export function createPage(background) {
  return {
    id: uid('page'),
    background: background || { type: 'color', value: '#ffffff' },
    elements: [],
  };
}

function base(props) {
  return {
    id: uid(),
    x: 0, y: 0, w: 100, h: 100,
    rotation: 0,
    opacity: 1,
    locked: false,
    flipH: false,
    flipV: false,
    ...props,
  };
}

// Shape: geometry comes from the shape library (shapeId -> normalized path).
// fill is a paint: {kind:'solid', color} | {kind:'gradient', angle, stops:[{offset,color}...]}
export function createShape(props = {}) {
  return base({
    type: 'shape',
    shapeId: 'rect',
    fill: { kind: 'solid', color: '#8b5cf6' },
    stroke: null,           // color string or null
    strokeWidth: 0,
    radius: 0,              // corner radius, rect/rounded shapes only
    ...props,
  });
}

// Text: `text` may contain newlines; w is the wrap width; h auto-grows.
export function createText(props = {}) {
  return base({
    type: 'text',
    text: 'Add your text',
    fontFamily: 'sans',     // key into FONT_STACKS
    fontSize: 42,
    fontWeight: 400,
    italic: false,
    underline: false,
    align: 'center',        // left | center | right | justify
    lineHeight: 1.25,
    letterSpacing: 0,       // px
    color: '#1f2430',
    listStyle: 'none',      // none | bullet
    effect: { type: 'none' }, // none|shadow|lift|outline|neon|glitch|highlight
    autoHeight: true,
    ...props,
  });
}

// Image: src is a data URI or a procedural asset key ("asset:mesh-01").
export function createImage(props = {}) {
  return base({
    type: 'image',
    src: '',
    filter: 'none',         // key into IMAGE_FILTERS
    filterIntensity: 1,
    radius: 0,
    stroke: null,
    strokeWidth: 0,
    ...props,
  });
}

// Sticker: an emoji glyph rendered large (exported by drawing the glyph).
export function createSticker(props = {}) {
  return base({
    type: 'sticker',
    glyph: '⭐',
    ...props,
  });
}

// Line: rendered as a stroked segment across the element box's midline.
// Arrowheads at either end; dash styles.
export function createLine(props = {}) {
  return base({
    type: 'line',
    h: 4,
    w: 300,
    color: '#1f2430',
    thickness: 4,
    dash: 'solid',          // solid | dashed | dotted
    startCap: 'none',       // none | arrow | dot
    endCap: 'none',
    ...props,
  });
}

export const FACTORIES = {
  shape: createShape,
  text: createText,
  image: createImage,
  sticker: createSticker,
  line: createLine,
};

// Deep-clone an element with a fresh id (for duplicate / copy-paste).
export function cloneElement(el, offset = 0) {
  const copy = JSON.parse(JSON.stringify(el));
  copy.id = uid();
  copy.x += offset;
  copy.y += offset;
  return copy;
}

export function clonePage(page) {
  const copy = JSON.parse(JSON.stringify(page));
  copy.id = uid('page');
  copy.elements.forEach(el => { el.id = uid(); });
  return copy;
}

// Migrate older saved docs to the current schema (placeholder for future).
export function migrateDoc(doc) {
  if (!doc || typeof doc !== 'object' || !Array.isArray(doc.pages)) return null;
  doc.version = DOC_VERSION;
  return doc;
}
