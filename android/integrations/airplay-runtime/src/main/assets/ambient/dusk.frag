// ambient-contract: 1
float hillMask(highp float py, highp float y){ return 1.0-smoothstep(y-0.003,y+0.003,py); }
void main(){
  highp vec2 p=vP; float a=uAspect;
  highp float py=p.y-0.02*sin(uPhase.z);                 // whole scene drifts vertically (burn-in)
  vec3 col=mix(uBase,uPalette[2]*0.45,1.0-smoothstep(-0.1,0.5,py));
  col+=uPalette[1]*0.22*(0.4+0.6*uLevel)*exp(-max(py+0.05,0.0)*6.0);
  highp vec2 sc=vec2(0.28*a*sin(uPhase.w), -0.02+0.03*sin(uPhase.w+1.0));
  float r=0.055*(1.0+0.15*uBass*(1.0-uReduced));
  float d=length(vec2(p.x,py)-sc);
  float disc=1.0-smoothstep(r-0.003,r+0.003,d);
  float halo=(0.25+0.35*uPulse*(1.0-uReduced))/(1.0+d*d/(r*r*4.0));
  col+=uPalette[1]*(disc*0.9+halo*0.5);
  highp float y0=-0.06+0.05*sin(p.x*1.3+uPhase.x+0.7)+0.02*sin(p.x*3.1+2.0*uPhase.x);
  highp float y1=-0.16+0.06*sin(p.x*1.7+2.0*uPhase.x+2.0)+0.02*sin(p.x*4.3+3.0*uPhase.x);
  highp float y2=-0.27+0.05*sin(p.x*2.2+3.0*uPhase.x+4.1)+0.015*sin(p.x*6.1+4.0*uPhase.x);
  col=mix(col,mix(uBase,uPalette[2],0.30),hillMask(py,y0));
  col=mix(col,mix(uBase,uPalette[2],0.16),hillMask(py,y1));
  col=mix(col,uBase*0.85,hillMask(py,y2));
  col+=uPalette[3]*0.25*uHigh*(1.0-uReduced)*exp(-sq((py-y2)/0.006));
  fragColor=vec4(finish(col,p),1.0);
}
