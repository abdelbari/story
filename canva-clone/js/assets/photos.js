// Procedural "photo" library. Every image is generated as an SVG string and
// exposed as a data URI, so the app is fully offline yet ships a gallery of
// rich backgrounds and art. Elements reference them as "asset:<id>"; user
// uploads are stored as plain data URIs.

const generators = {};
const cache = new Map();

function svgURI(svg) {
  return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
}

function def(id, name, category, w, h, build) {
  generators[id] = { id, name, category, w, h, build };
}

function wrap(w, h, body, defs = '') {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs>${defs}</defs>${body}</svg>`;
}

// Deterministic pseudo-random for repeatable art.
function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

// ---- Mesh-like gradients -------------------------------------------------
function meshGradient(id, colors, seed) {
  const w = 1200, h = 900;
  const r = rng(seed);
  let defs = `<linearGradient id="base" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="${colors[0]}"/><stop offset="1" stop-color="${colors[1] || colors[0]}"/>
  </linearGradient><filter id="blur"><feGaussianBlur stdDeviation="90"/></filter>`;
  let body = `<rect width="${w}" height="${h}" fill="url(#base)"/>`;
  const blobs = colors.slice(1).concat(colors[0]);
  body += '<g filter="url(#blur)">';
  blobs.forEach((c, i) => {
    const cx = Math.round(r() * w), cy = Math.round(r() * h);
    const rad = Math.round(180 + r() * 320);
    body += `<circle cx="${cx}" cy="${cy}" r="${rad}" fill="${c}" opacity="0.85"/>`;
  });
  body += '</g>';
  return wrap(w, h, body, defs);
}

def('mesh-sunset', 'Sunset Mesh', 'Gradients', 1200, 900,
  () => meshGradient('mesh-sunset', ['#ff9a8b', '#ff6a88', '#ff99ac', '#fbc2eb', '#f6d365'], 7));
def('mesh-ocean', 'Ocean Mesh', 'Gradients', 1200, 900,
  () => meshGradient('mesh-ocean', ['#2b5876', '#4e4376', '#00c6fb', '#005bea', '#43e97b'], 21));
def('mesh-candy', 'Candy Mesh', 'Gradients', 1200, 900,
  () => meshGradient('mesh-candy', ['#a18cd1', '#fbc2eb', '#fad0c4', '#ff9a9e', '#fecfef'], 33));
def('mesh-forest', 'Forest Mesh', 'Gradients', 1200, 900,
  () => meshGradient('mesh-forest', ['#134e5e', '#71b280', '#2af598', '#009efd', '#0f3443'], 55));
def('mesh-ember', 'Ember Mesh', 'Gradients', 1200, 900,
  () => meshGradient('mesh-ember', ['#1a1a2e', '#e94560', '#903749', '#53354a', '#ff7b54'], 91));
def('mesh-gold', 'Golden Hour', 'Gradients', 1200, 900,
  () => meshGradient('mesh-gold', ['#f8b500', '#fceabb', '#e96443', '#904e95', '#ffd194'], 13));

// ---- Geometric patterns --------------------------------------------------
def('geo-triangles', 'Triangle Mosaic', 'Patterns', 1200, 900, () => {
  const w = 1200, h = 900, size = 150, r = rng(42);
  const palette = ['#22223b', '#4a4e69', '#9a8c98', '#c9ada7', '#f2e9e4'];
  let body = '';
  for (let y = 0; y < h; y += size) {
    for (let x = 0; x < w; x += size) {
      const c1 = palette[Math.floor(r() * palette.length)];
      const c2 = palette[Math.floor(r() * palette.length)];
      if (r() > 0.5) {
        body += `<path d="M${x},${y}L${x + size},${y}L${x},${y + size}Z" fill="${c1}"/><path d="M${x + size},${y}L${x + size},${y + size}L${x},${y + size}Z" fill="${c2}"/>`;
      } else {
        body += `<path d="M${x},${y}L${x + size},${y}L${x + size},${y + size}Z" fill="${c1}"/><path d="M${x},${y}L${x + size},${y + size}L${x},${y + size}Z" fill="${c2}"/>`;
      }
    }
  }
  return wrap(w, h, body);
});

def('geo-waves', 'Layered Waves', 'Patterns', 1200, 900, () => {
  const w = 1200, h = 900;
  const colors = ['#03045e', '#0077b6', '#00b4d8', '#90e0ef', '#caf0f8'];
  let body = `<rect width="${w}" height="${h}" fill="${colors[0]}"/>`;
  colors.slice(1).forEach((c, i) => {
    const baseY = 300 + i * 150;
    const amp = 70 - i * 10;
    let d = `M0,${baseY}`;
    for (let x = 0; x <= w; x += 100) {
      const y = baseY + Math.sin((x / w) * Math.PI * 3 + i * 1.4) * amp;
      d += ` L${x},${Math.round(y)}`;
    }
    d += ` L${w},${h} L0,${h}Z`;
    body += `<path d="${d}" fill="${c}"/>`;
  });
  return wrap(w, h, body);
});

def('geo-dots', 'Dot Grid', 'Patterns', 1200, 900, () => {
  const w = 1200, h = 900;
  let defs = `<pattern id="dots" width="60" height="60" patternUnits="userSpaceOnUse"><circle cx="30" cy="30" r="6" fill="#e0aaff" opacity="0.8"/></pattern>`;
  const body = `<rect width="${w}" height="${h}" fill="#10002b"/><rect width="${w}" height="${h}" fill="url(#dots)"/>`;
  return wrap(w, h, body, defs);
});

def('geo-arcs', 'Art Deco Arcs', 'Patterns', 1200, 900, () => {
  const w = 1200, h = 900;
  let body = `<rect width="${w}" height="${h}" fill="#1d3557"/>`;
  for (let x = 0; x < w; x += 200) {
    for (let y = 0; y < h; y += 200) {
      for (let i = 5; i > 0; i--) {
        body += `<circle cx="${x + 100}" cy="${y + 200}" r="${i * 20}" fill="none" stroke="${i % 2 ? '#e63946' : '#f1faee'}" stroke-width="10"/>`;
      }
    }
  }
  return wrap(w, h, body);
});

// ---- Scenic illustrations ------------------------------------------------
def('scene-mountains', 'Mountain Dusk', 'Scenes', 1200, 900, () => {
  const w = 1200, h = 900;
  const defs = `<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#ff9e7d"/><stop offset="0.55" stop-color="#845ec2"/><stop offset="1" stop-color="#2c2a4a"/>
  </linearGradient>`;
  let body = `<rect width="${w}" height="${h}" fill="url(#sky)"/>`;
  body += `<circle cx="880" cy="260" r="90" fill="#fff1c1" opacity="0.95"/>`;
  const layers = [
    { c: '#4b3f72', pts: [[0, 620], [200, 430], [400, 600], [620, 380], [860, 610], [1080, 460], [1200, 580]] },
    { c: '#38304f', pts: [[0, 730], [260, 520], [520, 720], [760, 500], [1000, 700], [1200, 560]] },
    { c: '#241f36', pts: [[0, 900], [180, 660], [420, 850], [700, 620], [950, 830], [1200, 680]] },
  ];
  for (const layer of layers) {
    let d = `M0,${h}`;
    for (const [x, y] of layer.pts) d += ` L${x},${y}`;
    d += ` L${w},${h}Z`;
    body += `<path d="${d}" fill="${layer.c}"/>`;
  }
  return wrap(w, h, body, defs);
});

def('scene-sunwave', 'Retro Sun', 'Scenes', 1200, 900, () => {
  const w = 1200, h = 900;
  const defs = `<linearGradient id="rsun" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#ffd23f"/><stop offset="1" stop-color="#ee4266"/></linearGradient>`;
  let body = `<rect width="${w}" height="${h}" fill="#0a0e2a"/>`;
  body += `<circle cx="600" cy="430" r="260" fill="url(#rsun)"/>`;
  for (let i = 0; i < 6; i++) {
    body += `<rect x="300" y="${380 + i * 40}" width="600" height="${12 + i * 3}" fill="#0a0e2a"/>`;
  }
  for (let x = -600; x < w + 600; x += 120) {
    body += `<line x1="600" y1="690" x2="${x}" y2="${h}" stroke="#ff2d78" stroke-width="3" opacity="0.7"/>`;
  }
  for (let y = 700; y < h; y += 45) {
    body += `<line x1="0" y1="${y}" x2="${w}" y2="${y}" stroke="#ff2d78" stroke-width="2" opacity="0.7"/>`;
  }
  return wrap(w, h, body, defs);
});

// ---- registry API --------------------------------------------------------

export function listPhotos() {
  return Object.values(generators).map(g => ({ id: g.id, name: g.name, category: g.category }));
}

export function photoURI(id) {
  if (!cache.has(id)) {
    const g = generators[id];
    if (!g) return '';
    cache.set(id, svgURI(g.build()));
  }
  return cache.get(id);
}

// Element/background src resolver: "asset:<id>" -> generated data URI,
// anything else passes through (user-uploaded data URIs).
export function resolveImageSrc(src) {
  if (typeof src === 'string' && src.startsWith('asset:')) return photoURI(src.slice(6));
  return src || '';
}

export function registerPhoto(id, name, category, build) {
  def(id, name, category, 1200, 900, build);
}
