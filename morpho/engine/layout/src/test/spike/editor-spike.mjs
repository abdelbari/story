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
// Timing: two hundred keystrokes, one round trip each.
const started = Date.now();
await p.keyboard.type('abcdefghij'.repeat(20)); await settled();
const ms = (Date.now() - started) / 200;
console.log(`time per keystroke, round trip through HTTP: ${ms.toFixed(1)} ms`);
await check('after two hundred keystrokes');
await b.close();
console.log('SPIKE OK');
