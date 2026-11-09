#pragma once

#include <optional>

namespace pokeclip {

// SRT 지연 허용치(latency_ms) 규칙 — OBS 없이 시험한다(POK-556). 범위·기본값은 constants.hpp.

// 독·브리지 PUT이 받는 값인가(kMinLatencyMs–kMaxLatencyMs).
bool IsValidLatencyMs(int ms);

// 설정 파일에서 읽은 latency_ms를 쓸 값으로 바꾼다. stored = 파일의 값(키가 없으면 nullopt), migrated = 파일에
// 이관 표지(latency_migrated — 저장할 때마다 쓴다)가 있다.
//  - 값이 없거나 범위 밖이면(파일을 손으로 고쳤다) 지금 기본값.
//  - 표지 없는 파일(POK-556 이전 저장)의 옛 기본값(kLegacyDefaultLatencyMs)은 손대지 않은 기본값으로 보고 지금
//    기본값으로 올린다. 올린 값은 다음 저장 때 표지와 함께 파일에 남는다.
//  - 그 밖은 고른 값이라 그대로 — 표지가 있으면 옛 기본값과 같은 값도 이관 뒤 다시 고른 것이다.
int LoadedLatencyMs(std::optional<int> stored, bool migrated);

} // namespace pokeclip
