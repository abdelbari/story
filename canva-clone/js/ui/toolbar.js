// Contextual toolbar: morphs with the selection (text / shape / image /
// line / sticker / multi) plus a universal right-side cluster (position,
// opacity, lock, duplicate, delete) and the copy-style roller.

import { h, iconBtn, openPopover, closePopover, toast } from './widgets.js';
import { openColorPicker } from './colorpicker.js';
import { openPositionPopover } from './position.js';
import * as commands from '../editor/commands.js';
import { FONT_STACKS, TEXT_EFFECTS, fontStack } from '../assets/typography.js';
import { IMAGE_FILTERS, filterCss } from '../assets/filters.js';
import { paintToCss, paintPrimaryColor } from '../core/paint.js';
import { resolveImageSrc } from '../assets/photos.js';
import { SHAPE_MAP } from '../assets/shapes.js';

let styleClipboard = null;
let paintMode = false;

export function initToolbar({ store, root }) {
  const render = () => renderToolbar(root, store);
  store.on('selection', () => {
    if (paintMode) {
      applyCopiedStyle(store);
      paintMode = false;
    }
    render();
  });
  store.on('history', render);
  store.on('pages', render);
  render();
}

function renderToolbar(root, store) {
  root.innerHTML = '';
  const selected = store.selectedElements();

  if (!selected.length) {
    root.appendChild(h('span', { class: 'hint-empty' },
      'Select an element to edit it — or press T for text, R for a rectangle, C for a circle, L for a line'));
    return;
  }

  const types = new Set(selected.map(el => el.type));
  const el = selected[0];
  const single = selected.length === 1;

  if (single || types.size === 1) {
    switch (el.type) {
      case 'text': textControls(root, store, el); break;
      case 'shape': shapeControls(root, store, el); break;
      case 'image': imageControls(root, store, el); break;
      case 'line': lineControls(root, store, el); break;
      case 'sticker': break;
    }
  }

  if (!single) {
    root.appendChild(h('button', {
      class: 'btn btn-ghost', 'data-testid': 'btn-group',
      onclick: () => {
        const grouped = selected.every(s => s.group && s.group === selected[0].group);
        if (grouped) commands.ungroupSelected(store); else commands.groupSelected(store);
      },
    }, selected.every(s => s.group && s.group === selected[0].group) ? 'Ungroup' : 'Group'));
  }

  root.appendChild(h('div', { class: 'topbar-spacer' }));
  universalCluster(root, store, selected);
}

// ---- text ----
function textControls(root, store, el) {
  // Font family
  const fontSel = h('select', { class: 'select', 'data-testid': 'font-select' },
    Object.entries(FONT_STACKS).map(([key, f]) =>
      h('option', { value: key, selected: el.fontFamily === key, style: { fontFamily: f.stack } }, f.name)));
  fontSel.addEventListener('change', () => commands.updateSelected(store, { fontFamily: fontSel.value }));
  root.appendChild(fontSel);

  // Size stepper
  const sizeInput = h('input', {
    class: 'num-input', type: 'number', min: 6, max: 500, value: Math.round(el.fontSize),
    'data-testid': 'font-size-input', style: { width: '52px' },
  });
  sizeInput.addEventListener('change', () => {
    const v = Math.min(500, Math.max(6, Number(sizeInput.value) || el.fontSize));
    commands.updateSelected(store, { fontSize: v });
  });
  root.append(
    iconBtn('−', 'Decrease font size', () => commands.updateSelected(store, e => ({ fontSize: Math.max(6, Math.round(e.fontSize) - (e.fontSize > 40 ? 4 : 2)) })), { 'data-testid': 'font-size-dec' }),
    sizeInput,
    iconBtn('+', 'Increase font size', () => commands.updateSelected(store, e => ({ fontSize: Math.min(500, Math.round(e.fontSize) + (e.fontSize >= 40 ? 4 : 2)) })), { 'data-testid': 'font-size-inc' }),
  );

  // Color
  root.appendChild(colorChipButton(el.color, 'Text color', 'text-color', anchor =>
    openColorPicker(anchor, {
      store, current: el.color,
      onPick: c => commands.updateSelected(store, { color: c }),
    })));

  root.appendChild(h('div', { class: 'divider-v' }));

  // B / I / U
  root.append(
    iconBtn(h('b', {}, 'B'), 'Bold', () => commands.updateSelected(store, e => ({ fontWeight: e.fontWeight >= 700 ? 400 : 700 })),
      { class: 'icon-btn' + (el.fontWeight >= 700 ? ' active' : ''), 'data-testid': 'btn-bold' }),
    iconBtn(h('i', {}, 'I'), 'Italic', () => commands.updateSelected(store, e => ({ italic: !e.italic })),
      { class: 'icon-btn' + (el.italic ? ' active' : ''), 'data-testid': 'btn-italic' }),
    iconBtn(h('u', {}, 'U'), 'Underline', () => commands.updateSelected(store, e => ({ underline: !e.underline })),
      { class: 'icon-btn' + (el.underline ? ' active' : ''), 'data-testid': 'btn-underline' }),
  );

  // Align cycle
  const alignIcons = { left: '⯇', center: '☰', right: '⯈' };
  root.appendChild(iconBtn(el.align === 'left' ? '⤆' : el.align === 'right' ? '⤇' : '☰',
    `Align: ${el.align} (click to cycle)`,
    () => {
      const next = { left: 'center', center: 'right', right: 'left' }[el.align] || 'center';
      commands.updateSelected(store, { align: next });
    }, { 'data-testid': 'btn-align' }));

  // List toggle
  root.appendChild(iconBtn('•≡', 'Bulleted list', () =>
    commands.updateSelected(store, e => ({ listStyle: e.listStyle === 'bullet' ? 'none' : 'bullet' })),
    { class: 'icon-btn' + (el.listStyle === 'bullet' ? ' active' : ''), 'data-testid': 'btn-list' }));

  // Spacing popover
  const spacingBtn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-spacing' }, 'Spacing');
  spacingBtn.addEventListener('click', () => openPopover(spacingBtn, pop => {
    pop.append(
      h('h5', {}, 'Line height'),
      sliderRow(store, el, 'lineHeight', 0.8, 2.4, 0.05, 'spacing-lineheight'),
      h('h5', {}, 'Letter spacing'),
      sliderRow(store, el, 'letterSpacing', -2, 20, 0.5, 'spacing-letterspacing'),
    );
  }));
  root.appendChild(spacingBtn);

  // Effects popover
  const fxBtn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-effects' }, 'Effects');
  fxBtn.addEventListener('click', () => openPopover(fxBtn, pop => {
    pop.style.width = '300px';
    pop.append(h('h5', {}, 'Text effects'));
    const grid = h('div', { class: 'tile-grid', style: { gridTemplateColumns: 'repeat(4, 1fr)' } });
    for (const [key, fx] of Object.entries(TEXT_EFFECTS)) {
      const active = (el.effect?.type || 'none') === key;
      const preview = h('div', { style: { fontSize: '20px', fontWeight: '700', color: '#333' } }, 'Ag');
      Object.assign(preview.style, fx.css({ color: '#6d28d9' }));
      if (key === 'outline' || key === 'splice') preview.style.color = 'transparent';
      const tile = h('button', {
        class: 'tile', title: fx.name, 'data-testid': `effect-${key}`,
        style: active ? { outline: '2px solid var(--accent)' } : {},
        onclick: () => { commands.updateSelected(store, { effect: { type: key } }); closePopover(); },
      }, h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' } },
        preview, h('span', { style: { fontSize: '10px', color: 'var(--text-dim)' } }, fx.name)));
      grid.appendChild(tile);
    }
    pop.appendChild(grid);
  }));
  root.appendChild(fxBtn);
}

// ---- shape ----
function shapeControls(root, store, el) {
  root.appendChild(colorChipButton(paintToCss(el.fill), 'Fill color', 'shape-fill', anchor =>
    openColorPicker(anchor, {
      store, current: paintPrimaryColor(el.fill),
      onPick: c => commands.updateSelected(store, { fill: { kind: 'solid', color: c } }),
      onPickGradient: g => commands.updateSelected(store, { fill: g }),
    })));

  // Stroke popover
  const strokeBtn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-stroke' }, 'Border');
  strokeBtn.addEventListener('click', () => openPopover(strokeBtn, pop => {
    const widths = [0, 2, 4, 8, 12];
    pop.append(h('h5', {}, 'Border weight'),
      h('div', { class: 'row' }, widths.map(w => h('button', {
        class: 'btn btn-ghost', 'data-testid': `stroke-w-${w}`,
        style: (el.strokeWidth || 0) === w ? { background: 'var(--accent-soft)' } : {},
        onclick: () => commands.updateSelected(store, {
          strokeWidth: w,
          stroke: w > 0 ? (el.stroke || '#0d1216') : el.stroke,
        }),
      }, w === 0 ? 'None' : String(w)))));
    pop.append(h('h5', {}, 'Border color'));
    const chip = colorChipButton(el.stroke || '#0d1216', 'Border color', 'stroke-color', anchor =>
      openColorPicker(anchor, {
        store, current: el.stroke || '#0d1216',
        onPick: c => commands.updateSelected(store, { stroke: c, strokeWidth: el.strokeWidth || 2 }),
      }));
    pop.appendChild(chip);
  }));
  root.appendChild(strokeBtn);

  const def = SHAPE_MAP[el.shapeId];
  if (def?.rectLike) {
    root.appendChild(h('span', { style: { fontSize: '12.5px', color: 'var(--text-dim)' } }, 'Round'));
    const radius = h('input', {
      class: 'slider', type: 'range', min: 0, max: Math.floor(Math.min(el.w, el.h) / 2), step: 1,
      value: el.radius || 0, style: { width: '90px' }, 'data-testid': 'radius-slider',
    });
    radius.addEventListener('input', () => commands.updateSelectedTransient(store, { radius: Number(radius.value) }));
    radius.addEventListener('change', () => store.commit());
    root.appendChild(radius);
  }
}

// ---- image ----
function imageControls(root, store, el) {
  const filterBtn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-filter' }, 'Filter');
  filterBtn.addEventListener('click', () => openPopover(filterBtn, pop => {
    pop.style.width = '304px';
    pop.append(h('h5', {}, 'Filters'));
    const grid = h('div', { class: 'tile-grid', style: { gridTemplateColumns: 'repeat(3, 1fr)' } });
    const src = resolveImageSrc(el.src);
    for (const [key, f] of Object.entries(IMAGE_FILTERS)) {
      const active = (el.filter || 'none') === key;
      grid.appendChild(h('button', {
        class: 'tile', title: f.name, 'data-testid': `filter-${key}`,
        style: { aspectRatio: '1', padding: 0, ...(active ? { outline: '2px solid var(--accent)' } : {}) },
        onclick: () => commands.updateSelected(store, { filter: key }),
      },
        h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '2px', width: '100%', height: '100%', justifyContent: 'center' } },
          h('img', { src, style: { width: '58px', height: '42px', objectFit: 'cover', borderRadius: '6px', filter: f.css(1) } }),
          h('span', { style: { fontSize: '10px', color: 'var(--text-dim)' } }, f.name))));
    }
    pop.appendChild(grid);
  }));
  root.appendChild(filterBtn);

  const cropBtn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-crop' }, 'Crop');
  cropBtn.addEventListener('click', () => openPopover(cropBtn, pop => {
    pop.append(
      h('h5', {}, 'Zoom'),
      cropSlider(store, 'cropScale', 1, 3, 0.02, el.cropScale || 1),
      h('h5', {}, 'Horizontal position'),
      cropSlider(store, 'cropX', 0, 1, 0.01, el.cropX ?? 0.5),
      h('h5', {}, 'Vertical position'),
      cropSlider(store, 'cropY', 0, 1, 0.01, el.cropY ?? 0.5),
      h('div', { class: 'row', style: { marginTop: '10px' } },
        h('button', {
          class: 'btn btn-ghost', 'data-testid': 'crop-reset',
          onclick: () => commands.updateSelected(store, { cropScale: 1, cropX: 0.5, cropY: 0.5 }),
        }, 'Reset')),
    );
  }));
  root.appendChild(cropBtn);

  root.appendChild(h('button', {
    class: 'btn btn-ghost', 'data-testid': 'btn-replace-image',
    onclick: () => {
      window.dispatchEvent(new CustomEvent('canvia:replace-image'));
      toast('Pick a photo or upload to replace this image', 'success');
    },
  }, 'Replace'));

  root.appendChild(h('span', { style: { fontSize: '12.5px', color: 'var(--text-dim)' } }, 'Round'));
  const radius = h('input', {
    class: 'slider', type: 'range', min: 0, max: Math.floor(Math.min(el.w, el.h) / 2), step: 1,
    value: el.radius || 0, style: { width: '90px' }, 'data-testid': 'img-radius-slider',
  });
  radius.addEventListener('input', () => commands.updateSelectedTransient(store, { radius: Number(radius.value) }));
  radius.addEventListener('change', () => store.commit());
  root.appendChild(radius);

  function cropSlider(store2, prop, min, max, step, value) {
    const s = h('input', { class: 'slider', type: 'range', min, max, step, value, 'data-testid': `crop-${prop}` });
    s.addEventListener('input', () => commands.updateSelectedTransient(store2, { [prop]: Number(s.value) }));
    s.addEventListener('change', () => store2.commit());
    return s;
  }
}

// ---- line ----
function lineControls(root, store, el) {
  root.appendChild(colorChipButton(el.color, 'Line color', 'line-color', anchor =>
    openColorPicker(anchor, {
      store, current: el.color,
      onPick: c => commands.updateSelected(store, { color: c }),
    })));

  const thick = h('input', {
    class: 'slider', type: 'range', min: 1, max: 30, step: 1, value: el.thickness,
    style: { width: '90px' }, 'data-testid': 'line-thickness',
  });
  thick.addEventListener('input', () => commands.updateSelectedTransient(store, e => ({
    thickness: Number(thick.value), h: Math.max(8, Number(thick.value)),
  })));
  thick.addEventListener('change', () => store.commit());
  root.append(h('span', { style: { fontSize: '12.5px', color: 'var(--text-dim)' } }, 'Weight'), thick);

  const dashSel = h('select', { class: 'select', 'data-testid': 'line-dash' },
    ['solid', 'dashed', 'dotted'].map(d => h('option', { value: d, selected: el.dash === d }, d[0].toUpperCase() + d.slice(1))));
  dashSel.addEventListener('change', () => commands.updateSelected(store, { dash: dashSel.value }));
  root.appendChild(dashSel);

  const capOptions = [['none', '—'], ['arrow', '→'], ['dot', '●']];
  const startSel = h('select', { class: 'select', title: 'Start cap', 'data-testid': 'line-startcap' },
    capOptions.map(([v, icon]) => h('option', { value: v, selected: el.startCap === v }, icon + ' start')));
  startSel.addEventListener('change', () => commands.updateSelected(store, { startCap: startSel.value }));
  const endSel = h('select', { class: 'select', title: 'End cap', 'data-testid': 'line-endcap' },
    capOptions.map(([v, icon]) => h('option', { value: v, selected: el.endCap === v }, 'end ' + icon)));
  endSel.addEventListener('change', () => commands.updateSelected(store, { endCap: endSel.value }));
  root.append(startSel, endSel);
}

// ---- universal cluster ----
function universalCluster(root, store, selected) {
  const el = selected[0];
  const posBtn = h('button', { class: 'btn btn-ghost', 'data-testid': 'btn-position' }, 'Position');
  posBtn.addEventListener('click', () => openPositionPopover(posBtn, store));
  root.appendChild(posBtn);

  // Copy style roller
  root.appendChild(iconBtn('🖌️', styleClipboard ? 'Click an element to paste style' : 'Copy style', () => {
    if (!styleClipboard) {
      styleClipboard = extractStyle(el);
      paintMode = true;
      toast('Style copied — now select another element to apply it');
    } else {
      applyCopiedStyle(store);
      paintMode = false;
    }
  }, { 'data-testid': 'btn-copystyle', class: 'icon-btn' + (paintMode ? ' active' : '') }));

  // Opacity popover
  const opBtn = iconBtn('◐', 'Opacity', () => {
    openPopover(opBtn, pop => {
      pop.append(h('h5', {}, 'Opacity'));
      const s = h('input', {
        class: 'slider', type: 'range', min: 0.02, max: 1, step: 0.01, value: el.opacity,
        'data-testid': 'opacity-slider',
      });
      s.addEventListener('input', () => commands.setOpacitySelected(store, Number(s.value), { commit: false }));
      s.addEventListener('change', () => store.commit());
      pop.appendChild(s);
    });
  }, { 'data-testid': 'btn-opacity' });
  root.appendChild(opBtn);

  const anyUnlocked = selected.some(s => !s.locked);
  root.appendChild(iconBtn(anyUnlocked ? '🔓' : '🔒', anyUnlocked ? 'Lock' : 'Unlock',
    () => commands.toggleLockSelected(store), { 'data-testid': 'btn-lock', class: 'icon-btn' + (anyUnlocked ? '' : ' active') }));
  root.appendChild(iconBtn('⧉', 'Duplicate (Ctrl+D)', () => commands.duplicateSelected(store), { 'data-testid': 'btn-duplicate' }));
  root.appendChild(iconBtn('🗑', 'Delete (Del)', () => commands.deleteSelected(store), { 'data-testid': 'btn-delete' }));
}

// ---- helpers ----
function colorChipButton(background, title, testid, onOpen) {
  const chip = h('button', { class: 'color-chip', title, 'data-testid': testid });
  chip.style.background = background;
  chip.addEventListener('click', () => onOpen(chip));
  return chip;
}

function sliderRow(store, el, prop, min, max, step, testid) {
  const s = h('input', { class: 'slider', type: 'range', min, max, step, value: el[prop], 'data-testid': testid });
  s.addEventListener('input', () => commands.updateSelectedTransient(store, { [prop]: Number(s.value) }));
  s.addEventListener('change', () => store.commit());
  return s;
}

function extractStyle(el) {
  const common = { opacity: el.opacity };
  switch (el.type) {
    case 'text': return { type: 'text', props: { ...common, fontFamily: el.fontFamily, fontSize: el.fontSize, fontWeight: el.fontWeight, italic: el.italic, underline: el.underline, align: el.align, lineHeight: el.lineHeight, letterSpacing: el.letterSpacing, color: el.color, effect: JSON.parse(JSON.stringify(el.effect)) } };
    case 'shape': return { type: 'shape', props: { ...common, fill: JSON.parse(JSON.stringify(el.fill)), stroke: el.stroke, strokeWidth: el.strokeWidth, radius: el.radius } };
    case 'image': return { type: 'image', props: { ...common, filter: el.filter, radius: el.radius, stroke: el.stroke, strokeWidth: el.strokeWidth } };
    case 'line': return { type: 'line', props: { ...common, color: el.color, thickness: el.thickness, dash: el.dash, startCap: el.startCap, endCap: el.endCap } };
    default: return { type: el.type, props: common };
  }
}

function applyCopiedStyle(store) {
  if (!styleClipboard) return;
  const clip = styleClipboard;
  const targets = store.selectedElements().filter(t => !t.locked);
  if (!targets.length) return;
  store.apply(() => {
    for (const target of targets) {
      if (target.type === clip.type) Object.assign(target, JSON.parse(JSON.stringify(clip.props)));
      else target.opacity = clip.props.opacity;
      // Cross-type color transfer where it makes sense.
      if (target.type !== clip.type) {
        const color = clip.props.color || (clip.props.fill && clip.props.fill.kind === 'solid' ? clip.props.fill.color : null);
        if (color) {
          if (target.type === 'text' || target.type === 'line') target.color = color;
          if (target.type === 'shape') target.fill = { kind: 'solid', color };
        }
      }
    }
  });
  styleClipboard = null;
  toast('Style applied');
}
