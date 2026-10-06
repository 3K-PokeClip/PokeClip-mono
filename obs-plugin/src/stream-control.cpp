#include "stream-control.hpp"

#include "config.hpp"
#include "gop-guard.hpp"
#include "stream-target.hpp"

#include <obs-module.h>
#include <plugin-support.h>

namespace pokeclip {

void SetStreamError(StreamPhase phase, const std::string &code)
{
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.phase = phase;
		s.errorCode = code;
		s.errorDetail.clear();
		s.retry = {};
	});
}

bool StartSrtOutputChecked()
{
	PluginConfig config = ConfigStore::Instance().Get();
	if (!config.HasKey()) {
		obs_log(LOG_INFO, "main stream is live but not paired — skipping SRT output");
		SetStreamError(StreamPhase::Idle, "no_key");
		return false;
	}

	GopGuardResult gop = EnforceStreamEncoderPolicy();
	AppState::Instance().Mutate([&](StateSnapshot &s) { s.checks = gop.checks; });
	if (!gop.ok) {
		obs_log(LOG_WARNING, "not starting SRT output: %s", gop.errorCode.c_str());
		SetStreamError(StreamPhase::Error, gop.errorCode);
		return false;
	}

	std::string error;
	if (!StreamTarget::Instance().Start(config, error)) {
		obs_log(LOG_WARNING, "SRT output start failed: %s", error.c_str());
		SetStreamError(StreamPhase::Error, error);
		return false;
	}
	return true;
}

void SendNow()
{
	// GOP는 이 방송 구간을 시작할 때 확인했다 — 기다림만 건너뛴다.
	if (StreamTarget::Instance().RetryNow())
		return;

	StateSnapshot s = AppState::Instance().Snapshot();
	if (!CanSendNow(s)) {
		// 브리지가 미리 거르지만 그새 상태가 바뀔 수 있다(본방이 끝났다, 이미 붙었다).
		obs_log(LOG_INFO, "send-now ignored (phase %s)", PhaseName(s.phase));
		return;
	}
	obs_log(LOG_INFO, "send-now: starting SRT output while the main stream stays live");
	StartSrtOutputChecked();
}

void StopRetryNow()
{
	if (!StreamTarget::Instance().StopRetry())
		obs_log(LOG_INFO, "stop-retry ignored — not retrying");
}

} // namespace pokeclip
