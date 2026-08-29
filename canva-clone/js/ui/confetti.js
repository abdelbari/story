// Export celebration: a short confetti burst on the fixed overlay canvas.
// Skipped entirely when the user prefers reduced motion or ?nomotion=1.

const COLORS = ['#8b3dff', '#ff5ca8', '#00c4cc', '#ffb02e', '#16c79a', '#3e63dd'];

export function celebrate() {
  if (document.body.classList.contains('nomotion')) return;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  const canvas = document.getElementById('confetti-canvas');
  if (!canvas) return;
  canvas.hidden = false;
  canvas.width = window.innerWidth;
  canvas.height = window.innerHeight;
  const ctx = canvas.getContext('2d');

  const parts = [];
  for (let i = 0; i < 140; i++) {
    parts.push({
      x: canvas.width / 2 + (Math.random() - 0.5) * 120,
      y: canvas.height * 0.35,
      vx: (Math.random() - 0.5) * 16,
      vy: -6 - Math.random() * 10,
      size: 5 + Math.random() * 7,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      rot: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.3,
    });
  }

  const started = performance.now();
  function frame(now) {
    const t = now - started;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (const p of parts) {
      p.x += p.vx;
      p.y += p.vy;
      p.vy += 0.35;
      p.vx *= 0.99;
      p.rot += p.vr;
      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rot);
      ctx.fillStyle = p.color;
      ctx.globalAlpha = Math.max(0, 1 - t / 1800);
      ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size * 0.6);
      ctx.restore();
    }
    if (t < 1900) requestAnimationFrame(frame);
    else {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      canvas.hidden = true;
    }
  }
  requestAnimationFrame(frame);
}
