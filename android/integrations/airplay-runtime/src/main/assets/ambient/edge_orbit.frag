// ambient-contract: 1
const highp float PI = 3.14159265359;
const highp float TWO_PI = 6.28318530718;
const float RADIUS = 0.30;      // corner radius of the orbit path; removes the mitred "box" seams

// Hand-picked colour path: cyan → violet → magenta → amber, looping.
vec3 palette(highp float t) {
  highp float t4 = fract(t) * 4.0;
  int i = int(floor(t4));
  float f = smoothstep(0.0, 1.0, fract(t4));
  vec3 k0 = vec3(0.10, 0.82, 1.00), k1 = vec3(0.48, 0.32, 1.00), k2 = vec3(1.00, 0.26, 0.74), k3 = vec3(1.00, 0.60, 0.14);
  vec3 a = i == 0 ? k0 : i == 1 ? k1 : i == 2 ? k2 : k3;
  vec3 b = i == 0 ? k1 : i == 1 ? k2 : i == 2 ? k3 : k0;
  return mix(a, b, f);
}

// Clockwise position (0..1) around a rounded rectangle, starting at the top-left of the top edge.
highp float orbitOf(highp vec2 q, highp vec2 b) {
  highp vec2 c = clamp(q, -b, b);
  highp vec2 v = q - c;
  highp float r = RADIUS;
  highp float L = 4.0 * b.x + 4.0 * b.y + TWO_PI * r;
  highp float s;
  if (v.x == 0.0 && v.y == 0.0) {                       // deep inside: nearest straight side (feathered away)
    if (b.y - abs(q.y) < b.x - abs(q.x)) v.y = sign(q.y); else v.x = sign(q.x);
  }
  if (v.y > 0.0 && v.x == 0.0) s = q.x + b.x;
  else if (v.x > 0.0 && v.y > 0.0) s = 2.0 * b.x + atan(v.x, v.y) * r;
  else if (v.x > 0.0 && v.y == 0.0) s = 2.0 * b.x + 0.5 * PI * r + (b.y - q.y);
  else if (v.x > 0.0) s = 2.0 * b.x + 0.5 * PI * r + 2.0 * b.y + atan(-v.y, v.x) * r;
  else if (v.y < 0.0 && v.x == 0.0) s = 2.0 * b.x + PI * r + 2.0 * b.y + (b.x - q.x);
  else if (v.y < 0.0) s = 4.0 * b.x + PI * r + 2.0 * b.y + atan(-v.x, -v.y) * r;
  else if (v.y == 0.0) s = 4.0 * b.x + 1.5 * PI * r + 2.0 * b.y + (q.y + b.y);
  else s = 4.0 * b.x + 1.5 * PI * r + 4.0 * b.y + atan(v.y, -v.x) * r;
  return fract(s / L);
}

// Streak along the orbit: crisp head, long fading tail behind it (direction of travel is clockwise).
float streak(highp float behind) {
  if (behind > 0.85) return exp(-sq((1.0 - behind) / 0.012));   // just ahead of the head
  return exp(-behind * 5.5);
}

// A beat sends a bright wave from the head down the tail; it fades out within 0.7 s.
float wave(highp float behind, float age) {
  if (age > 0.7) return 0.0;
  float amp = sq(1.0 - age / 0.7);
  return amp * exp(-sq((behind - age * 0.55) / 0.03));
}

void main() {
  highp vec2 q = vP;                                   // centred, y up, width = uAspect
  highp vec2 halfSize = vec2(0.5 * uAspect, 0.5);
  highp vec2 b = halfSize - RADIUS;
  highp vec2 e = abs(q) - b;
  highp float outside = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0) - RADIUS;
  highp float d = max(-outside, 0.0);                  // distance in from the rounded border

  highp float hb = uPhase.w / TWO_PI;              // one smooth palette loop per slow clock cycle (~3 min)
  vec3 colA = palette(hb);
  vec3 colB = palette(hb + 0.5);
  vec3 bg = uBase * 0.30 + mix(colA, colB, 0.5) * 0.03;
  // The dark centre (most of the screen) needs none of the orbit work.
  if (d > 0.42) {
    fragColor = vec4(finish(bg * (0.92 + 0.12 * uLevel), vP), 1.0);
    return;
  }

  highp float orbit = orbitOf(q, b);
  float motion = 1.0 - uReduced;
  highp float head = mix(fract(5.0 * uPhase.x / TWO_PI), 0.125, uReduced);   // integer laps per cycle: no jump at wrap

  highp float behindA = fract(head - orbit);
  highp float behindB = fract(head + 0.5 - orbit);
  float sA = streak(behindA);
  float sB = streak(behindB);
  if (uReduced < 0.5) {
    if (behindA < 0.42) sA += wave(behindA, uBeatAges.x) + wave(behindA, uBeatAges.y) + wave(behindA, uBeatAges.z) + wave(behindA, uBeatAges.w);
    if (behindB < 0.42) sB += 0.7 * (wave(behindB, uBeatAges.x) + wave(behindB, uBeatAges.y) + wave(behindB, uBeatAges.z) + wave(behindB, uBeatAges.w));
  }

  // Thin bright core at the border with a soft glow inward; bass breathes the glow, never the core.
  float core = exp(-sq(d / 0.045));
  float glow = exp(-d / (0.11 + motion * 0.05 * uBass));
  float profile = core + 0.55 * glow;
  vec3 rim = mix(colA, colB, 0.5 + 0.5 * cos(TWO_PI * orbit));   // faint colour all round, so the LEDs never go dark

  vec3 light = profile * (colA * sA * 1.55 + colB * sB * 1.25) + rim * glow * 0.24;
  light /= max(1.0 - 0.35 * dot(vP, vP), 0.45);         // undo the shared vignette at the edges Ambilight samples
  vec3 col = (bg + light) * (0.92 + 0.12 * uLevel);
  fragColor = vec4(finish(col, vP), 1.0);
}
