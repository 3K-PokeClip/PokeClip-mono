/*
Portions adapted from obs-multi-rtmp (https://github.com/sorayuki/obs-multi-rtmp),
Copyright (C) SoraYuki, licensed under GPL-2.0.
*/
#include "stream-target.hpp"

#include "app-state.hpp"
#include "audio-router.hpp"
#include "constants.hpp"
#include "end-signal-policy.hpp"
#include "end-signal-sender.hpp"
#include "srt-target.hpp"
#include "srt-url.hpp"
#include "ui-thread.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <array>
#include <cstdlib>
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

int64_t MsBetween(std::chrono::steady_clock::time_point from, std::chrono::steady_clock::time_point to)
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(to - from).count();
}

int64_t NowEpochMs()
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch())
		.count();
}

// 단조 시계의 시각을 ms로 — 종료 신호 송신기(end-signal-sender)가 같은 시계로 elapsedSinceStopMs를 다시 잰다.
int64_t ToSteadyMs(std::chrono::steady_clock::time_point tp)
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(tp.time_since_epoch()).count();
}

// 재시도 정책의 시간을 몇 배로 빨리 감을지. 배포 빌드는 늘 1이다. 로컬 개발 빌드(macos-local)에서만 환경 변수
// POKECLIP_RETRY_SCALE을 읽는다 — 65분짜리 정책 표를 실측에서 포기까지 돌려 보기 위해서다.
double RetryTimeScale()
{
#ifdef POKECLIP_DEV_RETRY_SCALE
	static const double scale = []() {
		const char *env = std::getenv("POKECLIP_RETRY_SCALE");
		double value = env ? std::atof(env) : 1.0;
		return value >= 1.0 ? value : 1.0;
	}();
	return scale;
#else
	return 1.0;
#endif
}
} // namespace

const char *StopCodeName(int code)
{
	switch (code) {
	case OBS_OUTPUT_SUCCESS:
		return "";
	case OBS_OUTPUT_BAD_PATH:
		return "bad_path"; // SRT: 호스트 이름을 찾지 못함(DNS)·URL 형식 (obs-ffmpeg-srt.h libsrt_setup)
	case OBS_OUTPUT_CONNECT_FAILED:
		// 서버 무응답과 거절(키 폐기·passphrase 불일치·경로 없음)이 모두 이 코드다 — OBS의 SRT는 비차단으로
		// 접속해 거절 사유를 신호까지 올리지 않는다.
		return "connect_failed";
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
		return "timeout"; // 우리 URL로는 거의 나오지 않는다 — 접속 단계의 결과는 connect_failed·output_error로 덮인다
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
	// 스스로 멈춰 그만둔 출력(재시도하지 않는 코드·포기 — wanted_가 거짓)도 같다. active는 stop 신호 뒤 캡처가 다 끝나야
	// 내려가므로, 그 사이에 온 「다시 연결」을 「이미 보내는 중」으로 읽으면 눌러도 아무 일이 없다.
	if (IsActive() && !stopRequested_ && wanted_)
		return true;

	// 앞 구간의 종료 신호가 아직 정해지지 않았으면 여기서 정한다 — 멈추는 중인 출력의 stop 신호를 기다리지 않고 새 구간을
	// 시작하면 아래 BeginAttempt의 Release()가 시그널을 끊어 HandleStop이 오지 않는다(끝내고 곧바로 다시 켠 방송 —
	// 4D가 가르려는 바로 그 경우). 멈춘 순간은 지금으로 잡는다(정지 요청과 지금 사이 어딘가 — 최선 노력).
	if (!endSignalDecided_ && (output_ || retryPending_)) {
		const bool intent = stopIntentional_;
		const bool hadOutput = output_ != nullptr;
		// 옛 출력을 실제로 멈춘 뒤에 판정한다 — 신호는 출력이 멈춘 뒤에 가야 하고 두 시간 값의 기준도 그 순간이다.
		// 멈추는 중이던 출력은 여기서 강제로 멈춘다(어차피 BeginAttempt가 그렇게 한다).
		Release();
		DecideEndSignal(intent, hadOutput && connectedThisOutput_, std::chrono::steady_clock::now(),
				"restart_before_stop");
	}

	// 새 방송 구간이다 — 예약된 재시도를 버리고 처음부터 센다.
	CancelRetry();
	wanted_ = true;
	everConnected_ = false;
	stopIntentional_ = false;
	endSignalDecided_ = false;
	connectedAt_ = {};
	attempt_ = 0;
	rejects_.Reset();
	outageAt_ = std::chrono::steady_clock::now();
	if (!BeginAttempt(config, errorCode, false)) {
		wanted_ = false;
		return false;
	}
	return true;
}

bool StreamTarget::BeginAttempt(const PluginConfig &config, std::string &errorCode, bool isRetry)
{
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
	// libobs 재연결은 끈다(재시도 0회면 can_reconnect가 거짓 — obs-output.c). 끊기면 stop 신호가 곧바로 오고, 다시
	// 붙일지는 HandleStop이 정책 표로 정한다. libobs에 맡기면 간격이 1.5배씩 늘어 300초 창을 놓친다.
	obs_output_set_reconnect_settings(output_, 0, 0);

	const bool reconnecting = everConnected_;
	// 전송 시간과 드롭 수는 방송 구간 단위다 — 붙어 있다 끊긴 뒤의 시도는 출력을 새로 만들어도 이어 센다. 다시 붙자마자
	// 0으로 보이지 않게 첫 통계(PollStats) 전에 지금 값을 실어 둔다. 비트레이트는 출력마다 새로 잰다.
	StreamStats carried;
	if (reconnecting) {
		const auto sinceStart = std::chrono::steady_clock::now() - startedAt_;
		droppedBefore_ += lastDropped_;
		carried.uptimeSec = std::chrono::duration_cast<std::chrono::seconds>(sinceStart).count();
		carried.droppedFrames = droppedBefore_;
	} else {
		droppedBefore_ = 0;
	}
	lastDropped_ = 0;
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.phase = reconnecting ? StreamPhase::Reconnecting : StreamPhase::Starting;
		if (isRetry) {
			s.retry.nextAt = 0; // 지금 시도 중 — 마지막 실패 사유는 붙을 때까지 남겨 둔다
		} else {
			s.errorCode.clear();
			s.errorDetail.clear();
			s.retry = {};
		}
		s.stats = carried;
	});

	// 종료 신호는 이 출력이 쓴 키로 만든다(DecideEndSignal) — 구간 안에서는 키가 잠겨 시도마다 같은 값이다.
	sessionStreamId_ = config.streamId;
	sessionPassphrase_ = config.passphrase;
	sessionEndSignalBase_ = config.endSignalBase;

	obs_log(LOG_INFO, "starting SRT output → %s:%d (key …%s, passphrase %s)", config.ingestHost.c_str(),
		config.ingestPort, KeyHintOf(config.streamId).c_str(),
		config.sendPassphrase && !config.passphrase.empty() ? "on" : "off");

	// start 신호는 접속 스레드에서 obs_output_start가 돌아오기 전에도 올 수 있다 — 먼저 세운다.
	stopWhenConnected_ = false;
	connecting_ = true;
	connectedThisOutput_ = false;
	stopInternal_ = false;
	attemptAt_ = std::chrono::steady_clock::now();
	if (!obs_output_start(output_)) {
		connecting_ = false; // 접속 스레드가 뜨지 않았다 — Release()가 접속 결과를 기다리지 않게
		const char *last = obs_output_get_last_error(output_);
		errorCode = "start_failed";
		obs_log(LOG_WARNING, "obs_output_start failed: %s", last ? last : "(no detail)");
		Release();
		return false;
	}

	lastPollAt_ = std::chrono::steady_clock::now();
	lastBytes_ = 0;
	// 전송 시간의 기준은 이 구간에서 처음 붙은 시도다 — 붙은 뒤에는 다시 잡지 않는다. 붙지 못한 시도가 이어지는
	// 동안은 시도마다 새로 잡는다(그 시간은 보낸 시간이 아니다).
	if (!reconnecting)
		startedAt_ = lastPollAt_;
	return true;
}

void StreamTarget::Stop(const char *reason, bool intentional, bool deferIntent)
{
	// 방송 구간이 끝났다 — 예약된 재시도부터 버린다. 다음 시도를 기다리던 중이면 접속 중인 출력이 없으므로
	// 여기서 끝난다(끊긴 출력이 아직 풀리는 중이면 ReleaseWhenStopped가 마저 푼다).
	const bool wasPending = retryPending_;
	CancelRetry();
	wanted_ = false;
	stopKeepsFailure_ = false; // 「재시도 중지」가 건 정지였어도, 여기로 다시 오면 방송이 끝난 것이다
	// 사유를 든 정지(본방 시작 실패)는 스트리머 조작이 아니다. 출력이 있으면 HandleStop이 stop 신호 뒤에 이 값을 본다.
	stopIntentional_ = intentional && !reason;
	if (wasPending) {
		// 출력이 없으니 stop 신호도 없다 — 멈춘 순간은 지금이고, 마지막 연결은 outageAt_에 끊긴 것이다(자동 재연결 중의
		// 「방송 종료」도 보낸다 — 계약4 3-1). 조작인지 아직 모르면(deferIntent) 구간을 열어 둔 채 STOPPED를 기다린다.
		if (deferIntent)
			obs_log(LOG_INFO, "end-signal: intent deferred to main STOPPED (no output)");
		else
			DecideEndSignal(stopIntentional_, false, std::chrono::steady_clock::now(),
					reason ? "stopped_with_reason" : "main_stop");
		AppState::Instance().Mutate([reason](StateSnapshot &s) {
			s.phase = reason ? StreamPhase::Error : StreamPhase::Idle;
			s.errorCode = reason ? reason : "";
			s.errorDetail.clear();
			s.retry = {};
			s.stats.bitrateKbps = 0;
		});
		return;
	}
	if (!output_) {
		// 출력이 없다. 「재시도 중지」로 구간을 열어 둔 채 멈춰 있었으면(연결 이력 있음, 아직 판정 안 함) 지금의 조작이
		// 그 구간의 끝이다 — 마지막 연결은 outageAt_에 끊겼다. 이미 판정한 구간은 endSignalDecided_가 거른다.
		// 조작인지 아직 모르면(deferIntent) STOPPED에서 다시 온다.
		if (!endSignalDecided_ && everConnected_) {
			if (deferIntent)
				obs_log(LOG_INFO, "end-signal: intent deferred to main STOPPED (no output)");
			else
				DecideEndSignal(stopIntentional_, false, std::chrono::steady_clock::now(),
						reason ? "stopped_with_reason" : "main_stop");
		}
		return;
	}
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

void StreamTarget::ForceStop(bool intentional)
{
	bool had = output_ != nullptr || retryPending_; // 다음 시도를 기다리는 중이면 출력이 없어도 단계는 송출 중이다
	// 이미 정지를 요청한 출력이면 그 정지의 의도를 따른다(stop 신호를 기다리던 중에 OBS가 닫혔다). 아니면 이번 강제
	// 멈춤의 의도다. 멈춘 순간은 Release()가 돌아온 때 — 강제 멈춤도 출력이 실제로 멈춘 때가 기준이다(계약4 3절).
	const bool requested = stopRequested_; // Release()가 지우므로 먼저 잡는다 — 판정과 로그 trigger가 같은 근거를 쓴다
	// 먼저 들어온 정지가 본방 정지였으면 그 의도를 따른다. 「재시도 중지」가 건 정지(stopKeepsFailure_)는 구간을 닫지
	// 않는 정지라 이번 강제 멈춤(OBS 닫기)의 의도가 끝을 정한다.
	const bool intent = (requested && !stopKeepsFailure_) ? stopIntentional_ : intentional;
	const bool hadOutput = output_ != nullptr;
	// 「재시도 중지」로 열어 둔 구간(출력도 대기도 없지만 연결 이력이 있고 아직 판정 안 함)도 이번 멈춤이 끝이다.
	const bool openSegment = !endSignalDecided_ && everConnected_;
	CancelRetry();
	wanted_ = false;
	Release(); // 시그널을 먼저 끊으므로 OnStop이 오지 않는다 — 상태를 여기서 되돌린다
	// 붙어 있었는지는 Release() 뒤에 본다 — 접속 중이던 출력이 기다리는 사이 붙었으면 Release()가 그것을 적는다.
	// 로그 trigger는 판정에 쓴 의도와 같은 근거로 적는다 — 먼저 들어온 정지를 마저 끝낸 것이면 그 정지가 사유다.
	if (had || openSegment)
		DecideEndSignal(intent, hadOutput && connectedThisOutput_, std::chrono::steady_clock::now(),
				requested ? "main_stop" : intentional ? "obs_exit" : "force_stop");
	if (had)
		AppState::Instance().Mutate([](StateSnapshot &s) {
			if (s.phase != StreamPhase::Error)
				s.phase = StreamPhase::Idle;
			s.retry = {};
			s.stats.bitrateKbps = 0;
		});
}

void StreamTarget::CancelRetry()
{
	retrySeq_++;
	retryPending_ = false;
}

bool StreamTarget::RetryNow()
{
	if (!retryPending_)
		return false;
	obs_log(LOG_INFO, "SRT output: retrying now (skipping the wait)");
	FireRetry(++retrySeq_); // 먼저 예약한 타이머는 토큰이 달라 아무것도 안 한다
	return true;
}

bool StreamTarget::StopRetry()
{
	if (retryPending_) {
		CancelRetry();
		wanted_ = false;
		obs_log(LOG_INFO, "SRT output: retry stopped by the streamer");
		// 종료 신호는 지금 보내지 않는다 — 본방은 살아 있고 마지막 연결은 이미 끊겼다. 지금 보내면 키를 고쳐 다시 붙는
		// 송출이 새 회차가 돼 되감기를 잃는다. 그러나 구간은 닫지 않는다(endSignalDecided_ 그대로) — 뒤에 「방송 종료」·
		// OBS 닫기가 오면 그때 마지막 연결(outageAt_) 기준으로 보낸다. 「다시 연결」로 새 구간을 시작하면 그냥 잊는다.
		obs_log(LOG_INFO, "end-signal: deferred on stop_retry");
		// 마지막 실패 사유(errorCode)는 그대로 둔다 — 왜 멈춰 있는지가 그것이다.
		AppState::Instance().Mutate([](StateSnapshot &s) {
			const bool keySuspect = s.retry.keySuspect;
			s.phase = StreamPhase::Error;
			s.retry = {};
			s.retry.keySuspect = keySuspect; // 키 확인 안내는 남긴다 — 중지한 뒤에 연결을 해제하고 새 코드를 넣는다
			s.stats.bitrateKbps = 0;
		});
		return true;
	}
	// 재시도 접속이 진행 중이다 — 접속은 중간에 끊을 수 없으니 평소 정지 경로로 보낸다(붙으면 곧바로 멈추고,
	// 실패하면 그 코드가 남는다).
	if (wanted_ && attempt_ > 0 && output_ && !connectedThisOutput_) {
		obs_log(LOG_INFO, "SRT output: retry stopped by the streamer (attempt in flight)");
		Stop();
		stopKeepsFailure_ = true; // Stop()이 지운 뒤에 세운다 — 이 정지는 방송이 끝나서가 아니다
		return true;
	}
	return false;
}

void StreamTarget::FireRetry(uint64_t seq)
{
	if (seq != retrySeq_ || !retryPending_ || !wanted_)
		return;
	retryPending_ = false;

	// 설정은 시도마다 새로 읽는다. 키·수신 주소는 송출 단계에서 잠기므로 그사이 바뀌지 않는다.
	PluginConfig config = ConfigStore::Instance().Get();
	std::string error;
	if (BeginAttempt(config, error, true))
		return;
	// 그 자리 실패(본방 인코더가 사라짐 등)는 다시 해도 같다 — 그만둔다.
	obs_log(LOG_WARNING, "SRT output retry could not start: %s", error.c_str());
	wanted_ = false;
	DecideEndSignal(false, false, std::chrono::steady_clock::now(), "retry_start_failed");
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.phase = StreamPhase::Error;
		s.errorCode = error;
		s.errorDetail.clear();
		s.retry = {};
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
		// 기다리는 사이 접속이 성립했을 수 있다 — OnStart가 큐에 넣은 OnConnected는 UI 스레드가 여기 막혀 있어 돌지
		// 못하고, 아래에서 output_을 지우면 버려진다. 종료 신호 판정에 「성립한 연결」로 세도록 여기서 적는다(성립
		// 시각은 지금으로 어림 — 지속 시간은 0에 가깝다).
		if (resolved && !connectedThisOutput_ && obs_output_active(output_)) {
			connectedThisOutput_ = true;
			everConnected_ = true;
			connectedAt_ = std::chrono::steady_clock::now();
		}
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
	stopKeepsFailure_ = false;
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
	lastDropped_ = obs_output_get_frames_dropped(output_);
	stats.droppedFrames = droppedBefore_ + lastDropped_;
	stats.uptimeSec = std::chrono::duration_cast<std::chrono::seconds>(now - startedAt_).count();
	AppState::Instance().Mutate([&](StateSnapshot &s) { s.stats = stats; });
}

void StreamTarget::ConnectSignals()
{
	signal_handler_t *sh = obs_output_get_signal_handler(output_);
	// starting은 받지 않는다 — libobs는 info.start가 돌아온 뒤에 보내므로(obs-output.c obs_output_start) 그새 접속이
	// 끝나 온 start·stop을 덮는다. Starting은 Start()가 obs_output_start 전에 둔다.
	// reconnect·reconnect_success도 받지 않는다 — libobs 재연결을 꺼서 오지 않는다. 다시 붙을 때마다 start가 온다.
	signal_handler_connect(sh, "start", &StreamTarget::OnStart, signalContext_.get());
	signal_handler_connect(sh, "stopping", &StreamTarget::OnStopping, signalContext_.get());
	signal_handler_connect(sh, "stop", &StreamTarget::OnStop, signalContext_.get());
}

void StreamTarget::DisconnectSignals()
{
	signal_handler_t *sh = obs_output_get_signal_handler(output_);
	signal_handler_disconnect(sh, "start", &StreamTarget::OnStart, signalContext_.get());
	signal_handler_disconnect(sh, "stopping", &StreamTarget::OnStopping, signalContext_.get());
	signal_handler_disconnect(sh, "stop", &StreamTarget::OnStop, signalContext_.get());
}

// 아래 시그널은 libobs 스레드에서 온다. AppState는 스레드 안전하다. 출력 해제는 UI 스레드로 넘긴다.

void StreamTarget::OnStart(void *data, calldata_t *)
{
	auto *ctx = static_cast<SignalContext *>(data);
	StreamTarget *self = ctx->self;
	// 연결이 성립한 순간 — libobs는 SRT 핸드셰이크를 마치고 캡처를 시작한 뒤 start를 보낸다. 종료 신호의
	// connectionDurationMs 기준(계약4 3-1)이라 UI 스레드로 넘기기 전에 여기서 잡는다.
	const auto connectedAt = std::chrono::steady_clock::now();
	self->connecting_ = false;
	if (self->stopWhenConnected_.exchange(false)) {
		// 접속하는 사이 본방이 멈췄다 — 본방 없이 우리만 보내지 않게 붙자마자 멈춘다.
		// 이 신호는 접속 스레드 안에서 온다. 여기서 obs_output_stop을 부르면 그 스레드를 join하려 하므로 UI 스레드로 넘긴다.
		obs_log(LOG_INFO, "SRT output connected after the main stream stopped — stopping");
		uint64_t generation = ctx->generation;
		RunInUiThread([self, generation, connectedAt]() {
			if (self->generation_ != generation)
				return;
			// 붙기는 붙었다 — 종료 신호 판정에 「성립한 연결」로 센다(지속 시간은 0에 가깝다). 안 세면 첫 연결에서는
			// never_connected로 신호를 건너뛰고, 재연결 중이면 앞 연결의 시각을 그대로 쓴다.
			if (self->output_)
				self->OnConnected(connectedAt);
			// 붙자마자 멈춘다 — 먼저 들어온 정지의 의도와 「재시도 중지」 표시를 그대로 잇는다(Stop이 지우므로 되살린다 —
			// 안 그러면 HandleStop이 그 정지를 본방 정지로 적고 phase를 Idle로 둬 마지막 실패 사유가 사라진다).
			const bool keepFailure = self->stopKeepsFailure_;
			self->Stop(nullptr, self->stopIntentional_);
			self->stopKeepsFailure_ = keepFailure;
		});
		return;
	}
	obs_log(LOG_INFO, "SRT output started");
	// 우리 출력도 오디오 배정의 잠금 조건(AudioRouter Locked)이다 — 켜진 뒤 한 번 계산해 독·폴백의 locked를 맞춘다.
	AudioRouter::Instance().Schedule("srt started");
	AppState::Instance().Mutate([](StateSnapshot &s) {
		s.phase = StreamPhase::Live;
		s.errorCode.clear();
		s.errorDetail.clear();
		s.retry = {};
	});
	// 재시도 상태는 UI 스레드 것이다. 이 출력의 stop은 이보다 뒤에 큐에 들어가므로 HandleStop이 늘 이 뒤에 돈다.
	uint64_t generation = ctx->generation;
	RunInUiThread([self, generation, connectedAt]() {
		if (self->generation_ == generation && self->output_)
			self->OnConnected(connectedAt);
	});
}

void StreamTarget::OnConnected(std::chrono::steady_clock::time_point connectedAt)
{
	connectedAt_ = connectedAt;
	connectedThisOutput_ = true;
	everConnected_ = true;
	attempt_ = 0;
	rejects_.Reset();
}

void StreamTarget::OnStopping(void *data, calldata_t *)
{
	// 우리가 부르는 정지(obs_output_stop·Release의 force_stop)는 모두 UI 스레드에서 난다. 다른 스레드에서 난 stopping은
	// libobs가 시작한 정지다 — 공유 인코더가 실패하면 full_stop이 인코더 스레드에서 본방·우리 출력을 force_stop하고
	// (stop_code 0), 본방의 그 stopping으로 STREAMING_STOPPING까지 나와 조작처럼 보인다. 그 정지는 종료 신호를 보내지
	// 않는다(계약4 3-1 「오류로 멈춘 경우」). 큐에 넣는 STOPPING·HandleStop보다 이 신호가 먼저 돌아 순서가 보장된다.
	auto *ctx = static_cast<SignalContext *>(data);
	if (!OnUiThread())
		ctx->self->stopInternal_ = true;
	AppState::Instance().Mutate([](StateSnapshot &s) { s.phase = StreamPhase::Stopping; });
}

void StreamTarget::OnStop(void *data, calldata_t *params)
{
	auto *ctx = static_cast<SignalContext *>(data);
	StreamTarget *self = ctx->self;
	uint64_t generation = ctx->generation;
	// 멈춘 순간 — 종료 신호의 두 시간 값의 기준은 누른 순간이 아니라 이 stop 신호가 난 순간이다(계약4 3절).
	const auto stopAt = std::chrono::steady_clock::now();
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
	// 그만둘지 다시 시도할지는 UI 스레드가 정한다 — 재시도 상태와 stopRequested_가 UI 스레드 것이고, 여기(접속·송신
	// 스레드)에서 출력을 다시 시작하면 libobs가 지금 도는 이 스레드를 join하려 든다(obs-ffmpeg-mpegts.c start).
	RunInUiThread([self, generation, code, detail = std::move(detail), reason, stopAt]() {
		self->HandleStop(generation, code, detail, reason, stopAt);
	});
}

void StreamTarget::HandleStop(uint64_t generation, int code, const std::string &detail, const char *reason,
			      std::chrono::steady_clock::time_point stopAt)
{
	// 그새 출력이 바뀌었거나(새 시도) 이미 풀렸다(ForceStop) — 그쪽이 상태를 정했다.
	if (generation_ != generation || !output_)
		return;
	// stop 신호가 온 뒤, 여기 닿기 전에 사유를 든 정지가 들어왔을 수 있다(본방이 그 자리에서 실패).
	if (!reason)
		reason = stopReason_.exchange(nullptr);

	const std::string name = StopCodeName(code);
	const auto now = std::chrono::steady_clock::now();
	// 아래 ReleaseWhenStopped가 출력을 풀면 깃발도 지워진다 — 먼저 읽는다.
	const bool requested = stopRequested_;
	const bool keepFailure = stopKeepsFailure_;
	const bool wasConnected = connectedThisOutput_;

	// 우리가 멈췄거나(본방 정지·사유를 든 정지), 방송 구간이 끝났거나, 다시 해도 같은 오류다 — 그만둔다.
	if (reason || requested || !wanted_ || !IsRetryableStop(name)) {
		CancelRetry();
		wanted_ = false;
		// 본방이 끝나 우리가 멈춘 출력이면, 접속 중이던 시도가 실패 코드로 끝나도 사유를 남기지 않는다 — 방송은 이미
		// 끝났고, 다음 시도를 기다리던 중에 멈춘 경우(Stop)와 결과가 같아야 한다. 「재시도 중지」가 건 정지는 남긴다.
		const bool stoppedWithMain = requested && !keepFailure;
		AppState::Instance().Mutate([&](StateSnapshot &s) {
			const bool keySuspect = s.retry.keySuspect;
			if (reason) {
				// 이유를 들고 멈췄다(본방이 시작되지 않음 등) — 정지 코드보다 그 이유가 스트리머에게 맞는 설명이다.
				s.phase = StreamPhase::Error;
				s.errorCode = reason;
				s.errorDetail.clear();
			} else if (stoppedWithMain) {
				s.phase = StreamPhase::Idle;
				s.errorCode.clear();
				s.errorDetail.clear();
			} else {
				s.phase = code == OBS_OUTPUT_SUCCESS ? StreamPhase::Idle : StreamPhase::Error;
				s.errorCode = name;
				s.errorDetail = detail;
			}
			s.retry = {};
			// 「재시도 중지」가 건 정지로 실패했으면 키 확인 안내는 남긴다(기다리던 중에 중지한 StopRetry와 같다).
			s.retry.keySuspect = keepFailure && s.phase == StreamPhase::Error && keySuspect;
			s.stats.bitrateKbps = 0;
		});
		// 방송 구간이 끝났다 — 종료 신호는 우리가 스트리머 조작으로 멈춘 출력에만. 오류 코드로 스스로 멈춘 출력
		// (다시 해도 같은 오류), 사유를 든 정지, libobs가 시작한 정지(공유 인코더 실패 — stopInternal_)는 보내지 않는다.
		// 「재시도 중지」가 건 정지는 구간을 닫지 않는다 — 본방은 살아 있다. 뒤에 「방송 종료」·OBS 닫기가 오면 그때
		// 마지막 연결 기준으로 판정한다(Stop·ForceStop의 열린 구간 가지).
		const bool internalStop = stopInternal_.load();
		if (requested && keepFailure) {
			// 이 출력이 붙었다 멈춘 것이면(접속 중 중지가 성공으로 끝남) 그 연결의 끝을 적어 둔다 — 뒤에 「방송 종료」가
			// 열린 구간을 닫을 때 connectionDurationMs가 이 연결 기준이 되게.
			if (wasConnected)
				outageAt_ = stopAt;
			obs_log(LOG_INFO, "end-signal: deferred on stop_retry");
		} else {
			DecideEndSignal(requested && stopIntentional_ && !internalStop, wasConnected, stopAt,
					reason ? "stopped_with_reason"
					       : internalStop ? "internal_stop"
							: requested ? "main_stop"
								    : "output_error");
		}
		ReleaseWhenStopped(generation, kReleasePollAttempts);
		return;
	}

	// 붙어 있다 끊겼으면 그 stop 신호가 난 때가 「끊긴 순간」이다(UI 스레드가 밀려 여기 늦게 닿아도 — 종료 신호의
	// connectionDurationMs가 그 지연을 품지 않게). 붙지 못한 시도가 이어지는 중이면 처음 끊긴 순간부터 계속 센다.
	if (wasConnected) {
		outageAt_ = stopAt;
		attempt_ = 0;
		rejects_.Reset();
	}
	rejects_.Record(name, MsBetween(attemptAt_, now));

	const double scale = RetryTimeScale();
	const RetryDecision next = NextRetry(static_cast<int64_t>(MsBetween(outageAt_, now) * scale));
	if (!next.retry) {
		obs_log(LOG_WARNING, "SRT output: giving up after %d retries (%s)", attempt_, name.c_str());
		CancelRetry();
		wanted_ = false;
		const bool suspect = rejects_.KeySuspect(); // 거절이 이어지다 포기했으면 키 확인 안내를 남긴다
		AppState::Instance().Mutate([&](StateSnapshot &s) {
			s.phase = StreamPhase::Error;
			s.errorCode = name;
			s.errorDetail = detail;
			s.retry = {};
			s.retry.gaveUp = true;
			s.retry.keySuspect = suspect;
			s.stats.bitrateKbps = 0;
		});
		DecideEndSignal(false, wasConnected, stopAt, "gave_up"); // 자동 재연결 포기 — 보내지 않는다(계약4 3-1)
		ReleaseWhenStopped(generation, kReleasePollAttempts);
		return;
	}

	const int delayMs = static_cast<int>(next.delayMs / scale);
	attempt_++;
	retryPending_ = true;
	const uint64_t seq = ++retrySeq_;
	const int attempt = attempt_;
	const bool reconnecting = everConnected_;
	const bool keySuspect = rejects_.KeySuspect();
	const int64_t nextAt = NowEpochMs() + delayMs;
	obs_log(LOG_INFO, "SRT output: retry %d in %d ms (%s)", attempt, delayMs, name.c_str());
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.phase = reconnecting ? StreamPhase::Reconnecting : StreamPhase::Starting;
		s.errorCode = name; // 다시 붙을 때까지 독이 원인을 보여 준다
		s.errorDetail = detail;
		s.retry.attempt = attempt;
		s.retry.nextAt = nextAt;
		s.retry.gaveUp = false;
		s.retry.keySuspect = keySuspect;
		s.stats.bitrateKbps = 0;
	});
	RunInUiThreadAfter(delayMs, [this, seq]() { FireRetry(seq); });
	// 끊긴 출력은 실제로 멈춘 뒤에 푼다. 풀기가 늦어져도 다음 시도(BeginAttempt)가 Release()부터 하므로 새지 않는다.
	ReleaseWhenStopped(generation, kReleasePollAttempts);
}

void StreamTarget::DecideEndSignal(bool intentional, bool connectedAtStop, std::chrono::steady_clock::time_point stopAt,
				   const char *trigger)
{
	if (endSignalDecided_)
		return; // 한 구간에 한 번 — STOPPING·STOPPED가 둘 다 Stop()을 부르고, EXIT의 ForceStop이 뒤따를 수 있다
	endSignalDecided_ = true;

	const EndSignalSkip skip = EndSignalSkipReason(intentional, everConnected_);
	if (skip != EndSignalSkip::None) {
		obs_log(LOG_INFO, "end-signal: not sent on %s (%s)", trigger, EndSignalSkipName(skip));
		return;
	}

	// 지금의 설정이 아니라 이 구간의 SRT 출력이 쓴 키로 만든다(BeginAttempt가 적은 값) — HandleStop이 상태를 Idle로
	// 올린 뒤라 브리지가 연결 해제·재페어링을 받아들일 수 있다. 그 순간 설정을 읽으면 「키 없음」이 되거나 다른 키의
	// 신호가 된다.
	if (sessionStreamId_.empty() || sessionPassphrase_.empty()) {
		obs_log(LOG_WARNING, "end-signal: not sent on %s (no key)", trigger);
		return;
	}
	EndSignalRequest req;
	req.streamId = sessionStreamId_;
	req.passphrase = sessionPassphrase_; // Submit이 파생 값으로 바꾸고 지운다
	req.baseOverride = sessionEndSignalBase_;
	req.stopAtSteadyMs = ToSteadyMs(stopAt);
	req.connectionDurationMs = ConnectionDurationMs(ToSteadyMs(connectedAt_), connectedAtStop,
							ToSteadyMs(outageAt_), ToSteadyMs(stopAt));
	req.keyHint = KeyHintOf(sessionStreamId_);
	req.trigger = trigger;
	EndSignalSender::Instance().Submit(std::move(req));
}

void StreamTarget::ReleaseWhenStopped(uint64_t generation, int attemptsLeft)
{
	if (generation_ != generation || !output_)
		return;
	if (!obs_output_active(output_)) {
		Release(); // 우리가 만든 오디오 인코더 5개도 여기서 풀린다
		// 본방 STOPPED의 재계산은 우리 출력이 아직 active일 때 돌아 locked가 참으로 남는다 — 풀린 뒤 한 번 더.
		AudioRouter::Instance().Schedule("srt released");
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
