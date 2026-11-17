// ambient-contract: 1
float ring(float d, float age){
  if(age>2.4) return 0.0;
  float r=0.04+age*0.32, w=0.035+age*0.03, t=(d-r)/w, f=1.0-age/2.4;
  return exp(-t*t)*f*f;
}
void main(){
  highp vec2 p=vP;
  highp vec2 c=vec2(0.02*sin(uPhase.x), 0.03+0.02*sin(uPhase.y));
  float d=length(p-c);
  float lvl=0.55+0.45*uLevel;
  vec3 col=uBase+uPalette[2]*0.55*lvl/(1.0+d*d*6.0);
  float rings=0.0;
  if(uReduced<0.5){ rings=ring(d,uBeatAges.x)+ring(d,uBeatAges.y)+ring(d,uBeatAges.z)+ring(d,uBeatAges.w); }
  col+=mix(uPalette[0],uPalette[3],0.3)*rings*0.45*lvl;
  float dotR=0.018*(1.0+(1.0-uReduced)*0.35*uBass);
  float dotF=1.0-smoothstep(dotR*0.6,dotR*1.6,d);
  col+=uPalette[1]*(dotF*0.9+0.25*lvl/(1.0+d*d/(dotR*dotR*30.0)));
  fragColor=vec4(finish(col,p),1.0);
}
