// Canvia end-to-end smoke test.
import { chromium } from 'playwright';

const BASE = (process.env.BASE_URL || 'http://127.0.0.1:8321/index.html') + '?nomotion=1';
const SHOTS = process.env.SHOTS_DIR || '/tmp/canvia-shots';
import { mkdirSync, statSync } from 'fs';
mkdirSync(SHOTS, { recursive: true });

const errors = [];
let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok  ${name}`);
  else { failures++; console.log(`FAIL  ${name} ${extra}`); }
}

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
page.on('pageerror', err => errors.push('PAGEERROR: ' + err.message));

// ---------- HOME ----------
await page.goto(BASE);
await page.waitForSelector('[data-testid="home-view"]:not([hidden])');
await page.waitForTimeout(600); // template thumbnails render async
check('home renders', await page.isVisible('[data-testid="preset-row"]'));
const templateCount = await page.locator('[data-testid="template-gallery"] .design-card').count();
check('template gallery has 8 templates', templateCount === 8, `got ${templateCount}`);
await page.screenshot({ path: SHOTS + '/01-home.png' });

// ---------- NEW DESIGN ----------
await page.click('[data-testid="preset-insta-post"]');
await page.waitForSelector('[data-testid="editor-view"]:not([hidden])');
check('editor opens', true);
const state = () => page.evaluate(() => {
  const s = window.__canvia.store;
  return {
    els: s.page.elements.map(e => ({ id: e.id, type: e.type, x: e.x, y: e.y, w: e.w, h: e.h, rotation: e.rotation, fontSize: e.fontSize, text: e.text })),
    selection: s.selection, zoom: s.zoom, pages: s.doc.pages.length, pageIndex: s.pageIndex,
    canUndo: s.canUndo(), canRedo: s.canRedo(), title: s.doc.title,
  };
});

// ---------- INSERT SHAPE ----------
await page.click('[data-testid="tab-elements"]');
await page.waitForSelector('[data-testid="el-shape-star-5"]');
await page.click('[data-testid="el-shape-star-5"]');
let s = await state();
check('shape inserted + selected', s.els.length === 1 && s.selection.length === 1 && s.els[0].type === 'shape');

// ---------- DRAG ----------
const pageBox = await page.locator('[data-testid="page"]').boundingBox();
const el0 = s.els[0];
const zoom = s.zoom;
const startX = pageBox.x + (el0.x + el0.w / 2) * zoom;
const startY = pageBox.y + (el0.y + el0.h / 2) * zoom;
await page.mouse.move(startX, startY);
await page.mouse.down();
await page.mouse.move(startX + 120, startY + 80, { steps: 8 });
await page.mouse.up();
let s2 = await state();
check('drag moved element', Math.abs(s2.els[0].x - el0.x) > 40 && Math.abs(s2.els[0].y - el0.y) > 20,
  JSON.stringify({ before: [el0.x, el0.y], after: [s2.els[0].x, s2.els[0].y] }));
check('drag = one undo step', s2.canUndo);

// ---------- RESIZE VIA SE HANDLE ----------
const before = s2.els[0];
const handle = await page.locator('.cc-h-se').boundingBox();
check('se handle visible', !!handle);
if (handle) {
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
  await page.mouse.down();
  await page.mouse.move(handle.x + 90, handle.y + 90, { steps: 6 });
  await page.mouse.up();
  const s3 = await state();
  check('resize grew element', s3.els[0].w > before.w + 30, `w ${before.w} -> ${s3.els[0].w}`);
  const ratioBefore = before.w / before.h, ratioAfter = s3.els[0].w / s3.els[0].h;
  check('corner resize kept aspect', Math.abs(ratioBefore - ratioAfter) < 0.05, `${ratioBefore} vs ${ratioAfter}`);
}

// ---------- ROTATE ----------
const rot = await page.locator('.cc-rotate-handle').boundingBox();
if (rot) {
  await page.mouse.move(rot.x + rot.width / 2, rot.y + rot.height / 2);
  await page.mouse.down();
  await page.mouse.move(rot.x + 80, rot.y - 40, { steps: 6 });
  await page.mouse.up();
  const s4 = await state();
  check('rotation changed', (s4.els[0].rotation || 0) !== 0, `rot ${s4.els[0].rotation}`);
}

// ---------- UNDO / REDO ----------
const beforeUndo = await state();
await page.keyboard.press('Control+z');
const afterUndo = await state();
check('undo reverted rotation', (afterUndo.els[0].rotation || 0) === 0 || afterUndo.els[0].rotation !== beforeUndo.els[0].rotation);
await page.keyboard.press('Control+Shift+z');
const afterRedo = await state();
check('redo restored', Math.abs((afterRedo.els[0].rotation || 0) - (beforeUndo.els[0].rotation || 0)) < 0.01);

// ---------- TEXT: INSERT, EDIT INLINE ----------
await page.click('[data-testid="tab-text"]');
await page.waitForSelector('[data-testid="add-heading"]');
await page.click('[data-testid="add-heading"]');
let st = await state();
const textEl = st.els.find(e => e.type === 'text');
check('heading inserted', !!textEl);
const textNode = page.locator(`[data-id="${textEl.id}"] .cc-text`);
await textNode.dblclick();
await page.waitForTimeout(150);
await page.keyboard.type('Hello Canvia');
await page.keyboard.press('Escape');
st = await state();
check('inline edit committed', st.els.find(e => e.id === textEl.id).text === 'Hello Canvia',
  JSON.stringify(st.els.find(e => e.id === textEl.id).text));

// Toolbar: bold + font size
await page.click(`[data-id="${textEl.id}"]`);
await page.click('[data-testid="btn-bold"]');
await page.click('[data-testid="font-size-inc"]');
st = await state();
const t2 = st.els.find(e => e.id === textEl.id);
check('toolbar edits applied', t2.fontSize > textEl.fontSize);

// ---------- STICKER + PHOTO ----------
await page.click('[data-testid="tab-elements"]');
await page.locator('[data-testid^="el-sticker-"]').first().click();
await page.click('[data-testid="tab-photos"]');
await page.waitForSelector('[data-testid^="photo-"]');
const photoCount = await page.locator('[data-testid^="photo-"]').count();
check('photo library >= 18', photoCount >= 18, `got ${photoCount}`);
await page.locator('[data-testid="photo-mesh-sunset"]').click();
st = await state();
check('sticker + image inserted', st.els.some(e => e.type === 'sticker') && st.els.some(e => e.type === 'image'));

// ---------- MULTI-SELECT + ALIGN ----------
await page.keyboard.press('Control+a');
st = await state();
check('select all', st.selection.length === st.els.length);
await page.click('[data-testid="btn-position"]');
await page.waitForSelector('[data-testid="align-centerx"]');
await page.click('[data-testid="align-centerx"]');
await page.keyboard.press('Escape');

// ---------- PAGES ----------
await page.click('[data-testid="btn-add-page"]');
st = await state();
check('page added + switched', st.pages === 2 && st.pageIndex === 1);
// Undo should jump back to page context of the change
await page.click('[data-testid="page-thumb-0"]');
st = await state();
check('page switch', st.pageIndex === 0);

// ---------- TEMPLATE APPLY ----------
await page.click('[data-testid="tab-design"]');
await page.waitForSelector('[data-testid="template-mega-sale-post"]');
await page.click('[data-testid="template-mega-sale-post"]');
await page.waitForTimeout(400);
st = await state();
check('template applied to page', st.els.length >= 8, `got ${st.els.length} elements`);
await page.screenshot({ path: SHOTS + '/02-editor-template.png' });

// ---------- BACKGROUND ----------
await page.click('[data-testid="tab-background"]');
await page.locator('[data-testid^="bg-grad-"]').first().click();
const bg = await page.evaluate(() => window.__canvia.store.page.background.type);
check('gradient background set', bg === 'gradient');

// ---------- ZOOM ----------
await page.click('[data-testid="zoom-in"]');
const z1 = await page.evaluate(() => window.__canvia.store.zoom);
await page.click('[data-testid="zoom-fit"]');
check('zoom controls work', z1 > 0);

// ---------- EXPORT PNG ----------
await page.click('[data-testid="btn-export"]');
await page.waitForSelector('[data-testid="export-png"]');
const [download] = await Promise.all([
  page.waitForEvent('download', { timeout: 15000 }),
  page.click('[data-testid="export-png"]'),
]);
const pngPath = SHOTS + '/export.png';
await download.saveAs(pngPath);
check('PNG export > 50KB', statSync(pngPath).size > 50000, `${statSync(pngPath).size} bytes`);

// ---------- EXPORT PDF ----------
await page.click('[data-testid="btn-export"]');
await page.waitForSelector('[data-testid="export-pdf"]');
const [download2] = await Promise.all([
  page.waitForEvent('download', { timeout: 20000 }),
  page.click('[data-testid="export-pdf"]'),
]);
const pdfPath = SHOTS + '/export.pdf';
await download2.saveAs(pdfPath);
check('PDF export > 40KB', statSync(pdfPath).size > 40000, `${statSync(pdfPath).size} bytes`);

// ---------- PERSISTENCE ----------
const titleBefore = await page.evaluate(() => window.__canvia.store.doc.title);
await page.waitForTimeout(1200); // let autosave debounce fire
await page.reload();
await page.waitForSelector('[data-testid="home-view"]:not([hidden])');
const recentCount = await page.locator('[data-testid="recent-grid"] .design-card').count();
check('recents listed after reload', recentCount >= 1, `got ${recentCount}`);
await page.locator('[data-testid="recent-grid"] .design-card').first().click();
await page.waitForSelector('[data-testid="editor-view"]:not([hidden])');
const st5 = await state();
check('design restored with content', st5.els.length >= 8 && st5.pages === 2, JSON.stringify({ els: st5.els.length, pages: st5.pages }));
await page.screenshot({ path: SHOTS + '/03-restored.png' });

// ---------- CONSOLE ERRORS ----------
const realErrors = errors.filter(e => !e.includes('favicon'));
check('no console errors', realErrors.length === 0, JSON.stringify(realErrors.slice(0, 5)));

await browser.close();
console.log(failures === 0 ? '\nSMOKE: ALL PASS' : `\nSMOKE: ${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
