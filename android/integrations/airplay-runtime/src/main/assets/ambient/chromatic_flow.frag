// ambient-contract: 1
const highp float TWO_PI = 6.28318530718;

// Same bold path as Edge Orbit: cyan → violet → magenta → amber, looping.
vec3 palette(highp float t) {
  highp float t4 = fract(t) * 4.0;
  int i = int(floor(t4));
  float f = smoothstep(0.0, 1.0, fract(t4));
  vec3 k0 = vec3(0.00, 0.80, 1.00), k1 = vec3(0.42, 0.08, 1.00), k2 = vec3(1.00, 0.02, 0.62), k3 = vec3(1.00, 0.48, 0.00);
  vec3 a = i == 0 ? k0 : i == 1 ? k1 : i == 2 ? k2 : k3;
  vec3 b = i == 0 ? k1 : i == 1 ? k2 : i == 2 ? k3 : k0;
  return mix(a, b, f);
}

float field(vec2 p, vec2 center, vec2 radius) {
  vec2 q = (p - center) / radius;
  return exp(-dot(q, q));
}

// Each colour follows a part of the music, using the already-smoothed band levels:
// bass pushes a field toward the edges, mids breathe one, treble shimmers one, one drifts as an anchor.
// Safety: no flashes, no hue steps; colours are blended (not added) and each field's brightness swings ≤ ~25%.
void main() {
  highp vec2 p = vP / vec2(uAspect, 1.0);
  float motion = 1.0 - 0.88 * uReduced;
  highp float hb = uPhase.w / TWO_PI;
  float bass = motion * uBass;
  float mid = motion * clamp((uMid - 0.3) / 0.7, 0.0, 1.0);
  float high = motion * uHigh;

  vec2 cBass = vec2(-0.30, 0.12) + motion * vec2(0.08 * sin(uPhase.x), 0.10 * cos(uPhase.y));
  cBass += normalize(cBass + vec2(0.0001)) * 0.16 * bass;                       // kick pushes it outward
  vec2 cMid = vec2(0.30, 0.26) + motion * vec2(0.10 * cos(uPhase.y + 0.8), 0.08 * sin(uPhase.z));
  vec2 cHigh = vec2(0.24, -0.30) + motion * vec2(0.12 * sin(uPhase.z + 1.7), 0.08 * cos(uPhase.x))
      + high * 0.025 * vec2(sin(7.0 * uPhase.z), cos(5.0 * uPhase.y));       // gentle shimmer
  vec2 cAnchor = vec2(-0.28, -0.28) + motion * vec2(0.08 * cos(uPhase.w + 2.3), 0.09 * sin(uPhase.y + 0.4));

  float wBass = field(p, cBass, vec2(0.34, 0.40) * (1.0 + 0.35 * bass)) * (0.75 + 0.25 * bass);
  float wMid = field(p, cMid, vec2(0.36, 0.32) * (1.0 + 0.25 * mid)) * (0.78 + 0.22 * mid);
  float wHigh = field(p, cHigh, vec2(0.34, 0.30) * (1.0 + 0.20 * high)) * (0.78 + 0.22 * high);
  float wAnchor = field(p, cAnchor, vec2(0.32, 0.36)) * 0.80;

  vec3 cB = palette(hb + 0.50), cM = palette(hb + 0.25), cH = palette(hb), cA = palette(hb + 0.75);
  float total = wBass + wMid + wHigh + wAnchor;
  vec3 hueMix = (cB * wBass + cM * wMid + cH * wHigh + cA * wAnchor) / max(total, 0.0001);   // blended: overlaps stay bold
  float coverage = 1.0 - exp(-2.2 * total);

  vec3 col = uBase * 0.15 + mix(cB, cH, 0.5) * 0.03;
  vec3 light = hueMix * coverage * 0.95;
  float lum = dot(light, vec3(0.299, 0.587, 0.114));
  light = max(mix(vec3(lum), light, 1.3), 0.0);
  light /= max(1.0 - 0.35 * dot(vP, vP), 0.5);                                  // keep the edges bright for Ambilight
  col += light;
  col *= 0.94 + 0.10 * motion * uLevel;
  fragColor = vec4(finish(col, vP), 1.0);
}
