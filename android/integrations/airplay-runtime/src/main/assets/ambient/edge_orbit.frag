// ambient-contract: 1
const highp float PI = 3.14159265359;
const highp float TWO_PI = 6.28318530718;
const float RADIUS = 0.30;      // corner radius of the orbit path; removes the mitred "box" seams
const float REACH = 0.22;       // light ends here, hugging the edge (Ambilight samples ~0.12 top/bottom, ~0.21 sides)

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

// Beat wave at a precomputed position along the tail (amplitude 0 once a beat is older than 0.7 s).
float wave(highp float behind, float pos, float amp) { return amp * exp(-sq((behind - pos) / 0.03)); }

flat in vec3 vA; flat in vec3 vB; flat in vec3 vRimA; flat in vec3 vRimB; flat in vec3 vBg;
flat in highp vec4 vMisc;       // x head, y glow depth
flat in vec4 vWaveAmp;          // per recent beat
flat in vec4 vWavePos;

void main() {
  highp vec2 q = vP;                                   // centred, y up, width = uAspect
  highp vec2 halfSize = vec2(0.5 * uAspect, 0.5);
  highp vec2 b = halfSize - RADIUS;
  highp vec2 e = abs(q) - b;
  highp float outside = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0) - RADIUS;
  highp float d = max(-outside, 0.0);                  // distance in from the rounded border
  // The dark centre (most of the screen) needs none of the orbit work.
  if (d > REACH) {
    fragColor = vec4(finish(vBg, vP), 1.0);
    return;
  }

  highp float orbit = orbitOf(q, b);
  highp float behindA = fract(vMisc.x - orbit);
  highp float behindB = fract(vMisc.x + 0.5 - orbit);
  float sA = streak(behindA);
  float sB = streak(behindB);
  if (behindA < 0.42) sA += wave(behindA, vWavePos.x, vWaveAmp.x) + wave(behindA, vWavePos.y, vWaveAmp.y)
                          + wave(behindA, vWavePos.z, vWaveAmp.z) + wave(behindA, vWavePos.w, vWaveAmp.w);
  if (behindB < 0.42) sB += 0.7 * (wave(behindB, vWavePos.x, vWaveAmp.x) + wave(behindB, vWavePos.y, vWaveAmp.y)
                                 + wave(behindB, vWavePos.z, vWaveAmp.z) + wave(behindB, vWavePos.w, vWaveAmp.w));

  // Thin bright core at the border with a soft glow inward; bass breathes the glow, never the core.
  float core = exp(-sq(d / 0.045));
  float glow = exp(-d / vMisc.y);
  float rimMix = 0.5 + 0.5 * cos(TWO_PI * orbit);      // faint colour all round, so the LEDs never go dark
  vec3 light = (core + 0.55 * glow) * (vA * sA + vB * sB) + glow * mix(vRimA, vRimB, rimMix);
  light *= smoothstep(REACH, REACH - 0.10, d);          // soft fade to zero at the boundary: no visible line
  light /= max(1.0 - 0.35 * dot(vP, vP), 0.45);         // undo the shared vignette at the edges Ambilight samples
  fragColor = vec4(finish(vBg + light, vP), 1.0);
}
