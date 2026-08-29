// Right-click context menu for the canvas.

import { h } from './widgets.js';
import * as commands from '../editor/commands.js';

let menuEl = null;

export function closeContextMenu() {
  if (menuEl) { menuEl.remove(); menuEl = null; }
}

export function openContextMenu(event, store) {
  closeContextMenu();
  const hasSelection = store.selection.length > 0;
  const root = document.getElementById('contextmenu-root');

  const item = (label, kbd, onclick, { disabled = false, testid } = {}) => h('button', {
    class: 'menu-item', disabled, 'data-testid': testid || false,
    onclick: () => { closeContextMenu(); onclick(); },
  }, label, kbd ? h('span', { class: 'mi-kbd' }, kbd) : null);

  menuEl = h('div', { class: 'contextmenu', 'data-testid': 'contextmenu' },
    item('Copy', 'Ctrl+C', () => commands.copySelected(store), { disabled: !hasSelection }),
    item('Paste', 'Ctrl+V', () => commands.paste(store), { disabled: !commands.hasClipboard() }),
    item('Duplicate', 'Ctrl+D', () => commands.duplicateSelected(store), { disabled: !hasSelection, testid: 'ctx-duplicate' }),
    item('Delete', 'Del', () => commands.deleteSelected(store), { disabled: !hasSelection, testid: 'ctx-delete' }),
    h('div', { class: 'menu-sep' }),
    item('Bring to front', 'Ctrl+Alt+]', () => commands.bringToFront(store), { disabled: !hasSelection, testid: 'ctx-front' }),
    item('Bring forward', 'Ctrl+]', () => commands.bringForward(store), { disabled: !hasSelection }),
    item('Send backward', 'Ctrl+[', () => commands.sendBackward(store), { disabled: !hasSelection }),
    item('Send to back', 'Ctrl+Alt+[', () => commands.sendToBack(store), { disabled: !hasSelection, testid: 'ctx-back' }),
    h('div', { class: 'menu-sep' }),
    item(anyLocked(store) ? 'Unlock' : 'Lock', null, () => commands.toggleLockSelected(store), { disabled: !hasSelection, testid: 'ctx-lock' }),
    item('Select all', 'Ctrl+A', () => commands.selectAll(store)),
  );

  root.appendChild(menuEl);
  const mw = menuEl.offsetWidth, mh = menuEl.offsetHeight;
  menuEl.style.left = Math.min(event.clientX, window.innerWidth - mw - 8) + 'px';
  menuEl.style.top = Math.min(event.clientY, window.innerHeight - mh - 8) + 'px';

  const dismiss = e => {
    if (menuEl && !menuEl.contains(e.target)) closeContextMenu();
    window.removeEventListener('pointerdown', dismiss, true);
  };
  window.addEventListener('pointerdown', dismiss, true);
  window.addEventListener('keydown', function onKey(e) {
    if (e.key === 'Escape') closeContextMenu();
    window.removeEventListener('keydown', onKey, true);
  }, true);
}

function anyLocked(store) {
  return store.selectedElements().some(el => el.locked);
}
