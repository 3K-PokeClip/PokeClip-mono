#pragma once

#include <atomic>
#include <chrono>
#include <functional>
#include <future>
#include <memory>
#include <optional>
#include <utility>

namespace pokeclip {

// 다른 스레드(UI 스레드)에 일을 넘기고 잠깐 기다려 그 결과로 답한다 — 브리지 워커가 UI 스레드 일의 결과를 독에 돌려줄 때.
// post는 일을 그 스레드 큐에 넣는 함수다(plugin-main은 RunInUiThread). Qt·OBS에 기대지 않아 단위 시험이 된다.
// - 시간 안에 시작하지 못했으면 일을 거두고(나중에 큐가 풀려도 돌지 않는다) nullopt — 「바빠요」라고 답한 변경이
//   뒤늦게 적용되면 독이 보인 말과 실제가 어긋난다(PR #455 리뷰). 시간 초과와 시작이 엇갈렸으면 끝을 기다려 실제대로 답한다.
// - 일이 던진 예외는 그 스레드(Qt 이벤트 루프) 밖으로 내보내지 않는다 — 잡는 곳이 없어 OBS가 꺼진다. 그때도 nullopt.
// - 종료 중이라 큐가 돌지 않으면(promise가 깨진다) nullopt.
template<typename T>
std::optional<T> PostAndWait(const std::function<void(std::function<void()>)> &post, std::function<T()> task,
			     int timeoutMs)
{
	enum : int { kPending, kRunning, kAbandoned };
	auto phase = std::make_shared<std::atomic<int>>(kPending);
	auto done = std::make_shared<std::promise<T>>();
	std::future<T> result = done->get_future();
	post([task = std::move(task), done, phase]() {
		int expected = kPending;
		if (!phase->compare_exchange_strong(expected, kRunning))
			return; // 기다리던 쪽이 이미 「바빠요」로 답했다
		try {
			done->set_value(task());
		} catch (...) {
			done->set_exception(std::current_exception());
		}
	});
	if (result.wait_for(std::chrono::milliseconds(timeoutMs)) != std::future_status::ready) {
		int expected = kPending;
		if (phase->compare_exchange_strong(expected, kAbandoned))
			return std::nullopt;
		result.wait(); // 시간 초과와 시작이 엇갈렸다 — 일은 짧으니 끝을 기다린다
	}
	try {
		return result.get();
	} catch (...) {
		return std::nullopt;
	}
}

} // namespace pokeclip
