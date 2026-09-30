#pragma once

#include "mark-policy.hpp"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

namespace pokeclip {

enum class MarkVia { Hotkey, Dock, Panel };

struct MarkAccept {
	bool ok = false;
	std::string reason; // mark_not_live · no_key · invalid_key · mark_too_soon
};

// A4 — 누른 시각을 대기열에 넣고 전용 작업 스레드가 서버로 보낸다(재시도·버림 규칙은 mark-policy).
// Mark()는 막지 않는다: 핫키 콜백(UI 스레드, libobs 핫키 락 안)·브리지 워커·폴백 패널 어디서 불러도 된다.
class MarkSender {
public:
	static MarkSender &Instance();

	void Start();
	// 보내는 중이면 끊고 작업 스레드를 기다린다. 못 보낸 마크는 버린다(로그에 개수만).
	void Stop();

	MarkAccept Mark(MarkVia via);
	// 새 방송 — 보낸·실패 개수를 0으로. 대기 중인 지난 방송 마크는 그대로 보낸다.
	void ResetCounters();

private:
	MarkSender() = default;

	struct Pending {
		std::string eventId;
		int64_t pressedAt = 0;       // UTC epoch ms — 서버로 간다
		int64_t pressedSteadyMs = 0; // 버림 판정용 (시계를 바꿔도 흔들리지 않게)
		int64_t dueSteadyMs = 0;
		int attempts = 0;
		std::string url;
		std::string passphrase; // 비밀 — 로그 금지
	};

	struct SendResult {
		bool transportOk = false;
		long status = 0;
		std::string body;
		int64_t retryAfterMs = 0;
	};

	void Run();
	SendResult Send(const Pending &p);
	// mutex_를 쥔 채 부른다. result가 비어 있지 않으면 seq를 올려 독이 토스트를 띄우게 한다.
	void PublishLocked(const char *result, const std::string &reason);

	std::mutex mutex_;
	std::condition_variable wake_;
	std::deque<Pending> queue_;
	bool inFlight_ = false;
	bool started_ = false;
	int sent_ = 0;
	int failed_ = 0;
	int64_t lastAt_ = 0;
	MarkDebouncer debouncer_;
	std::thread worker_;
	std::atomic<bool> stopping_{false};
};

} // namespace pokeclip
