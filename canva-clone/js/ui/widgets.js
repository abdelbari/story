// Shared UI primitives: a tiny hyperscript helper, one popover manager
// (single popover at a time, outside-click/Esc close, viewport clamping),
// modal dialogs and toasts.

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'html') el.innerHTML = value;
    else if (key in el && key !== 'width' && key !== 'height' && typeof value === 'boolean') el[key] = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child.nodeType ? child : document.createTextNode(child));
  }
  return el;
}

// ---- popover manager ----
let activePopover = null;

export function closePopover() {
  if (activePopover) {
    activePopover.el.remove();
    window.removeEventListener('pointerdown', activePopover.onOutside, true);
    window.removeEventListener('keydown', activePopover.onKey, true);
    activePopover.onClose?.();
    activePopover = null;
  }
}

export function openPopover(anchor, build, { align = 'left', onClose, width } = {}) {
  // Toggling: clicking the same anchor closes.
  if (activePopover && activePopover.anchor === anchor) { closePopover(); return null; }
  closePopover();
  const el = h('div', { class: 'popover', role: 'dialog' });
  if (width) el.style.width = width + 'px';
  build(el);
  document.getElementById('popover-root').appendChild(el);

  const rect = anchor.getBoundingClientRect();
  const pw = el.offsetWidth, ph = el.offsetHeight;
  let x = align === 'right' ? rect.right - pw : rect.left;
  let y = rect.bottom + 8;
  if (y + ph > window.innerHeight - 8) y = Math.max(8, rect.top - ph - 8);
  x = Math.min(Math.max(8, x), window.innerWidth - pw - 8);
  el.style.left = x + 'px';
  el.style.top = y + 'px';

  const onOutside = e => {
    if (!el.contains(e.target) && !anchor.contains(e.target)) closePopover();
  };
  const onKey = e => {
    if (e.key === 'Escape') { e.stopPropagation(); closePopover(); }
  };
  window.addEventListener('pointerdown', onOutside, true);
  window.addEventListener('keydown', onKey, true);
  activePopover = { el, anchor, onOutside, onKey, onClose };
  return el;
}

export function isPopoverOpen() {
  return !!activePopover;
}

// ---- dialogs ----
export function confirmDialog({ title, message, okLabel = 'Delete', danger = true }) {
  return new Promise(resolve => {
    const root = document.getElementById('dialog-root');
    const close = result => { backdrop.remove(); resolve(result); };
    const backdrop = h('div', { class: 'dialog-backdrop', onpointerdown: e => { if (e.target === backdrop) close(false); } },
      h('div', { class: 'dialog', role: 'alertdialog' },
        h('h3', {}, title),
        h('p', {}, message),
        h('div', { class: 'dialog-actions' },
          h('button', { class: 'btn btn-ghost', onclick: () => close(false) }, 'Cancel'),
          h('button', {
            class: 'btn btn-primary', 'data-testid': 'dialog-ok',
            style: danger ? { background: '#e5484d' } : {},
            onclick: () => close(true),
          }, okLabel),
        ),
      ));
    root.appendChild(backdrop);
  });
}

export function promptDialog({ title, message, value = '', okLabel = 'Save' }) {
  return new Promise(resolve => {
    const root = document.getElementById('dialog-root');
    const close = result => { backdrop.remove(); resolve(result); };
    const input = h('input', { type: 'text', value, 'data-testid': 'dialog-input' });
    const backdrop = h('div', { class: 'dialog-backdrop', onpointerdown: e => { if (e.target === backdrop) close(null); } },
      h('div', { class: 'dialog', role: 'dialog' },
        h('h3', {}, title),
        message ? h('p', {}, message) : null,
        input,
        h('div', { class: 'dialog-actions' },
          h('button', { class: 'btn btn-ghost', onclick: () => close(null) }, 'Cancel'),
          h('button', { class: 'btn btn-primary', 'data-testid': 'dialog-ok', onclick: () => close(input.value) }, okLabel),
        ),
      ));
    root.appendChild(backdrop);
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') close(input.value);
      if (e.key === 'Escape') close(null);
    });
    requestAnimationFrame(() => { input.focus(); input.select(); });
  });
}

// ---- toasts ----
export function toast(message, type = 'success', duration = 2600) {
  const root = document.getElementById('toast-root');
  const el = h('div', { class: `toast ${type}` }, message);
  root.appendChild(el);
  setTimeout(() => el.remove(), duration);
}

// ---- small helpers ----
export function iconBtn(icon, title, onclick, attrs = {}) {
  return h('button', { class: 'icon-btn', title, onclick, ...attrs }, icon);
}

export function relativeTime(ts) {
  if (!ts) return '';
  const diff = Date.now() - ts;
  const min = Math.floor(diff / 60000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min}m ago`;
  const hours = Math.floor(min / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(ts).toLocaleDateString();
}
