#version 300 es
// ADR-045 colour grade, stages 0-12. Mechanical port of PhotoGradeMath (the CPU reference): one
// function per stage, same constants, same order, same early returns. Keep both in sync.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;

uniform sampler2D uSource;     // premultiplied RGBA8 of the tile, fetched by bitmap pixel (row 0 = top)
uniform sampler3D uLegacyLut;  // stage 0, RGBA16F N^3, linear + clamp
uniform sampler3D uLookLut;    // stage 10
uniform sampler2D uBase;       // local linear-luma base (R16F), linear + clamp, global UV

uniform float uBrightness;
uniform float uContrast;
uniform float uTone;
uniform float uWhitePoint;
uniform float uHighlights;
uniform float uShadows;
uniform float uBlackPoint;
uniform float uSaturation;
uniform float uWarmth;
uniform float uTint;
uniform float uSkinTone;
uniform float uBlueTone;
uniform float uVignette;
uniform vec3 uBands[8];        // hue, saturation, luminance per colour-mix band
uniform float uLookIntensity;
uniform float uLegacySize;
uniform float uLookSize;
uniform bool uHasMix;
uniform bool uHasLegacy;
uniform bool uHasLook;
uniform bool uNeedsBase;
uniform bool uHasBase;
uniform ivec2 uTileOrigin;
uniform ivec2 uFrame;

out vec4 outColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float luma(vec3 c) { return dot(c, LUMA); }

float lin1(float p) {
    return p <= 0.04045 ? p / 12.92 : pow((p + 0.055) / 1.055, 2.4);
}

float enc1(float x) {
    float c = max(x, 0.0);
    return c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

// Own smoothstep: the built-in is undefined for e0 >= e1 and the ADR uses falling ramps.
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Kotlin's float % : the result has the sign of the dividend.
float fmodf(float a, float b) { return a - b * trunc(a / b); }

vec3 ratioApply(vec3 c, float y, float newY) { return c * (newY / max(y, 1e-5)); }

float hueDistance(float a, float b) {
    float d = fmodf(abs(a - b), 360.0);
    return d > 180.0 ? 360.0 - d : d;
}

vec3 lutLookup(sampler3D lut, float n, vec3 p) {
    return texture(lut, clamp(p, 0.0, 1.0) * ((n - 1.0) / n) + 0.5 / n).rgb;
}

// Stage 1
vec3 toLinear(vec3 c) { return vec3(lin1(c.r), lin1(c.g), lin1(c.b)); }

// Stage 2
vec3 warmthTint(vec3 c, float w, float t) {
    if (w == 0.0 && t == 0.0) return c;
    c.r *= 1.0 + 0.10 * w;
    c.b *= 1.0 - 0.10 * w;
    c.g *= 1.0 - 0.05 * t;
    return c;
}

// Stage 3
vec3 brightness(vec3 c, float b) {
    if (b == 0.0) return c;
    float y = luma(c);
    return ratioApply(c, y, pow(max(y, 0.0), exp2(-0.8 * b)));
}

// Stage 4
float levelsChannel(float x, float k, float q) {
    if (k > 0.0) {
        x = max(x - 0.05 * k, 0.0) / (1.0 - 0.05 * k);
    } else if (k < 0.0) {
        float f = 0.03 * abs(k);
        x = x * (1.0 - f) + f;
    }
    if (q > 0.0) {
        x /= 1.0 - 0.35 * q;
    } else if (q < 0.0) {
        x *= 1.0 - 0.30 * abs(q);
    }
    return x;
}

vec3 levels(vec3 c, float k, float q) {
    if (k == 0.0 && q == 0.0) return c;
    return vec3(levelsChannel(c.r, k, q), levelsChannel(c.g, k, q), levelsChannel(c.b, k, q));
}

// Stage 5
vec3 highlightsShadows(vec3 c, float h, float s, float base) {
    if (h == 0.0 && s == 0.0) return c;
    float bp = enc1(base);
    float ws = 1.0 - sstep(0.0, 0.5, bp);
    float wh = sstep(0.5, 1.0, bp);
    return c * exp2(1.2 * s * ws + 1.0 * h * wh);
}

// Stage 6
vec3 tone(vec3 c, float o, float base) {
    if (o == 0.0) return c;
    float y = luma(c);
    float b = max(base, 1e-4);
    float d = y / b;
    float compressed = exp2((1.0 - 0.5 * o) * log2(b) + 0.5 * o * log2(0.18));
    return ratioApply(c, y, compressed * d);
}

// Stage 7
vec3 contrast(vec3 c, float k) {
    if (k == 0.0) return c;
    float y = luma(c);
    float u = enc1(y);
    if (u <= 0.0 || u >= 1.0) return c;
    float shaped = k > 0.0
        ? u + (u * u * (3.0 - 2.0 * u) - u) * k
        : u + (0.5 - sin(asin(1.0 - 2.0 * u) / 3.0) - u) * abs(k);
    return ratioApply(c, y, lin1(shaped));
}

// Stage 8
vec3 toEncoded(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return vec3(enc1(c.r), enc1(c.g), enc1(c.b));
}

// Stage 9a
vec3 saturation(vec3 c, float a) {
    if (a == 0.0) return c;
    float yp = luma(c);
    return clamp(vec3(yp) + (c - vec3(yp)) * (1.0 + a), 0.0, 1.0);
}

// Stage 9b
vec3 hsvAdjust(vec3 c) {
    if (uSkinTone == 0.0 && uBlueTone == 0.0 && !uHasMix) return c;
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float delta = mx - mn;
    float hue;
    if (delta <= 0.0) hue = 0.0;
    else if (mx == c.r) hue = 60.0 * fmodf((c.g - c.b) / delta, 6.0);
    else if (mx == c.g) hue = 60.0 * ((c.b - c.r) / delta + 2.0);
    else hue = 60.0 * ((c.r - c.g) / delta + 4.0);
    if (hue < 0.0) hue += 360.0;
    float sat = mx <= 0.0 ? 0.0 : delta / mx;
    float chroma = sstep(0.02, 0.12, sat);
    float value = mx;

    if (uSkinTone != 0.0) {
        float w = sstep(25.0, 0.0, hueDistance(hue, 35.0)) * chroma;
        hue += 8.0 * uSkinTone * w;
        sat *= 1.0 + 0.25 * uSkinTone * w;
    }
    if (uBlueTone != 0.0) {
        float w = sstep(35.0, 0.0, hueDistance(hue, 220.0)) * chroma;
        sat *= 1.0 + 0.6 * uBlueTone * w;
        value *= 1.0 - 0.15 * uBlueTone * w;
    }
    if (uHasMix) {
        float dh = 0.0;
        float ds = 0.0;
        float dl = 0.0;
        for (int i = 0; i < 8; i++) {
            float w = max(0.0, 1.0 - hueDistance(hue, 45.0 * float(i)) / 45.0) * chroma;
            dh += w * uBands[i].x;
            ds += w * uBands[i].y;
            dl += w * uBands[i].z;
        }
        hue += 30.0 * dh;
        sat = clamp(sat * (1.0 + ds), 0.0, 1.0);
        value *= exp2(0.75 * dl);
    }
    sat = clamp(sat, 0.0, 1.0);
    value = clamp(value, 0.0, 1.0);
    hue = fmodf(fmodf(hue, 360.0) + 360.0, 360.0);

    float hp = hue / 60.0;
    int sector = int(floor(hp)) % 6;
    float f = hp - floor(hp);
    float p = value * (1.0 - sat);
    float q = value * (1.0 - sat * f);
    float t = value * (1.0 - sat * (1.0 - f));
    if (sector == 0) return vec3(value, t, p);
    if (sector == 1) return vec3(q, value, p);
    if (sector == 2) return vec3(p, value, t);
    if (sector == 3) return vec3(p, q, value);
    if (sector == 4) return vec3(t, p, value);
    return vec3(value, p, q);
}

// Stage 10
vec3 look(vec3 c) {
    if (uLookIntensity <= 0.0) return c;
    vec3 s = lutLookup(uLookLut, uLookSize, c);
    return c + (s - c) * uLookIntensity;
}

// Stage 11
vec3 vignette(vec3 c, float vig, float u, float v, float aspect) {
    if (vig == 0.0) return c;
    float dx = (u - 0.5) * aspect;
    float dy = v - 0.5;
    float r = sqrt(dx * dx + dy * dy) / sqrt(0.25 * aspect * aspect + 0.25);
    float w = sstep(0.35, 1.0, r);
    if (vig > 0.0) {
        return c * (1.0 - 0.6 * vig * w);
    }
    return c + (vec3(1.0) - c) * (0.5 * abs(vig) * w);
}

// Stage 12: uint32 hash, identical to PhotoGradeMath.hash.
uint hash(uint x, uint y) {
    uint h = (x * 0x8da6b343u) ^ (y * 0xd8163841u);
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return h;
}

void main() {
    // Row r of the framebuffer is bitmap row r, so the read-back needs no flip.
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 texel = texelFetch(uSource, px, 0);
    float alpha = floor(texel.a * 255.0 + 0.5);
    if (alpha == 0.0) {
        outColor = texel;
        return;
    }
    vec3 c = min(floor(texel.rgb * 255.0 + 0.5) / alpha, vec3(1.0));

    ivec2 g = px + uTileOrigin;
    float u = (float(g.x) + 0.5) / float(uFrame.x);
    float v = (float(g.y) + 0.5) / float(uFrame.y);
    float aspect = float(uFrame.x) / float(uFrame.y);

    if (uHasLegacy) c = lutLookup(uLegacyLut, uLegacySize, c);          // 0
    c = toLinear(c);                                                      // 1
    c = warmthTint(c, uWarmth, uTint);                                    // 2
    c = brightness(c, uBrightness);                                       // 3
    c = levels(c, uBlackPoint, uWhitePoint);                              // 4
    if (uNeedsBase) {
        float b = uHasBase ? texture(uBase, vec2(u, v)).r : luma(c);
        c = highlightsShadows(c, uHighlights, uShadows, b);               // 5
        c = tone(c, uTone, b);                                            // 6
    }
    c = contrast(c, uContrast);                                           // 7
    c = toEncoded(c);                                                     // 8
    c = saturation(c, uSaturation);                                       // 9
    c = hsvAdjust(c);                                                     // 9
    if (uHasLook) c = look(c);                                            // 10
    c = vignette(c, uVignette, u, v, aspect);                             // 11

    float d = (float(hash(uint(g.x), uint(g.y))) / 4294967296.0 - 0.5) / 255.0;
    vec3 q = floor(clamp(clamp(c, 0.0, 1.0) + d, 0.0, 1.0) * alpha + 0.5);
    outColor = vec4(q / 255.0, alpha / 255.0);
}
