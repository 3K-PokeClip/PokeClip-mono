#pragma once

#include "config.hpp"
#include "retry-policy.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct obs_encoder;
struct obs_output;
struct calldata;

namespace pokeclip {

// 우리 서버로 가는 SRT+MPEG-TS 출력 하나. obs-multi-rtmp PushWidgetImpl의 출력 수명 관리를 이식했다.
// 공개 함수는 모두(IsActive 포함) UI 스레드에서만 부른다 — 출력 포인터를 UI 스레드가 해제한다.
// 다른 스레드는 AppState의 phase(IsStreamingPhase)를 본다.
//
// A5 자동 재연결: libobs 재연결은 끄고, 출력이 스스로 멈출 때마다(끊김·처음 접속 실패) 여기서 정책 표
// (retry-policy.hpp)대로 다음 시도를 예약한다. 시도마다 출력을 새로 만든다. 한 「방송 구간」은 Start()부터
// Stop()·ForceStop()·포기까지이고, 그 안에서만 다시 시도한다 — 본방이 끝나면 우리도 그만둔다.
class StreamTarget {
public:
	static StreamTarget &Instance();

	// 본방 스트리밍 출력의 인코더를 공유해 새 방송 구간을 시작한다. 실패 시 errorCode 채우고 false.
	bool Start(const PluginConfig &config, std::string &errorCode);
	// reason: 멈춘 뒤 남길 오류 사유(정적 문자열, 예: main_stream_failed). 없으면 stop 신호의 코드대로 남는다.
	// 예약된 재시도도 함께 그만둔다.
	// intentional: 스트리머의 멈춤 조작(OBS 「방송 종료」)이다 — A3(4D) 종료 신호를 보낼지 가른다(계약4 3-1).
	// 사유를 든 정지는 조작이 아니다. 본방이 스스로 끊겨 끝난 경우(STOPPING 없이 STOPPED)도 아니다.
	// deferIntent: 출력이 없는 구간(재시도 대기·「재시도 중지」 뒤)에서 본방 STOPPING이 왔는데 조작인지 아직 모른다 —
	// 판정을 미루고 STOPPED에서 본방 stop 코드를 본 뒤 Stop(…, intentional)을 다시 부른다(공유 인코더 실패는 본방
	// stop 코드 ENCODE_ERROR로 온다. 출력이 있을 때는 우리 출력 stopping의 스레드로 가른다 — stopInternal_).
	void Stop(const char *reason = nullptr, bool intentional = false, bool deferIntent = false);
	void ForceStop(bool intentional = false);
	bool IsActive() const;
	bool HasOutput() const { return output_ != nullptr; } // UI 스레드
	void PollStats();
	void Release();

	// 기다리지 않고 지금 시도한다. 끊긴 뒤 지난 시간은 그대로 센다. 기다리는 중이 아니면 false.
	bool RetryNow();
	// 재시도를 그만두고 마지막 실패 사유를 오류로 남긴다 — 그래야 방송 중에 수신 주소·키를 고칠 수 있다
	// (설정·페어링은 송출 단계에서 잠긴다). 재시도 중이 아니면 false.
	bool StopRetry();

private:
	StreamTarget() = default;

	// Start()의 본체이자 재시도 한 번 — 출력을 새로 만들어 접속을 시작한다. isRetry면 독의 마지막 실패 사유를 남겨 둔다.
	bool BeginAttempt(const PluginConfig &config, std::string &errorCode, bool isRetry);
	// stop 신호의 뒷일(UI 스레드) — 그만둘지 다시 시도할지 정한다. stopAt = 그 신호가 난 순간(멈춘 순간, 계약4 3절).
	void HandleStop(uint64_t generation, int code, const std::string &detail, const char *reason,
			std::chrono::steady_clock::time_point stopAt);
	void OnConnected(std::chrono::steady_clock::time_point connectedAt);
	// 방송 구간이 끝나는 자리마다 한 번 — A3(4D) 종료 신호를 보낼지 정하고 보낸다(end-signal-policy). 두 번째 호출은
	// 아무것도 안 한다. connectedAtStop: 멈출 때 붙어 있었다(아니면 마지막 연결은 outageAt_에 끊긴 것).
	void DecideEndSignal(bool intentional, bool connectedAtStop, std::chrono::steady_clock::time_point stopAt,
			     const char *trigger);
	void FireRetry(uint64_t seq);
	void CancelRetry();

	void ConnectSignals();
	void DisconnectSignals();
	// A2: 트랙 1은 본방 오디오 인코더를 공유하고(AAC면 믹서와 상관없이 — 아니면 같은 믹서로 AAC를 만든다),
	// 트랙 2~6은 우리가 AAC 인코더를 만든다.
	bool AttachAudioEncoders(struct obs_encoder *streamAudio, std::string &errorCode);
	// "stop" 신호 뒤 출력이 실제로 멈추면 해제한다. 세대가 바뀌었으면(새 출력) 아무것도 안 한다.
	void ReleaseWhenStopped(uint64_t generation, int attemptsLeft);
	void ReleaseOwnedEncoders();
	// SRT 접속 결과(start·stop 신호)가 날 때까지 기다린다 — 접속 중인 출력은 해제하면 안 된다(Release). 상한 안에 나면 true.
	bool WaitForConnectResult();

	static void OnStart(void *data, struct calldata *params);
	static void OnStopping(void *data, struct calldata *params);
	static void OnStop(void *data, struct calldata *params);

	// 시그널 데이터로 넘기는 출력별 문맥. 늦게 도착한 옛 출력의 stop이 새 출력을 해제하지 않게 세대를 단다.
	struct SignalContext {
		StreamTarget *self;
		uint64_t generation;
	};

	struct obs_output *output_ = nullptr;
	// obs_output_start 뒤 SRT 접속을 마치기(start 신호) 전 — 이 동안 obs_output_active는 거짓이다.
	std::atomic<bool> connecting_{false};
	// 접속하는 사이 정지를 받았다(본방이 먼저 멈췄다) — 붙자마자 멈춘다.
	std::atomic<bool> stopWhenConnected_{false};
	// UI 스레드 전용 — 이 출력에 정지를 요청했다. 멈추는 중인 출력은 아직 active여도 새 방송에 다시 쓰지 않는다(Start).
	bool stopRequested_ = false;
	// UI 스레드 전용 — 그 정지는 「재시도 중지」가 접속 중인 시도에 건 것이다. 접속이 실패하면 그 사유를 남긴다(왜 멈춰
	// 있는지가 그것이다). 본방이 끝나서 건 정지는 실패해도 사유를 남기지 않는다.
	bool stopKeepsFailure_ = false;
	// UI 스레드 전용 — 이 정지는 스트리머 조작이다(Stop의 intentional). HandleStop이 종료 신호를 가를 때 본다.
	bool stopIntentional_ = false;
	// UI 스레드 전용 — 이 방송 구간의 종료 신호를 이미 정했다(보냈든 안 보냈든). Start()가 지운다.
	bool endSignalDecided_ = false;
	// 정지를 요청한 이유. OnStop이 성공 정지여도 이 사유로 오류를 남긴다 — 그 신호가 사유를 덮지 않게.
	std::atomic<const char *> stopReason_{nullptr};
	// 이 출력의 stopping 신호가 UI 스레드 밖에서 났다 — 우리가 부르는 정지(obs_output_stop·Release의 force_stop)는 모두
	// UI 스레드에서 나므로, 다른 스레드의 stopping은 libobs가 시작한 정지다(공유 인코더 실패 → full_stop이 인코더
	// 스레드에서 두 출력을 force_stop, stop_code 0). 그 정지는 뒤따르는 본방 STOPPING이 조작처럼 보여도 조작이 아니라
	// 종료 신호를 보내지 않는다(계약4 3-1 「오류로 멈춘 경우」). 시도마다 BeginAttempt가 지운다.
	std::atomic<bool> stopInternal_{false};
	// 우리가 만든 오디오 인코더만(생성 참조를 우리가 쥔다). 출력이 제 참조를 놓은 뒤 Release()에서 놓는다.
	std::vector<struct obs_encoder *> ownedAudioEncoders_;
	std::unique_ptr<SignalContext> signalContext_;
	uint64_t generation_ = 0;
	// 전송 시간의 기준 — 이 방송 구간에서 처음 붙은 시도를 시작한 순간. 끊겼다 다시 붙어도 그대로 둔다.
	std::chrono::steady_clock::time_point startedAt_{};
	std::chrono::steady_clock::time_point lastPollAt_{};
	uint64_t lastBytes_ = 0;
	// 드롭 수도 구간 단위로 센다. 출력은 시도마다 새로 만들어 제 수를 0부터 세므로 앞선 출력들의 합을 들고 간다.
	int droppedBefore_ = 0;
	int lastDropped_ = 0; // 지금 출력에서 마지막으로 읽은 수

	// ── A5 재시도 — 모두 UI 스레드 전용 ──
	// 지금이 본방과 함께 보내야 하는 구간인가. 거짓이면 출력이 멈춰도 다시 시도하지 않는다.
	bool wanted_ = false;
	// 이 구간에서 한 번이라도 붙었다 — 독 표기(재연결 중 / 연결 재시도 중)를 가른다.
	// A3(4D)도 이 값을 본다: 성립한 연결이 없던 송출에는 종료 신호를 보내지 않는다(계약4 3-1).
	bool everConnected_ = false;
	// 지금 출력이 붙었었다 — 이 출력이 멈추면 「끊긴 순간」을 새로 잡는다.
	bool connectedThisOutput_ = false;
	// 마지막 연결이 성립한 순간(우리 출력의 start 신호 — SRT 핸드셰이크를 마친 뒤). 종료 신호의 connectionDurationMs
	// 기준이다. 붙을 때마다 새로 잡고, 붙지 못한 시도는 건드리지 않는다.
	std::chrono::steady_clock::time_point connectedAt_{};
	// 이 방송 구간의 SRT 출력이 쓴 키 — 종료 신호는 지금의 설정이 아니라 이 값으로 만든다. HandleStop이 상태를 Idle로
	// 올린 뒤에는 브리지가 연결 해제·재페어링을 받아들일 수 있어, 그 순간 설정을 읽으면 「키 없음」이 되거나 다른 키의
	// 신호가 된다. BeginAttempt가 적는다.
	std::string sessionStreamId_;
	std::string sessionPassphrase_; // 비밀 — 로그 금지
	std::string sessionEndSignalBase_;
	bool retryPending_ = false;
	int attempt_ = 0;       // 끊긴 뒤 예약한 재시도 수
	uint64_t retrySeq_ = 0; // 예약 취소 토큰 — 값이 바뀌면 먼저 예약한 타이머는 아무것도 안 한다
	std::chrono::steady_clock::time_point outageAt_{};  // 끊긴 순간(붙은 적 없으면 구간 시작)
	std::chrono::steady_clock::time_point attemptAt_{}; // 이번 시도를 시작한 순간
	RejectStreak rejects_;
};

const char *StopCodeName(int code);

} // namespace pokeclip
