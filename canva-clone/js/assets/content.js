// Wires the generated content library (content-data.js) into the live
// registries. Imported once at boot, before any panel renders.

import { CONTENT } from './content-data.js';
import { SHAPES, SHAPE_MAP } from './shapes.js';
import { registerPalettes } from './palettes.js';
import { registerStickerGroups } from './stickers.js';
import { registerTemplates } from './templates.js';
import { registerPhotoSpecs } from './photos.js';

let loaded = false;

export function loadContent() {
  if (loaded) return;
  loaded = true;
  for (const s of CONTENT.shapes || []) {
    if (!SHAPE_MAP[s.id]) {
      SHAPES.push(s);
      SHAPE_MAP[s.id] = s;
    }
  }
  registerPalettes(CONTENT.palettes, CONTENT.gradients);
  registerStickerGroups(CONTENT.stickerGroups);
  registerPhotoSpecs(CONTENT.photoSpecs);
  registerTemplates(CONTENT.templates);
}

export const FONT_PAIRINGS = CONTENT.pairings || [];
