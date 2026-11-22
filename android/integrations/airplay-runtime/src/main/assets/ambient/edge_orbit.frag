// ambient-contract: 1
const highp float TWO_PI = 6.28318530718;

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
  highp float head = mix(fract(4.0 * uPhase.x / TWO_PI), 0.125, uReduced);

  highp float edge = d / min(size.x, size.y);
  float feather = 1.0 - smoothstep(0.06, 0.28, edge);
  float accent = (1.0 - uReduced) * (0.012 * uBass + 0.010 * uPulse);
  float lead = ribbon(orbit, head, 0.15 + accent);
  float trail = ribbon(orbit, fract(head + 0.4), 0.13 + accent);

  highp vec2 hazePos = vP / size;
  vec3 col = uBase + uPalette[2] * (0.09 + 0.16 * (1.0 - smoothstep(0.0, 0.38, dot(hazePos, hazePos))));
  col += feather * (mix(uPalette[0], uPalette[2], 0.30) * (0.76 * lead) + uPalette[1] * (0.39 * trail));
  col *= 0.96 + mix(0.10, 0.035, uReduced) * uLevel;
  fragColor = vec4(finish(col, vP), 1.0);
}
