// ambient-contract: 1
in highp vec3 vY; in vec3 vShim; in vec3 vT0; in vec3 vT1; in vec3 vT2;
vec3 ribbon(float dy, float width, vec3 tint, float shim){
  float t = dy / width;
  float soft = 1.0/(1.0+t*t) * (1.0 - smoothstep(-0.05, 0.35, dy));
  return tint * soft * (1.0 + shim * smoothstep(0.0, 0.05, dy));
}
void main(){
  vec3 col = uBase;
  col += ribbon(vP.y - vY.x, 0.05,  vT0, vShim.x);
  col += ribbon(vP.y - vY.y, 0.065, vT1, vShim.y);
  col += ribbon(vP.y - vY.z, 0.08,  vT2, vShim.z);
  fragColor = vec4(finish(col, vP), 1.0);
}
