// DOM renderer for the active page. Elements are absolutely-positioned nodes
// inside a page container that the workspace scales with CSS transform.
// Rendering is keyed by element id: existing nodes are reused and mutated in
// place, which keeps 60fps drags cheap and preserves contenteditable focus.

import { SHAPE_MAP } from '../assets/shapes.js';
import { fontStack, textEffectCss, TEXT_EFFECTS } from '../assets/typography.js';
import { filterCss } from '../assets/filters.js';
import { paintToCss } from '../core/paint.js';
import { resolveImageSrc } from '../assets/photos.js';

const SVG_NS = 'http://www.w3.org/2000/svg';

export class PageRenderer {
  constructor(pageEl, store) {
    this.pageEl = pageEl;
    this.store = store;
    this.nodes = new Map(); // element id -> DOM node
  }

  render() {
    const page = this.store.page;
    const doc = this.store.doc;
    if (!page || !doc) return;

    this.pageEl.style.width = doc.width + 'px';
    this.pageEl.style.height = doc.height + 'px';
    this.renderBackground(page);

    const seen = new Set();
    let prevNode = this.bgNode;
    for (const el of page.elements) {
      seen.add(el.id);
      let node = this.nodes.get(el.id);
      if (!node || node.dataset.type !== el.type) {
        if (node) node.remove();
        node = this.createNode(el);
        this.nodes.set(el.id, node);
      }
      // Insert before updating: text auto-height measures scrollHeight,
      // which requires the node to be in the document.
      if (prevNode ? node.previousSibling !== prevNode : node !== this.pageEl.firstChild) {
        this.pageEl.insertBefore(node, prevNode ? prevNode.nextSibling : this.pageEl.firstChild);
      }
      this.updateNode(node, el);
      prevNode = node;
    }
    for (const [id, node] of this.nodes) {
      if (!seen.has(id)) { node.remove(); this.nodes.delete(id); }
    }
  }

  renderBackground(page) {
    if (!this.bgNode) {
      this.bgNode = document.createElement('div');
      this.bgNode.className = 'cc-page-bg';
      this.bgNode.dataset.bg = '1';
      this.pageEl.prepend(this.bgNode);
    }
    const bg = page.background || { type: 'color', value: '#ffffff' };
    if (bg.type === 'image') {
      this.bgNode.style.background = `url("${resolveImageSrc(bg.value)}") center/cover no-repeat`;
    } else if (bg.type === 'gradient') {
      this.bgNode.style.background = paintToCss(bg.value);
    } else {
      this.bgNode.style.background = bg.value || '#ffffff';
    }
  }

  createNode(el) {
    const node = document.createElement('div');
    node.className = 'cc-el';
    node.dataset.id = el.id;
    node.dataset.type = el.type;
    if (el.type === 'shape') {
      const svg = document.createElementNS(SVG_NS, 'svg');
      svg.setAttribute('viewBox', '0 0 100 100');
      svg.setAttribute('preserveAspectRatio', 'none');
      svg.classList.add('cc-shape-svg');
      const defs = document.createElementNS(SVG_NS, 'defs');
      const path = document.createElementNS(SVG_NS, 'path');
      svg.append(defs, path);
      node.appendChild(svg);
    } else if (el.type === 'text') {
      const text = document.createElement('div');
      text.className = 'cc-text';
      text.spellcheck = false;
      node.appendChild(text);
    } else if (el.type === 'image') {
      const img = document.createElement('img');
      img.className = 'cc-img';
      img.draggable = false;
      img.alt = '';
      node.appendChild(img);
    } else if (el.type === 'sticker') {
      const glyph = document.createElement('div');
      glyph.className = 'cc-sticker';
      node.appendChild(glyph);
    } else if (el.type === 'line') {
      const svg = document.createElementNS(SVG_NS, 'svg');
      svg.classList.add('cc-line-svg');
      node.appendChild(svg);
    }
    return node;
  }

  updateNode(node, el) {
    const s = node.style;
    s.left = el.x + 'px';
    s.top = el.y + 'px';
    s.width = el.w + 'px';
    s.height = el.h + 'px';
    s.opacity = el.opacity;
    const flip = `${el.flipH ? ' scaleX(-1)' : ''}${el.flipV ? ' scaleY(-1)' : ''}`;
    s.transform = `rotate(${el.rotation || 0}deg)${flip}`;
    node.classList.toggle('cc-locked', !!el.locked);

    switch (el.type) {
      case 'shape': this.updateShape(node, el); break;
      case 'text': this.updateText(node, el); break;
      case 'image': this.updateImage(node, el); break;
      case 'sticker': this.updateSticker(node, el); break;
      case 'line': this.updateLine(node, el); break;
    }
  }

  updateShape(node, el) {
    const svg = node.firstChild;
    const defs = svg.querySelector('defs');
    const path = svg.querySelector('path');
    const def = SHAPE_MAP[el.shapeId] || SHAPE_MAP.rect;

    let fill;
    if (el.fill && el.fill.kind === 'gradient') {
      const gradId = `grad-${el.id}`;
      let grad = defs.querySelector('linearGradient');
      if (!grad) {
        grad = document.createElementNS(SVG_NS, 'linearGradient');
        grad.id = gradId;
        defs.appendChild(grad);
      }
      // CSS gradient angle -> SVG vector, compensating the unit-box
      // stretch so direction and line length match CSS on any aspect.
      const rad = ((el.fill.angle - 90) * Math.PI) / 180;
      const dx = Math.cos(rad), dy = Math.sin(rad);
      const len = Math.abs(el.w * dx) + Math.abs(el.h * dy);
      const ux = el.w > 0 ? (dx * len) / (2 * el.w) : 0;
      const uy = el.h > 0 ? (dy * len) / (2 * el.h) : 0;
      grad.setAttribute('x1', 0.5 - ux); grad.setAttribute('y1', 0.5 - uy);
      grad.setAttribute('x2', 0.5 + ux); grad.setAttribute('y2', 0.5 + uy);
      while (grad.childNodes.length > el.fill.stops.length) grad.lastChild.remove();
      el.fill.stops.forEach((stop, i) => {
        let node2 = grad.childNodes[i];
        if (!node2) {
          node2 = document.createElementNS(SVG_NS, 'stop');
          grad.appendChild(node2);
        }
        node2.setAttribute('offset', stop.offset);
        node2.setAttribute('stop-color', stop.color);
      });
      fill = `url(#${gradId})`;
    } else {
      defs.textContent = '';
      fill = el.fill?.color || 'transparent';
    }

    if (def.rectLike && el.radius > 0) {
      // Rounded rectangle: emit a path with true corner radius in px space.
      const rx = Math.min(el.radius, el.w / 2) * (100 / el.w);
      const ry = Math.min(el.radius, el.h / 2) * (100 / el.h);
      path.setAttribute('d',
        `M${rx},0H${100 - rx}A${rx},${ry} 0 0 1 100,${ry}V${100 - ry}A${rx},${ry} 0 0 1 ${100 - rx},100H${rx}A${rx},${ry} 0 0 1 0,${100 - ry}V${ry}A${rx},${ry} 0 0 1 ${rx},0Z`);
    } else {
      path.setAttribute('d', def.path);
    }
    path.setAttribute('fill', fill);
    if (el.stroke && el.strokeWidth > 0) {
      path.setAttribute('stroke', el.stroke);
      path.setAttribute('stroke-width', el.strokeWidth);
      path.setAttribute('vector-effect', 'non-scaling-stroke');
      path.setAttribute('stroke-linejoin', 'round');
    } else {
      path.removeAttribute('stroke');
      path.removeAttribute('stroke-width');
    }
  }

  updateText(node, el) {
    const text = node.firstChild;
    const editing = this.store.editingTextId === el.id;
    const s = text.style;
    s.fontFamily = fontStack(el.fontFamily);
    s.fontSize = el.fontSize + 'px';
    s.fontWeight = el.fontWeight;
    s.fontStyle = el.italic ? 'italic' : 'normal';
    s.textDecoration = el.underline ? 'underline' : 'none';
    s.textAlign = el.align;
    s.lineHeight = el.lineHeight;
    s.letterSpacing = el.letterSpacing + 'px';
    s.color = el.color;
    // Reset effect-controlled props, then apply the active effect.
    s.textShadow = 'none';
    s.webkitTextStroke = '';
    s.backgroundColor = 'transparent';
    s.padding = '0';
    // Underline must stay visible for hollow/splice (text fill is
    // transparent there, and decoration inherits the fill color).
    s.textDecorationColor = el.underline ? el.color : '';
    const isHighlight = (el.effect?.type || 'none') === 'highlight';
    const effectCss = textEffectCss(el);
    if (!isHighlight) {
      for (const [prop, value] of Object.entries(effectCss)) s[prop] = value;
    }

    if (!editing) {
      const content = el.listStyle === 'bullet'
        ? el.text.split('\n').map(line => line.trim() ? `•  ${line}` : line).join('\n')
        : el.text;
      // Content lives in an inline span so the highlight effect can paint
      // per-line backgrounds (box-decoration-break), matching the exporter.
      let span = text.firstElementChild;
      if (!span || span.tagName !== 'SPAN') {
        text.textContent = '';
        span = document.createElement('span');
        text.appendChild(span);
      }
      if (span.textContent !== content) span.textContent = content;
      if (isHighlight) {
        for (const [prop, value] of Object.entries(effectCss)) span.style[prop] = value;
      } else {
        span.style.backgroundColor = '';
        span.style.padding = '';
      }
      if (text.contentEditable !== 'false') text.contentEditable = 'false';
      node.classList.remove('cc-editing');
    } else {
      // While editing, the contenteditable node owns its text — never write
      // textContent here or the caret resets on every transient update.
      if (text.contentEditable !== 'true') {
        text.contentEditable = 'true';
        if (text.textContent !== el.text) text.textContent = el.text;
      }
      node.classList.add('cc-editing');
    }

    if (el.autoHeight !== false) {
      // Measure natural height at the current width and sync the model
      // silently (no event) — the caller renders overlay after us.
      const h = Math.max(text.scrollHeight, el.fontSize * el.lineHeight);
      if (Math.abs(h - el.h) > 0.5) {
        if (el.rotation) {
          // Rotation pivots on the center, so a height change moves every
          // point. Keep the rotated top-center anchored: c' = topCenter +
          // R(rot)·(0, h'/2).
          const rad = el.rotation * Math.PI / 180;
          const cx = el.x + el.w / 2, cy = el.y + el.h / 2;
          const topX = cx + (el.h / 2) * Math.sin(rad);
          const topY = cy - (el.h / 2) * Math.cos(rad);
          el.x = topX - (h / 2) * Math.sin(rad) - el.w / 2;
          el.y = topY + (h / 2) * Math.cos(rad) - h / 2;
          node.style.left = el.x + 'px';
          node.style.top = el.y + 'px';
        }
        el.h = h;
        node.style.height = h + 'px';
      }
    }
  }

  updateImage(node, el) {
    const img = node.firstChild;
    const src = resolveImageSrc(el.src);
    if (img.dataset.src !== src) {
      img.src = src;
      img.dataset.src = src;
    }
    img.style.filter = filterCss(el);
    // Cover-crop focus + zoom: object-position keeps the focus fraction of
    // the image aligned with the same fraction of the box; scaling about
    // that origin zooms while pinning the focus point (mirrors the
    // exporter's source-window math exactly).
    const cx = (el.cropX ?? 0.5) * 100, cy = (el.cropY ?? 0.5) * 100;
    img.style.objectPosition = `${cx}% ${cy}%`;
    const cs = el.cropScale || 1;
    img.style.transform = cs !== 1 ? `scale(${cs})` : '';
    img.style.transformOrigin = `${cx}% ${cy}%`;
    node.style.overflow = 'hidden';
    node.style.borderRadius = (el.radius || 0) + 'px';
    node.style.border = el.stroke && el.strokeWidth > 0 ? `${el.strokeWidth}px solid ${el.stroke}` : 'none';
  }

  updateSticker(node, el) {
    const glyph = node.firstChild;
    if (glyph.textContent !== el.glyph) glyph.textContent = el.glyph;
    glyph.style.fontSize = Math.min(el.w, el.h) * 0.86 + 'px';
  }

  updateLine(node, el) {
    const svg = node.firstChild;
    svg.setAttribute('viewBox', `0 0 ${el.w} ${el.h}`);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.innerHTML = '';
    const y = el.h / 2;
    const t = el.thickness;
    const capSize = Math.max(t * 3, 10);
    let x1 = 0, x2 = el.w;
    if (el.startCap === 'arrow') x1 += capSize * 0.9;
    if (el.endCap === 'arrow') x2 -= capSize * 0.9;
    const line = document.createElementNS(SVG_NS, 'line');
    line.setAttribute('x1', x1); line.setAttribute('y1', y);
    line.setAttribute('x2', x2); line.setAttribute('y2', y);
    line.setAttribute('stroke', el.color);
    line.setAttribute('stroke-width', t);
    line.setAttribute('stroke-linecap', 'round');
    if (el.dash === 'dashed') line.setAttribute('stroke-dasharray', `${t * 3} ${t * 2}`);
    if (el.dash === 'dotted') line.setAttribute('stroke-dasharray', `0 ${t * 2.2}`);
    svg.appendChild(line);
    const addCap = (atStart) => {
      const cap = atStart ? el.startCap : el.endCap;
      if (cap === 'none') return;
      if (cap === 'arrow') {
        const p = document.createElementNS(SVG_NS, 'path');
        const tip = atStart ? 0 : el.w;
        const dir = atStart ? 1 : -1;
        p.setAttribute('d', `M${tip},${y}L${tip + dir * capSize},${y - capSize * 0.6}L${tip + dir * capSize},${y + capSize * 0.6}Z`);
        p.setAttribute('fill', el.color);
        svg.appendChild(p);
      } else if (cap === 'dot') {
        const c = document.createElementNS(SVG_NS, 'circle');
        c.setAttribute('cx', atStart ? t : el.w - t);
        c.setAttribute('cy', y);
        c.setAttribute('r', Math.max(t * 1.4, 5));
        c.setAttribute('fill', el.color);
        svg.appendChild(c);
      }
    };
    addCap(true);
    addCap(false);
  }
}

export { TEXT_EFFECTS };
