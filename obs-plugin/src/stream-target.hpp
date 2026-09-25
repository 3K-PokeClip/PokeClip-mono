#pragma once

#include "config.hpp"

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
// Start/Stop/ForceStop/PollStats는 UI 스레드에서만 부른다.
class StreamTarget {
public:
	static StreamTarget &Instance();

	// 본방 스트리밍 출력의 인코더를 공유해 시작한다. 실패 시 errorCode 채우고 false.
	bool Start(const PluginConfig &config, std::string &errorCode);
	void Stop();
	void ForceStop();
	bool IsActive() const;
	void PollStats();
	void Release();

private:
	StreamTarget() = default;

	void ConnectSignals();
	void DisconnectSignals();
	// A2: 트랙 1은 본방 오디오 인코더를 공유하고(AAC·믹서 0일 때), 트랙 2~6은 우리가 AAC 인코더를 만든다.
	bool AttachAudioEncoders(struct obs_encoder *streamAudio, std::string &errorCode);
	// "stop" 신호 뒤 출력이 실제로 멈추면 해제한다. 세대가 바뀌었으면(새 출력) 아무것도 안 한다.
	void ReleaseWhenStopped(uint64_t generation, int attemptsLeft);
	void ReleaseOwnedEncoders();

	static void OnStarting(void *data, struct calldata *params);
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
