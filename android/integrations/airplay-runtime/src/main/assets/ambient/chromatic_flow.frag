// ambient-contract: 1
const highp float TWO_PI = 6.28318530718;

vec3 hue(float h) { return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }

float field(vec2 p, vec2 center, vec2 radius) {
  vec2 q = (p - center) / radius;
  return 1.0 - smoothstep(0.04, 1.25, dot(q, q));
}

void main() {
  highp vec2 p = vP / vec2(uAspect, 1.0);
  float motion = 1.0 - uReduced;
  // Hues rotate slowly and step on every beat, so each beat recolours the room.
  highp float hb = fract(uPhase.x / TWO_PI * 0.2 + motion * uBeatCount * 0.137);
  float swell = 1.0 + motion * (0.28 * uBass + 0.22 * uPulse);

  vec2 c0 = vec2(-0.36, 0.10) + motion * vec2(0.10 * sin(uPhase.x), 0.12 * cos(uPhase.y));
  vec2 c1 = vec2(0.30, 0.30) + motion * vec2(0.12 * cos(uPhase.y + 0.8), 0.10 * sin(uPhase.z));
  vec2 c2 = vec2(0.24, -0.34) + motion * vec2(0.14 * sin(uPhase.z + 1.7), 0.09 * cos(uPhase.x));
  vec2 c3 = vec2(-0.30, -0.30) + motion * vec2(0.10 * cos(uPhase.w + 2.3), 0.11 * sin(uPhase.y + 0.4));

  vec3 col = uBase + 0.08 * hue(hb + 0.5);
  col += 0.62 * hue(hb) * field(p, c0, swell * vec2(0.56, 0.62));
  col += 0.58 * hue(hb + 0.25) * field(p, c1, swell * vec2(0.60, 0.50));
  col += 0.62 * hue(hb + 0.50) * field(p, c2, swell * vec2(0.64, 0.52));
  col += 0.52 * hue(hb + 0.75) * field(p, c3, swell * vec2(0.52, 0.58));

  if (uReduced < 0.5) {
    // Each beat flashes colour outward to the edges, where Ambilight samples.
    float flash = exp(-uBeatAges.x * 4.5);
    float r = length(p * vec2(1.0, 1.6));
    float edges = smoothstep(0.20, 0.55, r);
    col += flash * edges * hue(hb + 0.5) * (0.45 + 0.35 * uBass);
    col += uHigh * 0.10 * hue(hb + 0.85) * edges;
  }
  col *= 0.90 + 0.25 * uLevel;
  fragColor = vec4(finish(col, vP), 1.0);
}
