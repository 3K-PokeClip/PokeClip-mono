#pragma once

#include "config.hpp"
#include "srt-url.hpp"

#include <string>

struct obs_data;

namespace pokeclip {

// rtmp_custom 서비스 설정. key → SRT streamid, use_auth+password → SRT passphrase.
// 호출자가 obs_data_release 한다.
obs_data *BuildSrtServiceSettings(const PluginConfig &config);

} // namespace pokeclip
