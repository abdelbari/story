// Central application store. Holds the current document plus editor state
// (selection, zoom, active page) and provides snapshot-based undo/redo.
//
// Mutation protocol:
//   store.apply(fn)          -> mutate + commit one history step + emit
//   store.applyTransient(fn) -> mutate + emit, NO history (during drags)
//   store.commit()           -> snapshot the current doc as one history step
// A drag calls applyTransient on every pointermove and commit() once on
// pointerup, so the whole gesture is a single undo step.

const HISTORY_LIMIT = 100;

function deepClone(obj) {
  return typeof structuredClone === 'function'
    ? structuredClone(obj)
    : JSON.parse(JSON.stringify(obj));
}

class Emitter {
  constructor() { this.listeners = new Map(); }
  on(event, fn) {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event).add(fn);
    return () => this.listeners.get(event).delete(fn);
  }
  emit(event, payload) {
    const set = this.listeners.get(event);
    if (set) for (const fn of [...set]) fn(payload);
  }
}

export class Store extends Emitter {
  constructor() {
    super();
    this.doc = null;            // current document (mutable working copy)
    this.pageIndex = 0;         // active page
    this.selection = [];        // array of element ids on the active page
    this.editingTextId = null;  // element id currently in inline text edit
    this.zoom = 1;
    this.past = [];
    this.future = [];
    this.savedSnapshot = null;  // JSON string of last persisted doc
  }

  // ---- document lifecycle ----
  loadDoc(doc) {
    this.doc = doc;
    this.pageIndex = 0;
    this.selection = [];
    this.editingTextId = null;
    this.past = [];
    this.future = [];
    this._pending = null;
    this._lastCommitted = this.snapshot();
    this.emit('doc');
    this.emit('selection');
    this.emit('pages');
  }

  get page() {
    return this.doc ? this.doc.pages[this.pageIndex] : null;
  }

  elementById(id) {
    const page = this.page;
    return page ? page.elements.find(e => e.id === id) : null;
  }

  selectedElements() {
    const page = this.page;
    if (!page) return [];
    return this.selection.map(id => page.elements.find(e => e.id === id)).filter(Boolean);
  }

  // ---- history ----
  // Entries are {doc, pageIndex} so undo/redo can jump back to the page the
  // change happened on instead of silently mutating an off-screen page.
  snapshot() {
    return deepClone(this.doc);
  }

  commit() {
    this.past.push({
      doc: this._pending || this._lastCommitted || this.snapshot(),
      pageIndex: this._pendingPage ?? this.pageIndex,
    });
    if (this.past.length > HISTORY_LIMIT) this.past.shift();
    this.future = [];
    this._pending = null;
    this._pendingPage = null;
    this._lastCommitted = this.snapshot();
    this.doc.updatedAt = Date.now();
    this.emit('history');
    this.emit('dirty');
  }

  // Call before a transient gesture starts so commit() stores the pre-drag
  // doc. If a gesture is already open (e.g. a toolbar apply lands mid text
  // edit), keep the outer snapshot so both fold into one undo step.
  beginGesture() {
    if (!this._pending) {
      this._pending = this.snapshot();
      this._pendingPage = this.pageIndex;
    }
  }

  // End a gesture that produced no change, without recording history.
  endGesture() {
    this._pending = null;
    this._pendingPage = null;
  }

  cancelGesture() {
    if (this._pending) {
      this.doc = this._pending;
      this._pending = null;
      this._pendingPage = null;
      this.emit('doc');
      this.emit('selection');
    }
  }

  apply(fn) {
    this.beginGesture();
    fn(this.doc, this);
    this.commit();
    this.emit('doc');
  }

  applyTransient(fn) {
    fn(this.doc, this);
    this.emit('doc');
  }

  undo() {
    if (!this.past.length) return;
    this.future.push({ doc: this.snapshot(), pageIndex: this.pageIndex });
    const entry = this.past.pop();
    this.doc = entry.doc;
    this.pageIndex = entry.pageIndex;
    this._lastCommitted = this.snapshot();
    this._afterTimeTravel();
  }

  redo() {
    if (!this.future.length) return;
    this.past.push({ doc: this.snapshot(), pageIndex: this.pageIndex });
    const entry = this.future.pop();
    this.doc = entry.doc;
    this.pageIndex = entry.pageIndex;
    this._lastCommitted = this.snapshot();
    this._afterTimeTravel();
  }

  _afterTimeTravel() {
    if (this.pageIndex >= this.doc.pages.length) this.pageIndex = this.doc.pages.length - 1;
    const ids = new Set(this.page.elements.map(e => e.id));
    this.selection = this.selection.filter(id => ids.has(id));
    this.editingTextId = null;
    this.emit('doc');
    this.emit('selection');
    this.emit('pages');
    this.emit('history');
    this.emit('dirty');
  }

  canUndo() { return this.past.length > 0; }
  canRedo() { return this.future.length > 0; }

  // ---- selection ----
  select(ids, { additive = false } = {}) {
    const next = additive ? [...new Set([...this.selection, ...ids])] : [...ids];
    const changed = next.length !== this.selection.length || next.some((id, i) => id !== this.selection[i]);
    this.selection = next;
    if (this.editingTextId && !next.includes(this.editingTextId)) this.editingTextId = null;
    if (changed) this.emit('selection');
  }

  toggleSelect(id) {
    if (this.selection.includes(id)) this.selection = this.selection.filter(s => s !== id);
    else this.selection = [...this.selection, id];
    this.emit('selection');
  }

  clearSelection() {
    if (this.selection.length || this.editingTextId) {
      this.selection = [];
      this.editingTextId = null;
      this.emit('selection');
    }
  }

  // ---- pages ----
  setPage(index) {
    if (index < 0 || index >= this.doc.pages.length || index === this.pageIndex) return;
    this.pageIndex = index;
    this.selection = [];
    this.editingTextId = null;
    this.emit('pages');
    this.emit('doc');
    this.emit('selection');
  }

  setZoom(z) {
    this.zoom = Math.min(4, Math.max(0.05, z));
    this.emit('zoom');
  }
}

export const store = new Store();
