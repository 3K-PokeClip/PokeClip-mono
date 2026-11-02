#pragma once

#include <string>

namespace pokeclip {

// 사람이 친 코드를 서버와 같은 규칙으로 정규화한다 (auth CrockfordBase32.normalize: '-' 제거·대문자·I/L→1·O→0).
// 공백도 버린다. 8자가 아니거나 알파벳 밖 문자가 있으면 빈 문자열.
std::string NormalizePairingCode(const std::string &input);

// 붙여넣을 글(OS 클립보드)에서 연결 코드 하나만 고른다(POK-495). 독 ui/src/lib/format.ts extractPairingCode와 같은 순서:
//   1. 하이픈(-·–·—)으로 이은 4자-4자  2. 붙은 8자·공백 하나로 나뉜 4자 4자 — 여럿이면 숫자가 든 것을 먼저, 없으면 맨 뒤 것.
// 독의 3단계(앞에서부터 유효한 글자 모으기)는 하지 않는다 — 코드가 아닌 클립보드 글이 조금도 독으로 새지 않게.
// 찾으면 정규화한 8자(NormalizePairingCode), 없으면 빈 문자열. 앞 4096바이트만 본다.
std::string ExtractPairingCode(const std::string &text);

} // namespace pokeclip
