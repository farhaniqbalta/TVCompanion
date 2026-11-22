// ambient-contract: 1
const highp float TWO_PI = 6.28318530718;

vec3 hue(float h) { return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }

float ribbon(highp float orbit, highp float head, float width) {
  highp float delta = abs(orbit - head);
  return 1.0 - smoothstep(0.02, width, min(delta, 1.0 - delta));
}

void main() {
  highp vec2 size = vec2(uAspect, 1.0);
  highp vec2 p = size * 0.5 + vec2(vP.x, -vP.y);
  highp vec2 farEdge = size - p;
  highp float d = min(min(p.x, p.y), min(farEdge.x, farEdge.y));

  // Adjacent sides meet along the diagonals of each inset rectangle.
  highp vec2 inner = size - 2.0 * d;
  highp float path;
  if (d == p.y) {
    path = p.x - d;
  } else if (d == farEdge.x) {
    path = inner.x + p.y - d;
  } else if (d == farEdge.y) {
    path = inner.x + inner.y + farEdge.x - d;
  } else {
    path = 2.0 * inner.x + inner.y + farEdge.y - d;
  }
  highp float orbit = clamp(path / max(2.0 * (inner.x + inner.y), 0.001), 0.0, 1.0);

  float motion = 1.0 - uReduced;
  // The beat nudges the lead forward on the smoothed pulse envelope, then it settles back into the orbit.
  highp float head = mix(fract(4.0 * uPhase.x / TWO_PI + motion * 0.035 * uPulse), 0.125, uReduced);

  // Complementary ribbon colours drift continuously; uPhase already runs faster when the music is louder.
  highp float hueBase = fract(uPhase.y / TWO_PI * 0.35);
  vec3 leadCol = mix(hue(hueBase), vec3(1.0), 0.12);
  vec3 trailCol = mix(hue(hueBase + 0.45), vec3(1.0), 0.18);

  highp float edge = d / min(size.x, size.y);
  float feather = 1.0 - smoothstep(0.06, 0.28 + motion * 0.06 * uBass, edge);
  float lead = ribbon(orbit, head, 0.15 + motion * (0.10 * uBass + 0.05 * uPulse));
  float trail = ribbon(orbit, fract(head + 0.4), 0.13 + motion * 0.08 * uBass);
  float glow = 0.80 + motion * 0.22 * uPulse;   // gentle, smoothed brightening on the beat

  highp vec2 hazePos = vP / size;
  vec3 col = uBase + mix(uPalette[2], hue(hueBase + 0.2), 0.5) * (0.08 + 0.14 * (1.0 - smoothstep(0.0, 0.38, dot(hazePos, hazePos))));
  col += feather * (leadCol * (0.78 * lead * glow) + trailCol * (0.46 * trail));
  col += feather * 0.10 * hue(hueBase + 0.2) * (0.5 + 0.5 * uLevel);   // faint coloured rim between ribbons
  col *= 0.96 + mix(0.10, 0.035, uReduced) * uLevel;
  fragColor = vec4(finish(col, vP), 1.0);
}
