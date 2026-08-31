precision highp float;

uniform sampler2D uTexSampler;
uniform sampler2D uInkSampler;
uniform sampler2D uMaskSampler;
uniform vec2 uTexelSize;
varying vec2 vTexSamplingCoord;

vec4 blurred(vec2 uv, float intensity) {
  vec2 d = uTexelSize * mix(2.0, 18.0, intensity);
  vec4 sum = texture2D(uTexSampler, uv) * 0.20;
  sum += texture2D(uTexSampler, uv + vec2(d.x, 0.0)) * 0.10;
  sum += texture2D(uTexSampler, uv - vec2(d.x, 0.0)) * 0.10;
  sum += texture2D(uTexSampler, uv + vec2(0.0, d.y)) * 0.10;
  sum += texture2D(uTexSampler, uv - vec2(0.0, d.y)) * 0.10;
  sum += texture2D(uTexSampler, uv + d) * 0.10;
  sum += texture2D(uTexSampler, uv - d) * 0.10;
  sum += texture2D(uTexSampler, uv + vec2(d.x, -d.y)) * 0.10;
  sum += texture2D(uTexSampler, uv + vec2(-d.x, d.y)) * 0.10;
  return sum;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 overlayUv = vec2(uv.x, 1.0 - uv.y);
  vec4 base = texture2D(uTexSampler, uv);
  vec4 mask = texture2D(uMaskSampler, overlayUv);
  float intensity = mask.g;
  vec4 softened = blurred(uv, intensity);
  vec2 block = uTexelSize * mix(6.0, 52.0, intensity);
  vec2 mosaicUv = (floor(uv / block) + 0.5) * block;
  vec4 mosaic = texture2D(uTexSampler, mosaicUv);
  vec4 redacted = mix(base, softened, mask.r);
  redacted = mix(redacted, mosaic, mask.b);
  vec4 ink = texture2D(uInkSampler, overlayUv);
  gl_FragColor = redacted * (1.0 - ink.a) + ink;
}
