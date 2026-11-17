#pragma once
#include <cstddef>

namespace tvc {
// Radix-2 FFT of n real samples (n a power of 2, >= 2); writes n/2+1 unnormalized magnitudes.
// Allocates scratch, so call from the analyzer thread only.
void realFftMagnitudes(const float *in, int n, float *magnitudes);
}
