#pragma once

#include <string>

namespace pokeclip {

// x264opts / NVENC opts에서 키프레임 간격을 덮어쓰는 토큰(keyint·min-keyint·keyint_min·gop·idrint)을 지운다.
// 이 옵션들은 keyint_sec보다 늦게 적용돼 이기므로, GOP 2s 강제(ADR-020) 전에 걷어낸다.
std::string StripKeyintOverrides(const std::string &opts);

} // namespace pokeclip
