#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

namespace pokeclip {

// 한 번의 멈춤에 대한 신호 하나. StreamTarget이 멈춘 순간과 마지막 연결 지속 시간을 잡아 UI 스레드에서 넘긴다.
struct EndSignalRequest {
	std::string streamId;   // SRT에 쓴 streamid 원문
	std::string passphrase; // 비밀 — Submit이 파생 값으로 바꾼 뒤 지운다. 큐에는 남지 않는다
	std::string baseOverride;      // config.endSignalBase — 루프백 주소만 인정한다(EndSignalUrl)
	int64_t stopAtSteadyMs = 0;    // 우리 출력이 실제로 멈춘 순간(단조 시계 ms)
	int64_t connectionDurationMs = 0;
	std::string keyHint;      // 로그용 — streamid 토큰 끝 4자
	const char *trigger = ""; // 로그용 — main_stop · obs_exit
};

// A3 의도적 종료 신호(계약4 4D) 송신기 — 전용 작업 스레드가 Media에 보낸다(규칙은 end-signal-policy).
// 시도마다 elapsedSinceStopMs를 다시 재고, 5xx·연결 실패·시간 초과만 최대 3회(1초·2초 뒤) 다시 보낸다.
// 멈춘 뒤 60초가 지나면 보내지 않고, OBS가 닫히면 남은 시도를 버린다(저장했다 나중에 보내지 않는다).
// Authorization 헤더·파생 값·passphrase는 어떤 로그에도 남기지 않는다 — 마지막 결과 한 줄(상태 코드·시도 수)만.
class EndSignalSender {
public:
	static EndSignalSender &Instance();

	void Start();
	// 진행 중인 전송을 끊고 작업 스레드를 기다린다(OBS 종료를 늦추지 않는다). 남은 시도는 버린다.
	void Stop();
	// 막지 않는다 — UI 스레드에서 부른다. 파생 값을 여기서 만들고 passphrase는 버린다.
	void Submit(EndSignalRequest request);

private:
	EndSignalSender() = default;

	struct Pending {
		EndSignalRequest req; // passphrase는 비어 있다
		std::string derived;  // 비밀에서 나온 값 — 로그 금지
		int attempts = 0;
		int64_t dueSteadyMs = 0;
	};

	struct SendResult {
		bool transportOk = false;
		long status = 0;
	};

	void Run();
	SendResult Send(const Pending &p, const std::string &body);

	std::mutex mutex_;
	std::condition_variable wake_;
	std::deque<Pending> queue_;
	bool started_ = false;
	std::thread worker_;
	std::atomic<bool> stopping_{false};
};

} // namespace pokeclip
