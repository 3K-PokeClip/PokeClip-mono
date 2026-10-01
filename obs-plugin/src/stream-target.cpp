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

#include <array>
#include <cstring>
#include <thread>

namespace pokeclip {

namespace {
constexpr const char *kOutputId = "ffmpeg_mpegts_muxer";
constexpr const char *kServiceId = "rtmp_custom";
constexpr int kOutputTimedOut = -10; // obs-ffmpeg-mpegts.c의 OBS_OUTPUT_TIMEDOUT (libobs 코드 아님)

bool IsAac(const char *codec)
{
	return codec && std::strcmp(codec, "aac") == 0;
}
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
	// 이미 보내는 중이면 그대로 쓴다(본방 송출 중에 동기화를 켠 경로). 정지를 요청한 출력은 active여도 멈추는 중이다 —
	// 그대로 두면 곧 멈춰 이번 방송이 PokeClip 없이 지나간다. Release()로 마저 멈추고 새로 만든다.
	if (IsActive() && !stopRequested_)
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
	bool audioOk = AttachAudioEncoders(aenc, errorCode);
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.checks.audioTracks = audioOk;
		s.checks.audioTrackCount = audioOk ? kAudioTrackCount : 0;
	});
	if (!audioOk) {
		Release(); // 반쯤 붙은 채로 보내지 않는다 — 트랙이 모자라면 서버의 트랙 번호가 어긋난다
		return false;
	}
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

	// start 신호는 접속 스레드에서 obs_output_start가 돌아오기 전에도 올 수 있다 — 먼저 세운다.
	stopWhenConnected_ = false;
	connecting_ = true;
	if (!obs_output_start(output_)) {
		connecting_ = false; // 접속 스레드가 뜨지 않았다 — Release()가 접속 결과를 기다리지 않게
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

void StreamTarget::Stop(const char *reason)
{
	if (!output_)
		return;
	stopRequested_ = true;
	if (reason)
		stopReason_ = reason;
	if (!obs_output_active(output_)) {
		// mpegts 출력은 접속 스레드가 SRT에 붙은 뒤에야 active가 된다(obs-ffmpeg-mpegts.c ffmpeg_mpegts_finalize).
		// 그 전에는 obs_output_stop이 아무것도 안 하므로, 붙자마자 OnStart가 멈추게 표시만 해 둔다.
		// 깃발을 먼저 세우고 접속 중인지 나중에 본다 — OnStart는 반대 순서라 둘 중 하나는 반드시 상대를 본다.
		if (connecting_) {
			stopWhenConnected_ = true;
			if (connecting_) {
				AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Stopping; });
				return;
			}
		}
		// 그새 접속이 끝났거나 이미 멈춘 출력(해제 대기)이다. libobs는 active를 켠 뒤에 start를 보내므로(OnStart가
		// connecting_을 내린다) 붙었으면 지금 active다 — 그러면 아래에서 바로 멈춘다(OnStart가 깃발을 봤으면 한 번 더
		// 부르지만 stopping 중이라 무시된다). 접속이 실패했으면 OnStop이 정리한다.
		if (!obs_output_active(output_))
			return;
	}
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
	if (output_) {
		// 아직 SRT 접속 중이면 결과가 날 때까지 기다린다. 접속 중인 출력을 그대로 해제하면 mpegts destroy가 접속 스레드를
		// join하는 사이 접속이 성공해 캡처가 시작되고, destroy가 이미 지나친 캡처 종료 스레드가 해제된 출력·인코더를 쓴다
		// (obs-output.c obs_output_destroy는 그 스레드를 info.destroy보다 먼저 join한다). 결과가 나면 평소 경로가 안전하다
		// — 붙었으면 active라 아래에서 멈추고, 실패했으면 이미 멈췄다.
		// 🔴 그동안 UI 스레드가 기다린다(SRT 접속 타임아웃, 기본 3초) — 접속 중의 OBS 종료·프로필 전환·본방 재시작 때만.
		bool resolved = WaitForConnectResult();
		DisconnectSignals();
		// 상한을 넘기면 그래도 멈춘다 — force_stop은 접속 스레드를 기다린 뒤 정지한다.
		if (obs_output_active(output_) || !resolved)
			obs_output_force_stop(output_); // mpegts stop은 비동기 — 아래 destroy가 stopping_event를 기다린다

		// 서비스는 우리가 만든 참조다. obs_output_set_service(nullptr)는 NULL을 거절해 떼어지지 않으므로,
		// 출력을 먼저 해제(destroy가 service->output을 끊음)한 뒤 서비스를 놓는다. 서비스가 active면 libobs가 파괴를 미룬다.
		obs_service_t *service = obs_output_get_service(output_);
		obs_output_release(output_); // 붙인 인코더의 출력 쪽 참조는 destroy가 놓는다
		output_ = nullptr;
		if (service)
			obs_service_release(service);
	}
	// 우리가 만든 오디오 인코더 — 출력이 제 참조를 놓은 뒤라 이 참조가 마지막이고, 여기서 파괴된다.
	// 출력보다 먼저 놓으면 출력이 아직 쥔 채라 파괴되지 않고, 출력 없이 실패한 시작에서는 여기가 유일한 정리다.
	ReleaseOwnedEncoders();
	signalContext_.reset(); // DisconnectSignals가 진행 중 콜백을 기다린 뒤라 안전하다
	connecting_ = false;
	stopWhenConnected_ = false;
	stopRequested_ = false;
	stopReason_ = nullptr;
}

bool StreamTarget::AttachAudioEncoders(obs_encoder_t *streamAudio, std::string &errorCode)
{
	// 트랙 2~6(idx 1~5)은 MULTI_TRACK_AUDIO 출력만 받는다. 아니면 obs_output_set_audio_encoder가 조용히 무시한다.
	if ((obs_output_get_flags(output_) & OBS_OUTPUT_MULTI_TRACK_AUDIO) == 0) {
		errorCode = "output_no_multitrack";
		obs_log(LOG_WARNING, "%s does not accept multiple audio tracks", kOutputId);
		return false;
	}

	// 첫 오디오(서버 audio1 = 최종 믹스)는 본방 오디오 인코더를 그대로 쓴다 — 시청용 믹스가 본방과 같은 바이트다
	// (ADR-017). 고급 출력에서 방송 트랙을 2~6으로 바꿨어도 그 믹서가 곧 시청자가 듣는 소리라 그대로 공유한다 —
	// 트랙 1에 따로 AAC를 만들면 스트리머가 트랙 1에서 뺀 소스(녹화용 구성 등)가 빠진다.
	// 본방이 AAC가 아니면(Opus 등) 같은 믹서로 AAC를 우리가 만든다 — 계약의 「전 트랙 AAC」를 지킨다.
	const bool streamIsAac = IsAac(obs_encoder_get_codec(streamAudio));
	const size_t streamMixer = obs_encoder_get_mixer_index(streamAudio);
	const bool shareTrack1 = streamIsAac;
	const std::string encoderId = streamIsAac ? obs_encoder_get_id(streamAudio) : kFallbackAacEncoderId;
	if (!IsAac(obs_get_encoder_codec(encoderId.c_str()))) {
		errorCode = "audio_encoder_failed";
		obs_log(LOG_WARNING, "AAC encoder '%s' is not available", encoderId.c_str());
		return false;
	}

	int track1Bitrate = kFallbackTrack0BitrateKbps;
	if (!shareTrack1) {
		obs_data_t *streamSettings = obs_encoder_get_settings(streamAudio);
		int bitrate = static_cast<int>(obs_data_get_int(streamSettings, "bitrate"));
		obs_data_release(streamSettings);
		if (bitrate > 0)
			track1Bitrate = bitrate;
	}

	// 설정은 시작 전에 확정한다 — 방송 중 인코더 설정을 바꾸면 수신 쪽 초기화 조각이 바뀐다(ADR-020).
	std::array<obs_encoder_t *, kAudioTrackCount> encoders{};
	if (shareTrack1)
		encoders[0] = streamAudio;
	// 출력 트랙 i: 0 = 최종 믹스(본방 믹서), 1~5 = 스템(믹서 1~5).
	for (size_t track = shareTrack1 ? 1 : 0; track < encoders.size(); track++) {
		const size_t mixer = track == 0 ? streamMixer : track;
		obs_data_t *settings = obs_data_create();
		obs_data_set_int(settings, "bitrate", track == 0 ? track1Bitrate : kStemAudioBitrateKbps);
		std::string name = std::string(kAudioEncoderNamePrefix) + std::to_string(track + 1);
		obs_encoder_t *encoder =
			obs_audio_encoder_create(encoderId.c_str(), name.c_str(), settings, mixer, nullptr);
		obs_data_release(settings);
		if (!encoder) {
			errorCode = "audio_encoder_failed";
			obs_log(LOG_WARNING, "could not create audio encoder '%s' (%s, mixer %zu)", name.c_str(),
				encoderId.c_str(), mixer);
			return false; // 앞서 만든 것은 호출부의 Release()가 놓는다
		}
		obs_encoder_set_audio(encoder, obs_get_audio());
		ownedAudioEncoders_.push_back(encoder);
		encoders[track] = encoder;
	}

	// 붙이는 순서가 곧 MPEG-TS 오디오 순서다 — 트랙 1(최종 믹스)이 첫 오디오여야 서버의 재생 렌디션이
	// 믹스를 고른다(ADR-057 -map 0:a:0). 붙은 결과를 되읽어 확인한다 — 조용히 무시되는 경로가 있다.
	for (size_t i = 0; i < encoders.size(); i++)
		obs_output_set_audio_encoder(output_, encoders[i], i);
	for (size_t i = 0; i < encoders.size(); i++) {
		if (obs_output_get_audio_encoder(output_, i) != encoders[i]) {
			errorCode = "audio_track_attach_failed";
			obs_log(LOG_WARNING, "audio track %zu did not attach", i + 1);
			return false;
		}
	}

	if (shareTrack1)
		obs_log(LOG_INFO, "audio tracks: T1 shared '%s' (%s, OBS track %zu) + %d x %s @%d kbps (%s2..%d)",
			obs_encoder_get_name(streamAudio), encoderId.c_str(), streamMixer + 1, kAudioTrackCount - 1,
			encoderId.c_str(), kStemAudioBitrateKbps, kAudioEncoderNamePrefix, kAudioTrackCount);
	else
		obs_log(LOG_INFO,
			"audio tracks: %d x %s — T1 own @%d kbps on OBS track %zu (stream audio %s), T2..T%d @%d kbps",
			kAudioTrackCount, encoderId.c_str(), track1Bitrate, streamMixer + 1,
			obs_encoder_get_codec(streamAudio), kAudioTrackCount, kStemAudioBitrateKbps);
	return true;
}

bool StreamTarget::WaitForConnectResult()
{
	// OnStart·OnStop이 접속 스레드에서 connecting_을 내린다. 둘 다 UI 스레드를 기다리지 않으므로(상태 리스너는
	// RunInUiThread로 넘기기만 한다) UI 스레드에서 여기서 막아도 교착되지 않는다.
	const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kConnectResultWaitMs);
	while (connecting_ && std::chrono::steady_clock::now() < deadline)
		std::this_thread::sleep_for(std::chrono::milliseconds(kConnectResultPollMs));
	if (connecting_)
		obs_log(LOG_WARNING, "SRT output still connecting after %d ms — stopping it anyway",
			kConnectResultWaitMs);
	return !connecting_;
}

void StreamTarget::ReleaseOwnedEncoders()
{
	for (obs_encoder_t *encoder : ownedAudioEncoders_)
		obs_encoder_release(encoder);
	if (!ownedAudioEncoders_.empty())
		obs_log(LOG_INFO, "released %zu audio encoder(s)", ownedAudioEncoders_.size());
	ownedAudioEncoders_.clear();
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
	// starting은 받지 않는다 — libobs는 info.start가 돌아온 뒤에 보내므로(obs-output.c obs_output_start) 그새 접속이
	// 끝나 온 start·stop을 덮는다. Starting은 Start()가 obs_output_start 전에 둔다.
	signal_handler_connect(sh, "start", &StreamTarget::OnStart, signalContext_.get());
	signal_handler_connect(sh, "reconnect", &StreamTarget::OnReconnect, signalContext_.get());
	signal_handler_connect(sh, "reconnect_success", &StreamTarget::OnReconnectSuccess, signalContext_.get());
	signal_handler_connect(sh, "stopping", &StreamTarget::OnStopping, signalContext_.get());
	signal_handler_connect(sh, "stop", &StreamTarget::OnStop, signalContext_.get());
}

void StreamTarget::DisconnectSignals()
{
	signal_handler_t *sh = obs_output_get_signal_handler(output_);
	signal_handler_disconnect(sh, "start", &StreamTarget::OnStart, signalContext_.get());
	signal_handler_disconnect(sh, "reconnect", &StreamTarget::OnReconnect, signalContext_.get());
	signal_handler_disconnect(sh, "reconnect_success", &StreamTarget::OnReconnectSuccess, signalContext_.get());
	signal_handler_disconnect(sh, "stopping", &StreamTarget::OnStopping, signalContext_.get());
	signal_handler_disconnect(sh, "stop", &StreamTarget::OnStop, signalContext_.get());
}

// 아래 시그널은 libobs 스레드에서 온다. AppState는 스레드 안전하다. 출력 해제는 UI 스레드로 넘긴다.

void StreamTarget::OnStart(void *data, calldata_t *)
{
	auto *ctx = static_cast<SignalContext *>(data);
	StreamTarget *self = ctx->self;
	self->connecting_ = false;
	if (self->stopWhenConnected_.exchange(false)) {
		// 접속하는 사이 본방이 멈췄다 — 본방 없이 우리만 보내지 않게 붙자마자 멈춘다.
		// 이 신호는 접속 스레드 안에서 온다. 여기서 obs_output_stop을 부르면 그 스레드를 join하려 하므로 UI 스레드로 넘긴다.
		obs_log(LOG_INFO, "SRT output connected after the main stream stopped — stopping");
		uint64_t generation = ctx->generation;
		RunInUiThread([self, generation]() {
			if (self->generation_ == generation)
				self->Stop();
		});
		return;
	}
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
	self->connecting_ = false; // 접속 실패도 여기로 온다
	self->stopWhenConnected_ = false;
	int code = (int)calldata_int(params, "code");
	auto *output = static_cast<obs_output_t *>(calldata_ptr(params, "output"));
	const char *lastError = output ? obs_output_get_last_error(output) : nullptr;
	std::string name = StopCodeName(code);

	if (code == OBS_OUTPUT_SUCCESS)
		obs_log(LOG_INFO, "SRT output stopped");
	else
		obs_log(LOG_WARNING, "SRT output stopped with code %d (%s)", code, name.c_str());

	std::string detail = lastError ? lastError : "";
	const char *reason = self->stopReason_.exchange(nullptr);
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		if (reason) {
			// 이유를 들고 멈췄다(본방이 시작되지 않음 등) — 정지 코드보다 그 이유가 스트리머에게 맞는 설명이다.
			s.phase = StreamPhase::Error;
			s.errorCode = reason;
			s.errorDetail.clear();
		} else {
			s.phase = code == OBS_OUTPUT_SUCCESS ? StreamPhase::Idle : StreamPhase::Error;
			s.errorCode = name;
			s.errorDetail = detail;
		}
		s.stats.bitrateKbps = 0;
	});

	RunInUiThread([self, generation]() { self->ReleaseWhenStopped(generation, kReleasePollAttempts); });
}

void StreamTarget::ReleaseWhenStopped(uint64_t generation, int attemptsLeft)
{
	if (generation_ != generation || !output_)
		return;
	if (!obs_output_active(output_)) {
		Release(); // 우리가 만든 오디오 인코더 5개도 여기서 풀린다
		return;
	}
	if (attemptsLeft <= 0) {
		// 다음 Start()나 ForceStop()이 어차피 해제한다 — 새지는 않는다.
		obs_log(LOG_WARNING, "SRT output still active %d ms after stop — releasing on next start",
			kReleasePollMs * kReleasePollAttempts);
		return;
	}
	RunInUiThreadAfter(kReleasePollMs, [this, generation, attemptsLeft]() {
		ReleaseWhenStopped(generation, attemptsLeft - 1);
	});
}

} // namespace pokeclip
