// ambient-contract: 1
// Each colour follows a part of the music, using the already-smoothed band levels:
// bass pushes a field toward the edges, mids breathe one, treble shimmers one, one drifts as an anchor.
// Safety: no flashes, no hue steps; colours are blended (not added) and each field's brightness swings ≤ ~25%.
// Per-frame values come from chromatic_flow.vert; each pixel only evaluates the four fields.

flat in highp vec4 vC01;
flat in highp vec4 vC23;
flat in highp vec4 vR01;
flat in highp vec4 vR23;
flat in vec4 vW;
flat in vec3 vCol0; flat in vec3 vCol1; flat in vec3 vCol2; flat in vec3 vCol3; flat in vec3 vBg;

float field(highp vec2 p, highp vec2 center, highp vec2 radius) {
  highp vec2 q = (p - center) / radius;
  return exp(-dot(q, q));
}

void main() {
  highp vec2 p = vP / vec2(uAspect, 1.0);
  float w0 = field(p, vC01.xy, vR01.xy) * vW.x;
  float w1 = field(p, vC01.zw, vR01.zw) * vW.y;
  float w2 = field(p, vC23.xy, vR23.xy) * vW.z;
  float w3 = field(p, vC23.zw, vR23.zw) * vW.w;
  float total = w0 + w1 + w2 + w3;
  vec3 hueMix = (vCol0 * w0 + vCol1 * w1 + vCol2 * w2 + vCol3 * w3) / max(total, 0.0001);   // blended: overlaps stay bold
  vec3 light = hueMix * (1.0 - exp(-2.2 * total)) * 0.95;
  float lum = dot(light, vec3(0.299, 0.587, 0.114));
  light = max(mix(vec3(lum), light, 1.3), 0.0);
  light /= max(1.0 - 0.35 * dot(vP, vP), 0.5);                                  // keep the edges bright for Ambilight
  vec3 col = (vBg + light) * (0.94 + 0.10 * (1.0 - 0.88 * uReduced) * uLevel);
  fragColor = vec4(finish(col, vP), 1.0);
}
