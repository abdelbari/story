// Image filter presets. Each preset is a function of intensity t in [0,1]
// producing a CSS filter string. The same string is applied to DOM <img>
// nodes and to ctx.filter during canvas export (Chromium supports both).

function lerp(a, b, t) { return a + (b - a) * t; }

export const IMAGE_FILTERS = {
  none: { name: 'None', css: () => 'none' },
  vivid: {
    name: 'Vivid',
    css: t => `saturate(${lerp(1, 1.6, t)}) contrast(${lerp(1, 1.15, t)}) brightness(${lerp(1, 1.05, t)})`,
  },
  warm: {
    name: 'Warm',
    css: t => `sepia(${lerp(0, 0.35, t)}) saturate(${lerp(1, 1.3, t)}) brightness(${lerp(1, 1.05, t)})`,
  },
  cool: {
    name: 'Cool',
    css: t => `hue-rotate(${lerp(0, -18, t)}deg) saturate(${lerp(1, 1.15, t)}) brightness(${lerp(1, 1.03, t)})`,
  },
  mono: {
    name: 'Mono',
    css: t => `grayscale(${t}) contrast(${lerp(1, 1.08, t)})`,
  },
  noir: {
    name: 'Noir',
    css: t => `grayscale(${t}) contrast(${lerp(1, 1.45, t)}) brightness(${lerp(1, 0.9, t)})`,
  },
  fade: {
    name: 'Fade',
    css: t => `saturate(${lerp(1, 0.65, t)}) brightness(${lerp(1, 1.12, t)}) contrast(${lerp(1, 0.88, t)})`,
  },
  retro: {
    name: 'Retro',
    css: t => `sepia(${lerp(0, 0.5, t)}) hue-rotate(${lerp(0, -12, t)}deg) saturate(${lerp(1, 1.2, t)}) contrast(${lerp(1, 0.95, t)})`,
  },
  dramatic: {
    name: 'Dramatic',
    css: t => `contrast(${lerp(1, 1.4, t)}) brightness(${lerp(1, 0.92, t)}) saturate(${lerp(1, 1.1, t)})`,
  },
  dreamy: {
    name: 'Dreamy',
    css: t => `blur(${lerp(0, 1.6, t)}px) brightness(${lerp(1, 1.08, t)}) saturate(${lerp(1, 1.15, t)})`,
  },
};

export function filterCss(el) {
  const preset = IMAGE_FILTERS[el.filter || 'none'] || IMAGE_FILTERS.none;
  const t = typeof el.filterIntensity === 'number' ? el.filterIntensity : 1;
  return preset.css(Math.min(1, Math.max(0, t)));
}
