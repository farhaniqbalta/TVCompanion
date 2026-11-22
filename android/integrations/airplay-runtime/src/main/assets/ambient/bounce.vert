// ambient-contract: 1
// Everything here is the same for the whole frame, so it runs 4 times per frame instead of once per pixel.
const highp float PI = 3.14159265359;
const highp float TWO_PI = 6.28318530718;
const float R = 0.023;          // ball radius
const float KX = 17.0;          // wall hits per half clock cycle (integers: the path closes, no jump at wrap)
const float KY = 23.0;

flat out highp vec4 vBall;      // xy position, z core size, w halo strength
flat out highp vec4 vTrailA;    // two trail positions
flat out highp vec2 vTrailB;    // third trail position
flat out highp vec4 vSide0;     // side-wall splash: along-edge hit, spread, side (+1 right / -1 left), amplitude
flat out highp vec4 vSide1;
flat out highp vec4 vCap0;      // top/bottom splash: along-edge hit, spread, side (+1 top / -1 bottom), amplitude
flat out highp vec4 vCap1;
flat out vec3 vSideCol0; flat out vec3 vSideCol1; flat out vec3 vCapCol0; flat out vec3 vCapCol1; flat out vec3 vTint;

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
highp vec2 ballAt(highp float ux, highp vec2 room) { return vec2((tri(ux) - 0.5) * room.x, (tri(ux * KY / KX) - 0.5) * room.y); }

// A splash rises softly and fades over ~1.6 bounces; the newest one also brightens on the beat.
highp vec4 splash(highp float hit, highp float age, highp float side, float widen, float beat) {
  float amp = age > 1.6 ? 0.0 : smoothstep(0.0, 0.08, age) * sq(1.0 - age / 1.6) * (1.0 + beat);
  return vec4(hit, 0.10 + 0.22 * min(age, 1.6) + widen, side, amp);
}

void main() {
  vP = vec2(aPos.x * 0.5 * uAspect, aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0);
  highp vec2 room = 2.0 * (vec2(0.5 * uAspect, 0.5) - R);
  highp float ux = KX * uPhase.x / PI;            // the phase clock runs faster when the music is louder
  highp float uy = KY * uPhase.x / PI;
  highp float hb = uPhase.w / TWO_PI;
  float motion = 1.0 - uReduced;
  float widen = motion * 0.06 * uBass;
  float beat = motion * 0.35 * exp(-uBeatAges.x * 3.0);

  // Unrolled on purpose: no dynamic indexing of outputs (Mali driver safety).
  highp float nx = floor(ux), ny = floor(uy);
  vSide0 = splash((tri(nx * KY / KX) - 0.5) * room.y, ux - nx, mod(nx, 2.0) < 0.5 ? 1.0 : -1.0, widen, beat);
  vSide1 = splash((tri((nx - 1.0) * KY / KX) - 0.5) * room.y, ux - nx + 1.0, mod(nx - 1.0, 2.0) < 0.5 ? 1.0 : -1.0, widen, 0.0);
  vCap0 = splash((tri(ny * KX / KY) - 0.5) * room.x, uy - ny, mod(ny, 2.0) < 0.5 ? 1.0 : -1.0, widen, beat);
  vCap1 = splash((tri((ny - 1.0) * KX / KY) - 0.5) * room.x, uy - ny + 1.0, mod(ny - 1.0, 2.0) < 0.5 ? 1.0 : -1.0, widen, 0.0);
  // Whole palette loops per clock cycle, so splash colours never jump when the clock wraps.
  vSideCol0 = palette(hb + nx * 3.0 / (2.0 * KX));
  vSideCol1 = palette(hb + (nx - 1.0) * 3.0 / (2.0 * KX));
  vCapCol0 = palette(hb + 0.5 + ny * 4.0 / (2.0 * KY));
  vCapCol1 = palette(hb + 0.5 + (ny - 1.0) * 4.0 / (2.0 * KY));

  vBall = vec4(ballAt(ux, room), R * (1.0 + motion * 0.25 * uPulse), 0.30);
  highp float trailStep = 0.035 * motion;
  vTrailA = vec4(ballAt(ux - trailStep, room), ballAt(ux - 2.0 * trailStep, room));
  vTrailB = ballAt(ux - 3.0 * trailStep, room);
  vTint = mix(vec3(1.0), palette(hb + nx * 3.0 / (2.0 * KX)), 0.35);
}
