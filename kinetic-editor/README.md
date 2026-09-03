# Kinetic — Native Android Video Editor Blueprint

A production-grade architecture and working code skeleton for a CapCut-class,
hardware-accelerated multi-track video editor built on **Jetpack Compose +
Media3 (ExoPlayer / Transformer / GL effects) + Kotlin Coroutines/StateFlow**
under a strict MVI contract.

## Build and run

```bash
cd kinetic-editor
./gradlew :app:installDebug      # or open the folder in Android Studio and Run
./gradlew :app:testDebugUnitTest # 54 pure-JVM logic tests
```

The Gradle wrapper, launcher icon, theme, ProGuard rules and the film LUT asset
are all committed, so a fresh clone builds and installs with no extra setup.
Code targets **media3 1.8.0** (see [API drift notes](#api-drift-notes)).

**Verification status.** Google's Maven was unreachable from the authoring
environment, so the app has not been assembled by Gradle there. Instead:

- Every file outside the Compose UI — the engines, the GL effects, the
  compositor, the export mapper and worker, the audio processors, the probe,
  the view model — **compiles with the real Kotlin 2.1.0 compiler against the
  media3 1.8.0 sources** (the `androidx/media` tag, loaded as Java source roots)
  and the Android 15 framework classes, with the kotlinx-serialization compiler
  plugin. ExoPlayer, Transformer, `VideoCompositorSettings`, `OverlaySettings`,
  `BaseGlShaderProgram` and friends are type-checked call sites, not reviewed
  ones. (This is what caught `OverlaySettings`/`VideoCompositorSettings` having
  moved to `androidx.media3.common`, and the deprecated composition-level
  `experimentalSetForceAudioTrack`.)
- The Compose UI (`EditorScreen`, `PreviewSurface`, `ui/timeline/*`,
  `MainActivity`) **compiles against Compose Multiplatform's desktop artifacts**
  — the same `androidx.compose.*` API surface, published to Maven Central —
  with the Compose compiler plugin, in a small Gradle JVM project that adds the
  android-all jar, the media3 sources and stubs for the handful of Android-only
  pieces (`AndroidView`, `LocalContext`, activity results, WorkManager,
  ViewModel). The whole tree type-checks clean. (This is what caught an
  undefined name and a missing import in `EditorScreen`.)
- The pure-logic core (models, reducer, undo store, timeline<->preview mapping,
  shared transition/sequence/PiP planning math, project codec, timeline
  geometry) runs on the JVM: the 54 tests in `app/src/test` pass under JUnit
  4.13.2, alongside a 56-scenario executable sandbox suite.

---

## 1. Architecture: three tiers, two temperatures

The defining problem of a mobile video editor is that three worlds run at
different frequencies and NONE of them may block another:

| World | Frequency | Owner |
|---|---|---|
| Gestures & drawing | 60–120 Hz | Compose (main thread, draw phase) |
| Document mutations | ~1–10 Hz (gesture commits) | MVI store (main thread) |
| Decode / GL / encode | frame rate of media | Media3 internal threads |

```mermaid
flowchart LR
    subgraph HOT ["HOT tier (input/frame rate)"]
        VP["TimelineViewportState\nscrollX · zoom · ghosts"]
        CV["Timeline Canvas\n(draw phase only)"]
    end
    subgraph COLD ["COLD tier (commit rate)"]
        ST["EditorStore\nStateFlow&lt;TimelineState&gt;"]
        RD["reduce() — pure"]
    end
    subgraph ENG ["Engines (media threads)"]
        PE["PreviewEngine\nExoPlayer + slaves"]
        GL["GradeShaderProgram\n(GL thread)"]
        EX["ExportEngine\nTransformer"]
    end
    CV -- "1 intent per gesture" --> ST
    ST --> RD --> ST
    ST -- "onCommitted(prev,next)" --> PE
    PE -- "volatile snapshot swap" --> GL
    ST -- "TimelineState (verbatim)" --> EX
    PE -- "positionMs (frame loop)" --> VP
    VP -- "playheadMs (conflated seeks)" --> PE
```

### The two-temperature state model (why the UI never stutters)

- **Cold state** — `TimelineState` (tracks → clips → trims/speed/effects) is an
  immutable persistent-collection document inside `EditorStore`. It changes only
  when a gesture **commits** (finger up) or a slider emits a coalesced intent.
  Undo/redo is a stack of these snapshots — structural sharing makes 100 levels
  cost kilobytes (`core/mvi/EditorStore.kt`).
- **Hot state** — scroll, zoom, in-progress trim/drag ghosts live in
  `TimelineViewportState` as Compose snapshot state, read **only in the draw
  phase and pointer handlers**. Scrubbing at 120 Hz mutates two floats and
  re-executes one draw lambda; composition and layout are skipped entirely
  (`ui/timeline/TimelineViewportState.kt`).

A gesture previews its result by merging a *ghost* into the drawn track
(`effectiveTrack()` in `TimelineCanvas.kt` — live ripple included) and dispatches
**exactly one intent** on release. The store is never hammered at input rate.

### Commit routing (the decoupling contract)

`EditorViewModel.route()` grades every commit by how much of the media pipeline
must actually move:

| Change class | Detector | Action | Cost |
|---|---|---|---|
| Cosmetic (grade/LUT/transition/volume/speed/text) | always | volatile FX + segment snapshot swap | ~µs |
| Audio / PiP structure | `audioStructureHash()`, `overlayStructureHash()` | rebuild slave playlists | ms, rare |

The line between the first two rows is load-bearing in both directions. A PiP's
placement is deliberately *absent* from `overlayStructureHash`, so dragging its
size or position does not tear down and re-prepare the PiP player sixty times a
second — but the preview still has to follow, so `publishOverlays` runs on the
cosmetic path too and re-emits the placement windows the UI lays its boxes out
from. Handles are values, so a commit that touches no placement produces an
equal list and the flow stays silent. A unit test pins both halves.
| Video structure (trim/reorder/add/remove) | `videoStructureHash()` | rebuild `ConcatenatingMediaSource2`, position-preserving | ~100 ms, rare |

This is why dragging a saturation slider during playback re-renders every frame
with the new value *without touching the player pipeline*: the slider dispatches
coalesced `SetGrade` intents, the store commits, the engine swaps a
`@Volatile PreviewFxTimeline`, and the GL thread picks it up on the next
`drawFrame` — no locks anywhere (`effects/GradeEffects.kt`).

---

## 2. Fluid timeline (`ui/timeline/`)

**One `Canvas` node renders every track.** LazyRows were rejected deliberately:
per-frame item recomposition, entry/exit churn, and N scroll states to sync
during pinch-zoom. Instead:

- Five lanes: main video, PiP video, text, stickers, audio. An empty lane
  draws its own name, fixed in screen space rather than timeline space, so a
  blank project says what each row is for and the label never scrolls away from
  its lane or covers a clip.
- `TimelineGeometry` — pure time↔pixel math + manual hit-testing; reads the
  viewport *at call time* so it's only ever evaluated in draw/gesture phases.
- `TimelineCanvas` — ruler with adaptive tick density, filmstrip thumbnails,
  min/max waveforms, text/sticker chips, selection handles, playhead. New
  thumbnails invalidate **draw only** via a `mutableIntStateOf` revision read
  inside the draw lambda.
- `TimelineGestures` — one `pointerInput` arbitrates scrub-scroll (with decay
  fling), pinch zoom (playhead-anchored), frame-snapped trims, long-press
  drag-reorder with edge auto-scroll and magnetic snapping, and tap-select.
  On a clip body the long-press timer is **raced against the touch slop**
  (`awaitLongPressOrSlop`): move first and you scrub, hold still and you pick
  the clip up. Catching a moving (flung) timeline transfers position ownership
  without dropping a frame, and a gesture that ends where it started commits
  nothing — no phantom undo entries.
- The playhead is **fixed at center; content scrolls beneath it** (CapCut
  model), which makes `scrollX == playheadMs * pxPerMs` an invariant and gives
  playhead-anchored pinch zoom for free.
- Thumbnails (`engine/ThumbnailEngine.kt`): LRU byte-budgeted to heap/6, 1s
  source buckets, pooled `MediaMetadataRetriever` per URI (seek-open cost
  dominates), `OPTION_CLOSEST_SYNC` decode at 256×160. `peek()` is
  allocation-free for the draw loop; prefetch is driven by a `snapshotFlow` of
  the visible window bucketed to 64 px.
- Waveforms (`engine/WaveformEngine.kt`): one-time MediaCodec PCM decode into a
  normalized peak array (~50 buckets/s) on a dedicated single-thread lane.

## 3. Preview sync (`engine/PreviewEngine.kt`)

- The main track plays as **one `ConcatenatingMediaSource2`** (single seekable
  window) of `ClippingConfiguration`-trimmed items — ideal for scrubbing.
- **Two time domains, one mapper.** The window advances in *source* time; the
  editor thinks in *timeline* time (post-speed). `PreviewSegments` is the only
  place the conversion exists, both directions, binary-searched.
- **Frame accuracy:** `SeekParameters.EXACT` + scrubbing mode (media3 1.8) + a
  **one-deep conflated seek queue**: while a seek is in flight, new targets
  overwrite `pendingScrubMs`; `onRenderedFirstFrame` (the "frame actually hit
  the surface" signal) drains it. The decoder is never more than one seek
  behind the finger, regardless of input rate. A 250 ms watchdog covers
  audio-only edge cases.
- **Ownership, not polling**, syncs the scrubber: while playing, a
  `withFrameNanos` loop pushes player position → viewport; while the user (or
  their fling) owns the playhead, a `snapshotFlow` pushes viewport → conflated
  seeks. `isUserInteracting` breaks the feedback loop (`ui/EditorScreen.kt`).
- Per-clip speed and volume envelopes are applied by a 10 Hz control tick in
  preview (`setPlaybackSpeed`, `volume`) — the export applies them
  sample-exactly.
- **Effect timestamps are window positions by construction**
  (`engine/PreviewRenderers.kt`). Stock ExoPlayer hands the effects pipeline
  `bufferTime − startOfFirstStream`, captured once per renderer: sequential
  playback yields window positions, but a seek into another clip restarts the
  renderer clock, after which the same formula yields that clip's source time
  and the grade/LUT/transition lookups land on the wrong clip. A small
  `MediaCodecVideoRenderer` subclass derives the adjustment per stream from
  the period's position in the window instead, so the `PreviewFxTimeline`
  lookup is exact however playback got there.
- The preview is two nested frames, mirroring the export: the **canvas** (output
  size) holds the **picture** — the main clip letterboxed by its own display
  aspect, which is the fit the export's `Presentation` applies — so a landscape
  clip on a portrait canvas is never stretched. Text and stickers are placed
  relative to the canvas, PiP boxes relative to the picture, because that is
  what the composition-level overlay and the compositor respectively see.
- Overlay audio **and PiP video** tracks play on **slave ExoPlayers** phase-locked
  to the master (re-seek on >80 ms drift). A PiP gets its own `TextureView`
  (drawn in the view tree, so Compose can rotate, fade and clip it), laid out
  from the same `pipWindowAt` lookup the export compositor uses — resolved for
  the current playhead behind a `derivedStateOf`, so the box only re-lays out
  when a clip's framing actually changes. `PipSpec.scale` is the fraction of
  the picture's width; height follows the source's own proportions; between
  clips the box is hidden rather than left frozen on a stale frame.
- **The preview letterboxes with the export's own `Presentation`**, rather than
  approximating the fit in the view tree. What reaches the surface is already
  canvas-sized, so the surface *is* the canvas and every overlay — PiP boxes,
  text, stickers — is placed in canvas coordinates. The export matches by
  construction: `Presentation` runs on each main item *before* the compositor,
  so the compositor and the composition-level overlays work in that same space.
  Getting this wrong is subtle — position a PiP against the letterboxed picture
  in one pipeline and against the canvas in the other, and it lands in a
  different place in the render than on screen.
- A PiP is **graded by the same shader as everything else**: its player carries
  `GradeGlEffect` too, fed by a one-snapshot provider rather than a timeline,
  because a PiP has no transitions and its uniforms are therefore constant
  across a clip. The snapshot is swapped on `onMediaItemTransition` (immediate
  at a clip boundary, rather than waiting for the 10Hz tick) and on every
  cosmetic commit. The clip is chosen by the player's own item index — what the
  decoder is actually reading — not by the playhead.
- **Playback errors are surfaced, not swallowed.** A source that vanishes after
  being added (a persisted project whose file was deleted, a revoked URI) would
  otherwise be a silently black preview; `onPlayerError` on the master and every
  slave maps the error code to a plain sentence the UI shows, and the state
  clears when the pipeline is next rebuilt — which is what removing the bad clip
  does. Compositing both streams into one GL surface
  just to preview them would cost a full render pass per frame for no visual
  difference. Text/stickers preview as a Compose layer above everything, matching
  the export layer order (compositor first, `OverlayEffect` last).

## 4. Export (`engine/CompositionMapper.kt`, `engine/ExportEngine.kt`)

`TimelineState` → `Composition`, mechanically:

- Main track → primary `EditedMediaItemSequence`; per item: clipping trims,
  the **same GLSL grade/LUT/transition shader as preview** (windows computed in
  clip-local source time, placed *before* `SpeedChangeEffect`), then
  the speed change, then `VolumeEnvelopeAudioProcessor` (which runs in clip
  *timeline* time — same domain the keyframes are authored in). Transformer
  adds each item's sequence offset ahead of the item's own effects, so the
  export provider measures from the first frame it sees — the same trick
  media3's own `SpeedChangeEffect` uses — rather than trusting the timestamps
  to start at zero.
- Each **VIDEO_OVERLAY (PiP) track → its own video sequence**, positioned in
  time by gaps (blank frames) and in space by `PipCompositorSettings`, which
  resolves compositor input ids (0 = main track, 1..n = overlays) to the
  `PipWindow` in force at each frame's timestamp. The compositor's output is
  the primary frame itself — so the main picture is never cropped — and the
  overlay's scale is derived from the two frame sizes per composite, so
  `PipSpec.scale` means the same "fraction of the picture's width, own
  proportions kept" as in the preview. The sequence is padded with a trailing
  gap to the main track's end (media3's compositor otherwise keeps re-drawing
  a finished sequence's last frame), and outside a clip's window the overlay
  is drawn at alpha 0.
- Each AUDIO track → an audio-only sequence with `addGap()` silences. Every
  sequence sets its own `experimentalSetForceAudioTrack` (and the PiP ones
  `experimentalSetForceVideoTrack`): a sequence that opens with a gap, or with
  an item lacking a track that later items carry, fails the export without it.
- **Speed is media3's interlinked pair**, not two independent knobs.
  `SpeedChangeEffect` and a Sonic processor set to the same factor are two
  timestamp mappings with independent rounding, which is exactly what drifts
  audio against video over a long clip;
  `Effects.createExperimentalSpeedChangingEffect` returns a pair built to stay
  in sync. media3 documents the plain video effect as the choice "when input has
  no audio", so that is when the mapper uses it — a muted or silent clip, and
  audio-only sequences, which take the audio half alone.
- Overlay rotation is specified **counter-clockwise** by media3 and clockwise
  by Compose, so every preview rotation is negated against its export value.
  A preview that turns the opposite way from the render is worse than none.
- **Text animations are one implementation, seen twice.** `textAnimAt` is pure
  timing math in `core/model/Planning.kt`: given an animation, a time and the
  clip's window it returns alpha, scale, an anchor offset and a character count.
  The export's `TextOverlay` reads it per frame and the preview's Canvas draws
  from it, so an animation cannot look one way on screen and another in the
  file — and because it is pure, its timing is unit-tested, which is otherwise
  the kind of thing only a rendered video reveals. A clip shorter than the
  animation gets a *shorter* animation rather than a truncated one, so the two
  ends never overlap. Type-on pre-builds its prefixes at export start rather
  than allocating a string per frame on the GL thread.
- **Type faces are Android's own families, not bundled fonts.** `TextFont`
  carries the family name (`sans-serif`, `serif`, `monospace`, `cursive`) that
  the export resolves through `TypefaceSpan` *and* that Compose's built-in
  `FontFamily`s are themselves defined as — so preview and render pick the
  identical face rather than two that merely look similar. They also exist on
  every device and cost nothing to install. Bundled display faces would change
  nothing but that enum.
- TEXT/STICKER tracks → one composition-level `OverlayEffect` with
  alpha-gated, fade-edged windows (composition time == timeline time). Sticker
  scale is folded with the canvas width so it, too, means "fraction of the
  frame's width" on any canvas rather than the asset's native pixel size.
- Composition-level `Presentation.createForWidthAndHeight` fixes the canvas.
- `Transformer` + `DefaultEncoderFactory(VideoEncoderSettings(bitrate))` renders
  hardware-to-hardware; progress is polled into a cold `callbackFlow`;
  `ExportWorker` (WorkManager foreground job) surfaces notification + WorkInfo
  progress and survives the app being backgrounded.
- The render lands in app-private storage and is then copied into **MediaStore**
  (`Movies/Kinetic`), because a file inside the app sandbox is one the gallery
  and share sheet can never see. `IS_PENDING` hides it until the copy completes,
  and the sandbox copy is deleted once the shared one exists. No permission is
  needed from API 29; below that it fails soft and the file stays app-private,
  which the UI reports honestly rather than claiming a save that did not happen.

Decode → GL → encode never leaves GPU/codec surfaces, so 4K export memory is
flat by construction — no frame ever exists as a Java `Bitmap`.

## 5. What the editor can actually do

Every feature below is reachable from the UI, previewed live, and rendered by
the exporter — the model, the preview and the export path agree on all three.

| Area | Controls |
|---|---|
| Timeline | pinch zoom, momentum scrub, trim, split at playhead, drag-reorder, delete |
| Clips | speed presets (0.5–4x), per-clip brightness/contrast/saturation, film LUT toggle |
| Transitions | dip-to-black, wipe, zoom-punch on any clip boundary |
| Audio | music and voiceover lanes, per-clip volume, fade in/out, track mute |
| Text | editable content (multi-line), four type faces, bold, italic, eight colours, size, position, and five entrance animations (cut, fade, pop, rise, type-on) |
| Stickers | size, position, rotation, and the same animations text uses |
| Overlays | picture-in-picture (size, position, rotation, opacity) — every control is the number the export consumes |
| Looks | eight one-tap filters that set the same grade/LUT fields the sliders edit, so a preset is a starting point rather than a mode |
| Canvas | 9:16, 16:9, 1:1 and 4:5 presets, each fitted, filled (cropped) or stretched — applied by the same `Presentation` in preview and export |
| Editing | trim, split, move, reorder, duplicate, delete, per-clip speed, detach audio |
| Transform | pan, zoom and rotate the picture inside its frame, on any video clip |
| Motion | one-tap push in, pull out, pan and drift that run across the whole clip, composed on top of a manual reframe |
| Output | background MP4 export with live progress, published to Movies/Kinetic |

Volume fades deserve a note: the model stores a general keyframe envelope, but
the UI exposes fade-in/fade-out durations, because that is what nearly every
volume edit actually is. `fadeKeyframes`/`readFades` convert between the two, so
the sliders reflect whatever envelope a clip really has.

### Transform rides the shader it already had

Pan, zoom and rotate are not a new pass. The grade shader was already warping
sampling coordinates for the zoom-punch transition, so a clip transform is four
more uniforms on the same draw: offset and scale move the sampling coordinate
the opposite way from the picture, and the rotation squares the frame up before
turning it so it does not shear. It is written branch-free, because with an
identity transform every term is exactly a no-op — an untouched clip pays a few
ALU ops and nothing else.

Anything the transform moves off the source reads as black rather than a
smeared edge texel, and that mask is applied *last*, so a brightened grade
cannot lift the surround off black.

Because it is the same shader in both pipelines, the preview and the render
share one implementation of the geometry, exactly as they share the colour.

Motion presets sit on top of it: `motionAt` is pure and takes the clip's
transform, a move and how far through the clip the frame is, so a push or a pan
is the transform evaluated per frame rather than a second mechanism. It is the
same trade the volume fades make over the general envelope beneath them — the
90% case as one tap, over a model that can express more. A pan is zoomed in far
enough that sliding never reveals the source's edge (sampling stays inside
while `scale >= 1 + |offset|`), and a unit test walks every preset across every
clip to hold that, because hand-tuned constants are exactly what drifts out of
that relationship.

## 5b. Lifecycle

`MainActivity.onStop` → `EditorViewModel.onEnterBackground`, which is the only
place three otherwise-missing guarantees live: playback stops (an editor that
keeps decoding over whatever the user switched to is both a bug and a codec
leak), an in-progress voiceover is sealed (capture from a backgrounded app has
no foreground service behind it, so the platform may hand it silence), and the
project is written immediately, because autosave is debounced and a
backgrounded process can be killed with no further notice. `onStop` rather than
`onPause`, so playback survives a permission dialog or the volume panel.

The take's timeline anchor lives in the ViewModel rather than the screen for
the same reason: backgrounding has to be able to seal a recording without the
UI handing the position back.

## 6. Persistence (`core/model/ProjectCodec.kt`)

The document is `@Serializable`, so the whole project round-trips as JSON. Two
behaviours depend on it, and both are correctness features:

- **The export worker is handed a file, not an object.** `ExportWorker` reads the
  project from disk, so a render survives the editor process being killed, and
  enqueuing snapshots the document — the user can keep editing while it renders.
- **The session is restored after process death**, not just after a rotation.
  The editor autosaves on a 700 ms debounce and restores through a `Replace`
  intent that clears undo history rather than letting undo walk into a previous
  session.

Supporting details that matter: `PersistentListSerializer` bridges
kotlinx-collections-immutable (which ships no serializers); saves are atomic
(temp file + rename) so a crash cannot truncate a project; decode fails soft to
`null`, ignores unknown keys (files from a newer version still load) and rejects
structurally impossible documents; and media is picked with `OpenDocument` plus
`takePersistableUriPermission`, because a `GetContent`/photo-picker grant dies
with the process and a restored project could not reopen its own media.

## 7. Performance directives

1. **Defer every hot read to the draw phase.** Scroll/zoom/ghosts are snapshot
   state read inside `Canvas` draw lambdas and pointer handlers only — scrubbing
   skips composition and layout entirely. Where a value must reach composition
   (transport counter), gate it behind `derivedStateOf` so one `Text` recomposes.
2. **Zero allocation on frame paths.** The GL uniform buffer is a reused
   mutable struct; envelope lookup uses struct-of-arrays with a monotonic
   cursor; waveform drawing strides primitives; `peek()` APIs return cached
   `ImageBitmap`s. Objects are only born when a gesture ghost exists.
3. **Lock-free cross-thread handoff.** UI→GL and UI→audio communication is
   "immutable snapshot + volatile swap" (`PreviewFxTimeline`), never shared
   mutable state, never a lock on a render thread.
4. **Budget the decoders.** Thumbnails: 2-lane dispatcher, LRU = heap/6,
   sync-frame-only decode, retriever pool keyed by URI. Waveforms: 1 lane.
   Player: small forward buffer + 3 s keyframe-anchored back buffer so
   short back-scrubs replay from memory. The main picture goes to a
   `SurfaceView`, which keeps the decoder path zero-copy; picture-in-picture
   boxes are `TextureView`s, paying a composite per frame on purpose, because
   they have to be rotated, faded and clipped by the view tree — a cost only
   the small overlay carries.
5. **Persistent collections everywhere in the document.** Structural sharing
   makes reducer edits O(changed clips) and undo effectively free — no
   `deepCopy()` GC storms mid-gesture.
6. (Ship-time) Add Baseline Profiles for the editor route, enable R8 +
   resource shrinking (already configured), and consider running export in an
   isolated `:export` process so encoder native heap never fragments the UI
   process.

---

## 8. Project map

```
kinetic-editor/
├── gradlew · gradle/wrapper/ · settings.gradle.kts · build.gradle.kts
├── gradle/libs.versions.toml · gradle.properties · app/proguard-rules.pro
└── app/src/
    ├── test/java/com/kinetic/editor/    CoreLogicTest (document, planning,
    │                                    codec, shader contract) +
    │                                    TimelineGeometryTest (hit-testing,
    │                                    time<->pixel math)
    └── main/
    ├── AndroidManifest.xml
    ├── res/                     strings, colors, dark theme, adaptive icon
    ├── assets/luts/             64-cube film LUT (matches the shader layout)
    ├── assets/stickers/         sticker art
    └── java/com/kinetic/editor/
        ├── MainActivity.kt · KineticApp.kt   entry point, onStop -> background
        ├── core/
        │   ├── model/Models.kt          TimelineState, Track, ClipModel, hashes
        │   ├── model/Planning.kt        shared transition/sequence/fade/PiP math
        │   ├── model/ProjectCodec.kt    JSON persistence, atomic save, soft decode
        │   ├── model/MediaProbe.kt      import-time metadata probe
        │   └── mvi/                     EditorIntent, reduce(), EditorStore+undo
        ├── ui/
        │   ├── timeline/                ViewportState, Geometry, Gestures, Canvas
        │   ├── preview/PreviewSurface.kt SurfaceView, PiP layer, overlay layer
        │   ├── EditorViewModel.kt       commit router (the decoupling contract)
        │   └── EditorScreen.kt          sync loops, transport, inspector, tools
        ├── engine/
        │   ├── PreviewEngine.kt         ExoPlayer, segments, conflated seeks, slaves
        │   ├── PreviewRenderers.kt      window-time video renderer for effects
        │   ├── ThumbnailEngine.kt · WaveformEngine.kt
        │   ├── CompositionMapper.kt     TimelineState -> Composition
        │   ├── ExportEngine.kt          Transformer flow + ExportWorker
        │   └── MediaStorePublisher.kt   render -> shared Movies collection
        ├── effects/
        │   ├── Shaders.kt               shared GLSL (grade/LUT/transitions)
        │   ├── GradeEffects.kt          BaseGlShaderProgram + providers
        │   ├── PipCompositor.kt         per-frame PiP placement (export)
        │   └── Overlays.kt              timed text/sticker overlays (export)
        └── audio/
            ├── VolumeEnvelopeAudioProcessor.kt
            └── VoiceRecorder.kt         WAV voiceover capture + level meter
```

## API drift notes

Pinned to `media3 = 1.8.0`. If you move:

- `ExoPlayer.setScrubbingModeEnabled` — 1.8+. On older versions delete the two
  call sites in `PreviewEngine.setScrubbing`; conflation + `EXACT` still works.
- `OverlaySettings` and `VideoCompositorSettings` live in
  `androidx.media3.common` in 1.8.0 (earlier releases had them in
  `androidx.media3.effect`); `StaticOverlaySettings.Builder` — 1.6+, on ≤1.5 use
  `OverlaySettings.Builder`.
- `EditedMediaItemSequence.Builder` (`addItem`/`addGap`,
  `experimentalSetForceAudioTrack`/`experimentalSetForceVideoTrack`) — 1.6+.
  The composition-level `experimentalSetForceAudioTrack` is deprecated in 1.8.
- `Composition.Builder.setVideoCompositorSettings` — 1.4+, and the
  `VideoCompositorSettings` interface has gained methods over time. This is the
  least-settled surface the project touches; `PipCompositorSettings` also relies
  on `DefaultVideoCompositor` asking for the output size right before it draws,
  which is how it learns the frame sizes it scales by. If PiP fails to compile
  or sizes wrongly, check that class against your media3 version first.
- Most Transformer/effect surfaces are `@UnstableApi`; the module opts in
  globally via `-opt-in` in `app/build.gradle.kts`.

## Known scope cuts (deliberate, documented)

- **Speed ramps** are modeled as stepped constant-speed segments (split a clip,
  set per-segment speeds) — the same rendering strategy CapCut uses for its
  curve presets. A `SpeedProvider`-based continuous ramp can replace
  `SpeedChangeEffect` later without touching the model.
- **True A/B cross-dissolves** need overlapping streams (compositor); the three
  shipped transitions are single-stream by design and export-identical.
- Trim commits currently snap to whole milliseconds on the source frame grid
  (`snapToFrame`); at 29.97/59.94 fps switch the model to µs if you need
  sub-frame-exact conform. The same millisecond model is what `planSequence`
  computes its gaps from, while media3 derives item durations by flooring in
  microseconds — so a *retimed* clip on an overlay or audio track can place the
  clips after it under a millisecond early, accumulating per retimed item. At
  speed 1 both agree exactly, and the error stays far below a frame for any
  realistic clip count; moving the model to µs removes it entirely.
