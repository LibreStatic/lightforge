// Host-side check for core/frame-interpolation's ncnn -> LFW_KIND_HVX weight converter.
// Usage: hvx-weights-check flownet.bin flownet.param reference_hvx.bin
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

#include "hvx_weights.h"

static std::vector<uint8_t> slurp(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
}

int main(int argc, char** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s flownet.bin flownet.param reference_hvx.bin\n", argv[0]);
        return 2;
    }
    const std::vector<uint8_t> bin = slurp(argv[1]), param = slurp(argv[2]), reference = slurp(argv[3]);
    std::vector<uint8_t> blob;
    std::string error;
    if (!hvxw::convert_ncnn_to_hvx(bin.data(), bin.size(), reinterpret_cast<const char*>(param.data()),
                                     param.size(), blob, error)) {
        std::fprintf(stderr, "conversion failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("converted %zu bytes, reference %zu bytes, valid=%d\n", blob.size(), reference.size(),
                hvxw::is_valid_hvx_blob(blob.data(), blob.size()));
    if (blob.size() != reference.size()) return 1;
    size_t diff = 0, first = 0;
    for (size_t i = 0; i < blob.size(); ++i)
        if (blob[i] != reference[i] && diff++ == 0) first = i;
    std::printf("differing bytes: %zu (first at %zu)\n", diff, first);
    std::puts(diff == 0 ? "MATCH" : "MISMATCH");
    return diff == 0 ? 0 : 1;
}
