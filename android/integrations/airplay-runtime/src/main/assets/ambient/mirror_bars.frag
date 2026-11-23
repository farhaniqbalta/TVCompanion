// ambient-contract: 1
// 32 equalizer bars mirrored about the centre (bass in the middle, highs at the sides), each growing up and down
// from the centre line. Every beat kicks them toward the top and bottom edges, where Ambilight samples, so the strip
// pulses with the beat. Beats change height only, never brightness: no flashes.
const highp float TWO_PI = 6.28318530718;
const float CELLS = 16.0;       // bars per half screen
const float FILL = 0.62;        // bar width as a share of its cell
const float EDGE = 0.5;         // screen edge in vP units

vec3 palette(highp float t) {   // Bounce's cyan -> violet -> magenta -> amber loop
  highp float t4 = fract(t) * 4.0;
  int i = int(floor(t4));
  float f = smoothstep(0.0, 1.0, fract(t4));
  vec3 k0 = vec3(0.00, 0.80, 1.00), k1 = vec3(0.42, 0.08, 1.00), k2 = vec3(1.00, 0.02, 0.62), k3 = vec3(1.00, 0.48, 0.00);
  vec3 a = i == 0 ? k0 : i == 1 ? k1 : i == 2 ? k2 : k3;
  vec3 b = i == 0 ? k1 : i == 1 ? k2 : i == 2 ? k3 : k0;
  return mix(a, b, f);
}

float band(int i) {             // no dynamic component indexing (Mali driver safety)
  return dot(uBands[i / 4], vec4(equal(ivec4(i - (i / 4) * 4), ivec4(0, 1, 2, 3))));
}

void main() {
  highp vec2 q = vP;
  highp float halfW = 0.5 * uAspect;
  highp float a = min(abs(q.x) / halfW, 0.9999);          // 0 at centre, 1 at the side edges
  int i = int(floor(a * CELLS));
  highp float cellW = halfW / CELLS;
  highp float dx = (fract(a * CELLS) - 0.5) * cellW;      // offset from this bar's axis
  float motion = 1.0 - uReduced;
  float ai = (float(i) + 0.5) / CELLS;

  // Height: band level, plus a beat kick that decays in ~170 ms; bass bars kick hardest. Never fully empty.
  float level = smoothstep(0.20, 0.95, band(i));
  float kick = motion * mix(0.30, 0.16, ai) * exp(-6.0 * uBeatAges.x);
  float h = min(0.05 + 0.30 * level + kick, EDGE + 0.02);

  // Rounded bar: distance to a capsule of half-width hw spanning |y| < h.
  float hw = 0.5 * FILL * cellW;
  highp vec2 e = vec2(abs(dx), abs(q.y)) - vec2(0.0, max(h - hw, 0.0));
  float d = length(max(e, 0.0)) - hw;                     // < 0 inside the bar

  highp float hue = ai * 0.75 + uPhase.w / TWO_PI;        // whole cycles of uPhase only: no jump at wrap
  vec3 c = palette(hue);
  float along = clamp(abs(q.y) / max(h, 0.001), 0.0, 1.0);
  vec3 body = c * mix(0.22, 1.05, along * along) * (1.0 - smoothstep(-0.0015, 0.0015, d));
  vec3 glow = c * 0.35 * exp(-max(d, 0.0) / 0.018) * smoothstep(0.4, 1.0, along);   // soft halo round the tips

  // Faint rim and centre line: Ambilight must not read short-bar frames as letterbox bars or a blank screen.
  highp float dEdge = min(halfW - abs(q.x), EDGE - abs(q.y));
  vec3 col = uBase * 0.10 + palette(uPhase.w / TWO_PI) * (0.030 * (1.0 - smoothstep(0.0, 0.02, dEdge))
                                                          + 0.05 * exp(-abs(q.y) / 0.006));
  vec3 light = body + glow;
  light /= max(1.0 - 0.35 * dot(vP, vP), 0.45);            // undo finish()'s vignette so edge tips stay bright
  col += light;
  fragColor = vec4(finish(col, vP), 1.0);
}
