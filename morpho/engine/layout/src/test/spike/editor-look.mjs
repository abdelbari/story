// Photographs of the editor's page, for a person to look at.
//
// `EditorLookTest` writes the pages under layout/build/editor-look/;
// this opens each in headless Chromium at a phone's size and a tablet's,
// in light and in dark, and saves the photographs beside this script under
// look/, which is not committed. Run from this directory:
//   node editor-look.mjs
import { chromium } from 'playwright';
import { readdirSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
const pages = resolve('../../../build/editor-look');
mkdirSync('look', { recursive: true });
const b = await chromium.launch();
const shots = [
  { name: 'phone', viewport: { width: 390, height: 844 }, scale: 3 },
  { name: 'tablet', viewport: { width: 1024, height: 1366 }, scale: 2 },
];
for (const file of readdirSync(pages).filter(f => f.endsWith('.html'))) {
  for (const shot of shots) for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: shot.viewport, deviceScaleFactor: shot.scale, colorScheme: scheme, isMobile: shot.name === 'phone', hasTouch: shot.name === 'phone' });
    const p = await ctx.newPage();
    await p.goto('file://' + resolve(pages, file));
    await p.waitForTimeout(150);
    const out = `look/${file.replace('.html', '')}-${shot.name}-${scheme}.png`;
    await p.screenshot({ path: out, fullPage: false });
    console.log('wrote', out);
    if (scheme === 'light') {
      // With words selected and a picture picked, as the reader sees them.
      await p.evaluate(() => {
        const blocks = document.querySelectorAll('[data-block]');
        const long = Array.from(blocks).find(b => b.tagName === 'P' && b.textContent.length > 60);
        const w = document.createTreeWalker(long, NodeFilter.SHOW_TEXT);
        const first = w.nextNode();
        const second = w.nextNode() || first;
        getSelection().setBaseAndExtent(first, 3, second, Math.min(6, second.data.length));
      });
      await p.click('p.image img');
      await p.waitForTimeout(100);
      const picked = out.replace('.png', '-selected.png');
      await p.screenshot({ path: picked, fullPage: false });
      console.log('wrote', picked);
    }
    await ctx.close();
  }
}
await b.close();
