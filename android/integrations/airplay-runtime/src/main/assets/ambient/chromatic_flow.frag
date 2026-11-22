// ambient-contract: 1
float field(vec2 p, vec2 center, vec2 radius) {
  vec2 q = (p - center) / radius;
  return 1.0 - smoothstep(0.04, 1.25, dot(q, q));
}

void main() {
  highp vec2 p = vP / vec2(uAspect, 1.0);
  float motion = 1.0 - 0.88 * uReduced;
  float swell = 1.0 + motion * (0.10 * uBass + 0.04 * uPulse);

  vec2 mintCenter = vec2(-0.38, 0.04)
      + motion * vec2(0.09 * sin(uPhase.x), 0.12 * cos(uPhase.y));
  vec2 amberCenter = vec2(0.26, 0.35)
      + motion * vec2(0.12 * cos(uPhase.y + 0.8), 0.10 * sin(uPhase.z));
  vec2 indigoCenter = vec2(0.19, -0.37)
      + motion * vec2(0.15 * sin(uPhase.z + 1.7), 0.09 * cos(uPhase.x));

  vec3 col = uBase + 0.12 * uPalette[2];
  col += 0.66 * mix(uPalette[2], uPalette[0], 0.62)
      * field(p, mintCenter, swell * vec2(0.54, 0.64));
  col += 0.56 * uPalette[1] * field(p, amberCenter, swell * vec2(0.62, 0.48));
  col += 0.70 * vec3(0.13, 0.14, 0.46) * field(p, indigoCenter, swell * vec2(0.67, 0.52));
  col *= 0.94 + (0.04 + 0.10 * motion) * uLevel;
  fragColor = vec4(finish(col, vP), 1.0);
}
