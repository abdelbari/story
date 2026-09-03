// Color picker popover: document colors, default swatches, curated
// palettes, gradient presets (optional) and a custom hex / native picker.

import { h, openPopover } from './widgets.js';
import { PALETTES, GRADIENT_PRESETS, DEFAULT_SWATCHES, documentColors } from '../assets/palettes.js';
import { paintToCss } from '../core/paint.js';

export function openColorPicker(anchor, { store, current, onPick, onPickGradient, testid = 'colorpicker' }) {
  return openPopover(anchor, pop => {
    pop.dataset.testid = testid;
    pop.style.width = '292px';

    // Custom row: hex input + native picker.
    const hexInput = h('input', {
      class: 'num-input', style: { width: '92px', textAlign: 'left' },
      value: typeof current === 'string' ? current : '#000000',
      'data-testid': 'color-hex-input',
      placeholder: '#rrggbb',
    });
    const nativeInput = h('input', {
      type: 'color', style: { width: '34px', height: '30px', border: 'none', cursor: 'pointer', background: 'none' },
      value: normalizeHex(typeof current === 'string' ? current : '#000000'),
      'data-testid': 'color-native-input',
    });
    hexInput.addEventListener('change', () => {
      const v = normalizeHex(hexInput.value);
      if (v) { nativeInput.value = v; onPick(v); }
    });
    nativeInput.addEventListener('input', () => {
      hexInput.value = nativeInput.value;
      onPick(nativeInput.value);
    });
    pop.append(
      h('h5', {}, 'Custom'),
      h('div', { class: 'row' }, nativeInput, hexInput),
    );

    const swatchRow = (colors, label, testPrefix) => {
      if (!colors.length) return null;
      return [
        h('h5', {}, label),
        h('div', { class: 'swatch-grid' },
          colors.map(c => h('button', {
            class: 'swatch' + (sameColor(c, current) ? ' active' : ''),
            style: { background: c },
            title: c,
            'data-testid': `${testPrefix}-${c.replace('#', '')}`,
            onclick: () => onPick(c),
          }))),
      ];
    };

    if (store?.doc) {
      pop.append(...(swatchRow(documentColors(store.doc), 'Document colors', 'doccolor') || []));
    }
    pop.append(...swatchRow(DEFAULT_SWATCHES, 'Default colors', 'swatch'));

    for (const palette of PALETTES) {
      pop.append(
        h('h5', {}, palette.name),
        h('div', { class: 'swatch-grid' },
          palette.colors.map(c => h('button', {
            class: 'swatch', style: { background: c }, title: c,
            onclick: () => onPick(c),
          }))),
      );
    }

    if (onPickGradient) {
      pop.append(
        h('h5', {}, 'Gradients'),
        h('div', { class: 'swatch-grid' },
          GRADIENT_PRESETS.map(g => h('button', {
            class: 'swatch',
            style: { background: paintToCss({ kind: 'gradient', angle: g.angle, stops: g.stops }) },
            title: g.name,
            'data-testid': `gradient-${g.id}`,
            onclick: () => onPickGradient({ kind: 'gradient', angle: g.angle, stops: JSON.parse(JSON.stringify(g.stops)) }),
          }))),
      );
    }
  }, {});
}

function normalizeHex(value) {
  if (typeof value !== 'string') return null;
  let v = value.trim();
  if (!v.startsWith('#')) v = '#' + v;
  if (/^#[0-9a-fA-F]{3}$/.test(v)) v = '#' + v.slice(1).split('').map(c => c + c).join('');
  return /^#[0-9a-fA-F]{6}$/.test(v) ? v.toLowerCase() : null;
}

function sameColor(a, b) {
  return typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase();
}
