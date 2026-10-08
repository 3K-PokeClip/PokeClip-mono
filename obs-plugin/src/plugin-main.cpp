/*
PokeClip for OBS
Copyright (C) 2026 PokeClip

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>

Structure of frontend-event handling and dock registration adapted from
obs-multi-rtmp (https://github.com/sorayuki/obs-multi-rtmp), GPL-2.0.
*/

#include "app-state.hpp"
#include "audio-router.hpp"
#include "bridge-server.hpp"
#include "config.hpp"
#include "constants.hpp"
#include "dock-host.hpp"
#include "end-signal-sender.hpp"
#include "mark-hotkey.hpp"
#include "mark-sender.hpp"
#include "pairing.hpp"
#include "srt-target.hpp"
#include "stream-control.hpp"
#include "stream-target.hpp"
#include "ui-thread.hpp"

#include <curl/curl.h>
#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <QMainWindow>
#include <QTimer>

#include <atomic>

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("pokeclip-obs", "en-US")
OBS_MODULE_AUTHOR("PokeClip")

const char *obs_module_name(void)
{
	return "PokeClip for OBS";
}

const char *obs_module_description(void)
{
	return "Sends your broadcast to PokeClip over SRT alongside your main stream, sharing the stream encoder.";
}

using namespace pokeclip;

namespace {

BridgeServer *g_bridge = nullptr;
DockHost *g_dock = nullptr;
QTimer *g_statsTimer = nullptr;

std::string JsonReason(bool ok, const std::string &reason)
{
	obs_data_t *d = obs_data_create();
	obs_data_set_bool(d, "ok", ok);
	if (!reason.empty())
		obs_data_set_string(d, "reason", reason.c_str());
	std::string json = obs_data_get_json(d);
	obs_data_release(d);
	return json;
}

int StatusForPairingReason(const std::string &reason)
{
	if (reason == "invalid_format")
		return 400;
	if (reason == "not_found")
		return 404;
	if (reason == "expired")
		return 410;
	if (reason == "already_used" || reason == "streaming")
		return 409;
	if (reason == "rate_limited")
		return 429;
	if (reason == "save_failed")
		return 500;
	return 502; // network · server_error · bad_response — 상위 서버 문제
}

void SyncStateFromConfig()
{
	PluginConfig c = ConfigStore::Instance().Get();
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.paired = c.HasKey();
		s.keyHint = KeyHintOf(c.streamId);
		s.ingest = c.ingestHost + ":" + std::to_string(c.ingestPort);
		s.apiBase = c.apiBase;
		s.syncStart = c.syncStart;
	});
}

std::string ConfigJson()
{
	PluginConfig c = ConfigStore::Instance().Get();
	obs_data_t *d = obs_data_create();
	obs_data_set_string(d, "api_base", c.apiBase.c_str());
	obs_data_set_string(d, "ingest_host", c.ingestHost.c_str());
	obs_data_set_int(d, "ingest_port", c.ingestPort);
	obs_data_set_bool(d, "send_passphrase", c.sendPassphrase);
	obs_data_set_int(d, "latency_ms", c.latencyMs);
	obs_data_set_bool(d, "sync_start", c.syncStart);
	obs_data_set_bool(d, "force_fallback", c.forceFallback);
	obs_data_set_bool(d, "audio_auto_assign", c.audioAutoAssign);
	obs_data_set_bool(d, "audio_assign_prompted", c.audioAssignPrompted);
	obs_data_set_string(d, "clip_api_base", c.clipApiBase.c_str());
	std::string json = obs_data_get_json(d);
	obs_data_release(d);
	return json;
}

BridgeCallbacks::Reply PutConfig(const std::string &body)
{
	// 브리지 워커 스레드다 — 출력 객체(StreamTarget)는 UI 스레드가 해제하므로 만지지 않고 상태의 phase를 본다.
	const bool streaming = IsStreamingPhase(AppState::Instance().Snapshot().phase);

	obs_data_t *d = obs_data_create_from_json(body.c_str());
	if (!d)
		return {400, JsonReason(false, "invalid_json")};

	const PluginConfig current = ConfigStore::Instance().Get();
	PluginConfig next = current;
	bool syncWasOn = current.syncStart;
	bool autoAssignWas = current.audioAutoAssign;
	bool promptedWas = current.audioAssignPrompted;
	auto str = [&](const char *key, std::string &out) {
		if (obs_data_has_user_value(d, key))
			out = obs_data_get_string(d, key);
	};
	auto num = [&](const char *key, int &out) {
		if (obs_data_has_user_value(d, key))
			out = (int)obs_data_get_int(d, key);
	};
	auto flag = [&](const char *key, bool &out) {
		if (obs_data_has_user_value(d, key))
			out = obs_data_get_bool(d, key);
	};
	// api_base·clip_api_base는 여기서 받지 않는다 — 마크가 passphrase를 Bearer로 싣고 가는 곳이라,
	// 브리지 토큰만으로 목적지를 바꿀 수 있으면 「비밀은 독에 주지 않는다」가 우회된다.
	// 개발 중에는 설정 파일(pokeclip.json)로 바꾼다.
	str("ingest_host", next.ingestHost);
	num("ingest_port", next.ingestPort);
	flag("send_passphrase", next.sendPassphrase);
	num("latency_ms", next.latencyMs);
	flag("sync_start", next.syncStart);
	flag("force_fallback", next.forceFallback);
	flag("audio_auto_assign", next.audioAutoAssign);
	flag("audio_assign_prompted", next.audioAssignPrompted);
	obs_data_release(d);
	// 스위치를 직접 바꿨으면 답한 것으로 친다 — 처음 안내를 다시 띄우지 않는다.
	if (next.audioAutoAssign != autoAssignWas)
		next.audioAssignPrompted = true;

	// 송출 중에는 SRT 출력·동기화·독 표시 방식만 막는다. 오디오 배정 스위치·처음 안내 응답은 AudioRouter가 방송·녹화가
	// 끝날 때까지 지금 나가는 트랙을 옮기지 않으므로 받는다 — 막으면 방송 중에 뜬 안내 카드에 답할 수 없다.
	if (streaming && !next.SameOutputSettings(current))
		return {409, JsonReason(false, "streaming")};

	if (!IsSafeIngestHost(next.ingestHost))
		return {400, JsonReason(false, "invalid_ingest_host")};
	if (next.ingestPort < 1 || next.ingestPort > 65535)
		return {400, JsonReason(false, "invalid_ingest_port")};
	if (next.latencyMs < 20 || next.latencyMs > 8000)
		return {400, JsonReason(false, "invalid_latency")};

	if (!ConfigStore::Instance().Commit([&](PluginConfig &c) {
		    std::string streamId = c.streamId;
		    std::string passphrase = c.passphrase;
		    std::vector<AudioTrackMapEntry> trackMap = c.audioTrackMap;
		    std::vector<AudioMixerBackup> mixerBackup = c.audioMixerBackup;
		    bool dockIntroShown = c.dockIntroShown;
		    std::string markHotkey = c.markHotkey;
		    c = next;
		    c.streamId = streamId; // 키는 이 경로로 바꾸지 않는다 (페어링 전용)
		    c.passphrase = passphrase;
		    // 배정 기억·원래 체크·독 첫 실행 표시·단축키 사본은 UI 스레드가 따로 저장한다 —
		    // 이 요청이 읽은 옛 값으로 덮지 않는다.
		    c.audioTrackMap = std::move(trackMap);
		    c.audioMixerBackup = std::move(mixerBackup);
		    c.dockIntroShown = dockIntroShown;
		    c.markHotkey = std::move(markHotkey);
	    }))
		return {500, JsonReason(false, "save_failed")};

	SyncStateFromConfig();
	if (next.audioAutoAssign != autoAssignWas || next.audioAssignPrompted != promptedWas)
		AudioRouter::Instance().Schedule("setting");

	// 동기화를 본방 송출 중에 켰다 — 「본방이 보내면 우리도 보낸다」를 지키려면 다음 방송을 기다리지 않고
	// 지금 시작한다. 키·GOP 검사는 본방 STARTING과 같지만, 그때 하는 오디오 재배정·마크 카운터 초기화·본방 시작
	// 감시는 다시 하지 않는다 — 방송 도중이다(「다시 연결」과 같은 경로). 브리지 워커 스레드라 UI 스레드로 넘긴다.
	// 🔴 이미 돌던 인코더는 keyint를 못 바꾸므로(x264) 그때는 encoder_active로 거절된다 — 본방을 다시 켜야 한다.
	if (next.syncStart && !syncWasOn && !streaming && obs_frontend_streaming_active()) {
		obs_log(LOG_INFO, "sync switched on while main stream is live — starting SRT output now");
		// 위 확인은 워커 스레드에서 했다 — 큐에서 기다리는 사이 본방이 멈췄거나 동기화를 다시 껐으면 시작하지 않는다.
		// 본방 없이 시작하면 멈춰 줄 STREAMING_STOPPED가 다시 오지 않아 우리만 계속 보낸다(끊기면 재시도까지 한다).
		RunInUiThread([]() {
			StateSnapshot s = AppState::Instance().Snapshot();
			if (!CanStartOnSyncEnabled(s)) {
				obs_log(LOG_INFO, "sync-on start skipped (main stream %s, sync %s, phase %s)",
					s.obsStreaming ? "live" : "not live", s.syncStart ? "on" : "off",
					PhaseName(s.phase));
				return;
			}
			StartSrtOutputChecked();
		});
	}
	return {200, ConfigJson()};
}

std::string HelloJson()
{
	obs_data_t *d = obs_data_create();
	obs_data_set_string(d, "plugin", PLUGIN_NAME);
	obs_data_set_string(d, "pluginVersion", PLUGIN_VERSION);
	obs_data_set_string(d, "obsVersion", obs_get_version_string());
	std::string state = AppState::ToJson(AppState::Instance().Snapshot());
	obs_data_t *stateObj = obs_data_create_from_json(state.c_str());
	obs_data_set_obj(d, "state", stateObj);
	obs_data_release(stateObj);
	std::string json = obs_data_get_json(d);
	obs_data_release(d);
	return json;
}

BridgeCallbacks MakeBridgeCallbacks()
{
	BridgeCallbacks cb;
	cb.waitState = [](uint64_t since, int timeoutMs, std::string &json, uint64_t &version) {
		StateSnapshot s;
		if (!AppState::Instance().WaitForChange(since, std::chrono::milliseconds(timeoutMs), s))
			return false;
		json = AppState::ToJson(s);
		version = s.version;
		return true;
	};
	cb.stateJson = []() { return AppState::ToJson(AppState::Instance().Snapshot()); };
	cb.helloJson = HelloJson;
	cb.onFirstHello = []() { obs_log(LOG_INFO, "dock: page connected to bridge"); };
	cb.pair = [](const std::string &body) -> BridgeCallbacks::Reply {
		obs_data_t *d = obs_data_create_from_json(body.c_str());
		std::string code = d ? obs_data_get_string(d, "code") : "";
		if (d)
			obs_data_release(d);
		PairingResult r = PairWithCode(code); // httplib 워커 스레드 — 블로킹 허용
		return {r.ok ? 200 : StatusForPairingReason(r.reason), JsonReason(r.ok, r.reason)};
	};
	cb.unpair = []() -> BridgeCallbacks::Reply {
		PairingResult r = Unpair();
		return {r.ok ? 200 : StatusForPairingReason(r.reason), JsonReason(r.ok, r.reason)};
	};
	cb.getConfig = ConfigJson;
	cb.putConfig = PutConfig;
	cb.mark = []() -> BridgeCallbacks::Reply {
		MarkAccept a = MarkSender::Instance().Mark(MarkVia::Dock);
		int status = a.ok ? 202 : (a.reason == "mark_too_soon" ? 429 : 409);
		return {status, JsonReason(a.ok, a.reason)};
	};
	// A5 — 워커 스레드라 상태로 미리 거르기만 하고, 출력은 UI 스레드에서 만진다. 결과는 상태(phase·errorCode)로 간다.
	cb.sendNow = []() -> BridgeCallbacks::Reply {
		std::string reason = SendNowRejection(AppState::Instance().Snapshot());
		if (!reason.empty())
			return {409, JsonReason(false, reason)};
		RunInUiThread([]() { SendNow(); });
		return {202, JsonReason(true, "")};
	};
	cb.stopRetry = []() -> BridgeCallbacks::Reply {
		if (AppState::Instance().Snapshot().retry.attempt == 0)
			return {409, JsonReason(false, "not_retrying")};
		RunInUiThread([]() { StopRetryNow(); });
		return {202, JsonReason(true, "")};
	};
	return cb;
}

void UpdateTheme()
{
	bool dark = obs_frontend_is_theme_dark();
	AppState::Instance().Mutate([dark](StateSnapshot &s) { s.darkTheme = dark; });
}

// 본방 출력의 starting 신호 — obs_output_start가 그 자리에서 성공했을 때만 온다(obs-output.c obs_output_start,
// 스트림 지연이면 obs-output-delay.c obs_output_delay_start). 아직 접속 중이거나 지연을 기다리는 중이어도 온다.
std::atomic<bool> g_mainStartAccepted{false};

void OnMainOutputStarting(void *, calldata_t *)
{
	g_mainStartAccepted = true;
}

// 본방 출력의 stop 코드 — 우리 출력이 없는 구간(재시도 대기·「재시도 중지」 뒤)에서 본방 STOPPING이 조작인지 가를 때
// 쓴다. 공유 인코더 실패는 본방 출력이 ENCODE_ERROR로 멈추고(rtmp-stream.c encode_error), 조작·재연결 중 정지는 0이다.
// STREAMING_STOPPED는 이 stop 신호를 UI 스레드로 넘긴 뒤에 나므로 그때는 값이 있다. 우리 출력이 있을 때는 그 출력
// stopping의 스레드로 가른다(StreamTarget stopInternal_).
std::atomic<int> g_mainStopCode{0};
obs_output_t *g_mainOutputWatched = nullptr; // UI 스레드 — 우리가 쥔 참조. 본방이 멈추거나 OBS가 닫히면 놓는다

void OnMainOutputStop(void *, calldata_t *params)
{
	g_mainStopCode = (int)calldata_int(params, "code");
}

void UnwatchMainOutputStop()
{
	if (!g_mainOutputWatched)
		return;
	signal_handler_disconnect(obs_output_get_signal_handler(g_mainOutputWatched), "stop", OnMainOutputStop, nullptr);
	obs_output_release(g_mainOutputWatched);
	g_mainOutputWatched = nullptr;
}

void WatchMainOutputStop(obs_output_t *mainOutput)
{
	UnwatchMainOutputStop();
	g_mainOutputWatched = obs_output_get_ref(mainOutput);
	if (!g_mainOutputWatched)
		return;
	g_mainStopCode = 0;
	signal_handler_connect(obs_output_get_signal_handler(g_mainOutputWatched), "stop", OnMainOutputStop, nullptr);
}

// 본방 시작이 그 자리에서 실패하면(OBSBasic::StartStreaming → DisplayStreamStartError) OBS는 STREAMING_STOPPED를
// 보내지 않는다 — 방송 중 표시(obsStreaming)도, 이미 시작한 우리 출력도 그대로 남는다. OBS는 STREAMING_STARTING을
// 보낸 같은 호출 안에서 본방 obs_output_start를 부르므로 다음 이벤트 루프 차례에 결과가 나와 있다. 시간으로 재지 않는다
// — 접속이 느리거나 스트림 지연을 켠 본방은 start 신호(obs_frontend_streaming_active)가 몇 초~수십 초 뒤에 온다.
// 그 뒤의 실패는 OBS가 STREAMING_STOPPED를 보낸다.
void WatchMainStreamStart()
{
	obs_output_t *mainOutput = obs_frontend_get_streaming_output();
	if (!mainOutput)
		return;
	signal_handler_t *sh = obs_output_get_signal_handler(mainOutput);
	g_mainStartAccepted = false;
	signal_handler_connect(sh, "starting", OnMainOutputStarting, nullptr);
	WatchMainOutputStop(mainOutput); // 이 방송의 본방 stop 코드를 받아 둔다(종료 신호 판정)

	auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
	QTimer::singleShot(0, main, [mainOutput, sh]() {
		signal_handler_disconnect(sh, "starting", OnMainOutputStarting, nullptr);
		// 본방 송출 중에 동기화를 켠 경로는 starting이 다시 오지 않는다 — 이미 보내고 있으면 받아들인 것이다.
		bool accepted = g_mainStartAccepted || obs_frontend_streaming_active() || obs_output_active(mainOutput);
		obs_output_release(mainOutput);
		if (accepted)
			return;
		obs_log(LOG_WARNING, "main stream did not start");
		AppState::Instance().Mutate([](StateSnapshot &s) { s.obsStreaming = false; });
		if (!IsStreamingPhase(AppState::Instance().Snapshot().phase))
			return; // 우리 출력은 시작하지 않았다(동기화 꺼짐·키 없음·GOP 거절·시작 실패)
		obs_log(LOG_WARNING, "stopping SRT output with the main stream");
		StreamTarget::Instance().Stop("main_stream_failed"); // 정지가 끝나도 이 사유가 남는다(HandleStop)
		SetStreamError(StreamPhase::Error, "main_stream_failed");
	});
}

// 본방의 이번 정지가 스트리머 조작인가. STREAMING_STOPPING은 obs_output_stop 안에서만 동기로 오므로(OBS 「방송 종료」
// 버튼·websocket 등), 본방이 스스로 끊겨 끝나면(재연결 소진) STOPPING 없이 STOPPED만 온다. A3(4D) 종료 신호는
// 조작일 때만 보낸다(계약4 3-1) — 자력 종료 뒤 300초 안의 재시작은 같은 회차로 이어져야 한다.
static bool g_mainStopRequested = false;

void OnStreamingStarting()
{
	g_mainStopRequested = false;
	// 본방 인코더가 돌기 전 마지막 배정 정리 — 이후 방송 중에는 이미 나가는 배정을 옮기지 않는다.
	// 우리 송출을 안 해도(동기화 꺼짐) 녹화 트랙이 같은 믹서를 쓰므로 먼저 한다.
	AudioRouter::Instance().Reconcile("stream starting");
	MarkSender::Instance().ResetCounters(); // 보낸·실패 수는 방송 단위

	AppState::Instance().Mutate([](StateSnapshot &s) { s.checks = {}; });
	WatchMainStreamStart(); // 그 자리 실패면 아래에서 올린 방송 표시와 시작한 우리 출력을 되돌린다

	if (ConfigStore::Instance().Get().syncStart)
		StartSrtOutputChecked(); // 키·GOP 확인 뒤 시작 — 실패 사유는 상태에 남는다

	// 방송 표시는 우리 출력의 단계가 정해진 뒤에 올린다. 먼저 올리면 GOP 검사·출력 생성이 도는 동안 「본방은 나가는데
	// 우리는 멈춰 있다」(CanSendNow)가 독에 나가 「다시 연결」이 잠깐 뜬다 — STOPPING에서 먼저 내리는 것과 같은 이유다.
	AppState::Instance().Mutate([](StateSnapshot &s) { s.obsStreaming = true; });
}

void OnFrontendEvent(enum obs_frontend_event event, void *)
{
	switch (event) {
	case OBS_FRONTEND_EVENT_FINISHED_LOADING: {
		UpdateTheme();
		PluginConfig config = ConfigStore::Instance().Get();
		std::string url = g_bridge && g_bridge->Running() ? g_bridge->DockUrl() : std::string();
#ifdef POKECLIP_DEV_LOG_DOCK_URL
		// 개발 빌드 전용(macos-local 프리셋) — 브라우저에서 독 페이지를 직접 열어 보기 위해. 배포 빌드에는 없다.
		obs_log(LOG_INFO, "[dev] dock url: %s", url.c_str());
#endif
		if (g_dock) {
			g_dock->Mount(url, config.forceFallback, []() { return g_bridge && g_bridge->HelloSeen(); });
			// 새 플러그인 독은 OBS가 숨긴 채 등록한다 — 첫 실행에 한 번만 펼쳐 스트리머가 찾게 한다.
			if (!config.dockIntroShown && g_dock->RevealDock())
				ConfigStore::Instance().Update([](PluginConfig &c) { c.dockIntroShown = true; });
		}
		if (!g_statsTimer) {
			auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
			g_statsTimer = new QTimer(main);
			QObject::connect(g_statsTimer, &QTimer::timeout, []() { StreamTarget::Instance().PollStats(); });
			g_statsTimer->start(1000);
		}
		AudioRouter::Instance().CollectionChanged();
		AudioRouter::Instance().Reconcile("loaded");
		RegisterMarkHotkey(); // 프로필 설정을 읽어야 해서 로드 뒤에
		break;
	}
	case OBS_FRONTEND_EVENT_SCENE_COLLECTION_CHANGING:
		AudioRouter::Instance().SetLoading(true);
		break;
	case OBS_FRONTEND_EVENT_SCENE_COLLECTION_CHANGED:
		AudioRouter::Instance().CollectionChanged();
		AudioRouter::Instance().SetLoading(false);
		break;
	case OBS_FRONTEND_EVENT_SCENE_COLLECTION_RENAMED:
		AudioRouter::Instance().CollectionRenamed();
		break;
	case OBS_FRONTEND_EVENT_RECORDING_STARTING:
		AudioRouter::Instance().Reconcile("recording starting");
		break;
	case OBS_FRONTEND_EVENT_RECORDING_STOPPED:
		AudioRouter::Instance().Schedule("recording stopped");
		break;
	// 동기화 규칙(설정 sync_start): 본방의 시작·정지만 따라간다. 본방이 잠시 끊겨 재연결 중일 때는
	// 프론트엔드 이벤트가 없고 obs_frontend_streaming_active()도 참으로 남으므로 우리 송출을 건드리지 않는다 —
	// 그 사이 PokeClip 녹화에 구멍이 나지 않는다. 재연결이 소진돼 본방이 실제로 멈추면 STOPPED가 와서 같이 멈춘다.
	case OBS_FRONTEND_EVENT_STREAMING_STARTING:
		OnStreamingStarting();
		break;
	case OBS_FRONTEND_EVENT_STREAMING_STARTED:
		AppState::Instance().Mutate([](StateSnapshot &s) { s.obsStreaming = true; });
		break;
	case OBS_FRONTEND_EVENT_STREAMING_STOPPING:
		// obs_output_stop 안에서 동기로 온다. 본방과 같이 멈춘다(예약된 재시도도 버린다).
		// 방송 표시도 여기서 내린다 — STOPPED까지 남겨 두면 그사이 독에 「다시 연결」이 잠깐 뜬다.
		AppState::Instance().Mutate([](StateSnapshot &s) { s.obsStreaming = false; });
		g_mainStopRequested = true;
		// 우리 출력이 있으면 조작으로 보고 멈춘다 — 공유 인코더 실패는 그 출력의 stopping 스레드로 걸러진다. 출력이
		// 없으면(재시도 대기·「재시도 중지」 뒤) 가를 수단이 없어 판정을 STOPPED(본방 stop 코드)로 미룬다.
		if (StreamTarget::Instance().HasOutput())
			StreamTarget::Instance().Stop(nullptr, /*intentional=*/true);
		else
			StreamTarget::Instance().Stop(nullptr, false, /*deferIntent=*/true);
		break;
	case OBS_FRONTEND_EVENT_STREAMING_STOPPED:
		AppState::Instance().Mutate([](StateSnapshot &s) { s.obsStreaming = false; });
		// STOPPING이 앞서 왔으면 같은 조작의 뒷부분이고(Stop은 한 구간에 한 번만 신호를 정한다), 아니면 본방이 스스로
		// 끊겨 끝난 것이다 — 종료 신호를 보내지 않는다. 출력 없이 미뤄 둔 판정은 본방 stop 코드로 가른다 — 공유 인코더
		// 실패(ENCODE_ERROR)면 조작이 아니다.
		StreamTarget::Instance().Stop(nullptr, g_mainStopRequested && (StreamTarget::Instance().HasOutput() ||
									      g_mainStopCode == OBS_OUTPUT_SUCCESS));
		g_mainStopRequested = false;
		UnwatchMainOutputStop();
		AudioRouter::Instance().Schedule("stream stopped");
		break;
	case OBS_FRONTEND_EVENT_THEME_CHANGED:
		UpdateTheme();
		break;
	case OBS_FRONTEND_EVENT_PROFILE_CHANGING:
		SaveMarkHotkeyCopy(); // 새 프로필에 단축키가 없으면 지금 키를 이어 쓴다
		break;
	case OBS_FRONTEND_EVENT_PROFILE_CHANGED:
		StreamTarget::Instance().ForceStop();
		ReloadMarkHotkey();
		AudioRouter::Instance().Schedule("profile changed"); // 본방 트랙(출력 설정)이 프로필마다 다르다
		break;
	case OBS_FRONTEND_EVENT_PROFILE_LIST_CHANGED:
		StreamTarget::Instance().ForceStop();
		break;
	case OBS_FRONTEND_EVENT_EXIT:
		AudioRouter::Instance().Shutdown(); // 종료 중 소스 정리 신호에 반응하지 않는다
		UnregisterMarkHotkey();
		MarkSender::Instance().Stop(); // 보내는 중이면 끊는다 — 종료를 막지 않는다
		// 방송 중 OBS 닫기 — 스트리머 조작이므로 종료 신호를 보낸다(보낼 수 있을 때만). 송신기는 여기서 멈추지 않는다 —
		// 언로드까지 남은 시간에 한 번 시도하고, obs_module_unload가 진행 중인 전송을 끊는다(남은 시도는 버린다).
		StreamTarget::Instance().ForceStop(/*intentional=*/true);
		UnwatchMainOutputStop();
		if (g_statsTimer)
			g_statsTimer->stop();
		if (g_dock)
			g_dock->CloseBrowser();
		AppState::Instance().Shutdown();
		if (g_bridge)
			g_bridge->Stop();
		break;
	default:
		break;
	}
}

} // namespace

bool obs_module_load(void)
{
	auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
	if (!main) {
		obs_log(LOG_ERROR, "no frontend main window — PokeClip needs the OBS Studio UI");
		return false;
	}
	SetUiThreadContext(main);

	if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK)
		obs_log(LOG_WARNING, "curl_global_init failed — pairing will not work");
	MarkSender::Instance().Start();
	EndSignalSender::Instance().Start();

	ConfigStore::Instance().Load();
	SyncStateFromConfig();
	AudioRouter::Instance().Init(); // 장면 컬렉션보다 먼저 — 소스 생성·로드 신호를 받아야 한다

	g_bridge = new BridgeServer();
	char *uiDir = obs_module_file("ui");
	bool bridgeOk = false;
	if (uiDir) {
		bridgeOk = g_bridge->Start(uiDir, MakeBridgeCallbacks());
		bfree(uiDir);
	}
	// 포트·토큰은 로그에 남기지 않는다.
	obs_log(LOG_INFO, "version %s loaded (bridge %s)", PLUGIN_VERSION,
		bridgeOk ? "up" : (uiDir ? "failed" : "no ui files"));

	g_dock = new DockHost();
	if (!obs_frontend_add_dock_by_id(kDockId, obs_module_text("Dock.Title"), g_dock)) {
		obs_log(LOG_ERROR, "failed to add dock");
		delete g_dock;
		g_dock = nullptr;
	}

	obs_frontend_add_event_callback(OnFrontendEvent, nullptr);
	return true;
}

void obs_module_unload(void)
{
	obs_frontend_remove_event_callback(OnFrontendEvent, nullptr);
	MarkSender::Instance().Stop(); // EXIT에서 이미 멈췄으면 아무것도 안 한다
	EndSignalSender::Instance().Stop(); // EXIT 뒤 보내던 종료 신호가 있으면 여기서 끊는다 — 종료를 막지 않는다
	AppState::Instance().Shutdown();
	if (g_bridge) {
		g_bridge->Stop();
		delete g_bridge;
		g_bridge = nullptr;
	}
	// curl_global_cleanup()은 부르지 않는다 — 폴백 패널의 분리 스레드가 아직 전송 중일 수 있고
	// (libcurl은 다른 스레드가 쓰는 중 cleanup을 금지), OBS는 모듈을 dlclose하지 않아 프로세스 종료가 정리한다.
	obs_log(LOG_INFO, "unloaded");
}
