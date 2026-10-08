#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace pokeclip {

// A3 의도적 종료 신호(계약4 4D) — 스트리머가 우리 SRT 송출을 멈추는 조작을 하면, 우리 출력이 실제로 멈춘 직후
// Media에 1회 알린다. Media는 그 회차를 멈춘 순간에 끝내고(보장 시간 300초를 기다리지 않고) 뒤 송출을 새 회차로
// 시작한다. 규격 정본 = 위키 계약4 3-1절(2026-09-26 발행, POK-249). 여기는 OBS·curl 없이 시험하는 규칙만 둔다
// (파생 값 · 본문 · 응답 판정 · 재시도 간격 · 만료 · 보낼지 판정 · 기준 주소).

// 운영 기준 주소. 바꿀 수 있는 것은 루프백 주소뿐이다(EndSignalUrl) — 규약 「개발 빌드만 루프백」을 빌드 플래그
// 없이 지킨다: 루프백이 아닌 값은 어느 빌드에서도 받지 않는다.
inline constexpr const char *kEndSignalBase = "https://media.pokeclip.com";
inline constexpr const char *kEndSignalPath = "/api/ingest/end-signal";
// 파생 값의 메시지. 키는 passphrase 문자열의 UTF-8 바이트다.
inline constexpr const char *kEndSignalHmacMessage = "pokeclip-4d-v1";
inline constexpr int kEndSignalMaxAttempts = 3;                   // 첫 시도 포함
inline constexpr int64_t kEndSignalRetryDelayMs[] = {1000, 2000}; // 첫 실패 1초 뒤, 둘째 실패 2초 뒤
inline constexpr long kEndSignalAttemptTimeoutSec = 5;            // 시도당 제한 — 최악 약 18초 뒤 포기
inline constexpr int64_t kEndSignalExpireMs = 60000; // 멈춘 순간부터 이 시간이 지나면 보내지 않는다(elapsedSinceStopMs 상한)

// SHA-256(FIPS 180-4). 외부 의존이 없다 — OBS deps에 OpenSSL 헤더가 없고, Qt는 OBS 없는 테스트에 못 묶는다.
std::array<uint8_t, 32> Sha256(const std::string &data);
// HMAC-SHA256(RFC 2104) → 소문자 16진 64자. 64바이트보다 긴 키는 먼저 해시한다(passphrase는 최대 79자).
std::string HmacSha256Hex(const std::string &key, const std::string &message);
// 파생 값 = HMAC-SHA256(passphrase, "pokeclip-4d-v1"). Authorization: Bearer <이 값>. passphrase 원문은 보내지 않는다.
std::string EndSignalDerivedValue(const std::string &passphrase);

// 주소가 이 PC 안(루프백)인가 — http·https 모두. 호스트가 localhost · 127.x.x.x · [::1]이고 포트는 숫자뿐이어야 한다.
bool IsLoopbackBase(const std::string &base);
// 요청 주소. override가 루프백이면 그것(끝 '/'는 무시), 아니면 kEndSignalBase. 둘 다 + kEndSignalPath.
std::string EndSignalUrl(const std::string &baseOverride);

// 요청 본문. streamid는 SRT에 쓴 원문 그대로(계약4C와 같은 값·이름 — streamId가 아니다).
// 두 시간 값은 단조 시계 ms이고 기준은 우리 출력이 실제로 멈춘 순간이다.
std::string EndSignalBodyJson(const std::string &streamId, int64_t elapsedSinceStopMs, int64_t connectionDurationMs);

enum class EndSignalOutcome { Done, Retry, Stop };

struct EndSignalVerdict {
	EndSignalOutcome outcome = EndSignalOutcome::Stop;
	const char *reason = ""; // 로그용 짧은 사유
};

// 응답 → 다음 할 일. 플러그인은 상태 코드만 본다(본문은 사람용). transportOk=false면 연결 실패·시간 초과(status 무시).
// 202(와 그 밖 2xx)는 끝. 3xx·4xx는 무엇이든 끝 — 재시도 금지(리다이렉트를 따르지 않는다). 5xx·전송 실패만 재시도.
EndSignalVerdict ClassifyEndSignalResponse(bool transportOk, long status);

// attempt번째(1부터) 시도가 실패한 뒤 다음 시도까지 기다릴 ms. 더 시도하지 않으면 -1.
int64_t EndSignalRetryDelayMs(int attempt);

// 멈춘 순간부터 지난 시간으로 보낼 수 없게 됐는가(첫 시도든 재시도든). 범위 밖이면 400이 된다.
bool EndSignalExpired(int64_t elapsedSinceStopMs);

// 보내지 않는 이유(계약4 3-1 「보내지 않는다」). None이면 보낸다.
//  - NotIntentional: 스트리머 조작이 아니다 — 오류·끊김으로 멈춤, 자동 재연결 포기, 독 「재시도 중지」(본방은 살아 있고
//    마지막 연결은 이미 끊겼다 — 보내면 키를 고쳐 다시 붙는 송출이 새 회차가 돼 되감기를 잃는다), 본방이 스스로 끊겨
//    끝남(OBS 「방송 종료」 조작이 아니다), 사유를 든 정지(본방 시작 실패).
//  - NeverConnected: 그 송출에서 성립한 연결이 한 번도 없었다.
enum class EndSignalSkip { None, NotIntentional, NeverConnected };
EndSignalSkip EndSignalSkipReason(bool intentional, bool everConnected);
const char *EndSignalSkipName(EndSignalSkip skip);

// connectionDurationMs — 마지막 연결의 지속 시간. 멈출 때 붙어 있었으면 멈춘 순간까지, 멈추기 전에 끊겨 다시 붙는
// 중이었으면 끊김을 안 순간(outageAt)까지. 모두 단조 시계 ms. 음수가 되는 어긋남은 0으로.
int64_t ConnectionDurationMs(int64_t connectedAtMs, bool connectedAtStop, int64_t outageAtMs, int64_t stopAtMs);

} // namespace pokeclip
