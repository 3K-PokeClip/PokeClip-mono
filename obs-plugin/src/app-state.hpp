#pragma once

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

struct EncoderChecks {
	std::optional<bool> gop2s;
	std::optional<bool> res1080p;
	std::optional<bool> sharedEncoder;
	int keyintSec = -1;
	int width = 0;
	int height = 0;
	double fps = 0;
};

struct StreamStats {
	double bitrateKbps = 0;
	uint64_t totalFrames = 0;
	int droppedFrames = 0;
	int64_t uptimeSec = 0;
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
	bool obsStreaming = false;
	bool darkTheme = true;
	StreamStats stats;
	EncoderChecks checks;
};

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
