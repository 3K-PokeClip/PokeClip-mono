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
#include "bridge-server.hpp"
#include "config.hpp"
#include "constants.hpp"
#include "dock-host.hpp"
#include "gop-guard.hpp"
#include "pairing.hpp"
#include "srt-target.hpp"
#include "stream-target.hpp"
#include "ui-thread.hpp"

#include <curl/curl.h>
#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <QMainWindow>
#include <QTimer>

#include <cctype>

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
	std::string json = obs_data_get_json(d);
	obs_data_release(d);
	return json;
}

bool IsSafeHost(const std::string &host)
{
	if (host.empty() || host.size() > 253)
		return false;
	for (char ch : host) {
		unsigned char c = static_cast<unsigned char>(ch);
		if (!(std::isalnum(c) || c == '.' || c == '-' || c == ':' || c == '[' || c == ']'))
			return false;
	}
	return true;
}

void OnStreamingStarting();

BridgeCallbacks::Reply PutConfig(const std::string &body)
{
	if (StreamTarget::Instance().IsActive() || AppState::Instance().Snapshot().phase == StreamPhase::Starting)
		return {409, JsonReason(false, "streaming")};

	obs_data_t *d = obs_data_create_from_json(body.c_str());
	if (!d)
		return {400, JsonReason(false, "invalid_json")};

	PluginConfig next = ConfigStore::Instance().Get();
	bool syncWasOn = next.syncStart;
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
	str("api_base", next.apiBase);
	str("ingest_host", next.ingestHost);
	num("ingest_port", next.ingestPort);
	flag("send_passphrase", next.sendPassphrase);
	num("latency_ms", next.latencyMs);
	flag("sync_start", next.syncStart);
	flag("force_fallback", next.forceFallback);
	obs_data_release(d);

	bool apiOk = (next.apiBase.rfind("http://", 0) == 0 || next.apiBase.rfind("https://", 0) == 0) &&
		     next.apiBase.size() < 256 && next.apiBase.find_first_of(" \"'<>") == std::string::npos;
	if (!apiOk)
		return {400, JsonReason(false, "invalid_api_base")};
	if (!IsSafeHost(next.ingestHost))
		return {400, JsonReason(false, "invalid_ingest_host")};
	if (next.ingestPort < 1 || next.ingestPort > 65535)
		return {400, JsonReason(false, "invalid_ingest_port")};
	if (next.latencyMs < 20 || next.latencyMs > 8000)
		return {400, JsonReason(false, "invalid_latency")};

	if (!ConfigStore::Instance().Update([&](PluginConfig &c) {
		    std::string streamId = c.streamId;
		    std::string passphrase = c.passphrase;
		    c = next;
		    c.streamId = streamId; // 키는 이 경로로 바꾸지 않는다 (페어링 전용)
		    c.passphrase = passphrase;
	    }))
		return {500, JsonReason(false, "save_failed")};

	SyncStateFromConfig();

	// 동기화를 본방 송출 중에 켰다 — 「본방이 보내면 우리도 보낸다」를 지키려면 다음 방송을 기다리지 않고
	// 지금 시작한다. 시작 경로는 본방 STARTING과 같다(키·GOP 검사 포함). 브리지 워커 스레드라 UI 스레드로 넘긴다.
	// 🔴 이미 돌던 인코더는 keyint를 못 바꾸므로(x264) 그때는 encoder_active로 거절된다 — 본방을 다시 켜야 한다.
	if (next.syncStart && !syncWasOn && obs_frontend_streaming_active() && !StreamTarget::Instance().IsActive()) {
		obs_log(LOG_INFO, "sync switched on while main stream is live — starting SRT output now");
		RunInUiThread([]() { OnStreamingStarting(); });
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
	return cb;
}

void UpdateTheme()
{
	bool dark = obs_frontend_is_theme_dark();
	AppState::Instance().Mutate([dark](StateSnapshot &s) { s.darkTheme = dark; });
}

void SetError(StreamPhase phase, const std::string &code)
{
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.phase = phase;
		s.errorCode = code;
		s.errorDetail.clear();
	});
}

void OnStreamingStarting()
{
	AppState::Instance().Mutate([](StateSnapshot &s) {
		s.obsStreaming = true;
		s.checks = {};
	});

	PluginConfig config = ConfigStore::Instance().Get();
	if (!config.syncStart)
		return;
	if (!config.HasKey()) {
		obs_log(LOG_INFO, "main stream starting but not paired — skipping SRT output");
		SetError(StreamPhase::Idle, "no_key");
		return;
	}

	GopGuardResult gop = EnforceStreamEncoderPolicy();
	AppState::Instance().Mutate([&](StateSnapshot &s) { s.checks = gop.checks; });
	if (!gop.ok) {
		obs_log(LOG_WARNING, "not starting SRT output: %s", gop.errorCode.c_str());
		SetError(StreamPhase::Error, gop.errorCode);
		return;
	}

	std::string error;
	if (!StreamTarget::Instance().Start(config, error)) {
		obs_log(LOG_WARNING, "SRT output start failed: %s", error.c_str());
		SetError(StreamPhase::Error, error);
		return;
	}

	// 본방 출력이 비동기로 실패하면(키 오류 등) 우리 출력만 남는다 — 확인 후 같이 멈춘다.
	auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
	QTimer::singleShot(kMainStreamGuardMs, main, []() {
		if (!obs_frontend_streaming_active() && StreamTarget::Instance().IsActive()) {
			obs_log(LOG_WARNING, "main stream is not active — stopping SRT output");
			StreamTarget::Instance().Stop();
			SetError(StreamPhase::Error, "main_stream_failed");
		}
	});
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
		break;
	}
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
		// obs_output_stop 안에서 동기로 온다. 본방과 같이 멈춘다.
		StreamTarget::Instance().Stop();
		break;
	case OBS_FRONTEND_EVENT_STREAMING_STOPPED:
		AppState::Instance().Mutate([](StateSnapshot &s) { s.obsStreaming = false; });
		StreamTarget::Instance().Stop();
		break;
	case OBS_FRONTEND_EVENT_THEME_CHANGED:
		UpdateTheme();
		break;
	case OBS_FRONTEND_EVENT_PROFILE_CHANGED:
	case OBS_FRONTEND_EVENT_PROFILE_LIST_CHANGED:
		StreamTarget::Instance().ForceStop();
		break;
	case OBS_FRONTEND_EVENT_EXIT:
		StreamTarget::Instance().ForceStop();
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

	ConfigStore::Instance().Load();
	SyncStateFromConfig();

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
