#pragma once

#include "config.hpp"

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
class StreamTarget {
public:
	static StreamTarget &Instance();

	// 본방 스트리밍 출력의 인코더를 공유해 시작한다. 실패 시 errorCode 채우고 false.
	bool Start(const PluginConfig &config, std::string &errorCode);
	// reason: 멈춘 뒤 남길 오류 사유(정적 문자열, 예: main_stream_failed). 없으면 stop 신호의 코드대로 남는다.
	void Stop(const char *reason = nullptr);
	void ForceStop();
	bool IsActive() const;
	void PollStats();
	void Release();

private:
	StreamTarget() = default;

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
	static void OnReconnect(void *data, struct calldata *params);
	static void OnReconnectSuccess(void *data, struct calldata *params);
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
	// 정지를 요청한 이유. OnStop이 성공 정지여도 이 사유로 오류를 남긴다 — 그 신호가 사유를 덮지 않게.
	std::atomic<const char *> stopReason_{nullptr};
	// 우리가 만든 오디오 인코더만(생성 참조를 우리가 쥔다). 출력이 제 참조를 놓은 뒤 Release()에서 놓는다.
	std::vector<struct obs_encoder *> ownedAudioEncoders_;
	std::unique_ptr<SignalContext> signalContext_;
	uint64_t generation_ = 0;
	std::chrono::steady_clock::time_point startedAt_{};
	std::chrono::steady_clock::time_point lastPollAt_{};
	uint64_t lastBytes_ = 0;
};

const char *StopCodeName(int code);

} // namespace pokeclip
