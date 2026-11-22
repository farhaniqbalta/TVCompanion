// ambient-contract: 1
// Per-frame values for Chromatic Flow: field centres, sizes, strengths and colours, computed 4 times per frame.
const highp float TWO_PI = 6.28318530718;

flat out highp vec4 vC01;       // centres of the bass and mid fields
flat out highp vec4 vC23;       // centres of the treble and anchor fields
flat out highp vec4 vR01;       // radii (x, y) of the bass and mid fields
flat out highp vec4 vR23;
flat out vec4 vW;               // field strengths
flat out vec3 vCol0; flat out vec3 vCol1; flat out vec3 vCol2; flat out vec3 vCol3; flat out vec3 vBg;

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

void main() {
  vP = vec2(aPos.x * 0.5 * uAspect, aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0);
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
  vC01 = vec4(cBass, cMid);
  vC23 = vec4(cHigh, cAnchor);
  vR01 = vec4(vec2(0.34, 0.40) * (1.0 + 0.35 * bass), vec2(0.36, 0.32) * (1.0 + 0.25 * mid));
  vR23 = vec4(vec2(0.34, 0.30) * (1.0 + 0.20 * high), vec2(0.32, 0.36));
  vW = vec4(0.75 + 0.25 * bass, 0.78 + 0.22 * mid, 0.78 + 0.22 * high, 0.80);
  vCol0 = palette(hb + 0.50); vCol1 = palette(hb + 0.25); vCol2 = palette(hb); vCol3 = palette(hb + 0.75);
  vBg = uBase * 0.15 + mix(vCol0, vCol2, 0.5) * 0.03;
}
