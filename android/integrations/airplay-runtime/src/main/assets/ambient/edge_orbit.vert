// ambient-contract: 1
// Per-frame values for Edge Orbit: computed 4 times per frame instead of once per pixel.
const highp float TWO_PI = 6.28318530718;

flat out vec3 vA; flat out vec3 vB; flat out vec3 vRimA; flat out vec3 vRimB; flat out vec3 vBg;
flat out highp vec4 vMisc;      // x head, y glow depth
flat out vec4 vWaveAmp;
flat out vec4 vWavePos;

// Hand-picked colour path: cyan → violet → magenta → amber, looping.
vec3 palette(highp float t) {
  highp float t4 = fract(t) * 4.0;
  int i = int(floor(t4));
  float f = smoothstep(0.0, 1.0, fract(t4));
  vec3 k0 = vec3(0.00, 0.80, 1.00), k1 = vec3(0.42, 0.08, 1.00), k2 = vec3(1.00, 0.02, 0.62), k3 = vec3(1.00, 0.48, 0.00);   // bold, near-pure hues
  vec3 a = i == 0 ? k0 : i == 1 ? k1 : i == 2 ? k2 : k3;
  vec3 b = i == 0 ? k1 : i == 1 ? k2 : i == 2 ? k3 : k0;
  return mix(a, b, f);
}

// Saturation boost is linear, so it can be applied to the colours once instead of to every pixel.
vec3 boost(vec3 c) { return max(mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, 1.4), 0.0); }
float waveAmp(float age) { return age > 0.7 ? 0.0 : sq(1.0 - age / 0.7); }

void main() {
  vP = vec2(aPos.x * 0.5 * uAspect, aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0);
  float motion = 1.0 - uReduced;
  float level = 0.92 + 0.12 * uLevel;
  highp float hb = uPhase.w / TWO_PI;              // one smooth palette loop per slow clock cycle (~3 min)
  vec3 colA = palette(hb);
  vec3 colB = palette(hb + 0.5);
  vec3 satA = boost(colA), satB = boost(colB);
  vA = satA * 1.25 * level;
  vB = satB * 1.05 * level;
  vRimA = satA * 0.24 * level;
  vRimB = satB * 0.24 * level;
  vBg = (uBase * 0.15 + mix(colA, colB, 0.5) * 0.025) * level;
  highp float head = mix(fract(5.0 * uPhase.x / TWO_PI), 0.125, uReduced);   // integer laps per cycle: no jump at wrap
  vMisc = vec4(head, 0.11 + motion * 0.05 * uBass, 0.0, 0.0);
  // Unrolled on purpose: no dynamic indexing of outputs (Mali driver safety).
  vWaveAmp = motion * vec4(waveAmp(uBeatAges.x), waveAmp(uBeatAges.y), waveAmp(uBeatAges.z), waveAmp(uBeatAges.w));
  vWavePos = uBeatAges * 0.55;
}
