import { chromium } from 'playwright';
import assert from 'node:assert/strict';
const port = process.argv[2];
const base = `http://127.0.0.1:${port}`;
const b = await chromium.launch();
const p = await b.newPage();
p.on('pageerror', e => { console.log('PAGE ERROR', e.message); });
p.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', m.text()); });
await p.exposeBinding('__morphoSend', async (_, json) => (await fetch(base + '/step', { method: 'POST', body: json })).text());
await p.goto(base + '/page');
// A selection change is dispatched a task after the action that made it; let that task run before waiting on the queue.
const settled = () => p.evaluate(() => new Promise(r => setTimeout(r, 30)).then(() => window.morphoEditor.settled()));
const truth = async () => (await fetch(base + '/truth')).json();
async function check(label) {
  await settled();
  const t = await truth();
  const d = await p.evaluate(() => window.morphoEditor.texts());
  assert.deepEqual(d, t.texts, label + ': page text vs document');
  const c = await p.evaluate(() => window.morphoEditor.caret());
  assert.deepEqual(c, t.selection, label + ': page caret vs document selection');
  console.log('ok  ', label.padEnd(44), JSON.stringify(t.texts));
  return t;
}
const select = (a, f) => p.evaluate(([a, f]) => window.morphoEditor.select(a, f), [a, f]);

await check('opened');
await select([0, 23]); await check('caret at the end of block 0');
await p.keyboard.type(' Now'); await check('typed at the end');
await p.keyboard.press('Enter'); await check('Return at the end');
await p.keyboard.type('Second'); await check('typed into the new paragraph');
await select([2, 24]); await p.keyboard.type(' مرحبا'); await check('typed Arabic into the RTL paragraph');
await select([2, 0]); await p.keyboard.press('Backspace'); await check('Backspace at the head joins upward');
await select([0, 4], [1, 3]); await p.keyboard.press('Control+b'); let t = await check('bold across two paragraphs');
assert.equal(t.runs[0][1][1], true, 'the second run of block 0 is bold');
await p.keyboard.press('Control+z'); await check('undo');
await p.keyboard.press('Control+y'); await check('redo');
// A click on the table lands the caret in the next paragraph.
await p.click('table'); await check('clicked the table');
// A caret set by the browser itself, then typing.
await p.evaluate(() => { const el = document.querySelectorAll('[data-block]')[3]; const s = getSelection(); s.collapse(el.firstChild, 4); });
await p.keyboard.type('!'); await check('typed after the browser placed the caret');
await p.keyboard.press('Enter'); await p.keyboard.press('Enter'); await check('two Returns');
await p.keyboard.press('Backspace'); await check('Backspace on an empty paragraph');
await select([3, 4], [3, 9]); await p.keyboard.type('X'); await check('typed over a selection');
await p.evaluate(() => window.morphoEditor.insertTable(2, 2)); await check('a table put in');
// Into the new table's cells: by a click, then by typing, Return, rows and columns.
await p.click('table[data-block="4"] td'); await check('clicked into a cell');
await p.keyboard.type('cell A'); await check('typed in a cell');
await p.keyboard.press('Enter'); await p.keyboard.type('more'); await check('Return in a cell');
await p.evaluate(() => window.morphoEditor.insertRow(true)); await check('a row put in below');
await select([4, 0, 2, 1, 0]); await p.keyboard.type('B'); await check('typed in the last cell');
await p.evaluate(() => window.morphoEditor.insertColumn(true)); await check('a column put in after');
await p.evaluate(() => window.morphoEditor.deleteColumn()); await check('the column taken out again');
await p.keyboard.press('Control+z'); await check('undo in a table');
await select([4, 0, 0, 0, 0], [5, 2]); await p.keyboard.type('Z'); await check('a selection out of a cell stands where it began');
await p.evaluate(() => window.morphoEditor.restyle({ listMarker: 'BULLET' })); await check('made a list item (whole body)');
await p.keyboard.press('Enter'); await p.keyboard.type('item two'); await check('Return in a list continues it');
// A search answers with the places; a replacement everywhere repaints them.
{
  const found = await p.evaluate(() => window.morphoEditor.find('Now'));
  assert.equal(found.length, 1, 'one Now to find'); await check('searched');
  await p.evaluate(() => window.morphoEditor.replaceAll('Now', 'Then')); const t2 = await check('replaced everywhere');
  assert.ok(t2.texts[0].endsWith('Then'), 'replaced in the first paragraph');
}
// Cells selected together are whole cells: Backspace empties them, typing fills the first,
// Tab moves on with the next cell selected whole, and two cells become one and two again.
{
  const before = await truth();
  const tb = before.texts.findIndex(t => Array.isArray(t) && t.length >= 2 && t[0].length >= 2);
  assert.ok(tb >= 0, 'a table of two rows and two columns to work in');
  const rows = before.texts[tb].length;
  await select([tb, 0, 0, 0, 0], [tb, 0, 1, 1, 0]); const s1 = await check('a selection across cells');
  assert.deepEqual(s1.selection, [[tb, 0, 0, 0, 0], [tb, 0, 1, 1, 0]], 'the selection stands as it was made');
  await p.keyboard.press('Backspace'); const s2 = await check('Backspace empties the cells');
  assert.deepEqual(s2.texts[tb].slice(0, 2).map(r => r.slice(0, 2)), [[[''], ['']], [[''], ['']]]);
  await p.keyboard.type('one'); await check('typed into the first of them');
  await p.keyboard.press('Tab'); const s3 = await check('Tab selects the next cell whole');
  assert.deepEqual(s3.selection, [[tb, 0, 0, 1, 0], [tb, 0, 0, 1, 0]], 'an empty cell, so the selection is a caret');
  await p.keyboard.type('two'); await p.keyboard.press('Shift+Tab'); const s4 = await check('Shift+Tab back, the cell selected whole');
  assert.deepEqual(s4.selection, [[tb, 0, 0, 0, 0], [tb, 3, 0, 0, 0]]);
  await p.keyboard.type('uno'); await check('typed over the cell');
  await select([tb, 0, 0, 0, 0], [tb, 0, 0, 1, 0]); await p.evaluate(() => window.morphoEditor.mergeCells()); const s5 = await check('two cells merged');
  assert.equal(s5.texts[tb][0].length, s1.texts[tb][0].length - 1, 'one cell fewer in the row');
  assert.deepEqual(s5.texts[tb][0][0], ['uno', 'two'], 'holding both their paragraphs');
  await p.keyboard.type('!'); await check('typed in the merged cell');
  await p.evaluate(() => window.morphoEditor.splitCell()); const s6 = await check('split again');
  assert.equal(s6.texts[tb][0].length, s1.texts[tb][0].length);
  const lastRow = s6.texts[tb].length - 1, lastCell = s6.texts[tb][lastRow].length - 1;
  await select([tb, 0, lastRow, lastCell, 0]); await p.keyboard.press('Tab'); const s7 = await check('Tab from the last cell adds a row');
  assert.equal(s7.texts[tb].length, rows + 1);
}
// A tab typed into a paragraph set to tab stops is a character the page counts.
{
  const tabbed = (await truth()).texts.findIndex(t => typeof t === 'string' && t.startsWith('Name:'));
  assert.ok(tabbed >= 0, 'the tab-stopped paragraph');
  await select([tabbed, 5]); await p.keyboard.press('Tab'); const s = await check('a tab typed at a stop');
  assert.equal(s.texts[tabbed], 'Name:\t\tvalue');
  await p.keyboard.type('x'); const s2 = await check('typed after the tab');
  assert.equal(s2.texts[tabbed], 'Name:\tx\tvalue');
  await select([tabbed, 1]); await p.keyboard.type('a'); await check('typed before the tabs');
}
// Bold over a half-bold selection makes all of it bold, and over an all-bold one none; a paste is a paragraph a line; a cut cuts.
{
  const t0 = await truth();
  const first = t0.texts.findIndex(t => typeof t === 'string' && t.startsWith('The form'));
  // "The " is plain and what follows is bold, so the first six characters are half bold.
  await select([first, 0], [first, 6]); await settled();
  assert.equal(await p.evaluate(() => window.morphoEditor.look().bold), false, 'half bold is not bold');
  await p.keyboard.press('Control+b'); const b1 = await check('bold over a half-bold selection');
  const inside = (runs, from, to) => { let at = 0; return runs.filter(r => { const s = at; at += r[0].length; return at > from && s < to; }); };
  assert.ok(inside(b1.runs[first], 0, 6).every(r => r[1]), 'all of it bold now');
  assert.equal(await p.evaluate(() => window.morphoEditor.look().bold), true);
  await p.keyboard.press('Control+b'); const b2 = await check('and again over an all-bold one');
  assert.ok(inside(b2.runs[first], 0, 6).every(r => !r[1]), 'none of it bold now');
  await select([first, 4]);
  await p.evaluate(() => {
    const dt = new DataTransfer(); dt.setData('text/plain', 'line one\nline two');
    document.getElementById('doc').dispatchEvent(new InputEvent('beforeinput', { inputType: 'insertFromPaste', dataTransfer: dt, bubbles: true, cancelable: true }));
  });
  const pasted = await check('pasted two lines');
  assert.equal(pasted.texts[first], 'The line one', 'the first line joins the paragraph');
  assert.equal(pasted.texts[first + 1].startsWith('line two'), true, 'the second is a paragraph of its own');
  await p.keyboard.press('Control+z'); const undone = await check('a paste undone is one step');
  assert.equal(undone.texts[first], t0.texts[first]);
  await select([first, 0], [first, 4]); await p.keyboard.press('Control+x'); const cut = await check('cut');
  assert.equal(cut.texts[first], t0.texts[first].slice(4), 'what was cut is gone');
}
// Tab at the head of an item of a list moves it a level in, and Shift+Tab out.
{
  const one = (await truth()).texts.findIndex(t => t === ' one');
  assert.ok(one >= 0, 'the paragraph to make an item of');
  await select([one, 0]); await p.evaluate(() => window.morphoEditor.restyle({ listMarker: 'NUMBERED' })); await check('made a numbered item');
  await p.keyboard.press('Tab'); const s1 = await check('an item moved a level in');
  assert.equal(s1.texts[one], ' one', 'no tab typed into it');
  assert.equal(await p.evaluate(() => window.morphoEditor.paragraph().listLevel), 1);
  await p.keyboard.press('Shift+Tab'); await check('and out again');
  assert.equal(await p.evaluate(() => window.morphoEditor.paragraph().listLevel), 0);
}
// The doubt the reading left on a block is a band in the margin, green once the block is changed,
// and the filter jumps between the doubtful blocks.
{
  const t0 = await truth();
  const arabic = t0.texts.findIndex(t => typeof t === 'string' && t.includes('الاستمارة'));
  assert.deepEqual(await p.evaluate(() => window.morphoEditor.doubtful()), [arabic], 'one block to doubt');
  const band = await p.evaluate(i => document.querySelectorAll('[data-block]')[i].getAttribute('data-band'), arabic);
  assert.equal(band, 'medium');
  const changedBefore = await p.evaluate(i => document.querySelectorAll('[data-block]')[i].classList.contains('changed'), arabic);
  assert.equal(changedBefore, true, 'changed already, having been typed into earlier');
  await select([0, 0]);
  const jumped = await p.evaluate(() => window.morphoEditor.nextDoubtful()); await check('jumped to the next doubtful block');
  assert.equal(jumped, arabic);
  assert.deepEqual((await truth()).selection, [[arabic, 0], [arabic, 0]]);
}
// A picture tapped is told to the app; described and sized over the bridge; a link typed at the caret; the count.
{
  await p.evaluate(() => { window.Morpho = { tapped: j => { window.__tapped = JSON.parse(j); } }; });
  await p.click('p.image img'); await settled();
  const tapped = await p.evaluate(() => window.__tapped);
  const image = (await truth()).texts.length - 1;
  assert.deepEqual(tapped, { kind: 'image', block: image, alt: '' }, 'the tap names the block');
  await p.evaluate(i => window.morphoEditor.describeImage(i, 'the seal'), image); await check('a picture described');
  assert.equal(await p.evaluate(() => document.querySelector('p.image img').getAttribute('alt')), 'the seal');
  await p.evaluate(i => window.morphoEditor.resizeImage(i, 80, null), image); await check('a picture sized');
  assert.equal(await p.evaluate(() => document.querySelector('p.image img').style.width), '80pt');
  await p.evaluate(() => { delete window.Morpho; });
  const first = (await truth()).texts.findIndex(t => typeof t === 'string');
  await select([first, 0]); await p.evaluate(() => window.morphoEditor.link('https://x', 'Link ')); const linked = await check('a link typed at the caret');
  assert.deepEqual(linked.selection, [[first, 5], [first, 5]]);
  assert.equal(await p.evaluate(i => document.querySelectorAll('[data-block]')[i].querySelector('a').getAttribute('href'), first), 'https://x');
  const count = await p.evaluate(() => window.morphoEditor.count());
  assert.ok(count.words > 10 && count.paragraphs > 5, 'counted: ' + JSON.stringify(count));
}
// A table's cells are filled, its rules taken off, its head set, and a column made a width.
{
  const tb = (await truth()).texts.findIndex(t => Array.isArray(t) && t.length >= 2);
  await select([tb, 0, 0, 0, 0], [tb, 0, 0, 1, 0]); await p.evaluate(() => window.morphoEditor.shadeCells(0xFFEE88)); await check('cells filled');
  const fill = await p.evaluate(i => document.querySelectorAll('[data-block]')[i].rows[0].cells[0].style.backgroundColor, tb);
  assert.equal(fill, 'rgb(255, 238, 136)');
  await p.evaluate(() => window.morphoEditor.ruleTable(false)); await check('rules taken off');
  assert.equal(await p.evaluate(i => document.querySelectorAll('[data-block]')[i].rows[1].cells[0].style.border, tb), '0px');
  await p.evaluate(() => window.morphoEditor.headRow(true)); await check('a head row');
  assert.ok(await p.evaluate(i => !!document.querySelectorAll('[data-block]')[i].tHead, tb), 'the head is a thead');
  await p.evaluate(() => window.morphoEditor.setColumnWidth(150)); await check('a column made a width');
  assert.equal(await p.evaluate(i => document.querySelectorAll('[data-block]')[i].querySelector('col').style.width, tb), '150pt');
}
// Timing: two hundred keystrokes, one round trip each.
const started = Date.now();
await p.keyboard.type('abcdefghij'.repeat(20)); await settled();
const ms = (Date.now() - started) / 200;
console.log(`time per keystroke, round trip through HTTP: ${ms.toFixed(1)} ms`);
await check('after two hundred keystrokes');
await b.close();
console.log('SPIKE OK');
