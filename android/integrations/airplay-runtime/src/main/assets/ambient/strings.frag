// ambient-contract: 1
const float N = 22.0;
flat in highp vec4 vPid; flat in vec4 vE1; flat in vec4 vE2;   // from strings.vert
flat in vec4 vCm; flat in vec4 vC0; flat in vec4 vCp;          // wobble of plucked string -1 / 0 / +1
vec3 stringGlow(highp float xs, float v, highp float s, highp float cell, float lvl){
  // All four beats at once, branch- and trig-free (per-pixel beat math was too much ALU for Mali-G31 at 720p (measured)).
  highp vec4 k = vec4(s) - vPid;                                  // this string's offset from each plucked one
  vec4 bw = max(vec4(0.0), 1.0-abs(k)*0.65);                      // plucked string + its two neighbours
  vec4 c = mix(mix(vCm, vC0, step(-0.5, k)), vCp, step(0.5, k));  // this string's wobble for each beat
  float d = dot(bw*vE1, c), warm = dot(bw, vE2);
  float shape = sin(3.14159*v);                    // fixed at top and bottom, like a real string
  highp float disp = cell*shape*((1.0-uReduced)*0.30*(0.6+0.6*uBass)*d
             + 0.05*(1.0+2.0*uBass)*(1.0-0.5*uReduced)*sin(3.0*uPhase.x+s*1.7));
  highp float dx = xs-(s+0.5)*cell-disp;
  float w = mix(0.0045, 0.0018, clamp(s/N,0.0,1.0)); // thick bass strings left, thin right
  float core = 1.0/(1.0+sq(dx/w));
  float halo = 1.0/(1.0+sq(dx/(w*6.0)));
  warm = clamp(warm,0.0,1.0)*(1.0-0.7*uReduced);
  core *= 1.0+0.5*uHigh*sin(v*28.0-9.0*uPhase.z+s*2.1);
  vec3 tint = mix(mix(uPalette[0],uPalette[3],0.5), uPalette[1], warm);
  return tint*(core*(0.22+0.38*lvl+0.5*warm)+halo*(0.05+0.45*warm));
}
void main(){
  highp vec2 p=vP; highp float a=uAspect;
  highp float cell=a/N; float v=p.y+0.5, lvl=uLevel;
  highp float xs=p.x+0.5*a+0.4*cell*sin(uPhase.w);  // whole harp sways so no line sits still
  highp float s0=floor(xs/cell);
  highp float s1=s0+(fract(xs/cell)>0.5?1.0:-1.0);
  vec3 col=uBase+uPalette[2]*0.12*(1.0-smoothstep(-0.5,0.5,p.y));
  float ends=smoothstep(0.0,0.08,v)*(1.0-smoothstep(0.92,1.0,v));
  col+=(stringGlow(xs,v,s0,cell,lvl)+stringGlow(xs,v,s1,cell,lvl))*ends;
  fragColor=vec4(finish(col,p),1.0);
}
