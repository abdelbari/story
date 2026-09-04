package com.kinetic.editor.effects

/**
 * One fragment shader serves color grading, LUTs, and boundary transitions for
 * BOTH the real-time preview and the Transformer export — identical math, so
 * what you see is exactly what renders.
 *
 * Transitions are single-stream by design (like most CapCut transitions): the
 * outgoing clip animates through phase [0, 0.5], the incoming clip through
 * [0.5, 1]. No second decoder, no pre-rendered overlap, works inside one
 * EditedMediaItemSequence.
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
// The print, rather than the scene: grain and vignette live on the frame, so
// they do not zoom or pan with the clip transform above.
uniform float uGrain;
uniform float uGrainSeed;
uniform float uVignette;
// Chroma key. Tolerance 0 disables it, so an unkeyed clip costs one compare.
uniform vec3 uKeyColor;
uniform float uKeyTolerance;
uniform float uKeySoftness;

// Cheap hash noise. Deterministic per pixel per frame, which is what makes
// grain sit still within a frame and dance between them, the way film does.
float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
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

void main() {
  vec2 uv = vTexCoords;
  float p = clamp(uTransProgress, 0.0, 1.0);

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
  uv = q + 0.5;

  // Zoom-punch warps sampling coords: scale peaks at the cut point (p = 0.5).
  if (uTransType > 2.5) {
    float bump = 1.0 - abs(1.0 - 2.0 * p);      // 0 -> 1 -> 0
    float s = 1.0 + 0.8 * bump * bump;
    uv = 0.5 + (uv - 0.5) / s;
  }

  vec4 texel = texture2D(uTexSampler, uv);
  vec3 c = texel.rgb;

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

  // Anything the transform moved off the source is black, not a smeared edge
  // texel: zooming out or panning past the border letterboxes cleanly. Applied
  // last so a brightened grade cannot lift the surround off black.
  // Transparent rather than black, so a zoomed-out or keyed overlay reveals
  // what is behind it. The compositor blends on straight alpha, so the colour
  // is left unmultiplied.
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), alpha * inside);
}
"""
}
