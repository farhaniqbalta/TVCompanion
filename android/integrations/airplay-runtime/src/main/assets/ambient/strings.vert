// ambient-contract: 1
const float N = 22.0;   // must match strings.frag
// All beat math depends only on uniforms, so it runs per vertex. Per beat: the plucked string, its two decay
// envelopes, and the wobble of the plucked string and its two neighbours (the only strings a pluck moves).
// Measured on Mali-G31: per-pixel beat math was too much ALU for Mali-G31 at 720p (measured).
flat out highp vec4 vPid; flat out vec4 vE1; flat out vec4 vE2;
flat out vec4 vCm; flat out vec4 vC0; flat out vec4 vCp;
float wobble(highp float s, highp float age){
  highp float om = 6.2831853*(2.0+2.5*hash12(vec2(s, 11.0)));   // each string has its own visible wobble rate
  return cos(om*age);
}
void beat(highp float age, highp float n, out highp float pid, out float e1, out float e2,
          out float cm, out float c0, out float cp){
  pid = floor(hash12(vec2(n, 3.0))*N); e1 = exp(-age*1.3); e2 = exp(-age*1.0);
  cm = wobble(pid-1.0, age); c0 = wobble(pid, age); cp = wobble(pid+1.0, age);
  if(age>6.0){ pid = -100.0; e1 = 0.0; e2 = 0.0; }   // far from every string: weight 0
}
void main(){
  vP = vec2(aPos.x*0.5*uAspect, aPos.y*0.5); gl_Position = vec4(aPos,0.0,1.0);
  // Unrolled on purpose: no dynamic indexing of outputs/vectors (Mali driver safety).
  highp float p0, p1, p2, p3; float a0, a1, a2, a3, b0, b1, b2, b3;
  float m0, m1, m2, m3, z0, z1, z2, z3, q0, q1, q2, q3;
  beat(uBeatAges.x, uBeatCount,     p0, a0, b0, m0, z0, q0);
  beat(uBeatAges.y, uBeatCount-1.0, p1, a1, b1, m1, z1, q1);
  beat(uBeatAges.z, uBeatCount-2.0, p2, a2, b2, m2, z2, q2);
  beat(uBeatAges.w, uBeatCount-3.0, p3, a3, b3, m3, z3, q3);
  vPid = vec4(p0,p1,p2,p3); vE1 = vec4(a0,a1,a2,a3); vE2 = vec4(b0,b1,b2,b3);
  vCm = vec4(m0,m1,m2,m3); vC0 = vec4(z0,z1,z2,z3); vCp = vec4(q0,q1,q2,q3);
}
