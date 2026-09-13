#pragma once

#include <string>

namespace pokeclip {

// 사람이 친 코드를 서버와 같은 규칙으로 정규화한다 (auth CrockfordBase32.normalize: '-' 제거·대문자·I/L→1·O→0).
// 공백도 버린다. 8자가 아니거나 알파벳 밖 문자가 있으면 빈 문자열.
std::string NormalizePairingCode(const std::string &input);

} // namespace pokeclip
