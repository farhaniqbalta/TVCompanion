#include "ambient/fft.h"
#include <cmath>
#include <utility>
#include <vector>

namespace tvc {
void realFftMagnitudes(const float *in, int n, float *magnitudes) {
    std::vector<double> re(in, in + n), im(n, 0.0);
    for (int i = 1, j = 0; i < n; ++i) {  // bit-reversal permutation
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(re[i], re[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * M_PI / len;
        for (int i = 0; i < n; i += len) {
            for (int k = 0; k < len / 2; ++k) {
                const double wr = std::cos(ang * k), wi = std::sin(ang * k);
                const int a = i + k, b = a + len / 2;
                const double tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr; im[b] = im[a] - ti;
                re[a] += tr; im[a] += ti;
            }
        }
    }
    for (int k = 0; k <= n / 2; ++k) magnitudes[k] = (float)std::sqrt(re[k] * re[k] + im[k] * im[k]);
}
}
