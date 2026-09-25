#pragma once

#include <atomic>
#include <string>

struct calldata;

namespace pokeclip {

// 오디오 트랙 자동 배정의 libobs 쪽(A2) — 소스 열거, 트랙 비트 쓰기, 배정 기억 저장, 독 상태 게시.
// 배정 규칙 자체는 audio-assign.hpp(순수 로직)에 있다.
//
// 스레드: Reconcile·SetLoading·Init·Shutdown은 UI 스레드에서만. Schedule은 아무 스레드에서나 —
// libobs 시그널은 여러 스레드에서 오므로 핸들러는 UI 스레드로 한 번 모아 넘기기만 한다.
class AudioRouter {
public:
	static AudioRouter &Instance();

	void Init();     // obs_module_load — 설정을 읽은 뒤, 장면 컬렉션을 읽기 전
	void Shutdown(); // OBS_FRONTEND_EVENT_EXIT
	// 장면 컬렉션 전환 중엔 소스 수십 개가 한꺼번에 사라지고 생긴다 — 모았다가 끝나고 한 번 계산한다.
	void SetLoading(bool loading);

	// 지금 소스를 열거해 배정을 계산하고, 자동 배정 중이면 트랙 비트를 쓰고 기억을 저장한 뒤 독 상태를 갱신한다.
	void Reconcile(const char *reason);
	// 여러 시그널을 한 번의 Reconcile로 모은다.
	void Schedule(const char *reason);

private:
	AudioRouter() = default;

	static void OnSourceCreate(void *data, struct calldata *params);
	static void OnSourceLoad(void *data, struct calldata *params);
	static void OnSourceChanged(void *data, struct calldata *params);
	static void OnMixersChanged(void *data, struct calldata *params);

	std::atomic<bool> pending_{false};
	std::atomic<bool> shutdown_{false};
	std::atomic<bool> applying_{false}; // 우리가 트랙 비트를 쓰는 중 — 그 시그널로 다시 계산하지 않는다
	bool loading_ = false;               // UI 스레드 전용
	bool initialized_ = false;
	std::string lastLogged_;
};

} // namespace pokeclip
