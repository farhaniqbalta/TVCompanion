#include "playout_clock.h"
#include "h264_config.h"
#include "video_preroll.h"
#include "audio_formats.h"
#include "ALACEncoder.h"
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include <cassert>
#include <cstring>
#include <vector>
#include <iostream>
int main() {
    using namespace tvc;
    assert(presentationTime(1000000000, 1000000000, 5000000000) == 5080000000);
    assert(presentationTime(1010000000, 1000000000, 5000000000) == 5090000000);
    assert(presentationTime(990000000, 1000000000, 5000000000) == 5070000000);
    assert(presentationTime(0, 1000000000, 5000000000) == 5080000000);
    assert(presentationTime(UINT64_MAX, 1000000000, 5000000000) == 5080000000);
    assert(expired(1000000000, 1200000000)); assert(!expired(1000000000, 1050000000));
    H264Config config;
    const uint8_t frame[] = {0,0,0,1,0x67,1,2,0,0,1,0x68,3,0,0,0,1,0x41,4,5};
    config.inspect(frame, sizeof(frame)); assert(config.complete()); assert(config.sps.size() == 7); assert(config.pps.size() == 6);
    const auto sps = config.sps, pps = config.pps;
    const uint8_t pframe[] = {0,0,1,0x41,1,2};
    config.inspect(pframe, sizeof(pframe)); assert(config.complete()); assert(config.sps == sps && config.pps == pps);
    const uint8_t truncated[] = {0,0,0}; config.inspect(truncated, sizeof(truncated)); assert(config.complete());
    std::vector<uint8_t> oversized(100000, 9); oversized[0]=oversized[1]=oversized[2]=0; oversized[3]=1; oversized[4]=0x67;
    config.inspect(oversized.data(), oversized.size()); assert(config.sps == sps);
    VideoPreroll preroll;
    preroll.append(frame, sizeof(frame), 100); preroll.append(pframe, sizeof(pframe), 200);
    assert(preroll.packets.size() == 2 && preroll.packets.front().bytes == std::vector<uint8_t>(frame, frame + sizeof(frame)));
    assert(preroll.packets.back().pts == 200);
    preroll.append(frame, sizeof(frame), 1000101); assert(preroll.exhausted && preroll.bytes == 0 && preroll.packets.empty());
    preroll.clear(); for (int i=0; i<61; ++i) preroll.append(frame, sizeof(frame), i);
    assert(preroll.exhausted && preroll.bytes == 0);
    preroll.clear(); std::vector<uint8_t> large(1024*1024, 1);
    preroll.append(large.data(), large.size(), 0); preroll.append(large.data(), large.size(), 1);
    assert(preroll.bytes == 2*1024*1024); preroll.append(frame, sizeof(frame), 2);
    assert(preroll.exhausted && preroll.packets.empty());
    // Independent encoder produces a real stereo ALAC packet; decode using production cookie.
    constexpr unsigned frames = 352;
    AudioFormatDescription input{}; input.mSampleRate=44100; input.mFormatID=kALACFormatLinearPCM;
    input.mFormatFlags=kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked;
    input.mChannelsPerFrame=2; input.mBitsPerChannel=16; input.mFramesPerPacket=1; input.mBytesPerPacket=4; input.mBytesPerFrame=4;
    AudioFormatDescription output{}; output.mSampleRate=44100; output.mFormatID=kALACFormatAppleLossless;
    output.mFormatFlags=1; output.mChannelsPerFrame=2; output.mBitsPerChannel=16; output.mFramesPerPacket=frames;
    ALACEncoder encoder; encoder.SetFrameSize(frames); assert(encoder.InitializeEncoder(output)==0);
    std::vector<int16_t> original(frames*2); for (size_t i=0; i<original.size(); ++i) original[i]=static_cast<int16_t>(static_cast<int>((i*127)%50000)-25000);
    std::vector<uint8_t> packet(8192); int32_t count=static_cast<int32_t>(original.size()*2);
    assert(encoder.Encode(input,output,reinterpret_cast<uint8_t*>(original.data()),packet.data(),&count)==0);
    assert(count>0 && count<8192);
    auto cookie=alacCookie(frames); ALACDecoder decoder; assert(decoder.Init(cookie.data(),cookie.size())==0);
    BitBuffer bits; BitBufferInit(&bits,packet.data(),count); std::vector<int16_t> decoded(frames*2); uint32_t actual=0;
    assert(decoder.Decode(&bits,reinterpret_cast<uint8_t*>(decoded.data()),frames,2,&actual)==0);
    assert(actual==frames); assert(decoded==original);
    std::cout << "Native checks passed: shared playout clock, bounded H.264 configuration, stereo ALAC round-trip.\n";
}
