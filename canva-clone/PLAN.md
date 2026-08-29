# Canvia — a Canva-inspired design studio (plan)

A single-page, offline-first design editor strongly inspired by Canva: a home
screen with size presets, recents and templates; a full editor with a left
asset rail, contextual toolbar, direct-manipulation canvas, multi-page
support; and a real export pipeline (PNG / JPEG / SVG / multi-page PDF / ZIP /
JSON). Built in pure vanilla JavaScript (ES modules), zero dependencies, zero
build step — serve the folder and it runs.

This plan was synthesized from three independent design passes (product/UX,
engine architecture, creative content) plus an adversarial completeness
critique; the decisions below are the reconciled result.

## Hard constraints

- **No dependencies, no build step.** Plain ES modules, HTML, CSS. Runs from
  any static file server.
- **Fully offline.** No CDN fonts or remote images. "Photos" are procedurally
  generated SVG art (seeded, deterministic). Fonts are ~12 curated system
  font stacks with distinct personalities.
- **Chromium is the fidelity target** for export parity (`ctx.letterSpacing`,
  `ctx.filter`). Other engines degrade gracefully but are not the test bar.
- **Single-user, no backend.** Autosave to `localStorage` + JSON file
  import/export as the durable path.

## Architecture

```
canva-clone/
  index.html            app shell (home + editor mount points)
  css/app.css           design tokens + full chrome styling
  js/
    core/               geometry.js  store.js  doc.js  paint.js  persistence.js
    editor/             renderer.js  overlay.js  interactions.js  commands.js  shortcuts.js
    ui/                 home.js  topbar.js  sidebar.js  toolbar.js  colorpicker.js
                        position.js  layers.js  pagestrip.js  contextmenu.js  widgets.js
    assets/             shapes.js  photos.js  stickers.js  typography.js  palettes.js
                        filters.js  templates.js
    export/             exporter.js  svg.js  pdf.js  zip.js
    main.js             routing (#/home ⇄ #/edit), boot, autosave loop
```

**Document model.** A design is plain JSON: `{version, id, title, width,
height, pages:[{id, background, elements:[…]}]}`. Elements are typed —
`shape | text | image | sticker | line` — sharing `x y w h rotation opacity
locked flipH flipV` plus type-specific props. Shapes reference a library of
normalized 100×100 SVG paths stretched to the element box, so one definition
serves the sidebar, the DOM renderer, and both exporters. Images reference
either upload data-URIs or `asset:<id>` keys resolved through the procedural
photo registry (one resolution contract everywhere).

**State & undo.** One store owns the doc plus editor state. History is
snapshot-based with gesture coalescing: `beginGesture()` on pointer-down /
edit-start, transient mutations per frame, one `commit()` on release — so a
whole drag or typing session is a single undo step. History entries carry
`{doc, pageIndex}` so undo jumps back to the page it changes. Cap 100 steps.

**Rendering.** DOM-based editor: absolutely-positioned nodes in a page
container scaled by CSS transform. Keyed reconciliation (node reuse by
element id) keeps drags at 60fps and preserves contenteditable focus. Text
uses native browser wrapping; the selection overlay lives beside the page in
the same scaled space with `1/zoom`-sized handles.

**Transform math.** All rotation-aware math is isolated in pure
`geometry.js`: the rotated-anchor resize algorithm (un-rotate the pointer
around the fixed opposite anchor, clamp instead of sign-flip, re-derive the
center), SAT marquee intersection, snapping candidates, angle snapping.

**Export.** Canvas rasterizer mirrors the DOM per element type (wrapped text
with effects, cover-cropped images with filters, gradients via one shared
angle→vector formula, rotation/flip/opacity). PNG/JPEG at 1×/2×/3×; SVG
serialization; **multi-page PDF written by hand** (JPEG pages via
`/DCTDecode`, no library); **ZIP (stored) of all pages** to dodge
multi-download blocking; JSON save/load. Thumbnails for recents and the page
strip come from the same rasterizer — the template gallery doubles as a
continuous exporter smoke test.

## Feature set

### Core editing
- Select / shift-click / marquee (SAT vs rotated elements) / select-all
- **Alt-click digs below** overlapping elements (select-behind)
- Drag with **snap-to guides**: page edges/center + other elements'
  edges/centers, zoom-aware threshold, magenta guide lines; Alt disables
- 8-handle resize, rotation-aware; corners proportional (Shift frees),
  text corners scale font size, side handles rewrap; rotate handle with
  angle badge and 45° snapping; size/position badges during gestures
- Multi-select: group outline, proportional corner scaling, align &
  distribute; lightweight sticky groups (Ctrl+G) as selection expansion —
  no nested transforms by design
- Inline text editing (double-click, caret preserved, plain-text paste,
  empty text auto-deletes; Escape/blur commits as one undo step)
- Layer order (forward/back/front/bottom), lock, duplicate, delete, flip,
  opacity, copy/paste/cut, **paste OS images & text from the clipboard**,
  **copy style / format painter**, nudge (1px / 10px)
- Zoom 5%–400% at cursor (Ctrl+wheel), buttons, fit; space-drag / middle
  pan; **auto-pan while dragging near the viewport edge**
- Full keyboard map + right-click context menu
- Multi-page: page strip with live thumbnails, add/duplicate/delete/reorder

### Content & panels (left rail)
- **Design**: template gallery (8–10 complete designs as builder functions
  calling the real factories) + resize-design controls
- **Elements**: ~36 shapes (basics, stars, arrows, callouts, symbols,
  seeded blobs), lines with caps/dashes, sticker/emoji library, procedural
  graphics — with a **search box** (the critic's #1 miss)
- **Text**: heading/subheading/body inserts + curated font-pairing cards
- **Photos**: ~14 deterministic procedural artworks (mesh gradients,
  patterns, scenes) — searchable
- **Uploads**: image upload (downscaled to protect quota), drag-drop onto
  canvas, paste-from-clipboard lands here
- **Background**: solid swatches, gradient presets + angle editor, photos
- **Layers**: reorderable list of the current page with lock/visibility

### Contextual toolbar (morphs per selection)
- Text: font stack, size stepper, color, B/I/U, align, line height, letter
  spacing, list, **text effects** (shadow, lift, hollow, splice, neon,
  echo, highlight)
- Shape: fill (solid/gradient), stroke, corner radius
- Image: **filter presets** (Vivid, Mono, Noir, Warm, …), **crop/reposition
  (cover-crop offset + zoom)**, **replace image in place**, corner radius,
  border
- Line: color, thickness, dash, end caps
- Universal cluster: position panel, opacity, lock, duplicate, delete
- Color picker popover: curated palettes, **document colors** (extracted,
  frequency-ranked), gradients, hex input + eyedropper-style native picker

### Home screen
- Size presets (Instagram post/story, presentation, poster, A4, business
  card, YouTube thumbnail, …) + custom size
- Recents grid from localStorage with thumbnails, rename/duplicate/delete
- Template gallery, JSON import

### Delight
- Confetti on export (reduced-motion aware), micro-animations, empty
  states, dismissible onboarding hint, autosave badge, **shuffle colors**
  (luminance-rank palette remap) as the "magic" touch

## Deliberate cuts (from the critique)

- No print-dialog PDF (replaced by the handwritten PDF writer)
- No nested group transforms; groups are sticky multi-selection
- No spacing/equal-gap guides or rulers (edge/center guides carry 90% of it)
- No justify alignment (canvas export can't honor it faithfully)
- No filter-intensity slider (fixed-strength presets)
- No per-line-span text rendering (browser wrapping is the single truth in
  the DOM; the canvas exporter re-wraps with identical font metrics — small
  drift accepted, Chromium-verified)

## Known honest limitations

- DOM-vs-canvas text wrapping can drift ~1px at extreme letter-spacing
- SVG export approximates image filters via CSS `filter` (fine in browsers,
  ignored by strict SVG tools) and bounding-box gradients on stretched
  shapes
- Emoji stickers render with the platform emoji font (varies per OS)
- Two tabs editing the same design last-write-wins (single-user scope)

## Verification

- `data-testid` on every control; deterministic boot; `?nomotion=1` kills
  animations for stable screenshots
- Playwright end-to-end: create → insert each element type → drag/resize/
  rotate with assertions on the model → undo/redo → autosave → reload →
  export PNG/PDF and assert non-trivial output
- Adversarial multi-agent code review over the geometry, history and export
  modules; confirmed findings fixed before ship

## Build order

1. Core: geometry, store/history, doc model, persistence ✚
2. Editor: renderer, overlay, interactions, commands, shortcuts ✚
3. Export: canvas rasterizer, SVG, PDF, ZIP, thumbnails ✚
4. Chrome: shell, home, panels, toolbars, pickers, pages, layers
5. Content: shapes/photos/stickers/palettes/templates (parallel generation)
6. Critic fixes: select-behind, clipboard paste, replace-image, crop,
   format painter, auto-pan, search
7. Verify: Playwright suite + review pass; fix and re-run until green
