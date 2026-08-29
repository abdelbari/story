// Position popover: align to page / selection, distribute, layer order,
// flip, and exact x/y/w/h/rotation inputs for a single selection.

import { h, openPopover } from './widgets.js';
import * as commands from '../editor/commands.js';

export function openPositionPopover(anchor, store) {
  return openPopover(anchor, pop => {
    pop.dataset.testid = 'position-popover';
    pop.style.width = '300px';
    const selected = store.selectedElements();
    const multi = selected.length > 1;

    pop.append(h('h5', {}, 'Arrange'));
    pop.append(h('div', { class: 'row' },
      arrangeBtn('To front', '⬆⬆', () => commands.bringToFront(store), 'arr-front'),
      arrangeBtn('Forward', '⬆', () => commands.bringForward(store), 'arr-forward'),
      arrangeBtn('Backward', '⬇', () => commands.sendBackward(store), 'arr-backward'),
      arrangeBtn('To back', '⬇⬇', () => commands.sendToBack(store), 'arr-back'),
    ));

    pop.append(h('h5', {}, multi ? 'Align selection' : 'Align to page'));
    pop.append(h('div', { class: 'row' },
      arrangeBtn('Left', '⇤', () => commands.alignSelected(store, 'left'), 'align-left'),
      arrangeBtn('Center', '↔', () => commands.alignSelected(store, 'centerX'), 'align-centerx'),
      arrangeBtn('Right', '⇥', () => commands.alignSelected(store, 'right'), 'align-right'),
    ));
    pop.append(h('div', { class: 'row' },
      arrangeBtn('Top', '⤒', () => commands.alignSelected(store, 'top'), 'align-top'),
      arrangeBtn('Middle', '↕', () => commands.alignSelected(store, 'centerY'), 'align-centery'),
      arrangeBtn('Bottom', '⤓', () => commands.alignSelected(store, 'bottom'), 'align-bottom'),
    ));

    if (selected.length >= 3) {
      pop.append(h('h5', {}, 'Distribute'));
      pop.append(h('div', { class: 'row' },
        arrangeBtn('Horizontally', '⇹', () => commands.distributeSelected(store, 'x'), 'dist-x'),
        arrangeBtn('Vertically', '⇳', () => commands.distributeSelected(store, 'y'), 'dist-y'),
      ));
    }

    pop.append(h('h5', {}, 'Flip'));
    pop.append(h('div', { class: 'row' },
      arrangeBtn('Flip horizontal', '⇋', () => commands.flipSelected(store, 'h'), 'flip-h'),
      arrangeBtn('Flip vertical', '⥮', () => commands.flipSelected(store, 'v'), 'flip-v'),
    ));

    if (selected.length === 1) {
      const el = selected[0];
      pop.append(h('h5', {}, 'Exact size & position'));
      const grid = h('div', { style: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' } });
      const fields = [
        ['x', 'X', el.x], ['y', 'Y', el.y],
        ['w', 'Width', el.w], ['h', 'Height', el.h],
        ['rotation', 'Rotate °', el.rotation || 0],
      ];
      for (const [prop, label, value] of fields) {
        if (prop === 'h' && el.type === 'text') continue; // auto height
        const input = h('input', {
          class: 'num-input', type: 'number', value: Math.round(value * 10) / 10,
          style: { width: '100%' }, 'data-testid': `pos-${prop}`,
        });
        input.addEventListener('change', () => {
          const v = Number(input.value);
          if (Number.isNaN(v)) return;
          commands.updateSelected(store, prop === 'w' || prop === 'h'
            ? { [prop]: Math.max(8, v) }
            : { [prop]: v });
        });
        grid.append(h('label', { style: { fontSize: '11.5px', color: 'var(--text-dim)' } }, label, input));
      }
      pop.appendChild(grid);
    }

    function arrangeBtn(title, icon, onclick, testid) {
      return h('button', {
        class: 'btn btn-ghost', title, 'data-testid': testid,
        style: { flex: '1', padding: '7px 4px' },
        onclick,
      }, icon);
    }
  }, { align: 'right' });
}
