#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <mutex>
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

	// 전역 장치의 원래 체크 열쇠는 장면 컬렉션 이름을 쓴다(ch:N@<이름>). OBS의 이름 바꾸기는 새 이름으로 컬렉션을
	// 다시 불러와 CHANGED를 보낸 뒤에 RENAMED를 보내므로(OBSBasic_SceneCollections.cpp SetupRenameSceneCollection),
	// CHANGED 때 이전 이름을 남겨 두었다가 RENAMED에서 열쇠를 옮긴다. 둘 다 UI 스레드.
	void CollectionChanged(); // FINISHED_LOADING · SCENE_COLLECTION_CHANGED
	void CollectionRenamed(); // SCENE_COLLECTION_RENAMED

private:
	AudioRouter() = default;

	static void OnSourceCreate(void *data, struct calldata *params);
	static void OnSourceLoad(void *data, struct calldata *params);
	static void OnSourceChanged(void *data, struct calldata *params);
	static void OnMixersChanged(void *data, struct calldata *params);
	static void OnMonitoringChanged(void *data, struct calldata *params);
	void SettleMonitoring(const std::map<std::string, int> &seen);

	std::atomic<bool> pending_{false};
	std::atomic<bool> shutdown_{false};
	std::atomic<bool> applying_{false}; // 우리가 트랙 비트를 쓰는 중 — 그 시그널로 다시 계산하지 않는다
	std::atomic<bool> applied_{false};  // 마지막 계산에서 자동 배정을 적용했다 — 새 소스 처리·보류 판정이 본다
	std::atomic<uint32_t> reserved_{0}; // 마지막 계산의 본방 믹서 비트 (트랙 2~6만)
	// audio_monitoring 시그널은 새 값을 저장하기 전에 온다(모니터 장치를 만들고 지운 뒤에야 저장 — macOS에서 수백 ms).
	// 신호로 받은 새 값을 저장될 때까지 여기 둔다. 소스 uuid → obs_monitoring_type.
	std::mutex monitoringMutex_;
	std::map<std::string, int> monitoringPending_;
	bool loading_ = false;              // UI 스레드 전용
	bool deferRetryArmed_ = false;      // UI 스레드 전용 — 보류 중 다시 확인할 타이머가 걸려 있다
	bool initialized_ = false;
	std::string lastLogged_;
	std::string collection_;         // UI 스레드 전용 — 지금 장면 컬렉션 이름
	std::string previousCollection_; // UI 스레드 전용 — 마지막 CHANGED 전의 이름
};

} // namespace pokeclip
