// ambient-contract: 1
const highp float TWO_PI = 6.28318530718;

vec3 hue(float h) { return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }

float ribbon(highp float orbit, highp float head, float width) {
  highp float delta = abs(orbit - head);
  return 1.0 - smoothstep(0.0, width, min(delta, 1.0 - delta));
}

// One comet per recent beat: launched from a beat-specific spot, racing clockwise and fading.
float comet(highp float orbit, float age, float slot) {
  if (age > 1.6) return 0.0;
  highp float start = fract((uBeatCount - slot) * 0.381966);
  highp float head = fract(start + age * 0.55);
  highp float behind = fract(head - orbit);          // 0 at the head, growing along the tail
  float tail = exp(-behind * 9.0) * step(behind, 0.5);
  float fade = 1.0 - age / 1.6;
  return tail * fade * fade;
}

void main() {
  highp vec2 size = vec2(uAspect, 1.0);
  highp vec2 p = size * 0.5 + vec2(vP.x, -vP.y);
  highp vec2 farEdge = size - p;
  highp float d = min(min(p.x, p.y), min(farEdge.x, farEdge.y));

  // Clockwise path around inset rectangles; adjacent sides meet along the diagonals.
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
  // Hue walks slowly around the edge and jumps by the golden ratio on every beat.
  highp float hueBase = fract(uPhase.x / TWO_PI * 0.5 + motion * uBeatCount * 0.618034);
  highp float edge = d / min(size.x, size.y);
  float band = 0.10 + motion * (0.10 * uBass + 0.06 * uPulse);
  float feather = 1.0 - smoothstep(band * 0.35, band + 0.18, edge);

  vec3 rainbow = hue(hueBase + orbit);
  vec3 col = uBase + 0.10 * hue(hueBase + 0.5);                       // soft tinted centre
  col += feather * rainbow * (0.34 + 0.26 * uLevel + motion * 0.30 * uBass);

  // A slow lead orbit keeps movement between beats.
  highp float head = mix(fract(uPhase.x / TWO_PI * 2.0), 0.125, uReduced);
  col += feather * hue(hueBase + head + 0.33) * 0.45 * ribbon(orbit, head, 0.10);

  if (uReduced < 0.5) {
    float c0 = comet(orbit, uBeatAges.x, 0.0);
    float c1 = comet(orbit, uBeatAges.y, 1.0);
    float c2 = comet(orbit, uBeatAges.z, 2.0);
    float c3 = comet(orbit, uBeatAges.w, 3.0);
    vec3 comets = hue(hueBase + 0.15) * c0 + hue(hueBase + 0.40) * c1 + hue(hueBase + 0.65) * c2 + hue(hueBase + 0.90) * c3;
    col += feather * comets * 1.25;
    // Treble sparkle along the band.
    float spark = step(0.985, hash12(floor(vec2(orbit * 220.0, uPhase.z * 6.0))));
    col += feather * spark * uHigh * 0.8 * rainbow;
  }
  fragColor = vec4(finish(col, vP), 1.0);
}
