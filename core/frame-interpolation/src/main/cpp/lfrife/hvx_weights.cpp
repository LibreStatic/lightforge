#include "hvx_weights.h"

#include <cstring>
#include <sstream>

namespace hvxw {
namespace {
constexpr int kBlocks = 4;
constexpr int kBlockCin[kBlocks] = {7, 12, 12, 12};
constexpr int kBlockC[kBlocks] = {192, 128, 96, 64};
constexpr int kBlockScale[kBlocks] = {8, 4, 2, 1};
constexpr int kLayersPerBlock = 11;  // conv0, conv1, 8 x ResConv, deconv
constexpr int kLayers = kBlocks * kLayersPerBlock;
constexpr size_t kHeaderBytes = 96;
constexpr size_t kLayerBytes = 48;
constexpr uint32_t kNcnnFp16Tag = 0x01306B47;

enum LayerType : uint32_t { ConvS2 = 0, ResConv = 1, Deconv = 2 };

struct Layer {
    LayerType type;
    int cin, cout, stride;
    std::vector<float> w;  // torch layout: conv [co][ci][3][3], deconv [ci][24][4][4]
    std::vector<float> b;
};

struct NcnnConv {
    bool deconv;
    int cout, weights, has_bias, kernel;
};

uint16_t float_to_half(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    const int32_t exp = static_cast<int32_t>((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffffu;
    if (((x >> 23) & 0xff) == 0xff) return static_cast<uint16_t>(sign | 0x7c00u | (mant ? 0x200u : 0));
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        const int shift = 14 - exp;
        uint32_t half = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1), mid = 1u << (shift - 1);
        if (rem > mid || (rem == mid && (half & 1))) ++half;
        return static_cast<uint16_t>(sign | half);
    }
    uint32_t half = (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    const uint32_t rem = mant & 0x1fffu;
    if (rem > 0x1000u || (rem == 0x1000u && (half & 1))) ++half;  // may carry into the exponent: correct
    return static_cast<uint16_t>(sign | half);
}

float half_to_float(uint16_t h) {
    const uint32_t sign = (h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1f, mant = h & 0x3ffu, bits;
    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            exp = 127 - 15 + 1;
            while (!(mant & 0x400u)) { mant <<= 1; --exp; }
            bits = sign | (exp << 23) | ((mant & 0x3ffu) << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000u | (mant << 13);
    } else {
        bits = sign | ((exp + 127 - 15) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

// ncnn param: "type name nin nout <bottoms> <tops> key=value ...". Collects Convolution/Deconvolution in order.
bool parse_param(const char* param, size_t size, std::vector<NcnnConv>& out, std::string& error) {
    std::istringstream stream(std::string(param, size));
    std::string line;
    int line_no = 0;
    while (std::getline(stream, line)) {
        if (++line_no <= 2) continue;  // magic, counts
        std::istringstream tokens(line);
        std::vector<std::string> t;
        for (std::string s; tokens >> s;) t.push_back(s);
        if (t.size() < 4 || (t[0] != "Convolution" && t[0] != "Deconvolution")) continue;
        const size_t first = 4 + std::stoul(t[2]) + std::stoul(t[3]);
        NcnnConv c{t[0] == "Deconvolution", 0, 0, 0, 0};
        for (size_t i = first; i < t.size(); ++i) {
            const size_t eq = t[i].find('=');
            if (eq == std::string::npos || t[i][0] == '-') continue;
            const int key = std::stoi(t[i].substr(0, eq));
            const int value = std::stoi(t[i].substr(eq + 1));
            if (key == 0) c.cout = value;
            else if (key == 1) c.kernel = value;
            else if (key == 5) c.has_bias = value;
            else if (key == 6) c.weights = value;
        }
        if (c.cout <= 0 || c.weights <= 0 || !c.has_bias) {
            error = "unsupported convolution parameters on param line " + std::to_string(line_no);
            return false;
        }
        out.push_back(c);
    }
    return true;
}

bool read_weights(const uint8_t* bin, size_t size, const std::vector<NcnnConv>& convs,
                  std::vector<std::vector<float>>& weights, std::vector<std::vector<float>>& biases,
                  std::string& error) {
    size_t off = 0;
    for (const NcnnConv& c : convs) {
        uint32_t tag;
        if (off + 4 > size) { error = "flownet.bin is truncated"; return false; }
        std::memcpy(&tag, bin + off, 4);
        off += 4;
        std::vector<float> w(static_cast<size_t>(c.weights));
        if (tag == kNcnnFp16Tag) {
            if (off + 2u * w.size() > size) { error = "flownet.bin is truncated"; return false; }
            for (size_t i = 0; i < w.size(); ++i) {
                uint16_t h;
                std::memcpy(&h, bin + off + 2 * i, 2);
                w[i] = half_to_float(h);
            }
            off += 2 * w.size();
            off = (off + 3) / 4 * 4;
        } else if (tag == 0) {
            if (off + 4u * w.size() > size) { error = "flownet.bin is truncated"; return false; }
            std::memcpy(w.data(), bin + off, 4 * w.size());
            off += 4 * w.size();
        } else {
            error = "unsupported ncnn weight tag";
            return false;
        }
        std::vector<float> b(static_cast<size_t>(c.cout));
        if (off + 4u * b.size() > size) { error = "flownet.bin is truncated"; return false; }
        std::memcpy(b.data(), bin + off, 4 * b.size());
        off += 4 * b.size();
        weights.push_back(std::move(w));
        biases.push_back(std::move(b));
    }
    if (off != size) { error = "flownet.bin has unexpected trailing data"; return false; }
    return true;
}

void put32(std::vector<uint8_t>& v, size_t at, uint32_t x) {
    for (int i = 0; i < 4; ++i) v[at + i] = static_cast<uint8_t>(x >> (8 * i));
}
void put64(std::vector<uint8_t>& v, size_t at, uint64_t x) {
    for (int i = 0; i < 8; ++i) v[at + i] = static_cast<uint8_t>(x >> (8 * i));
}
size_t align128(size_t n) { return (n + 127) / 128 * 128; }

// w[co][ci][3][3] -> fp16 [ob][tap][ci][64 lanes]
std::vector<uint16_t> hvx_conv_weights(const Layer& l) {
    const int nob = (l.cout + 63) / 64;
    std::vector<uint16_t> out(static_cast<size_t>(nob) * 9 * l.cin * 64, 0);
    for (int co = 0; co < l.cout; ++co)
        for (int ci = 0; ci < l.cin; ++ci)
            for (int tap = 0; tap < 9; ++tap)
                out[((static_cast<size_t>(co / 64) * 9 + tap) * l.cin + ci) * 64 + co % 64] =
                    float_to_half(l.w[(static_cast<size_t>(co) * l.cin + ci) * 9 + tap]);
    return out;
}

// fp32 [ob][2][32]: lane 2i then 2i+1 of each 64-wide output block.
std::vector<float> hvx_bias(const std::vector<float>& b, int nob) {
    std::vector<float> padded(static_cast<size_t>(nob) * 64, 0.f), out(static_cast<size_t>(nob) * 64);
    std::copy(b.begin(), b.end(), padded.begin());
    for (int ob = 0; ob < nob; ++ob)
        for (int eo = 0; eo < 2; ++eo)
            for (int i = 0; i < 32; ++i) out[(ob * 2 + eo) * 32 + i] = padded[ob * 64 + 2 * i + eo];
    return out;
}

int deconv_lane(int pv, int i, int j, int ch) { return ((pv * 2 + i) * 2 + j) * 5 + ch; }

// w[ci][24][4][4] -> fp16 [pu][tap6][ci][64], bias fp32 [2][32]
void hvx_deconv(const Layer& l, std::vector<uint16_t>& wout, std::vector<float>& bout) {
    wout.assign(static_cast<size_t>(2) * 6 * l.cin * 64, 0);
    // [pu][dy] -> kernel row/column, also valid for kx with pv
    static const int ky_of[2][3] = {{3, 1, -1}, {-1, 2, 0}};  // index dy + 1 (dy -1..1); -1 = unused
    for (int pu = 0; pu < 2; ++pu)
        for (int dyi = 0; dyi < 2; ++dyi) {
            const int dy = dyi - 1 + pu;
            const int ky = ky_of[pu][dy + 1];
            for (int dxi = 0; dxi < 3; ++dxi) {
                const int dx = dxi - 1;
                const int tap = dyi * 3 + dxi;
                for (int pv = 0; pv < 2; ++pv) {
                    const int kx = ky_of[pv][dx + 1];
                    if (kx < 0) continue;
                    for (int i = 0; i < 2; ++i)
                        for (int j = 0; j < 2; ++j)
                            for (int ch = 0; ch < 5; ++ch) {
                                const int lane = deconv_lane(pv, i, j, ch);
                                for (int ci = 0; ci < l.cin; ++ci)
                                    wout[((static_cast<size_t>(pu) * 6 + tap) * l.cin + ci) * 64 + lane] = float_to_half(
                                        l.w[((static_cast<size_t>(ci) * 24) + ch * 4 + i * 2 + j) * 16 + ky * 4 + kx]);
                            }
                }
            }
        }
    float bias[64] = {};
    for (int pv = 0; pv < 2; ++pv)
        for (int i = 0; i < 2; ++i)
            for (int j = 0; j < 2; ++j)
                for (int ch = 0; ch < 5; ++ch) bias[deconv_lane(pv, i, j, ch)] = l.b[ch * 4 + i * 2 + j];
    bout.resize(64);
    for (int eo = 0; eo < 2; ++eo)
        for (int i = 0; i < 32; ++i) bout[eo * 32 + i] = bias[2 * i + eo];
}
}  // namespace

bool convert_ncnn_to_hvx(const uint8_t* bin, size_t bin_size, const char* param, size_t param_size,
                         std::vector<uint8_t>& blob, std::string& error) {
    std::vector<NcnnConv> convs;
    if (!parse_param(param, param_size, convs, error)) return false;
    if (convs.size() != kLayers) {
        error = "expected 44 convolution layers, found " + std::to_string(convs.size());
        return false;
    }
    std::vector<std::vector<float>> weights, biases;
    if (!read_weights(bin, bin_size, convs, weights, biases, error)) return false;

    std::vector<Layer> layers;
    for (int k = 0; k < kLayers; ++k) {
        const int block = k / kLayersPerBlock, pos = k % kLayersPerBlock;
        const int c = kBlockC[block];
        Layer l;
        if (pos == 0) l = {ConvS2, kBlockCin[block], c / 2, 2, {}, {}};
        else if (pos == 1) l = {ConvS2, c / 2, c, 2, {}, {}};
        else if (pos < 10) l = {ResConv, c, c, 1, {}, {}};
        else l = {Deconv, c, 24, 2, {}, {}};
        const NcnnConv& n = convs[k];
        const int kernel = l.type == Deconv ? 4 : 3;
        if (n.deconv != (l.type == Deconv) || n.cout != l.cout || n.kernel != kernel ||
            static_cast<size_t>(n.weights) != static_cast<size_t>(l.cin) * l.cout * kernel * kernel) {
            error = "layer " + std::to_string(k) + " does not match RIFE v4.6";
            return false;
        }
        if (l.type == Deconv) {
            // ncnn stores deconvolution weights as (out, in, ky, kx); torch ConvTranspose is (in, out, ky, kx).
            l.w.resize(weights[k].size());
            for (int ci = 0; ci < l.cin; ++ci)
                for (int co = 0; co < 24; ++co)
                    for (int ky = 0; ky < 4; ++ky)
                        for (int kx = 0; kx < 4; ++kx)
                            l.w[((static_cast<size_t>(ci) * 24 + co) * 4 + ky) * 4 + kx] =
                                weights[k][((static_cast<size_t>(co) * l.cin + ci) * 4 + ky) * 4 + kx];
        } else {
            l.w = weights[k];  // ResConv beta is 1.0 in this model, so nothing to fold
        }
        l.b = biases[k];
        layers.push_back(std::move(l));
    }

    const size_t base = align128(kHeaderBytes + kLayers * kLayerBytes);
    std::vector<uint8_t> data;
    std::vector<std::vector<uint64_t>> table;
    const auto append = [&](const void* p, size_t n) {
        const size_t at = data.size();
        data.insert(data.end(), static_cast<const uint8_t*>(p), static_cast<const uint8_t*>(p) + n);
        data.resize(align128(data.size()), 0);
        return base + at;
    };
    for (const Layer& l : layers) {
        uint64_t w_off, w_bytes, b_off, b_bytes;
        if (l.type == Deconv) {
            std::vector<uint16_t> w;
            std::vector<float> b;
            hvx_deconv(l, w, b);
            w_bytes = w.size() * 2;
            w_off = append(w.data(), w_bytes);
            b_bytes = b.size() * 4;
            b_off = append(b.data(), b_bytes);
        } else {
            const std::vector<uint16_t> w = hvx_conv_weights(l);
            const std::vector<float> b = hvx_bias(l.b, (l.cout + 63) / 64);
            w_bytes = w.size() * 2;
            w_off = append(w.data(), w_bytes);
            b_bytes = b.size() * 4;
            b_off = append(b.data(), b_bytes);
        }
        table.push_back({l.type, static_cast<uint64_t>(l.cin), static_cast<uint64_t>(l.cout),
                         static_cast<uint64_t>(l.stride), w_off, w_bytes, b_off, b_bytes});
    }

    const size_t total = base + data.size();
    blob.assign(total, 0);
    std::memcpy(blob.data(), "LFRIFE46", 8);
    put32(blob, 8, 1);  // version
    put32(blob, 12, 1);  // LFW_KIND_HVX
    put32(blob, 16, kLayers);
    put64(blob, 24, total);
    for (int i = 0; i < kBlocks; ++i) {
        put32(blob, 32 + 4 * i, kBlockCin[i]);
        put32(blob, 48 + 4 * i, kBlockC[i]);
        put32(blob, 64 + 4 * i, kBlockScale[i]);
    }
    for (int k = 0; k < kLayers; ++k) {
        const size_t at = kHeaderBytes + k * kLayerBytes;
        const std::vector<uint64_t>& t = table[k];
        for (int f = 0; f < 4; ++f) put32(blob, at + 4 * f, static_cast<uint32_t>(t[f]));
        for (int f = 4; f < 8; ++f) put64(blob, at + 16 + 8 * (f - 4), t[f]);
    }
    std::memcpy(blob.data() + base, data.data(), data.size());
    return true;
}

bool is_valid_hvx_blob(const uint8_t* blob, size_t size) {
    if (!blob || size != kHvxBlobBytes || std::memcmp(blob, "LFRIFE46", 8) != 0) return false;
    uint32_t kind;
    uint64_t total;
    std::memcpy(&kind, blob + 12, 4);
    std::memcpy(&total, blob + 24, 8);
    return kind == 1 && total == size;
}

}  // namespace hvxw
