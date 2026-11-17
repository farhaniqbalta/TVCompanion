#pragma once
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
namespace tvc {
class AmbientTap;
/** Local native sink. All decoder/device operations are confined to one lazy worker. */
class AudioOutput {
public:
    explicit AudioOutput(std::function<void(int)> event);
    ~AudioOutput();
    void configure(unsigned type, unsigned samplesPerFrame);
    void submit(unsigned type, const uint8_t *data, size_t size, int64_t presentationNs);
    void focus(bool allowed, float duck);
    void volume(float decibels);
    void flush();
    void end();
    void setAmbientTap(AmbientTap *tap);   // receiver-owned; must outlive this output
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
}
