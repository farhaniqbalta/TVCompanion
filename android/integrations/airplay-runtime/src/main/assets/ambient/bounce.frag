// ambient-contract: 1
const highp float PI = 3.14159265359;
const highp float TWO_PI = 6.28318530718;
const float R = 0.035;          // ball radius
const float KX = 17.0;          // wall hits per half clock cycle (integers: the path closes, no jump at wrap)
const float KY = 23.0;

vec3 palette(highp float t) {
  highp float t4 = fract(t) * 4.0;
  int i = int(floor(t4));
  float f = smoothstep(0.0, 1.0, fract(t4));
  vec3 k0 = vec3(0.00, 0.80, 1.00), k1 = vec3(0.42, 0.08, 1.00), k2 = vec3(1.00, 0.02, 0.62), k3 = vec3(1.00, 0.48, 0.00);
  vec3 a = i == 0 ? k0 : i == 1 ? k1 : i == 2 ? k2 : k3;
  vec3 b = i == 0 ? k1 : i == 1 ? k2 : i == 2 ? k3 : k0;
  return mix(a, b, f);
}

highp float tri(highp float u) { return abs(fract(u * 0.5) * 2.0 - 1.0); }   // 1 at even u, 0 at odd: a wall hit at every integer

highp vec2 ballAt(highp float ux, highp vec2 room) {
  return vec2((tri(ux) - 0.5) * room.x, (tri(ux * KY / KX) - 0.5) * room.y);
}

// Light spreading along a wall from an impact point, rising softly and fading over ~1.5 bounces.
float splash(highp float along, highp float hit, highp float wallDist, highp float age, float widen) {
  if (age > 1.6) return 0.0;
  float rise = smoothstep(0.0, 0.08, age);
  float fade = sq(1.0 - age / 1.6);
  float spread = 0.10 + 0.22 * age + widen;
  return rise * fade * exp(-sq((along - hit) / spread)) * exp(-wallDist / 0.13);
}

void main() {
  highp vec2 q = vP;                                         // centred, y up, width = uAspect
  highp vec2 halfSize = vec2(0.5 * uAspect, 0.5);
  highp vec2 room = 2.0 * (halfSize - R);
  highp float theta = uPhase.x;                              // runs faster when the music is louder
  highp float ux = KX * theta / PI;
  highp float uy = KY * theta / PI;
  highp float hb = uPhase.w / TWO_PI;
  float motion = 1.0 - uReduced;

  highp vec2 ball = ballAt(ux, room);
  highp float dBall = length(q - ball);
  highp float dWall = min(halfSize.x - abs(q.x), halfSize.y - abs(q.y));

  // Faint rim: keeps Ambilight from reading the black edges as letterbox bars or a blank screen.
  vec3 col = uBase * 0.10 + palette(hb) * 0.030 * (1.0 - smoothstep(0.0, 0.02, dWall));

  // Most of the screen is black: only the ball's neighbourhood and the edge band do any work.
  if (dWall > 0.36 && dBall > 0.22) {
    fragColor = vec4(finish(col, vP), 1.0);
    return;
  }

  float widen = motion * 0.06 * uBass;
  float beat = motion * 0.35 * exp(-uBeatAges.x * 3.0);
  vec3 light = vec3(0.0);
  // Side walls: the last two hits. Even counts hit the right wall, odd the left.
  for (int k = 0; k < 2; k++) {
    highp float n = floor(ux) - float(k);
    highp float age = ux - n;
    float side = mod(n, 2.0) < 0.5 ? 1.0 : -1.0;
    if (q.x * side > 0.0) {
      highp float hitY = (tri(n * KY / KX) - 0.5) * room.y;
      float s = splash(q.y, hitY, halfSize.x - abs(q.x), age, widen) * (1.0 + (k == 0 ? beat : 0.0));
      light += palette(hb + n * 3.0 / (2.0 * KX)) * s;     // whole palette loops per clock cycle: no jump at wrap
    }
  }
  // Top and bottom walls. Even counts hit the top.
  for (int k = 0; k < 2; k++) {
    highp float n = floor(uy) - float(k);
    highp float age = uy - n;
    float side = mod(n, 2.0) < 0.5 ? 1.0 : -1.0;
    if (q.y * side > 0.0) {
      highp float hitX = (tri(n * KX / KY) - 0.5) * room.x;
      float s = splash(q.x, hitX, halfSize.y - abs(q.y), age, widen) * (1.0 + (k == 0 ? beat : 0.0));
      light += palette(hb + 0.5 + n * 4.0 / (2.0 * KY)) * s;
    }
  }

  // The ball: white-hot core tinted by the current colour, a soft halo, and a short trail.
  vec3 tint = mix(vec3(1.0), palette(hb + floor(ux) * 3.0 / (2.0 * KX)), 0.35);
  float size = R * (1.0 + motion * 0.25 * uPulse);
  float ballLight = exp(-sq(dBall / size)) + 0.30 * exp(-dBall / 0.07);
  for (int k = 1; k <= 3; k++) {
    highp float back = float(k) * 0.035 * motion;
    ballLight += (0.30 - 0.08 * float(k)) * exp(-sq(length(q - ballAt(ux - back, room)) / (size * 0.9)));
  }
  light += tint * ballLight;

  light /= max(1.0 - 0.35 * dot(vP, vP), 0.45);              // keep edge splashes bright for Ambilight
  col += light * 1.3;
  fragColor = vec4(finish(col, vP), 1.0);
}
