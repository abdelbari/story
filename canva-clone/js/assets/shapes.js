// Shape library. Every shape is an SVG path (or basic primitive) normalized
// to a 100x100 viewBox and stretched non-uniformly to the element's box
// (preserveAspectRatio="none"), so one definition serves the sidebar
// preview, the DOM renderer and the canvas exporter.
//
// Shape def: { id, name, category, path } with `path` in 0..100 space.
// `rectLike: true` marks shapes whose corner radius property applies.

function star(points, outer, inner, cx = 50, cy = 50) {
  const pts = [];
  for (let i = 0; i < points * 2; i++) {
    const r = i % 2 === 0 ? outer : inner;
    const a = (Math.PI * i) / points - Math.PI / 2;
    pts.push(`${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`);
  }
  return `M${pts.join('L')}Z`;
}

function polygon(sides, r = 50, cx = 50, cy = 50, startAngle = -Math.PI / 2) {
  const pts = [];
  for (let i = 0; i < sides; i++) {
    const a = startAngle + (2 * Math.PI * i) / sides;
    pts.push(`${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`);
  }
  return `M${pts.join('L')}Z`;
}

export const SHAPES = [
  // --- Basics ---
  { id: 'rect', name: 'Square', category: 'Basic', path: 'M0,0H100V100H0Z', rectLike: true },
  { id: 'circle', name: 'Circle', category: 'Basic', path: 'M50,0A50,50 0 1,1 49.99,0Z' },
  { id: 'triangle', name: 'Triangle', category: 'Basic', path: 'M50,0L100,100H0Z' },
  { id: 'triangle-down', name: 'Triangle Down', category: 'Basic', path: 'M0,0H100L50,100Z' },
  { id: 'diamond', name: 'Diamond', category: 'Basic', path: 'M50,0L100,50L50,100L0,50Z' },
  { id: 'pentagon', name: 'Pentagon', category: 'Basic', path: polygon(5) },
  { id: 'hexagon', name: 'Hexagon', category: 'Basic', path: polygon(6, 50, 50, 50, 0) },
  { id: 'octagon', name: 'Octagon', category: 'Basic', path: polygon(8, 50, 50, 50, Math.PI / 8) },
  { id: 'semicircle', name: 'Semicircle', category: 'Basic', path: 'M0,100A50,50 0 0,1 100,100Z' },
  { id: 'quarter', name: 'Quarter Circle', category: 'Basic', path: 'M0,0A100,100 0 0,1 100,100H0Z' },
  { id: 'parallelogram', name: 'Parallelogram', category: 'Basic', path: 'M25,0H100L75,100H0Z' },
  { id: 'trapezoid', name: 'Trapezoid', category: 'Basic', path: 'M20,0H80L100,100H0Z' },

  // --- Stars & badges ---
  { id: 'star-5', name: 'Star', category: 'Stars', path: star(5, 50, 20) },
  { id: 'star-4', name: 'Sparkle', category: 'Stars', path: star(4, 50, 12) },
  { id: 'star-6', name: 'Star 6', category: 'Stars', path: star(6, 50, 26) },
  { id: 'star-8', name: 'Burst', category: 'Stars', path: star(8, 50, 32) },
  { id: 'seal', name: 'Seal', category: 'Stars', path: star(12, 50, 42) },
  { id: 'burst-16', name: 'Sunburst', category: 'Stars', path: star(16, 50, 38) },

  // --- Arrows ---
  { id: 'arrow-right', name: 'Arrow Right', category: 'Arrows', path: 'M0,30H60V10L100,50L60,90V70H0Z' },
  { id: 'arrow-left', name: 'Arrow Left', category: 'Arrows', path: 'M100,30H40V10L0,50L40,90V70H100Z' },
  { id: 'arrow-up', name: 'Arrow Up', category: 'Arrows', path: 'M30,100V40H10L50,0L90,40H70V100Z' },
  { id: 'arrow-down', name: 'Arrow Down', category: 'Arrows', path: 'M30,0V60H10L50,100L90,60H70V0Z' },
  { id: 'arrow-double', name: 'Double Arrow', category: 'Arrows', path: 'M0,50L30,15V38H70V15L100,50L70,85V62H30V85Z' },
  { id: 'chevron', name: 'Chevron', category: 'Arrows', path: 'M0,0H70L100,50L70,100H0L30,50Z' },

  // --- Callouts & banners ---
  { id: 'speech', name: 'Speech Bubble', category: 'Callouts', path: 'M10,0H90Q100,0 100,10V60Q100,70 90,70H45L20,95L25,70H10Q0,70 0,60V10Q0,0 10,0Z' },
  { id: 'thought', name: 'Round Callout', category: 'Callouts', path: 'M50,0C77,0 100,15 100,35C100,55 77,70 50,70C43,70 36,69 30,67L8,80L18,60C7,53 0,45 0,35C0,15 23,0 50,0Z' },
  { id: 'ribbon', name: 'Ribbon', category: 'Callouts', path: 'M0,20H100L85,50L100,80H0L15,50Z' },
  { id: 'banner', name: 'Banner', category: 'Callouts', path: 'M0,0H100V100L50,78L0,100Z' },
  { id: 'tag', name: 'Tag', category: 'Callouts', path: 'M0,35L60,0H100V100H60L0,65Z' },
  { id: 'plaque', name: 'Plaque', category: 'Callouts', path: 'M20,0H80Q80,20 100,20V80Q80,80 80,100H20Q20,80 0,80V20Q20,20 20,0Z' },

  // --- Symbols ---
  { id: 'heart', name: 'Heart', category: 'Symbols', path: 'M50,100C20,75 0,55 0,32C0,14 14,2 28,2C37,2 45,7 50,15C55,7 63,2 72,2C86,2 100,14 100,32C100,55 80,75 50,100Z' },
  { id: 'cross', name: 'Cross', category: 'Symbols', path: 'M35,0H65V35H100V65H65V100H35V65H0V35H35Z' },
  { id: 'lightning', name: 'Lightning', category: 'Symbols', path: 'M60,0L15,58H42L32,100L85,40H55L60,0Z' },
  { id: 'moon', name: 'Moon', category: 'Symbols', path: 'M65,0A50,50 0 1,0 100,85A42,42 0 1,1 65,0Z' },
  { id: 'drop', name: 'Drop', category: 'Symbols', path: 'M50,0C70,30 100,50 100,70A50,30 0 0,1 0,70C0,50 30,30 50,0Z' },
  { id: 'shield', name: 'Shield', category: 'Symbols', path: 'M50,0L100,15V50C100,80 78,95 50,100C22,95 0,80 0,50V15Z' },
];

export const SHAPE_MAP = Object.fromEntries(SHAPES.map(s => [s.id, s]));

export function shapeCategories() {
  const cats = [];
  for (const s of SHAPES) if (!cats.includes(s.category)) cats.push(s.category);
  return cats;
}
