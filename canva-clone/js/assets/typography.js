// Typography assets: curated system font stacks (offline-safe) and text
// effects. Each effect knows how to express itself as CSS for the DOM
// renderer; the canvas exporter mirrors the same parameters.

export const FONT_STACKS = {
  sans: { name: 'Modern Sans', stack: "'Helvetica Neue', Arial, 'Segoe UI', sans-serif" },
  grotesk: { name: 'Grotesk', stack: "'Arial Black', 'Segoe UI', system-ui, sans-serif" },
  serif: { name: 'Classic Serif', stack: "Georgia, 'Times New Roman', serif" },
  didone: { name: 'Editorial', stack: "'Didot', 'Bodoni MT', 'Playfair Display', Georgia, serif" },
  slab: { name: 'Slab', stack: "'Rockwell', 'Roboto Slab', 'Courier New', serif" },
  mono: { name: 'Typewriter', stack: "'Courier New', 'SF Mono', Consolas, monospace" },
  rounded: { name: 'Rounded', stack: "'Arial Rounded MT Bold', 'Trebuchet MS', 'Segoe UI', sans-serif" },
  condensed: { name: 'Condensed', stack: "'Arial Narrow', 'Roboto Condensed', 'Segoe UI', sans-serif" },
  humanist: { name: 'Humanist', stack: "Verdana, 'Segoe UI', Geneva, sans-serif" },
  script: { name: 'Script', stack: "'Brush Script MT', 'Segoe Script', 'Comic Sans MS', cursive" },
  elegant: { name: 'Elegant', stack: "'Palatino Linotype', 'Book Antiqua', Palatino, serif" },
  impact: { name: 'Display', stack: "Impact, 'Arial Black', 'Franklin Gothic Bold', sans-serif" },
};

export function fontStack(key) {
  return (FONT_STACKS[key] || FONT_STACKS.sans).stack;
}

// Text effects: parameterized by the element's own color where sensible.
// css(el) returns style props to merge into the text node's style.
export const TEXT_EFFECTS = {
  none: { name: 'None', css: () => ({}) },
  shadow: {
    name: 'Shadow',
    css: () => ({ textShadow: '0.06em 0.06em 0.12em rgba(0,0,0,0.55)' }),
  },
  lift: {
    name: 'Lift',
    css: () => ({ textShadow: '0 0.18em 0.5em rgba(0,0,0,0.35)' }),
  },
  outline: {
    name: 'Hollow',
    css: el => ({
      color: 'transparent',
      webkitTextStroke: `max(1.5px, 0.035em) ${el.color}`,
    }),
  },
  splice: {
    name: 'Splice',
    css: el => ({
      color: 'transparent',
      webkitTextStroke: `max(1.5px, 0.03em) ${el.color}`,
      textShadow: `0.08em 0.08em 0 ${withAlpha(el.color, 0.45)}`,
    }),
  },
  neon: {
    name: 'Neon',
    css: el => ({
      textShadow: `0 0 0.12em ${withAlpha(el.color, 0.9)}, 0 0 0.45em ${withAlpha(el.color, 0.7)}, 0 0 1em ${withAlpha(el.color, 0.5)}`,
    }),
  },
  glitch: {
    name: 'Echo',
    css: el => ({
      textShadow: `0.06em 0 0 ${withAlpha('#00e5ff', 0.85)}, -0.06em 0 0 ${withAlpha('#ff2d78', 0.85)}`,
    }),
  },
  highlight: {
    name: 'Highlight',
    css: el => ({
      backgroundColor: highlightColor(el.color),
      boxDecorationBreak: 'clone',
      webkitBoxDecorationBreak: 'clone',
      padding: '0 0.18em',
    }),
    // highlight paints per-line background; exporter mirrors with rects
    perLineBackground: true,
  },
};

export function textEffectCss(el) {
  const effect = TEXT_EFFECTS[el.effect?.type || 'none'] || TEXT_EFFECTS.none;
  return effect.css(el);
}

// Pick a highlight color contrasting the text color: light text -> dark
// highlight, dark text -> warm yellow (Canva-like default).
export function highlightColor(textColor) {
  return isLightColor(textColor) ? '#1f2430' : '#ffe066';
}

export function isLightColor(hex) {
  const { r, g, b } = parseColor(hex);
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.62;
}

export function parseColor(color) {
  if (typeof color !== 'string') return { r: 0, g: 0, b: 0, a: 1 };
  if (color.startsWith('#')) {
    let hex = color.slice(1);
    if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
    const num = parseInt(hex.slice(0, 6), 16);
    const a = hex.length === 8 ? parseInt(hex.slice(6, 8), 16) / 255 : 1;
    return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255, a };
  }
  const m = color.match(/rgba?\(([^)]+)\)/);
  if (m) {
    const [r, g, b, a = 1] = m[1].split(',').map(Number);
    return { r, g, b, a };
  }
  return { r: 0, g: 0, b: 0, a: 1 };
}

export function withAlpha(color, alpha) {
  const { r, g, b } = parseColor(color);
  return `rgba(${r},${g},${b},${alpha})`;
}
