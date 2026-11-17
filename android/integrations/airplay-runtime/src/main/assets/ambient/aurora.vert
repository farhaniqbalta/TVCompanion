// ambient-contract: 1
out highp vec3 vY; out vec3 vShim; out vec3 vT0; out vec3 vT1; out vec3 vT2;
vec3 tintFor(float x, float fk, float pc, float crest, float lvl){
  vec3 t = mix(uPalette[0], uPalette[1], clamp(0.5+0.5*sin(x*0.8+pc+fk*1.3)+0.2*(uMid-0.5),0.0,1.0));
  return mix(t, uPalette[2], 0.175*fk) * (0.42-0.08*fk) * lvl * (1.0+0.4*crest);
}
float ribbonY(float x, float fk, float pa, float pb, float lift, float crest){
  return -0.18+0.11*fk+lift+0.06*sin(x*(1.1+0.3*fk)+pa+fk*1.7)+0.03*sin(x*(2.6+0.5*fk)-pb)+0.03*crest;
}
void main(){
  vP = vec2(aPos.x*0.5*uAspect, aPos.y*0.5); gl_Position = vec4(aPos,0.0,1.0);
  float x = vP.x, lvl = 0.55+0.45*uLevel, lift = (1.0-uReduced)*0.05*uBass;
  float cx = (x-(-0.6*uAspect+uBeatAges.x*1.4*uAspect))*3.0;
  float crest = (1.0-uReduced)*exp(-cx*cx)*exp(-uBeatAges.x*1.6);
  // Unrolled on purpose: no dynamic indexing of outputs/vectors (Mali driver safety).
  vY = vec3(ribbonY(x,0.0,uPhase.x,          uPhase.y,          lift,crest),
            ribbonY(x,1.0,uPhase.x+uPhase.z, 2.0*uPhase.y,      lift,crest),
            ribbonY(x,2.0,2.0*uPhase.x,      uPhase.y+uPhase.w, lift,crest));
  vShim = (1.0-uReduced)*0.25*uHigh*vec3(sin(x*38.0+9.0*uPhase.z), sin(x*38.0+9.0*uPhase.z+1.0), sin(x*38.0+9.0*uPhase.z+2.0));
  vT0 = tintFor(x,0.0,uPhase.w,          crest,lvl);
  vT1 = tintFor(x,1.0,uPhase.w+uPhase.x, crest,lvl);
  vT2 = tintFor(x,2.0,2.0*uPhase.w,      crest,lvl);
}
