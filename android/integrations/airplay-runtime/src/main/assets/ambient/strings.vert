// ambient-contract: 1
const float N = 22.0;   // must match strings.frag
flat out highp vec4 vPid; flat out highp vec4 vAge; flat out vec4 vE1; flat out vec4 vE2;
// Beat math depends only on uniforms, so it runs per vertex instead of per pixel (Mali-G31 fps).
void beat(highp float age, highp float n, out highp float pid, out float e1, out float e2){
  pid = floor(hash12(vec2(n, 3.0))*N); e1 = exp(-age*1.3); e2 = exp(-age*1.0);
  if(age>6.0){ pid = -100.0; e1 = 0.0; e2 = 0.0; }   // far from every string: weight 0
}
void main(){
  vP = vec2(aPos.x*0.5*uAspect, aPos.y*0.5); gl_Position = vec4(aPos,0.0,1.0);
  // Unrolled on purpose: no dynamic indexing of outputs/vectors (Mali driver safety).
  highp float p0, p1, p2, p3; float a0, a1, a2, a3, b0, b1, b2, b3;
  beat(uBeatAges.x, uBeatCount,     p0, a0, b0);
  beat(uBeatAges.y, uBeatCount-1.0, p1, a1, b1);
  beat(uBeatAges.z, uBeatCount-2.0, p2, a2, b2);
  beat(uBeatAges.w, uBeatCount-3.0, p3, a3, b3);
  vPid = vec4(p0,p1,p2,p3); vAge = uBeatAges; vE1 = vec4(a0,a1,a2,a3); vE2 = vec4(b0,b1,b2,b3);
}
