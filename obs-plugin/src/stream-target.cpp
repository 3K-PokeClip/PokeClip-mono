/*
Portions adapted from obs-multi-rtmp (https://github.com/sorayuki/obs-multi-rtmp),
Copyright (C) SoraYuki, licensed under GPL-2.0.
*/
#include "stream-target.hpp"

#include "app-state.hpp"
#include "constants.hpp"
#include "srt-target.hpp"
#include "ui-thread.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

namespace pokeclip {

namespace {
constexpr const char *kOutputId = "ffmpeg_mpegts_muxer";
constexpr const char *kServiceId = "rtmp_custom";
constexpr int kOutputTimedOut = -10; // obs-ffmpeg-mpegts.c의 OBS_OUTPUT_TIMEDOUT (libobs 코드 아님)
} // namespace

const char *StopCodeName(int code)
{
	switch (code) {
	case OBS_OUTPUT_SUCCESS:
		return "";
	case OBS_OUTPUT_BAD_PATH:
		return "bad_path"; // SRT: passphrase 불일치(REJ_BADSECRET) 또는 URL 형식
	case OBS_OUTPUT_CONNECT_FAILED:
		return "connect_failed"; // 서버가 거절 (streamid·인가)
	case OBS_OUTPUT_INVALID_STREAM:
		return "invalid_stream";
	case OBS_OUTPUT_ERROR:
		return "output_error";
	case OBS_OUTPUT_DISCONNECTED:
		return "disconnected";
	case OBS_OUTPUT_UNSUPPORTED:
		return "unsupported";
	case OBS_OUTPUT_NO_SPACE:
		return "no_space";
	case OBS_OUTPUT_ENCODE_ERROR:
		return "encode_error";
	case kOutputTimedOut:
		return "timeout"; // 서버 무응답
	default:
		return "unknown";
	}
}

StreamTarget &StreamTarget::Instance()
{
	static StreamTarget target;
	return target;
}

bool StreamTarget::IsActive() const
{
	return output_ != nullptr && obs_output_active(output_);
}

bool StreamTarget::Start(const PluginConfig &config, std::string &errorCode)
{
	if (IsActive())
		return true;

	Release();

	if (!IsSafeStreamId(config.streamId)) {
		errorCode = "invalid_key";
		return false;
	}

	output_ = obs_output_create(kOutputId, "pokeclip-output", nullptr, nullptr);
	if (!output_) {
		errorCode = "output_create_failed";
		return false;
	}
	signalContext_ = std::make_unique<SignalContext>(SignalContext{this, ++generation_});
	ConnectSignals();

	obs_data_t *serviceSettings = BuildSrtServiceSettings(config);
	obs_service_t *service = obs_service_create(kServiceId, "pokeclip-service", serviceSettings, nullptr);
	obs_data_release(serviceSettings);
	if (!service) {
		errorCode = "service_create_failed";
		Release();
		return false;
	}
	// 생성 참조는 우리가 들고 있다가 Release()에서 출력 해제 뒤 놓는다.
	obs_output_set_service(output_, service);

	obs_output_t *streamOutput = obs_frontend_get_streaming_output();
	obs_encoder_t *venc = streamOutput ? obs_output_get_video_encoder(streamOutput) : nullptr;
	obs_encoder_t *aenc = streamOutput ? obs_output_get_audio_encoder(streamOutput, 0) : nullptr;
	if (streamOutput)
		obs_output_release(streamOutput);
	if (!venc || !aenc) {
		errorCode = "no_shared_encoder";
		Release();
		return false;
	}

	// 본방 인코더 공유 — 추가 영상 인코딩 0 (ADR-001).
	// OBS 32의 setter는 스스로 참조를 잡고 obs_output_destroy가 놓는다 (obs-output.c set_video_encoder2·destroy).
	// obs-multi-rtmp처럼 obs_encoder_get_ref를 한 번 더 넘기면, 출력이 아직 active일 때 해제하는 경로에서 새다.
	obs_output_set_video_encoder(output_, venc);
	obs_output_set_audio_encoder(output_, aenc, 0);
	obs_output_set_reconnect_settings(output_, kReconnectRetries, kReconnectDelaySec);

	AppState::Instance().Mutate([](StateSnapshot &s) {
		s.phase = StreamPhase::Starting;
		s.errorCode.clear();
		s.errorDetail.clear();
		s.stats = {};
	});

	obs_log(LOG_INFO, "starting SRT output → %s:%d (key …%s, passphrase %s)", config.ingestHost.c_str(),
		config.ingestPort, KeyHintOf(config.streamId).c_str(),
		config.sendPassphrase && !config.passphrase.empty() ? "on" : "off");

	if (!obs_output_start(output_)) {
		const char *last = obs_output_get_last_error(output_);
		errorCode = "start_failed";
		obs_log(LOG_WARNING, "obs_output_start failed: %s", last ? last : "(no detail)");
		Release();
		return false;
	}

	startedAt_ = lastPollAt_ = std::chrono::steady_clock::now();
	lastBytes_ = 0;
	return true;
}

void StreamTarget::Stop()
{
	if (!IsActive())
		return;
	AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Stopping; });
	obs_output_stop(output_);
}

void StreamTarget::ForceStop()
{
	bool had = output_ != nullptr;
	Release(); // 시그널을 먼저 끊으므로 OnStop이 오지 않는다 — 상태를 여기서 되돌린다
	if (had)
		AppState::Instance().Mutate([](StateSnapshot &s) {
			if (s.phase != StreamPhase::Error)
				s.phase = StreamPhase::Idle;
			s.stats.bitrateKbps = 0;
		});
}

void StreamTarget::Release()
{
	if (!output_)
		return;
	DisconnectSignals();
	if (obs_output_active(output_))
		obs_output_force_stop(output_); // mpegts stop은 비동기 — 아래 destroy가 stopping_event를 기다린다

	// 서비스는 우리가 만든 참조다. obs_output_set_service(nullptr)는 NULL을 거절해 떼어지지 않으므로,
	// 출력을 먼저 해제(destroy가 service->output을 끊음)한 뒤 서비스를 놓는다. 서비스가 active면 libobs가 파괴를 미룬다.
	obs_service_t *service = obs_output_get_service(output_);
	obs_output_release(output_); // 인코더 참조는 destroy가 놓는다
	output_ = nullptr;
	if (service)
		obs_service_release(service);
	signalContext_.reset(); // DisconnectSignals가 진행 중 콜백을 기다린 뒤라 안전하다
}

void StreamTarget::PollStats()
{
	if (!IsActive())
		return;
	auto now = std::chrono::steady_clock::now();
	uint64_t bytes = obs_output_get_total_bytes(output_);
	double seconds = std::chrono::duration<double>(now - lastPollAt_).count();
	double kbps = seconds > 0 && bytes >= lastBytes_ ? (bytes - lastBytes_) * 8.0 / 1000.0 / seconds : 0;
	lastBytes_ = bytes;
	lastPollAt_ = now;

	StreamStats stats;
	stats.bitrateKbps = kbps;
	stats.totalFrames = static_cast<uint64_t>(obs_output_get_total_frames(output_));
	stats.droppedFrames = obs_output_get_frames_dropped(output_);
	stats.uptimeSec = std::chrono::duration_cast<std::chrono::seconds>(now - startedAt_).count();
	AppState::Instance().Mutate([&](StateSnapshot &s) { s.stats = stats; });
}

void StreamTarget::ConnectSignals()
{
	signal_handler_t *sh = obs_output_get_signal_handler(output_);
	signal_handler_connect(sh, "starting", &StreamTarget::OnStarting, signalContext_.get());
	signal_handler_connect(sh, "start", &StreamTarget::OnStart, signalContext_.get());
	signal_handler_connect(sh, "reconnect", &StreamTarget::OnReconnect, signalContext_.get());
	signal_handler_connect(sh, "reconnect_success", &StreamTarget::OnReconnectSuccess, signalContext_.get());
	signal_handler_connect(sh, "stopping", &StreamTarget::OnStopping, signalContext_.get());
	signal_handler_connect(sh, "stop", &StreamTarget::OnStop, signalContext_.get());
}

void StreamTarget::DisconnectSignals()
{
	signal_handler_t *sh = obs_output_get_signal_handler(output_);
	signal_handler_disconnect(sh, "starting", &StreamTarget::OnStarting, signalContext_.get());
	signal_handler_disconnect(sh, "start", &StreamTarget::OnStart, signalContext_.get());
	signal_handler_disconnect(sh, "reconnect", &StreamTarget::OnReconnect, signalContext_.get());
	signal_handler_disconnect(sh, "reconnect_success", &StreamTarget::OnReconnectSuccess, signalContext_.get());
	signal_handler_disconnect(sh, "stopping", &StreamTarget::OnStopping, signalContext_.get());
	signal_handler_disconnect(sh, "stop", &StreamTarget::OnStop, signalContext_.get());
}

// 아래 시그널은 libobs 스레드에서 온다. AppState는 스레드 안전하다. 출력 해제는 UI 스레드로 넘긴다.

void StreamTarget::OnStarting(void *, calldata_t *)
{
	AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Starting; });
}

void StreamTarget::OnStart(void *, calldata_t *)
{
	obs_log(LOG_INFO, "SRT output started");
	AppState::Instance().Mutate([](StateSnapshot &s) {
		s.phase = StreamPhase::Live;
		s.errorCode.clear();
		s.errorDetail.clear();
	});
}

void StreamTarget::OnReconnect(void *, calldata_t *)
{
	obs_log(LOG_INFO, "SRT output reconnecting");
	AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Reconnecting; });
}

void StreamTarget::OnReconnectSuccess(void *, calldata_t *)
{
	obs_log(LOG_INFO, "SRT output reconnected");
	AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Live; });
}

void StreamTarget::OnStopping(void *, calldata_t *)
{
	AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Stopping; });
}

void StreamTarget::OnStop(void *data, calldata_t *params)
{
	auto *ctx = static_cast<SignalContext *>(data);
	StreamTarget *self = ctx->self;
	uint64_t generation = ctx->generation;
	int code = (int)calldata_int(params, "code");
	auto *output = static_cast<obs_output_t *>(calldata_ptr(params, "output"));
	const char *lastError = output ? obs_output_get_last_error(output) : nullptr;
	std::string name = StopCodeName(code);

	if (code == OBS_OUTPUT_SUCCESS)
		obs_log(LOG_INFO, "SRT output stopped");
	else
		obs_log(LOG_WARNING, "SRT output stopped with code %d (%s)", code, name.c_str());

	std::string detail = lastError ? lastError : "";
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.phase = code == OBS_OUTPUT_SUCCESS ? StreamPhase::Idle : StreamPhase::Error;
		s.errorCode = name;
		s.errorDetail = detail;
		s.stats.bitrateKbps = 0;
	});

	RunInUiThread([self, generation]() {
		if (self->generation_ == generation && !self->IsActive())
			self->Release();
	});
}

} // namespace pokeclip
