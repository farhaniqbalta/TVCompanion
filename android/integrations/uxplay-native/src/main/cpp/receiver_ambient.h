#pragma once
#include <jni.h>
#include <memory>
#include "ambient/ambient_source.h"
namespace tvc {
// Receiver handle -> its ambient source. Call only where the handle is valid (engine worker).
std::shared_ptr<AmbientSource> receiverAmbient(jlong handle);
}
