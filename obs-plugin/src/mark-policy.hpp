#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace pokeclip {

// A4 핫키 마킹 — 스트리머가 단축키로 「지금 이 순간」을 표시하면 누른 시각을 서버로 보낸다.
// 여기는 OBS·curl 없이 시험하는 규칙만 둔다(본문·재시도 간격·버림·응답 판정·연타 거르기).
// 서버 계약은 제안 단계다(Jira POK-119): POST /api/clip/streams/{streamToken}/marks,
// Authorization: Bearer <passphrase>, 본문 {eventId, pressedAt, sentAt}. 카드 창 [T−30s, T+10s]은 서버가 정한다.

inline constexpr int64_t kMarkDebounceMs = 2000;         // 이보다 가까운 누름은 한 번으로 친다
inline constexpr int64_t kMarkRetryFirstMs = 1000;       // 1·2·4·8…초
inline constexpr int64_t kMarkRetryMaxMs = 30000;        // 재시도 간격 상한
inline constexpr int64_t kMarkRetryAfterMaxMs = 60000;   // 서버 Retry-After도 이 이상은 기다리지 않는다
inline constexpr int64_t kMarkGiveUpMs = 10 * 60 * 1000; // 누른 지 10분이 지나도 못 보냈으면 버린다
inline constexpr size_t kMarkQueueCap = 100;             // 대기열이 이보다 길면 가장 오래된 것부터 버린다
inline constexpr const char *kMarkPathPrefix = "/api/clip/streams/";
inline constexpr const char *kMarkPathSuffix = "/marks";

// clipBase(끝 '/'는 무시) + /api/clip/streams/{token}/marks. 토큰은 경로 조각으로 퍼센트 인코딩한다.
std::string MarkUrl(const std::string &clipBase, const std::string &streamToken);

// 서버로 보낼 본문. 시각은 모두 스트리머 PC 시계(UTC epoch ms) — 시계 어긋남은 서버가 sentAt으로 보정한다.
std::string MarkBodyJson(const std::string &eventId, int64_t pressedAt, int64_t sentAt);

// 무작위 16바이트로 UUID v4 문자열을 만든다(버전·변형 비트를 고정한다). 재전송해도 같은 값을 쓴다 — 서버 멱등 열쇠.
std::string FormatUuidV4(std::array<uint8_t, 16> bytes);

// attempt번째(1부터) 실패 뒤 기다릴 시간. 서버가 Retry-After를 줬으면 둘 중 긴 쪽(상한 60초).
int64_t MarkRetryDelayMs(int attempt, int64_t retryAfterMs = 0);

// Retry-After 헤더(초 단위 정수만). 없거나 못 읽으면 0.
int64_t ParseRetryAfterMs(const std::string &header);

enum class MarkOutcome { Delivered, Retry, Drop };

struct MarkVerdict {
	MarkOutcome outcome = MarkOutcome::Retry;
	// 독·폴백 패널 사유 코드(copy.ts REASON · locale Reason.*). 성공이면 빈 문자열.
	std::string reason;
};

// 전송 결과 → 다음 할 일. transportOk=false면 네트워크 실패(status 무시).
// 404는 둘로 가른다: 서버가 사유(broadcast_not_found)를 달았으면 방송 명부가 아직 없는 것이라 다시 보내고,
// 사유가 없으면 경로 자체가 없는 것(서버가 아직 마크를 안 받는다)이라 버린다.
MarkVerdict ClassifyMarkResponse(bool transportOk, long status, const std::string &body);

// 누른 뒤 지난 시간으로 버릴지.
bool MarkExpired(int64_t pressedSteadyMs, int64_t nowSteadyMs);

// 독 안내에 보여줄 단축키 표기. 글자·숫자·F키는 자판 배열과 무관하게 키 이름(OBS_KEY_M → M)으로 적는다 —
// macOS OBS 표기(obs_key_combination_to_str)는 입력 소스를 따라 한글 자판이면 M을 「ㅡ」로 보여준다(2026-09-30 실측).
// 그 밖의 키는 keyText(OBS 표기)를, 그것도 없으면 이름 뒷부분을 쓴다.
struct HotkeyLabelParts {
	bool control = false;
	bool alt = false;
	bool shift = false;
	bool command = false; // macOS ⌘ · Windows Win
	std::string keyName;  // obs_key_to_name — "OBS_KEY_M", 수정자만이면 빈 문자열
	std::string keyText;  // obs_key_to_str — 자판 배열을 따른 표기
};
std::string FormatHotkeyLabel(const HotkeyLabelParts &parts, bool mac);

// 연타 거르기. 단조 시계 ms로 부른다. 받아들이면 true(그 시각을 기억한다).
class MarkDebouncer {
public:
	bool Accept(int64_t nowSteadyMs);

private:
	bool hasLast_ = false;
	int64_t lastMs_ = 0;
};

} // namespace pokeclip
