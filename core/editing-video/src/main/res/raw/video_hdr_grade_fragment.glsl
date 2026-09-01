precision highp float;

uniform sampler2D uTexSampler;
uniform sampler2D uLutSampler;
uniform int uInputProfile;
uniform int uBypass;
uniform float uExposure;
uniform float uTemperature;
uniform float uTint;
uniform float uContrast;
uniform float uPivot;
uniform float uSaturation;
uniform vec4 uShadows;
uniform vec4 uMidtones;
uniform vec4 uHighlights;
uniform vec3 uBand0;
uniform vec3 uBand1;
uniform vec3 uBand2;
uniform vec3 uBand3;
uniform vec3 uBand4;
uniform vec3 uBand5;
uniform vec3 uBand6;
uniform vec3 uBand7;
uniform int uLook;
uniform float uLutIntensity;
uniform int uHasCustomLut;
uniform float uLutSize;
uniform vec3 uLutDomainMin;
uniform vec3 uLutDomainMax;
varying vec2 vTexSamplingCoord;

float decode709(float v) {
  return v < 0.081 ? v / 4.5 : pow((v + 0.099) / 1.099, 1.0 / 0.45);
}

float encode709(float v) {
  v = max(v, 0.0);
  return v < 0.018 ? v * 4.5 : 1.099 * pow(v, 0.45) - 0.099;
}

float decodeLog(float v, int profile) {
  if (profile == 0) return decode709(v);
  if (profile == 1) {
    if (v >= 0.2085553) return exp2((v - 0.69336945) / 0.08550479) - 0.00964052;
    return v >= 0.0 ? sqrt(v / 47.28711236) - 0.05641088 : -0.05641088;
  }
  if (profile == 2) {
    float signal = (v * 1023.0 - 64.0) / 876.0;
    float linear = signal >= 0.03000122 ? pow(10.0, (signal - 0.646596) / 0.432699) - 0.037584 : (signal - 0.03000122) / 5.0;
    return linear * 0.9 * 219.0 / 155.0;
  }
  if (profile == 3) return v >= 171.2102947 / 1023.0 ? pow(10.0, (v * 1023.0 - 420.0) / 261.5) * 0.19 - 0.01 : (v * 1023.0 - 95.0) * 0.01125 / (171.2102947 - 95.0);
  if (profile == 4) return v < 0.092864125 ? -(pow(10.0, (0.092864125 - v) / 0.24136077) - 1.0) / 87.09937546 * 0.9 : (pow(10.0, (v - 0.092864125) / 0.24136077) - 1.0) / 87.09937546 * 0.9;
  if (profile == 5) {
    if (v < 0.097465473) return -(pow(10.0, (0.12783901 - v) / 0.36726845) - 1.0) / 14.98325 * 0.9;
    if (v <= 0.15277891) return (v - 0.12512219) / 1.9754798 * 0.9;
    return (pow(10.0, (v - 0.12240537) / 0.36726845) - 1.0) / 14.98325 * 0.9;
  }
  if (profile == 6) return v < 0.181 ? (v - 0.125) / 5.6 : pow(10.0, (v - 0.598206) / 0.241514) - 0.00873;
  if (profile == 7) return v <= 0.14 ? (v - 0.0929) / 6.025 : (pow(10.0, 3.89616 * v - 2.27752) - 0.0108) / 0.9892;
  if (profile == 8) return v < 0.100537775 ? (v - 0.092864) / 8.735631 : (pow(10.0, (v - 0.790453) / 0.344676) - 0.009468) / 0.555556;
  if (profile == 9) return v < 0.100686685 ? (v - 0.092864) / 8.799461 : (pow(10.0, (v - 0.384316) / 0.245281) - 0.064829) / 5.555556;
  if (profile == 10) return v < 452.0 / 1023.0 ? pow(v / (650.0 / 1023.0), 3.0) - 0.0075 : exp((v - 619.0 / 1023.0) / (150.0 / 1023.0));
  if (profile == 11) return v < 0.133883783 ? (v - 0.092465753) / 8.283605932 : exp((v - 0.530013339) / 0.086928761) - 0.005494072;
  if (profile == 12) return v > 0.149658 ? (pow(10.0, (v - 0.385537) / 0.247190) - 0.052272) / 5.555556 : (v - 0.092809) / 5.367655;
  if (profile == 13) {
    float a = (pow(2.0, 18.0) - 16.0) / 117.45;
    float b = (1023.0 - 95.0) / 1023.0;
    float c = 95.0 / 1023.0;
    float s = (7.0 * log(2.0) * pow(2.0, 7.0 - 14.0 * c / b)) / (a * b);
    float t = (pow(2.0, 14.0 * (-c / b) + 6.0) - 64.0) / a;
    return v >= 0.0 ? (pow(2.0, 14.0 * ((v - c) / b) + 6.0) - 64.0) / a : v * s + t;
  }
  return v < 0.0 ? v / 15.1927 - 0.01 : (pow(10.0, v / 0.224282) - 1.0) / 155.975327 - 0.01;
}

vec3 bt2020To709(vec3 c) {
  return mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * c;
}

vec3 bt709To2020(vec3 c) {
  return mat3(0.6274, 0.0691, 0.0164, 0.3293, 0.9195, 0.0880, 0.0433, 0.0114, 0.8956) * c;
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float smoothWeight(float a, float b, float v) { float t = clamp((v - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }

vec3 applyWheel(vec3 rgb, vec4 wheel, float weight) {
  return rgb + (wheel.rgb * 0.18 + wheel.www * 0.25) * weight;
}

vec3 rgbToHsv(vec3 c) {
  vec4 k = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, k.wz), vec4(c.gb, k.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)) * 360.0, d / (q.x + 1e-10), q.x);
}

vec3 hsvToRgb(vec3 c) {
  vec3 p = abs(fract(c.xxx / 360.0 + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

void addBand(float hue, float center, vec3 band, inout vec3 delta) {
  float distance = min(abs(hue - center), 360.0 - abs(hue - center));
  delta += band * clamp(1.0 - distance / 45.0, 0.0, 1.0);
}

vec3 applyHueBands(vec3 rgb) {
  float scale = max(1.0, max(rgb.r, max(rgb.g, rgb.b)));
  vec3 hsv = rgbToHsv(clamp(rgb / scale, 0.0, 1.0));
  vec3 delta = vec3(0.0);
  addBand(hsv.x, 0.0, uBand0, delta); addBand(hsv.x, 45.0, uBand1, delta);
  addBand(hsv.x, 90.0, uBand2, delta); addBand(hsv.x, 135.0, uBand3, delta);
  addBand(hsv.x, 180.0, uBand4, delta); addBand(hsv.x, 225.0, uBand5, delta);
  addBand(hsv.x, 270.0, uBand6, delta); addBand(hsv.x, 315.0, uBand7, delta);
  hsv.x = mod(hsv.x + delta.x + 360.0, 360.0);
  hsv.y = clamp(hsv.y * (1.0 + delta.y), 0.0, 1.0);
  hsv.z = max(0.0, hsv.z + delta.z * 0.25);
  return hsvToRgb(hsv) * scale;
}

vec3 builtInLook(vec3 rgb) {
  if (uLook == 2) return vec3(rgb.r * 1.08 + 0.015, rgb.g * 1.01, rgb.b * 0.90);
  if (uLook == 3) return vec3(rgb.r * 0.92, rgb.g * 1.01, rgb.b * 1.08 + 0.01);
  if (uLook == 4) { vec3 c = (rgb - 0.5) * 1.25 + 0.5; return mix(vec3(luma(c)), c, 0.38); }
  if (uLook == 5) return vec3(rgb.r * 1.06, rgb.g * 0.99 + rgb.b * 0.015, rgb.b * 1.04 + rgb.g * 0.02);
  if (uLook == 6) return vec3(luma(rgb));
  return rgb;
}

vec3 sampleLut(vec3 rgb) {
  float scale = max(1.0, max(rgb.r, max(rgb.g, rgb.b)));
  vec3 normalized = clamp((rgb / scale - uLutDomainMin) / (uLutDomainMax - uLutDomainMin), 0.0, 1.0);
  float z = normalized.b * (uLutSize - 1.0);
  float z0 = floor(z);
  float z1 = min(z0 + 1.0, uLutSize - 1.0);
  float atlasWidth = uLutSize * uLutSize;
  vec2 uv0 = vec2((z0 * uLutSize + normalized.r * (uLutSize - 1.0) + 0.5) / atlasWidth, 1.0 - (normalized.g * (uLutSize - 1.0) + 0.5) / uLutSize);
  vec2 uv1 = vec2((z1 * uLutSize + normalized.r * (uLutSize - 1.0) + 0.5) / atlasWidth, uv0.y);
  return mix(texture2D(uLutSampler, uv0).rgb, texture2D(uLutSampler, uv1).rgb, fract(z)) * scale;
}

void main() {
  vec3 source709Linear = bt2020To709(texture2D(uTexSampler, vTexSamplingCoord).rgb);
  vec3 signal = vec3(encode709(source709Linear.r), encode709(source709Linear.g), encode709(source709Linear.b));
  vec3 rgb = vec3(decodeLog(signal.r, uInputProfile), decodeLog(signal.g, uInputProfile), decodeLog(signal.b, uInputProfile));

  if (uBypass == 0) {
    rgb *= exp2(uExposure);
    rgb.r *= 1.0 + uTemperature * 0.12;
    rgb.b *= 1.0 - uTemperature * 0.12;
    rgb.g *= 1.0 + uTint * 0.06;
    rgb = (rgb - uPivot) * exp2(uContrast * 1.5) + uPivot;
    float y = clamp(luma(rgb), 0.0, 1.0);
    float sw = smoothWeight(0.55, 0.05, 1.0 - y);
    float hw = smoothWeight(0.45, 0.95, y);
    rgb = applyWheel(rgb, uShadows, sw);
    rgb = applyWheel(rgb, uMidtones, clamp(1.0 - sw - hw, 0.0, 1.0));
    rgb = applyWheel(rgb, uHighlights, hw);
    rgb = mix(vec3(luma(rgb)), rgb, 1.0 + uSaturation);
    rgb = applyHueBands(rgb);
    rgb = mix(rgb, builtInLook(rgb), uLutIntensity);
    if (uHasCustomLut == 1) rgb = mix(rgb, sampleLut(rgb), uLutIntensity);
  }

  rgb = max(rgb, vec3(0.0));
  const float headroom = 1000.0 / 203.0;
  rgb = (rgb * headroom / (rgb + vec3(headroom - 1.0))) / headroom;
  gl_FragColor = vec4(max(bt709To2020(rgb), vec3(0.0)), 1.0);
}
