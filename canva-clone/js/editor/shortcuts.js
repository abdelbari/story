// Global keyboard shortcuts. Text-entry contexts (inputs, contenteditable)
// keep native behavior except Escape, which commits/exits.

import * as commands from './commands.js';
import { isTypingTarget } from './interactions.js';

export function installShortcuts({ store, interactions }) {
  window.addEventListener('keydown', e => {
    const mod = e.ctrlKey || e.metaKey;

    if (e.key === 'Escape') {
      if (store.editingTextId) {
        interactions.commitTextEditIfAny();
      } else {
        store.clearSelection();
      }
      return;
    }

    if (isTypingTarget(e.target) || store.editingTextId) return;

    // --- clipboard & history ---
    if (mod && e.key.toLowerCase() === 'z') {
      e.preventDefault();
      if (e.shiftKey) store.redo(); else store.undo();
      return;
    }
    if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); store.redo(); return; }
    if (mod && e.key.toLowerCase() === 'c') { e.preventDefault(); commands.copySelected(store); return; }
    if (mod && e.key.toLowerCase() === 'x') { e.preventDefault(); commands.cutSelected(store); return; }
    if (mod && e.key.toLowerCase() === 'v') { e.preventDefault(); commands.paste(store); return; }
    if (mod && e.key.toLowerCase() === 'd') { e.preventDefault(); commands.duplicateSelected(store); return; }
    if (mod && e.key.toLowerCase() === 'a') { e.preventDefault(); commands.selectAll(store); return; }

    // --- grouping & order ---
    if (mod && e.shiftKey && e.key.toLowerCase() === 'g') { e.preventDefault(); commands.ungroupSelected(store); return; }
    if (mod && e.key.toLowerCase() === 'g') { e.preventDefault(); commands.groupSelected(store); return; }
    if (mod && e.altKey && e.key === ']') { e.preventDefault(); commands.bringToFront(store); return; }
    if (mod && e.altKey && e.key === '[') { e.preventDefault(); commands.sendToBack(store); return; }
    if (mod && e.key === ']') { e.preventDefault(); commands.bringForward(store); return; }
    if (mod && e.key === '[') { e.preventDefault(); commands.sendBackward(store); return; }

    // --- zoom ---
    if (mod && (e.key === '=' || e.key === '+')) { e.preventDefault(); interactions.zoomCentered(store.zoom * 1.25); return; }
    if (mod && e.key === '-') { e.preventDefault(); interactions.zoomCentered(store.zoom / 1.25); return; }
    if (mod && e.key === '0') { e.preventDefault(); interactions.fitToScreen(); return; }
    if (mod && e.key === '1') { e.preventDefault(); interactions.zoomCentered(1); return; }

    if (mod) return;

    // --- delete & nudge ---
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault();
      commands.deleteSelected(store);
      return;
    }
    const step = e.shiftKey ? 10 : 1;
    if (e.key === 'ArrowLeft') { e.preventDefault(); commands.nudgeSelected(store, -step, 0); return; }
    if (e.key === 'ArrowRight') { e.preventDefault(); commands.nudgeSelected(store, step, 0); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); commands.nudgeSelected(store, 0, -step); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); commands.nudgeSelected(store, 0, step); return; }

    // --- quick-create (Canva-style single keys) ---
    if (e.key.toLowerCase() === 't') {
      commands.createAndAdd(store, 'text', { text: 'Add your text', w: 400, h: 60 });
      return;
    }
    if (e.key.toLowerCase() === 'r') { commands.createAndAdd(store, 'shape', { shapeId: 'rect', w: 300, h: 200 }); return; }
    if (e.key.toLowerCase() === 'c') { commands.createAndAdd(store, 'shape', { shapeId: 'circle', w: 240, h: 240 }); return; }
    if (e.key.toLowerCase() === 'l') { commands.createAndAdd(store, 'line', {}); return; }
  });
}
