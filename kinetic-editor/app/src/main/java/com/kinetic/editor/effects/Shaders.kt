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
precision mediump float;
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

  // Zoom-punch warps sampling coords: scale peaks at the cut point (p = 0.5).
  if (uTransType > 2.5) {
    float bump = 1.0 - abs(1.0 - 2.0 * p);      // 0 -> 1 -> 0
    float s = 1.0 + 0.8 * bump * bump;
    uv = 0.5 + (uv - 0.5) / s;
  }

  vec3 c = texture2D(uTexSampler, uv).rgb;

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
    float coverage = 1.0 - abs(1.0 - 2.0 * p);  // 0 -> 1 -> 0
    c *= smoothstep(coverage - 0.06, coverage + 0.06, uv.x);
  } else if (uTransType > 2.5) {
    // Zoom-punch adds a subtle exposure dip so the warp reads as intentional.
    c *= 1.0 - 0.25 * (1.0 - abs(1.0 - 2.0 * p));
  }

  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
