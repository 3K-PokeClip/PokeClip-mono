#pragma once

#include "audio-assign.hpp"

#include <functional>
#include <mutex>
#include <string>
#include <vector>

namespace pokeclip {

// plugin_config/pokeclip-obs/pokeclip.json. 계정 단위 설정이라 OBS 프로필과 무관하다.
struct PluginConfig {
	std::string apiBase;
	std::string ingestHost;
	int ingestPort = 0;
	std::string streamId;   // ADR-019 #!::r=<token>,m=publish — 공개 식별자
	std::string passphrase; // 비밀. 로그·브리지 응답에 싣지 않는다.
	bool sendPassphrase = true;
	int latencyMs = 0;
	bool syncStart = true;
	bool forceFallback = false;
	bool dockIntroShown = false; // 첫 실행에 독을 한 번 펼쳤는지 (새 플러그인 독은 OBS가 숨긴 채 등록한다)
	// A2: 오디오 소스를 트랙 2~6에 하나씩 자동 배정한다(audio-assign.hpp). 끄면 OBS 고급 오디오 설정 그대로.
	bool audioAutoAssign = true;
	std::vector<AudioTrackMapEntry> audioTrackMap; // 소스별로 기억한 자리 — 방송 간 배정을 유지한다
	std::vector<AudioMixerBackup> audioMixerBackup; // 자동 배정이 처음 쓰기 전의 트랙 체크 — 끄면 되돌린다
	uint32_t audioReservedMask = 0; // 마지막으로 본 본방 트랙 비트 — 새로 본방 트랙이 된 트랙을 알아본다
	// A4: 마크를 보낼 Clip API 주소. 비우면 apiBase (dev는 웹 프록시가 /api/clip/**을 Clip으로 넘긴다).
	std::string clipApiBase;
	// A4: 마지막으로 쓴 단축키 바인딩 사본({"bindings":[...]} JSON). 비었으면 사본 없음.
	// 프로필 basic.ini [Hotkeys]가 우선이고, 그 프로필에 없을 때 이것을, 이것도 없으면 기본 Ctrl+Shift+M을 쓴다.
	std::string markHotkey;

	bool HasKey() const { return !streamId.empty(); }
	const std::string &ClipBase() const { return clipApiBase.empty() ? apiBase : clipApiBase; }
};

class ConfigStore {
public:
	static ConfigStore &Instance();

	void Load();
	PluginConfig Get() const;
	// 변경 후 즉시 저장한다. 저장 실패 시 false.
	bool Update(const std::function<void(PluginConfig &)> &mutate);

private:
	bool SaveLocked();

	mutable std::mutex mutex_;
	PluginConfig config_;
	std::string path_;
};

PluginConfig DefaultConfig();

} // namespace pokeclip
