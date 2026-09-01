attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;

void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = uTexTransformationMatrix * aFramePosition;
  vTexSamplingCoord = texturePosition.xy * 0.5 + 0.5;
}
