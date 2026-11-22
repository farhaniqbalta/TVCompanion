// ambient-contract: 1
const highp float TWO_PI = 6.28318530718;

vec3 hue(float h) { return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }

float field(vec2 p, vec2 center, vec2 radius) {
  vec2 q = (p - center) / radius;
  return 1.0 - smoothstep(0.04, 1.25, dot(q, q));
}

// Calm by design: colours drift continuously, never step or flash; audio only moves size and a little brightness.
void main() {
  highp vec2 p = vP / vec2(uAspect, 1.0);
  float motion = 1.0 - 0.88 * uReduced;
  highp float hb = uPhase.w / TWO_PI;   // whole cycles only, so the hue never jumps when the clock wraps
  float swell = 1.0 + motion * (0.12 * uBass + 0.05 * uPulse);

  vec2 c0 = vec2(-0.36, 0.08) + motion * vec2(0.09 * sin(uPhase.x), 0.12 * cos(uPhase.y));
  vec2 c1 = vec2(0.28, 0.33) + motion * vec2(0.12 * cos(uPhase.y + 0.8), 0.10 * sin(uPhase.z));
  vec2 c2 = vec2(0.22, -0.35) + motion * vec2(0.14 * sin(uPhase.z + 1.7), 0.09 * cos(uPhase.x));
  vec2 c3 = vec2(-0.30, -0.30) + motion * vec2(0.09 * cos(uPhase.w + 2.3), 0.10 * sin(uPhase.y + 0.4));

  vec3 col = uBase + 0.07 * mix(hue(hb + 0.5), vec3(0.6), 0.4);
  col += 0.58 * mix(hue(hb), vec3(0.6), 0.22) * field(p, c0, swell * vec2(0.56, 0.62));
  col += 0.52 * mix(hue(hb + 0.25), vec3(0.6), 0.22) * field(p, c1, swell * vec2(0.60, 0.50));
  col += 0.56 * mix(hue(hb + 0.50), vec3(0.6), 0.22) * field(p, c2, swell * vec2(0.64, 0.52));
  col += 0.48 * mix(hue(hb + 0.75), vec3(0.6), 0.22) * field(p, c3, swell * vec2(0.52, 0.58));
  col *= 0.94 + 0.12 * motion * uLevel;
  fragColor = vec4(finish(col, vP), 1.0);
}
