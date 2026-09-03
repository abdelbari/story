# Canvia

A Canva-inspired design studio in pure vanilla JavaScript — zero
dependencies, zero build step, fully offline. See [PLAN.md](PLAN.md) for the
design plan this was built from.

![Canvia](https://img.shields.io/badge/deps-0-8b3dff) ![](https://img.shields.io/badge/build-none-16c79a)

## Run it

```bash
# any static file server works:
npx http-server canva-clone -p 8080
# then open http://localhost:8080
```

No install, no build. Chromium-family browsers are the fidelity target.

## What it does

**Home** — design-size presets (Instagram post/story, presentation, poster,
A4, business card, YouTube thumbnail, …), custom sizes, recent designs with
thumbnails (rename/duplicate/delete), a template gallery, JSON import.

**Editor**
- Direct manipulation: drag with snap guides, 8-handle rotation-aware
  resize (corners proportional, Shift frees), rotate with 45° snapping and
  angle badge, marquee multi-select, group scaling, align/distribute,
  Alt-click to select behind overlapping elements, auto-pan at the viewport
  edge, space/middle-drag pan, Ctrl+wheel zoom-at-cursor
- Elements: 44 shapes, lines with caps/dashes, emoji stickers, text with
  12 system font stacks and 8 text effects (shadow, lift, hollow, splice,
  neon, echo, highlight), 20 procedurally generated photos, uploads
  (drag-drop or Ctrl+V a screenshot)
- Text edited inline (double-click), auto-height, plain-text paste
- Contextual toolbar per element type + opacity/lock/duplicate/delete,
  copy-style roller, image filters, cover-crop (zoom + focus), replace
  image in place, corner radius, borders
- Color system: curated palettes, gradients, document-color extraction,
  ✨ Shuffle (luminance-ranked palette remap)
- Multi-page: page strip with live thumbnails, add/duplicate/reorder/delete
- 8 complete templates, applied into the current page at any canvas size
- Undo/redo (one gesture = one step, jumps back to the edited page),
  autosave to localStorage, full keyboard map, right-click menu

**Export** — PNG / JPEG (1–3×), SVG, multi-page **PDF** (handwritten
writer, JPEG pages via DCTDecode), **ZIP** of all pages (handwritten stored
ZIP), and editable JSON. With confetti.

## Testing

`data-testid` on every control, deterministic boot, `?nomotion=1` to kill
animations, and a `window.__canvia` state hook. The Playwright smoke suite
drives create → insert → drag/resize/rotate → edit text → pages →
template → export → reload and asserts on the document model.

## Architecture (short version)

```
js/core      geometry (rotated-anchor resize, SAT), store (snapshot undo,
             gesture coalescing), doc model, paints, persistence
js/editor    keyed DOM renderer, selection overlay, pointer state machine,
             commands, shortcuts
js/ui        topbar, contextual toolbar, sidebar panels, color picker,
             layers, page strip, popovers/dialogs/toasts, confetti
js/assets    shapes, procedural photos, typography, filters, palettes,
             stickers, templates (+ generated content-data)
js/export    canvas rasterizer, SVG serializer, PDF writer, ZIP writer
```

Known limitations are listed at the end of [PLAN.md](PLAN.md).
