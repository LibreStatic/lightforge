#ifndef HVX_WEIGHTS_H
#define HVX_WEIGHTS_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Converts the app's ncnn RIFE v4.6 weights (flownet.bin + flownet.param) into the LFW_KIND_HVX blob that
// lfrife_open() expects (layout in lfrife_weights.h). Plain standard C++ so tools/check-hvx-weights.sh can
// build it on the host and compare the result byte for byte with the lightforge-hvx exporter.
namespace hvxw {

// Size of the converted blob for RIFE v4.6.
constexpr size_t kHvxBlobBytes = 11471616;

// Returns false (with a message in [error]) when the model does not look like RIFE v4.6.
bool convert_ncnn_to_hvx(const uint8_t* bin, size_t bin_size, const char* param, size_t param_size,
                         std::vector<uint8_t>& blob, std::string& error);

// True when [blob] is a complete LFW_KIND_HVX blob of the expected size (cheap integrity check).
bool is_valid_hvx_blob(const uint8_t* blob, size_t size);

}  // namespace hvxw

#endif
