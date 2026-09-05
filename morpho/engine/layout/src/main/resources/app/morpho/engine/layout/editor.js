// The editor's script: a translator between the page and the engine.
//
// It holds no document. What the reader does to the page becomes an
// operation sent over the bridge; what comes back is a splice of blocks
// to paint and where to put the caret, and that is painted. The DOM is a
// picture of the document the engine holds, repainted a block at a time,
// and nothing in it is ever read back as the document.
(function () {
  'use strict';
  var doc = document.getElementById('doc');
  var painting = false;
  var composing = false;
  var lastSelection = '';
  var look = {};
  var paragraph = {};
  var status = {};
  var queue = Promise.resolve();

  // The bridge. On the phone it is an object the app gives the page,
  // whose send is a plain call that returns the reply. In a browser
  // driving the page for a test it is a function that returns a promise.
  function send(json) {
    if (window.Morpho && typeof window.Morpho.send === 'function') return Promise.resolve(window.Morpho.send(json));
    if (typeof window.__morphoSend === 'function') return window.__morphoSend(json);
    return Promise.resolve('{"error":"no bridge"}');
  }

  function blocks() { return Array.prototype.slice.call(doc.querySelectorAll('[data-block]')); }

  function blockOf(node) {
    var e = node && node.nodeType === 1 ? node : node && node.parentNode;
    return e && e.closest ? e.closest('[data-block]') : null;
  }

  // How much text a node holds, counted the way the engine counts it: a
  // text node its length, a line break one, a picture nothing.
  function lengthOf(node) {
    var n = 0;
    var w = document.createTreeWalker(node, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
    var c;
    while ((c = w.nextNode())) {
      if (c.nodeType === 3) n += c.data.length;
      else if (c.tagName === 'BR') n += 1;
    }
    return n;
  }

  // The block elements of a cell in the order the engine holds them: a
  // paragraph, a heading, an item of a list, a table inside the cell —
  // and not what stands inside that table, which is that table's.
  function blockElementsOf(td) {
    var out = [];
    var w = document.createTreeWalker(td, NodeFilter.SHOW_ELEMENT, null);
    var c;
    while ((c = w.nextNode())) {
      if (c.closest('td,th') !== td) continue;
      var t = c.tagName;
      if (t === 'P' || t === 'H1' || t === 'H2' || t === 'H3' || t === 'LI' || t === 'TABLE') out.push(c);
    }
    return out;
  }

  // The cell of the block `table` that holds `node`, climbing out of any
  // table inside it, or null.
  function cellOf(table, node) {
    var e = node.nodeType === 1 ? node : node.parentNode;
    var td = e && e.closest ? e.closest('td,th') : null;
    while (td && td.closest('table') !== table) td = td.parentNode ? td.parentNode.closest('td,th') : null;
    return td;
  }

  // The caret a point in the page is: [block, offset], or inside a cell
  // [block, offset, row, column, paragraph]. Null outside any block.
  function caretOf(node, offset) {
    var el = blockOf(node);
    if (!el) return null;
    var block = Number(el.getAttribute('data-block'));
    if (el.tagName === 'TABLE') {
      var td = cellOf(el, node);
      if (!td) return [block, 0];
      var row = td.parentNode.rowIndex, column = td.cellIndex;
      var held = blockElementsOf(td);
      for (var i = 0; i < held.length; i++) {
        if (held[i] === node || held[i].contains(node)) {
          var rr = document.createRange();
          rr.setStart(held[i], 0);
          rr.setEnd(node, offset);
          return [block, lengthOf(rr.cloneContents()), row, column, i];
        }
      }
      return [block, 0, row, column, 0];
    }
    var r = document.createRange();
    r.setStart(el, 0);
    r.setEnd(node, offset);
    return [block, lengthOf(r.cloneContents())];
  }

  // The element a caret's text is counted in: the block, or a cell's paragraph.
  function containerOf(caret) {
    var el = blocks()[caret[0]];
    if (!el) return null;
    if (caret.length < 5) return el;
    var tr = el.rows[caret[2]];
    var td = tr ? tr.cells[caret[3]] : null;
    if (!td) return null;
    return blockElementsOf(td)[caret[4]] || td;
  }

  // The point in block element `el` that is `offset` characters in.
  function pointAt(el, offset) {
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
    var c, seen = 0, lastText = null;
    while ((c = w.nextNode())) {
      if (c.nodeType === 3) {
        // A tab kept out of sight for the count's sake is no place to
        // leave a caret; the text after it is.
        var hidden = c.parentNode && c.parentNode.hasAttribute && c.parentNode.hasAttribute('data-tab');
        if (seen + c.data.length > offset || (seen + c.data.length === offset && !hidden)) return [c, offset - seen];
        seen += c.data.length;
        lastText = c;
      } else if (c.tagName === 'BR') {
        var at = Array.prototype.indexOf.call(c.parentNode.childNodes, c);
        if (seen >= offset) return [c.parentNode, at];
        seen += 1;
        if (seen === offset) return [c.parentNode, at + 1];
      }
    }
    if (lastText) return [lastText, lastText.data.length];
    return [el, el.childNodes.length];
  }

  function selectionKey(sel) { return sel.anchor.join(',') + ';' + sel.focus.join(','); }

  function currentSelection() {
    var s = window.getSelection();
    if (!s || s.rangeCount === 0) return null;
    var a = caretOf(s.anchorNode, s.anchorOffset);
    var f = caretOf(s.focusNode, s.focusOffset);
    if (!a || !f) return null;
    return { anchor: a, focus: f };
  }

  // A list item's outermost list, which is what stands in the body for it.
  function outermost(e) {
    var x = e;
    while (x.parentNode && x.parentNode !== doc &&
      (x.parentNode.tagName === 'UL' || x.parentNode.tagName === 'OL' || x.parentNode.tagName === 'LI')) x = x.parentNode;
    return x;
  }

  // The splice applied to the page: the blocks from `from` up to `to`
  // taken out, the new ones put in their place, and every block numbered
  // again from the top, since the ones after a splice have moved.
  function splice(s) {
    var all = blocks();
    var removed = all.slice(s.from, s.to);
    var parent, next;
    if (removed.length) {
      parent = removed[0].parentNode;
      next = removed[removed.length - 1].nextSibling;
      removed.forEach(function (r) { r.parentNode.removeChild(r); });
    } else if (all[s.to]) {
      var e = all[s.to];
      if (e.tagName === 'LI') e = outermost(e);
      parent = e.parentNode;
      next = e;
    } else if (all[s.from - 1]) {
      var b = all[s.from - 1];
      if (b.tagName === 'LI') b = outermost(b);
      parent = b.parentNode;
      next = b.nextSibling;
    } else {
      parent = doc;
      next = null;
    }
    var t = document.createElement('template');
    t.innerHTML = s.blocks.join('');
    parent.insertBefore(t.content, next);
  }

  function renumber() {
    blocks().forEach(function (e, i) { e.setAttribute('data-block', String(i)); });
  }

  // The blocks the reader has changed, marked so, which the band in the
  // margin beside a doubtful block shows as looked at.
  function markChanged(changed) {
    var set = {};
    (changed || []).forEach(function (i) { set[i] = true; });
    blocks().forEach(function (e, i) { e.classList.toggle('changed', !!set[i]); });
  }

  function placeCaret(sel) {
    var a = containerOf(sel.anchor), f = containerOf(sel.focus);
    if (!a || !f) return;
    var ap = pointAt(a, sel.anchor[1]);
    var fp = pointAt(f, sel.focus[1]);
    var s = window.getSelection();
    s.setBaseAndExtent(ap[0], ap[1], fp[0], fp[1]);
    lastSelection = selectionKey(sel);
  }

  function tell(reply) {
    look = reply.look || {};
    paragraph = reply.paragraph || {};
    status = reply;
    if (window.Morpho && typeof window.Morpho.status === 'function') {
      window.Morpho.status(JSON.stringify({
        look: reply.look, paragraph: reply.paragraph, canUndo: reply.canUndo, canRedo: reply.canRedo, modified: reply.modified,
        cells: reply.cells, canMerge: reply.canMerge, canSplit: reply.canSplit, table: reply.table, comments: reply.comments,
      }));
    }
  }

  function paint(reply, wasSelect) {
    if (!reply || reply.error) return;
    painting = true;
    try {
      if (reply.all) doc.innerHTML = reply.body;
      else if (reply.splice) splice(reply.splice);
      renumber();
      markChanged(reply.changed);
      // A selection the reader made is left where they made it unless the
      // engine moved it — onto a paragraph from a table, say.
      if (!wasSelect || selectionKey(reply.selection) !== lastSelection) placeCaret(reply.selection);
      tell(reply);
    } finally {
      painting = false;
    }
  }

  // An operation sent, its reply painted, and the reply handed back to
  // whoever asked — for a search, the places found.
  function op(o) {
    var json = JSON.stringify(o);
    queue = queue.then(function () { return send(json); }).then(function (r) {
      var reply;
      try { reply = JSON.parse(r); } catch (e) { return null; }
      paint(reply, o.op === 'select' || o.op === 'find' || o.op === 'doubtful' || o.op === 'count');
      return reply;
    });
    return queue;
  }

  // The reader moved the caret. Told to the engine only when it is not
  // where the engine already has it, and never while it is being placed.
  document.addEventListener('selectionchange', function () {
    if (painting || composing) return;
    var sel = currentSelection();
    if (!sel) return;
    var key = selectionKey(sel);
    if (key === lastSelection) return;
    lastSelection = key;
    op({ op: 'select', anchor: sel.anchor, focus: sel.focus });
  });

  // Every edit the browser would make is stopped and sent instead. What is
  // not understood is stopped too, so the page can never drift from the
  // document: a page that drifts is a page that lies.
  doc.addEventListener('beforeinput', function (ev) {
    if (composing) return;
    ev.preventDefault();
    var pasted = ev.dataTransfer ? ev.dataTransfer.getData('text/plain') : '';
    var rich = ev.dataTransfer ? ev.dataTransfer.getData('text/html') : '';
    switch (ev.inputType) {
      case 'insertText': op({ op: 'type', text: ev.data || '' }); break;
      case 'insertParagraph': op({ op: 'split' }); break;
      case 'insertLineBreak': op({ op: 'type', text: '\n' }); break;
      case 'deleteContentBackward': case 'deleteWordBackward': case 'deleteSoftLineBackward': case 'deleteHardLineBackward':
        op({ op: 'erase' }); break;
      case 'deleteContentForward': case 'deleteWordForward': case 'deleteSoftLineForward': case 'deleteHardLineForward':
        op({ op: 'eraseForward' }); break;
      // A cut is a copy the browser has already made and a deletion it
      // has not; a drag out of the page the same.
      case 'deleteByCut': case 'deleteByDrag': op({ op: 'erase' }); break;
      // What the clipboard carries beside the text goes too, for the
      // engine to read as paragraphs, headings, tables, pictures.
      case 'insertFromPaste': case 'insertFromDrop': case 'insertReplacementText':
        if (pasted || rich) op(rich ? { op: 'paste', text: pasted, html: rich } : { op: 'paste', text: pasted }); break;
      case 'historyUndo': op({ op: 'undo' }); break;
      case 'historyRedo': op({ op: 'redo' }); break;
      case 'formatBold': op({ op: 'format', bold: !look.bold }); break;
      case 'formatItalic': op({ op: 'format', italic: !look.italic }); break;
      case 'formatUnderline': op({ op: 'format', underline: !look.underline }); break;
      default: break;
    }
  });

  // An input method composes in the page itself, and cannot be stopped
  // while it does; so it is let finish, and what it composed is sent as
  // typing, which repaints the block from the engine's own document over
  // whatever the browser had put there.
  doc.addEventListener('compositionstart', function () { composing = true; });
  doc.addEventListener('compositionend', function (ev) {
    composing = false;
    var data = ev.data || '';
    if (data) op({ op: 'type', text: data });
    else {
      var sel = currentSelection();
      op({ op: 'select', anchor: sel ? sel.anchor : [0, 0], focus: sel ? sel.focus : [0, 0] });
    }
  });

  // A picture tapped is told to the app, which has the sheet for what
  // it shows and how big it is; a caret cannot stand in one.
  doc.addEventListener('click', function (ev) {
    var img = ev.target && ev.target.tagName === 'IMG' ? ev.target : null;
    var el = img ? blockOf(img) : null;
    if (!el || !el.classList.contains('image')) return;
    if (window.Morpho && typeof window.Morpho.tapped === 'function') {
      window.Morpho.tapped(JSON.stringify({ kind: 'image', block: Number(el.getAttribute('data-block')), alt: img.getAttribute('alt') || '' }));
    }
  });

  // A keyboard with modifier keys, for a tablet or a desk. Tab is the
  // engine's — between cells, into a list, or a tab — and never the
  // browser's, which would move the focus out of the page.
  doc.addEventListener('keydown', function (ev) {
    if (ev.key === 'Tab' && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
      ev.preventDefault();
      op({ op: 'tab', back: ev.shiftKey });
      return;
    }
    if (!(ev.ctrlKey || ev.metaKey)) return;
    var k = ev.key.toLowerCase();
    if (k === 'b') { ev.preventDefault(); op({ op: 'format', bold: !look.bold }); }
    else if (k === 'i') { ev.preventDefault(); op({ op: 'format', italic: !look.italic }); }
    else if (k === 'u') { ev.preventDefault(); op({ op: 'format', underline: !look.underline }); }
    else if (k === 'z' && ev.shiftKey) { ev.preventDefault(); op({ op: 'redo' }); }
    else if (k === 'z') { ev.preventDefault(); op({ op: 'undo' }); }
    else if (k === 'y') { ev.preventDefault(); op({ op: 'redo' }); }
  });

  // What the app's own toolbar calls, and what a test reads.
  window.morphoEditor = {
    format: function (change) { return op(Object.assign({ op: 'format' }, change)); },
    restyle: function (change) { return op(Object.assign({ op: 'restyle' }, change)); },
    undo: function () { return op({ op: 'undo' }); },
    redo: function () { return op({ op: 'redo' }); },
    insertTable: function (rows, columns) { return op({ op: 'insertTable', rows: rows, columns: columns }); },
    insertRow: function (below) { return op({ op: 'insertRow', below: below !== false }); },
    deleteRow: function () { return op({ op: 'deleteRow' }); },
    insertColumn: function (after) { return op({ op: 'insertColumn', after: after !== false }); },
    deleteColumn: function () { return op({ op: 'deleteColumn' }); },
    removeBlock: function (block) { return op({ op: 'removeBlock', block: block }); },
    tab: function (back) { return op({ op: 'tab', back: !!back }); },
    describeImage: function (block, description) { return op({ op: 'describeImage', block: block, description: description == null ? null : String(description) }); },
    resizeImage: function (block, widthPt, heightPt) { return op({ op: 'resizeImage', block: block, widthPt: widthPt == null ? null : widthPt, heightPt: heightPt == null ? null : heightPt }); },
    link: function (url, text) { return op({ op: 'link', url: url == null ? null : String(url), text: text == null ? null : String(text) }); },
    count: function () { return op({ op: 'count' }).then(function (r) { return r && r.count ? r.count : null; }); },
    mergeCells: function () { return op({ op: 'mergeCells' }); },
    splitCell: function () { return op({ op: 'splitCell' }); },
    shadeCells: function (rgb) { return op({ op: 'shadeCells', rgb: rgb == null ? null : rgb }); },
    ruleTable: function (ruled) { return op({ op: 'ruleTable', ruled: !!ruled }); },
    headRow: function (header) { return op({ op: 'headRow', header: !!header }); },
    setColumnWidth: function (widthPt) { return op({ op: 'setColumnWidth', widthPt: widthPt }); },
    comment: function (text, author) { return op(author == null ? { op: 'comment', text: String(text) } : { op: 'comment', text: String(text), author: String(author) }); },
    uncomment: function (id) { return op({ op: 'uncomment', id: id }); },
    setPage: function (widthPt, heightPt, top, bottom, left, right) {
      return op({ op: 'setPage', widthPt: widthPt, heightPt: heightPt, marginTopPt: top, marginBottomPt: bottom, marginLeftPt: left, marginRightPt: right });
    },
    describeDocument: function (properties) { return op(Object.assign({ op: 'describeDocument' }, properties)); },
    find: function (query, ignoreCase) {
      return op({ op: 'find', query: query, ignoreCase: !!ignoreCase }).then(function (r) { return r && r.matches ? r.matches : []; });
    },
    replaceAll: function (query, replacement, ignoreCase) {
      return op({ op: 'replaceAll', query: query, replacement: replacement, ignoreCase: !!ignoreCase });
    },
    // The blocks the reading was not sure of, and the caret put at the
    // next of them after the one it is in, round to the first.
    doubtful: function () {
      return op({ op: 'doubtful' }).then(function (r) { return r && r.blocks ? r.blocks : []; });
    },
    nextDoubtful: function () {
      var here = currentSelection();
      var at = here ? here.focus[0] : -1;
      return window.morphoEditor.doubtful().then(function (list) {
        if (!list.length) return null;
        var next = list.filter(function (i) { return i > at; })[0];
        if (next === undefined) next = list[0];
        return op({ op: 'select', anchor: [next, 0], focus: [next, 0] }).then(function () { return next; });
      });
    },
    select: function (anchor, focus) { return op({ op: 'select', anchor: anchor, focus: focus || anchor }); },
    settled: function () { return queue; },
    caret: function () { var s = currentSelection(); return s ? [s.anchor, s.focus] : null; },
    texts: function () {
      function textOf(e) {
        if (e.tagName === 'TABLE' || e.classList.contains('image')) return null;
        var out = '';
        var w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
        var c;
        while ((c = w.nextNode())) { if (c.nodeType === 3) out += c.data; else if (c.tagName === 'BR') out += '\n'; }
        return out;
      }
      return blocks().map(function (e) {
        if (e.tagName !== 'TABLE') return textOf(e);
        return Array.prototype.map.call(e.rows, function (tr) {
          return Array.prototype.map.call(tr.cells, function (td) { return blockElementsOf(td).map(textOf); });
        });
      });
    },
    look: function () { return look; },
    paragraph: function () { return paragraph; },
    status: function () { return status; },
  };

  doc.focus();
  op({ op: 'select', anchor: [0, 0], focus: [0, 0] });
})();
