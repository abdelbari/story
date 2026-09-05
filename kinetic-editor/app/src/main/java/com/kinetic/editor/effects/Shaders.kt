package com.kinetic.editor.effects

/**
 * One fragment shader serves color grading, LUTs, boundary transitions, the
 * clip transform, masks and frame effects for BOTH the real-time preview and
 * the Transformer export - identical math, so what you see is exactly what
 * renders.
 *
 * Transitions are single-stream by design (like most CapCut transitions): the
 * outgoing clip animates through phase [0, 0.5], the incoming clip through
 * [0.5, 1]. No second decoder, no pre-rendered overlap, works inside one
 * EditedMediaItemSequence.
 *
 * Order of operations, which is the order a print would see them: the frame is
 * warped (effects that move pixels, the transform, the mirror), then sampled,
 * then keyed, graded and toned, then the print-level treatments (vignette,
 * grain) go on, and the mask decides what survives at the very end.
 */
object EditorShaders {

    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoords;
void main() {
  gl_Position = aFramePosition;
  vTexCoords = aFramePosition.xy * 0.5 + 0.5;
}
"""

    // Transition type ids, mirrored in Kotlin (TransitionType.ordinal).
    // 0 = none, 1 = dip-to-black, 2 = wipe-left, 3 = zoom-punch.
    // Effect ids mirror ClipEffect.ordinal; mask ids are MaskShape.ordinal + 1.
    const val FRAGMENT = """
// highp where the device offers it: the LUT's tile coordinates are the one
// computation here that mediump's 10-bit mantissa can visibly band. Fragment
// highp is optional in GLES 2, and the guard macro is how the spec says to ask.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTexCoords;
uniform sampler2D uTexSampler;
uniform sampler2D uLutSampler;
uniform float uLutEnabled;
uniform float uLutIntensity;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemperature;
uniform float uTransType;
uniform float uTransProgress;
// Clip transform: pan, zoom and rotate the picture inside its own frame.
uniform float uXfScale;
uniform vec2 uXfOffset;
uniform float uXfRot;
uniform float uAspect;
// Mirror: -1 reflects that axis of the source, 1 leaves it alone.
uniform vec2 uFlip;
// The print, rather than the scene: grain and vignette live on the frame, so
// they do not zoom or pan with the clip transform above.
uniform float uGrain;
uniform float uGrainSeed;
uniform float uVignette;
// Chroma key. Tolerance 0 disables it, so an unkeyed clip costs one compare.
uniform vec3 uKeyColor;
uniform float uKeyTolerance;
uniform float uKeySoftness;
// Mask, on the frame rather than the picture. Type 0 = none. The centre is in
// texture space; sizes are fractions of the frame height, so a circle is round.
uniform float uMaskType;
uniform vec2 uMaskCenter;
uniform float uMaskSize;
uniform float uMaskAspect;
uniform float uMaskRound;
uniform float uMaskRot;
uniform float uMaskFeather;
uniform float uMaskInvert;
// Frame effect. Type 0 = none; amount 0..1; time in clip-local seconds, wrapped
// by the Kotlin side to a period every animation below divides evenly.
uniform float uFxType;
uniform float uFxAmount;
uniform float uTime;
// 1: composite onto black, for the main track, whose surfaces ignore alpha.
// 0: straight alpha, for an overlay the compositor will blend.
uniform float uOpaque;

// Cheap hash noise. Deterministic per pixel per frame, which is what makes
// grain sit still within a frame and dance between them, the way film does.
float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// Hash for small counters (frame numbers, band indices). Its constants keep
// every intermediate under a few thousand, inside mediump's range, where the
// classic hash above would overflow to infinity and come back as NaN.
float hashi(vec2 p) {
  return fract(sin(dot(p, vec2(0.918, 0.472))) * 213.7);
}

// 64^3 LUT packed as an 8x8 grid of 64x64 blue-slices in a 512x512 texture.
vec3 applyLut(vec3 c) {
  float b = clamp(c.b, 0.0, 1.0) * 63.0;
  float s0 = floor(b);
  float s1 = min(s0 + 1.0, 63.0);
  vec2 inner = (clamp(c.rg, 0.0, 1.0) * 63.0 + 0.5) / 512.0;
  vec2 uv0 = vec2(mod(s0, 8.0), floor(s0 / 8.0)) * 0.125 + inner;
  vec2 uv1 = vec2(mod(s1, 8.0), floor(s1 / 8.0)) * 0.125 + inner;
  return mix(texture2D(uLutSampler, uv0).rgb, texture2D(uLutSampler, uv1).rgb, b - s0);
}

// Signed distance to the mask edge, negative inside, in frame-height units.
float maskDistance(vec2 m) {
  if (uMaskType < 1.5) {
    return length(m) - uMaskSize * 0.5;
  } else if (uMaskType < 2.5) {
    vec2 hs = vec2(uMaskSize * 0.5 * uMaskAspect, uMaskSize * 0.5);
    float r = uMaskRound * min(hs.x, hs.y);
    vec2 e = abs(m) - hs + r;
    return length(max(e, 0.0)) + min(max(e.x, e.y), 0.0) - r;
  } else if (uMaskType < 3.5) {
    return m.y;
  }
  return abs(m.y) - uMaskSize * 0.5;
}

void main() {
  vec2 uv = vTexCoords;
  float p = clamp(uTransProgress, 0.0, 1.0);
  float fx = uFxType;
  float amt = uFxAmount;

  // Mirror effect: the left half reflected onto the right. Screen space and
  // before the transform, so it is the frame that folds, not the source.
  if (fx > 7.5 && fx < 8.5) {
    uv.x = 0.5 - abs(uv.x - 0.5);
  }

  // Shake: the whole frame jolted a little each frame, zoomed in a touch so
  // the jolt never shows the edge of the source.
  if (fx > 5.5 && fx < 6.5) {
    float f = floor(uTime * 12.0);
    vec2 jolt = vec2(hashi(vec2(f, 1.0)), hashi(vec2(f, 7.0))) - 0.5;
    uv = 0.5 + (uv - 0.5) / (1.0 + 0.06 * amt) + jolt * 0.05 * amt;
  }

  // Glitch: bands of rows torn sideways, on some frames and not others.
  if (fx > 1.5 && fx < 2.5) {
    float f = floor(uTime * 8.0);
    float on = step(0.55, hashi(vec2(f, 3.0)));
    float band = floor(uv.y * 12.0 + hashi(vec2(f, 5.0)) * 12.0);
    float tear = (hashi(vec2(band, f)) - 0.5) * step(0.5, hashi(vec2(band + 11.0, f)));
    uv.x += tear * 0.08 * amt * on;
  }

  // Move the CONTENT, so the sampling coordinate moves the opposite way.
  // Written branch-free: with an identity transform every term below is
  // exactly a no-op, so untransformed clips cost a few ALU ops and nothing else.
  vec2 q = uv - 0.5;
  q -= uXfOffset * 0.5;            // offsets are NDC, over a 2-unit frame
  q /= uXfScale;
  q.x *= uAspect;                  // square up, rotate, unsquare, so a
  float cs = cos(uXfRot);          // rotation does not shear the picture
  float sn = sin(uXfRot);
  q = vec2(q.x * cs + q.y * sn, -q.x * sn + q.y * cs);
  q.x /= uAspect;
  // The mirror goes on last in the chain, which puts it FIRST on the picture:
  // it is the source that flips, and a pan still goes the way it is dragged.
  uv = 0.5 + (q * uFlip);

  // Zoom-punch warps sampling coords: scale peaks at the cut point (p = 0.5).
  if (uTransType > 2.5) {
    float bump = 1.0 - abs(1.0 - 2.0 * p);      // 0 -> 1 -> 0
    float s = 1.0 + 0.8 * bump * bump;
    uv = 0.5 + (uv - 0.5) / s;
  }

  vec4 texel = texture2D(uTexSampler, uv);
  vec3 c = texel.rgb;

  // Chromatic fringe, and the milder version the glitch and tape looks share:
  // red and blue sampled a little either side of green.
  if (fx > 0.5 && fx < 3.5) {
    float split = amt * (fx < 1.5 ? 0.012 : (fx < 2.5 ? 0.008 : 0.004));
    c.r = texture2D(uTexSampler, uv + vec2(split, 0.0)).r;
    c.b = texture2D(uTexSampler, uv - vec2(split, 0.0)).b;
  }

  // Glow: a soft haze lifted from the brightest parts, four wide taps.
  if (fx > 6.5 && fx < 7.5) {
    vec2 o = vec2(0.012 / uAspect, 0.012);
    vec3 g = texture2D(uTexSampler, uv + o).rgb
           + texture2D(uTexSampler, uv - o).rgb
           + texture2D(uTexSampler, uv + vec2(o.x, -o.y)).rgb
           + texture2D(uTexSampler, uv + vec2(-o.x, o.y)).rgb;
    c += max(g * 0.25 - 0.45, 0.0) * amt * 1.2;
  }

  // Keyed BEFORE the grade, so the key is judged on the colour that was shot
  // rather than on one the user has since pushed around.
  float alpha = texel.a;
  if (uKeyTolerance > 0.0) {
    float d = distance(c, uKeyColor);
    alpha *= smoothstep(uKeyTolerance, uKeyTolerance + uKeySoftness + 0.001, d);
  }

  // Grade: brightness -> contrast -> saturation -> temperature.
  c += uBrightness;
  c = (c - 0.5) * uContrast + 0.5;
  float luma = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(luma), c, uSaturation);
  c += vec3(uTemperature, 0.0, -uTemperature) * 0.5;

  if (uLutEnabled > 0.5) {
    c = mix(c, applyLut(c), uLutIntensity);
  }

  if (uTransType > 0.5 && uTransType < 1.5) {
    // Dip to black: fully dark at the cut point.
    c *= abs(1.0 - 2.0 * p);
  } else if (uTransType > 1.5 && uTransType < 2.5) {
    // Wipe: a soft black curtain sweeps in over A, sweeps off B.
    // The edge travels from just off the left of the frame to just off the
    // right, so at both ends of the transition the frame is untouched. Mapping
    // it to [0, 1] instead would darken a strip of the left edge on the very
    // first frame, before the wipe has visibly begun.
    float coverage = 1.0 - abs(1.0 - 2.0 * p);  // 0 -> 1 -> 0
    float edge = mix(-0.07, 1.07, coverage);
    c *= smoothstep(edge - 0.06, edge + 0.06, uv.x);
  } else if (uTransType > 2.5) {
    // Zoom-punch adds a subtle exposure dip so the warp reads as intentional.
    c *= 1.0 - 0.25 * (1.0 - abs(1.0 - 2.0 * p));
  }

  // Tape: scanlines, a wash of the colour, and noise that never sits still.
  if (fx > 2.5 && fx < 3.5) {
    float scan = 0.5 + 0.5 * sin(vTexCoords.y * 1200.0);
    c *= 1.0 - 0.18 * amt * scan;
    c = mix(c, vec3(dot(c, vec3(0.299, 0.587, 0.114))), 0.25 * amt);
    c += (hash(vTexCoords * 700.0 + uGrainSeed) - 0.5) * 0.12 * amt;
  }

  // Light leak: a warm bloom drifting about the top corner. Its two drifts
  // divide the 20-second time period exactly, so the wrap is invisible.
  if (fx > 3.5 && fx < 4.5) {
    vec2 centre = vec2(0.85 + 0.15 * sin(uTime * 0.6283), 0.75 + 0.2 * cos(uTime * 0.3142));
    vec2 d = (vTexCoords - centre) * vec2(uAspect, 1.0);
    float dd = dot(d, d);
    float leak = exp(-dd * 3.0) + 0.35 * exp(-dd * 0.8);
    c += vec3(1.0, 0.55, 0.25) * leak * amt * (1.0 - 0.4 * c);
  }

  // Flicker: exposure that wobbles from frame to frame, as a projector's does.
  if (fx > 4.5 && fx < 5.5) {
    float f = floor(uTime * 24.0);
    c *= 1.0 + (hashi(vec2(f, 13.0)) - 0.5) * 0.5 * amt;
  }

  // Vignette before grain: the grain sits on top of the darkened corners, as
  // it would on a print, rather than being darkened along with them.
  if (uVignette > 0.0) {
    float r = length(vTexCoords - 0.5);
    c *= mix(1.0, 1.0 - smoothstep(0.32, 0.78, r), uVignette);
  }

  if (uGrain > 0.0) {
    // Screen space, not sampling space, so grain does not zoom with the clip.
    float n = hash(vTexCoords * 1024.0 + uGrainSeed) - 0.5;
    c += n * uGrain * 0.22;
  }

  // Mask: on the frame, so it holds still while the picture moves behind it.
  float mask = 1.0;
  if (uMaskType > 0.5) {
    vec2 m = (vTexCoords - uMaskCenter) * vec2(uAspect, 1.0);
    float mc = cos(uMaskRot);
    float ms = sin(uMaskRot);
    m = vec2(m.x * mc + m.y * ms, -m.x * ms + m.y * mc);
    // The upper edge is nudged so a zero feather is a step, not undefined.
    mask = 1.0 - smoothstep(-uMaskFeather, uMaskFeather + 0.0005, maskDistance(m));
    mask = mix(mask, 1.0 - mask, uMaskInvert);
  }

  // Anything the transform moved off the source is gone rather than a smeared
  // edge texel: zooming out or panning past the border letterboxes cleanly.
  // Applied last so a brightened grade cannot lift the surround.
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  float a = alpha * inside * mask;
  vec3 rgb = clamp(c, 0.0, 1.0);
  // The compositor blends overlays on straight alpha, so theirs is left
  // unmultiplied. The main track's surfaces ignore alpha altogether - an
  // encoder input and a SurfaceView both do - so it composites onto black
  // here, where the preview and the render cannot disagree about it.
  gl_FragColor = mix(vec4(rgb, a), vec4(rgb * a, 1.0), uOpaque);
}
"""

    /*
     * Canvas fill: the three passes that letterbox a clip over a background
     * that is not black. See CanvasFill.kt for the order they run in. These
     * are simple enough for mediump: no LUT, no large constants.
     */

    /**
     * Pass 1: the picture at FILL (cover) geometry, shrunk to a small texture.
     * Four taps a quarter-texel apart, so the shrink averages neighbouring
     * pixels rather than skipping over them, which would shimmer as the video
     * moves.
     */
    const val CANVAS_DOWNSAMPLE = """
precision mediump float;
varying vec2 vTexCoords;
uniform sampler2D uTexSampler;
uniform vec2 uFillScale;
uniform vec2 uTexel;
void main() {
  vec2 src = 0.5 + (vTexCoords - 0.5) * uFillScale;
  vec2 d = uTexel * 0.25 * uFillScale;
  vec3 c = texture2D(uTexSampler, src + d).rgb
         + texture2D(uTexSampler, src - d).rgb
         + texture2D(uTexSampler, src + vec2(d.x, -d.y)).rgb
         + texture2D(uTexSampler, src + vec2(-d.x, d.y)).rgb;
  gl_FragColor = vec4(c * 0.25, 1.0);
}
"""

    /**
     * Passes 2 and 3 (and again, per round): a Gaussian along one axis. Five
     * bilinear taps at the classic offsets read nine texels, and the weights
     * sum to one so the blur neither darkens nor brightens.
     */
    const val CANVAS_BLUR = """
precision mediump float;
varying vec2 vTexCoords;
uniform sampler2D uTexSampler;
uniform vec2 uStep;
void main() {
  vec3 c = texture2D(uTexSampler, vTexCoords).rgb * 0.2270;
  c += (texture2D(uTexSampler, vTexCoords + uStep * 1.3846).rgb
      + texture2D(uTexSampler, vTexCoords - uStep * 1.3846).rgb) * 0.3162;
  c += (texture2D(uTexSampler, vTexCoords + uStep * 3.2308).rgb
      + texture2D(uTexSampler, vTexCoords - uStep * 3.2308).rgb) * 0.0703;
  gl_FragColor = vec4(c, 1.0);
}
"""

    /**
     * Last pass: the picture at FIT geometry over the background, which is the
     * blurred fill when uUseBlur is set and a flat colour otherwise. Opaque
     * out, because a canvas has no outside.
     */
    const val CANVAS_COMPOSITE = """
precision mediump float;
varying vec2 vTexCoords;
uniform sampler2D uTexSampler;
uniform sampler2D uBackSampler;
uniform vec2 uFitScale;
uniform float uUseBlur;
uniform vec3 uBackColor;
void main() {
  vec2 src = 0.5 + (vTexCoords - 0.5) * uFitScale;
  float inside = step(0.0, src.x) * step(src.x, 1.0) * step(0.0, src.y) * step(src.y, 1.0);
  vec3 fg = texture2D(uTexSampler, src).rgb;
  vec3 bg = mix(uBackColor, texture2D(uBackSampler, vTexCoords).rgb, uUseBlur);
  gl_FragColor = vec4(mix(bg, fg, inside), 1.0);
}
"""
}
