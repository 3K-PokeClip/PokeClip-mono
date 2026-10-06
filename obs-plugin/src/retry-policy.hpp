#pragma once

#include <cstdint>
#include <string>

namespace pokeclip {

// A5 자동 재연결 — 우리 SRT 출력이 끊기거나 처음부터 붙지 못했을 때 플러그인이 다시 시도하는 규칙.
// libobs 재연결은 간격을 약 1.5배씩 늘려(obs-output.c) 300초 창 근처에서 한 번에 1~2분씩 비므로 쓰지 않는다.
// 여기는 OBS 없이 시험하는 규칙만 둔다(간격 표·정지 코드 분류·키 의심 판정).

// 같은 키로 이 시간 안에 돌아오면 Media가 같은 회차로 잇는다(계약9 — 연결 끊김 보장 시간). 그 안에서는 촘촘히 시도한다.
inline constexpr int64_t kRetryWindowMs = 300 * 1000;
inline constexpr int64_t kRetryWindowIntervalMs = 5 * 1000;
// 창을 넘기면 어차피 새 회차다 — 남은 방송을 살리려고 더 성기게 이어 간다(사용자 결정 2026-10-06).
inline constexpr int64_t kRetryMidSpanMs = 30 * 60 * 1000;
inline constexpr int64_t kRetryMidIntervalMs = 30 * 1000;
inline constexpr int64_t kRetrySlowSpanMs = 30 * 60 * 1000;
inline constexpr int64_t kRetrySlowIntervalMs = 60 * 1000;
inline constexpr int64_t kRetryGiveUpMs = kRetryWindowMs + kRetryMidSpanMs + kRetrySlowSpanMs; // 65분

struct RetryDecision {
	bool retry = false;
	int64_t delayMs = 0; // 이번 실패부터 다음 시도 시작까지
};

// elapsedMs: 끊긴 순간(한 번도 붙지 못했으면 첫 시도를 시작한 순간)부터 이번 실패까지 지난 시간.
// 판정은 실패한 시각으로 하므로 마지막 시도는 kRetryGiveUpMs를 한 간격만큼 넘겨 시작할 수 있다.
RetryDecision NextRetry(int64_t elapsedMs);

// 정지 코드 이름(StopCodeName) → 다시 시도할 만한가.
// 회선·서버 쪽 사정(disconnected·connect_failed·bad_path·timeout·output_error)만 다시 시도한다. output_error는
// 쓰기는 실패했는데 소켓이 아직 BROKEN이 아닐 때의 끊김과 접속 단계의 그 밖 오류가 함께 쓰는 코드다
// (obs-ffmpeg-mpegts.c write_thread · obs-ffmpeg-srt.h libsrt_setup). 인코더·스트림 오류는 다시 해도 같다.
bool IsRetryableStop(const std::string &codeName);

// 서버가 살아 있는데 거절이 이어지는가 — 키 폐기·암호 오류·경로 없음은 모두 connect_failed로 오고 서로 가를 수 없다.
// 서버 무응답은 SRT 접속 타임아웃을 다 쓰고 실패하지만 거절은 곧바로 돌아오므로, 빠른 connect_failed가 이어지면
// 스트리머에게 키 상태를 확인하라고 안내한다(확정이 아니라 의심이다).
inline constexpr int64_t kFastRejectMs = 1000;
inline constexpr int kKeySuspectRejects = 3;

class RejectStreak {
public:
	// attemptMs: 그 시도를 시작해서 실패가 올 때까지 걸린 시간.
	void Record(const std::string &codeName, int64_t attemptMs);
	void Reset() { streak_ = 0; }
	bool KeySuspect() const { return streak_ >= kKeySuspectRejects; }

private:
	int streak_ = 0;
};

} // namespace pokeclip
