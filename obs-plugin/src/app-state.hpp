#pragma once

#include "audio-assign.hpp"

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <string>

namespace pokeclip {

enum class StreamPhase { Idle, Starting, Live, Reconnecting, Stopping, Error };

const char *PhaseName(StreamPhase phase);
// 우리 SRT 출력이 시작했거나 아직 멈추는 중이다. 브리지 워커처럼 UI 스레드가 아닌 곳은 출력 객체 대신 이걸 본다
// (출력 포인터는 UI 스레드가 해제한다).
bool IsStreamingPhase(StreamPhase phase);

struct EncoderChecks {
	std::optional<bool> gop2s;
	std::optional<bool> res1080p;
	std::optional<bool> sharedEncoder;
	std::optional<bool> audioTracks; // A2: 6트랙이 출력에 붙었다
	int audioTrackCount = 0;
	int keyintSec = -1;
	int width = 0;
	int height = 0;
	double fps = 0;
};

// 송출을 막는 점검은 GOP 2초 하나다. 1080p는 권장값이라(ADR-020, POK-268) 어긋나도 독에 경고만 띄우고 보낸다 —
// 본방 인코더를 공유해(ADR-001) 우리 출력만 다른 해상도로 보낼 수 없다.
inline bool ChecksAllowSend(const EncoderChecks &c)
{
	return c.gop2s.value_or(false);
}

struct StreamStats {
	double bitrateKbps = 0;
	uint64_t totalFrames = 0;
	int droppedFrames = 0;
	int64_t uptimeSec = 0;
};

// A4 핫키 마킹 — 보낸·대기·실패 개수와 마지막 결과. seq가 오를 때마다 독이 토스트를 한 번 띄운다.
struct MarkStats {
	std::string hotkey; // 지금 묶인 단축키(OBS 표기). 빈 문자열이면 안 묶였다
	int sent = 0;       // 이번 방송에서 서버가 받은 수
	int pending = 0;    // 보내는 중·다시 보낼 차례를 기다리는 수 (지난 방송 것 포함)
	int failed = 0;     // 이번 방송에서 버린 수
	uint64_t seq = 0;
	std::string result; // sent · retrying · failed · rejected (seq와 함께 바뀐다)
	std::string reason; // result의 사유 코드 (copy.ts REASON · locale Reason.*)
	int64_t lastAt = 0; // 마지막으로 받아들인 누름의 시각(UTC epoch ms)
};

// A5 — 우리 출력이 끊기거나 붙지 못해 다시 시도하는 진행. attempt가 0이면 재시도 중이 아니다.
struct RetryView {
	int attempt = 0;         // 끊긴 뒤 몇 번째 재시도인가(1부터)
	int64_t nextAt = 0;      // 다음 시도 시각(UTC epoch ms). 0이면 지금 시도 중
	bool gaveUp = false;     // 정책 시간을 다 써서 멈췄다(phase는 Error)
	bool keySuspect = false; // 서버가 살아 있는데 거절이 이어진다 — 키 상태 확인 안내
};

// 독 페이지·폴백 패널이 그리는 유일한 상태 원천. 비밀은 담지 않는다.
struct StateSnapshot {
	uint64_t version = 0;
	bool paired = false;
	std::string keyHint; // streamid 토큰 끝 4자
	std::string ingest;  // host:port
	std::string apiBase;
	StreamPhase phase = StreamPhase::Idle;
	std::string errorCode;
	std::string errorDetail;
	RetryView retry;
	bool obsStreaming = false;
	bool syncStart = true; // 설정 sync_start 사본 — 「다시 연결」 조건에 쓴다
	bool darkTheme = true;
	StreamStats stats;
	EncoderChecks checks;
	AudioRoutingView audio; // A2: 트랙 2~6에 어느 소스가 실리는지 (실제 트랙 비트 기준)
	MarkStats marks;
};

// 「다시 연결」을 보여 줄 상태인가 — 본방은 나가는데 우리 송출은 멈춰 있다(오류로 멈춤 · 재시도 포기 ·
// 방송 중에 페어링함). 독·폴백 패널·브리지가 같은 판정을 쓴다.
inline bool CanSendNow(const StateSnapshot &s)
{
	if (!s.obsStreaming || !s.paired || !s.syncStart)
		return false;
	if (s.phase != StreamPhase::Idle && s.phase != StreamPhase::Error)
		return false;
	// 본방 인코더를 다시 띄워야 풀리는 사유 — 눌러도 같은 결과라 버튼을 주지 않는다.
	if (s.errorCode == "encoder_active" || s.errorCode == "keyint_not_applied" || s.errorCode == "multitrack_video")
		return false;
	// 저장된 키가 규칙에 어긋난다 — 이것도 눌러도 같은 결과다. 연결을 해제하고 새 코드를 넣으면 사유가 지워져
	// (PairWithCode) 버튼이 다시 뜬다.
	return s.errorCode != "invalid_key";
}

// 브리지가 「다시 연결」 요청을 거절할 사유. 받을 수 있으면 빈 문자열.
inline const char *SendNowRejection(const StateSnapshot &s)
{
	if (s.retry.attempt > 0 && s.retry.nextAt > 0)
		return ""; // 다음 재시도를 기다리는 중 — 기다림만 건너뛴다
	if (!s.obsStreaming)
		return "main_not_live";
	if (!s.paired)
		return "no_key";
	return CanSendNow(s) ? "" : "send_unavailable";
}

// 방송 중에 동기화를 켰을 때 지금 시작해도 되는가 — 본방이 아직 나가고(STOPPING부터 거짓이다), 동기화가 켜져 있고,
// 우리 송출이 돌고 있지 않다. 브리지 워커가 보고 UI 스레드로 넘긴 뒤에 다시 본다 — 그새 본방이 끝났을 수 있다.
// 키·GOP는 여기서 보지 않는다(StartSrtOutputChecked가 보고 사유를 상태에 남긴다).
inline bool CanStartOnSyncEnabled(const StateSnapshot &s)
{
	return s.obsStreaming && s.syncStart && (s.phase == StreamPhase::Idle || s.phase == StreamPhase::Error);
}

class AppState {
public:
	static AppState &Instance();

	StateSnapshot Snapshot() const;
	void Mutate(const std::function<void(StateSnapshot &)> &mutate);

	using Listener = std::function<void(const StateSnapshot &)>;
	uint64_t AddListener(Listener listener);
	void RemoveListener(uint64_t id);

	// SSE용: sinceVersion보다 새 상태가 오면 true. timeout이면 false. Shutdown이면 false.
	bool WaitForChange(uint64_t sinceVersion, std::chrono::milliseconds timeout, StateSnapshot &out);
	void Shutdown();
	bool IsShutdown() const;

	static std::string ToJson(const StateSnapshot &snapshot);

private:
	mutable std::mutex mutex_;
	std::condition_variable changed_;
	StateSnapshot state_{};
	bool shutdown_ = false;
	std::map<uint64_t, Listener> listeners_;
	uint64_t nextListenerId_ = 1;
};

} // namespace pokeclip
