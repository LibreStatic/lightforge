#ifndef GUIDED_COMPOSE_H
#define GUIDED_COMPOSE_H

#include <cstddef>
#include <cstdint>

struct GuidedComposeParams {
    const uint8_t* first;   // RGBA_8888, width x height
    const uint8_t* second;  // RGBA_8888, width x height
    uint8_t* output;        // RGBA_8888, width x height
    size_t first_stride;
    size_t second_stride;
    size_t output_stride;
    int width;
    int height;
    int low_width;
    int low_height;
    const float* flow;      // 4 planes of the padded network grid
    size_t flow_cstep;      // floats between flow planes
    size_t flow_stride;     // floats per flow row (padded width)
    const float* mask;      // 1 plane, same row stride as flow
    size_t mask_stride;
};

// Builds the intermediate frame at full resolution by warping the high-res
// frames with the (bilinearly upsampled) low-res flow and blending with the mask.
void guided_compose(const GuidedComposeParams& params);

#endif
