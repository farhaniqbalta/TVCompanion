// ambient-contract: 1
// Per-frame values (hits, colours, ball) come from bounce.vert; each pixel only measures distances.
const float DEPTH_SIDE = 0.11;  // how far a side splash glows inward (Ambilight samples ~0.21 deep at the sides)
const float DEPTH_CAP = 0.08;   // top/bottom (~0.12 sampled)

flat in highp vec4 vBall;
flat in highp vec4 vTrailA;
flat in highp vec2 vTrailB;
flat in highp vec4 vSide0;
flat in highp vec4 vSide1;
flat in highp vec4 vCap0;
flat in highp vec4 vCap1;
flat in vec3 vSideCol0; flat in vec3 vSideCol1; flat in vec3 vCapCol0; flat in vec3 vCapCol1; flat in vec3 vTint;

float along(highp float coord, highp vec4 s) { return s.w * exp(-sq((coord - s.x) / s.y)); }

void main() {
  highp vec2 q = vP;
  highp vec2 halfSize = vec2(0.5 * uAspect, 0.5);
  highp float dX = halfSize.x - abs(q.x);
  highp float dY = halfSize.y - abs(q.y);
  highp float dBall = length(q - vBall.xy);

  // Faint rim: keeps Ambilight from reading the black edges as letterbox bars or a blank screen.
  vec3 col = uBase * 0.10 + vCapCol0 * 0.030 * (1.0 - smoothstep(0.0, 0.02, min(dX, dY)));

  // Far from every wall and from the ball, all glows below have already faded to exactly zero.
  if (dX > 0.42 && dY > 0.30 && dBall > 0.16) {
    fragColor = vec4(finish(col, vP), 1.0);
    return;
  }

  // Long, gradual fades (not a cut-off) so splashes melt into the black.
  float sideFade = exp(-dX / DEPTH_SIDE) * smoothstep(0.42, 0.05, dX);
  float capFade = exp(-dY / DEPTH_CAP) * smoothstep(0.30, 0.04, dY);
  float right = step(0.0, q.x), top = step(0.0, q.y);
  vec3 light = sideFade * (vSideCol0 * along(q.y, vSide0) * (vSide0.z > 0.0 ? right : 1.0 - right)
                         + vSideCol1 * along(q.y, vSide1) * (vSide1.z > 0.0 ? right : 1.0 - right))
             + capFade * (vCapCol0 * along(q.x, vCap0) * (vCap0.z > 0.0 ? top : 1.0 - top)
                        + vCapCol1 * along(q.x, vCap1) * (vCap1.z > 0.0 ? top : 1.0 - top));

  // Ball: white-hot core, a tight halo that fades out on its own, and a short trail.
  float size = vBall.z;
  float ball = exp(-sq(dBall / size)) + vBall.w * exp(-dBall / 0.032)
      + 0.22 * exp(-sq(length(q - vTrailA.xy) / (size * 0.9)))
      + 0.14 * exp(-sq(length(q - vTrailA.zw) / (size * 0.9)))
      + 0.06 * exp(-sq(length(q - vTrailB) / (size * 0.9)));
  light += vTint * ball * smoothstep(0.16, 0.05, dBall);

  light /= max(1.0 - 0.35 * dot(vP, vP), 0.45);              // keep edge splashes bright for Ambilight
  col += light * 1.3;
  fragColor = vec4(finish(col, vP), 1.0);
}
