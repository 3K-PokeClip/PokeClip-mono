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
	// 기본은 꺼짐 — 스트리머가 짜 둔 트랙 체크를 동의 없이 바꾸지 않는다. 페어링 뒤 독에서 한 번 묻는다
	// (사용자 결정 2026-10-01).
	bool audioAutoAssign = false;
	bool audioAssignPrompted = false; // 그 질문에 답했다(켜기·나중에) — 다시 묻지 않는다
	std::vector<AudioTrackMapEntry> audioTrackMap; // 소스별로 기억한 자리 — 방송 간 배정을 유지한다
	// 자동 배정이 처음 쓰기 전의 트랙 체크(소스·컬렉션별) — 끄면 되돌리고, 본방 트랙이 바뀌면 오간다
	std::vector<AudioMixerBackup> audioMixerBackup;
	// A4: 마크를 보낼 Clip API 주소. 비우면 apiBase (dev는 웹 프록시가 /api/clip/**을 Clip으로 넘긴다).
	std::string clipApiBase;
	// A4: 마지막으로 쓴 단축키 바인딩 사본({"bindings":[...]} JSON). 비었으면 사본 없음.
	// 프로필 basic.ini [Hotkeys]가 우선이고, 그 프로필에 없을 때 이것을, 이것도 없으면 기본 Ctrl+Shift+M을 쓴다.
	std::string markHotkey;

	bool HasKey() const { return !streamId.empty(); }
	// 송출 중에 바꾸면 안 되는 값(SRT 출력·동기화·독 표시 방식)이 같은가. 오디오 배정 값은 보지 않는다.
	bool SameOutputSettings(const PluginConfig &o) const
	{
		return ingestHost == o.ingestHost && ingestPort == o.ingestPort && sendPassphrase == o.sendPassphrase &&
		       latencyMs == o.latencyMs && syncStart == o.syncStart && forceFallback == o.forceFallback;
	}
	const std::string &ClipBase() const { return clipApiBase.empty() ? apiBase : clipApiBase; }
};

class ConfigStore {
public:
	static ConfigStore &Instance();

	void Load();
	PluginConfig Get() const;
	// 변경 후 즉시 저장한다. 저장 실패 시 false지만 메모리에는 남는다 — 이미 OBS에 적용한 값(트랙 체크 백업 등)을
	// 이번 실행 동안이라도 기억해야 하는 경로용.
	bool Update(const std::function<void(PluginConfig &)> &mutate);
	// Update와 같되 저장에 실패하면 메모리도 되돌린다 — 실패를 독에 알리고 상태를 바꾸지 않는 경로(페어링·연결 해제·
	// 설정 저장)용. 안 되돌리면 독은 옛 상태를 보이는데 다음 방송은 바뀐 값으로 돈다.
	bool Commit(const std::function<void(PluginConfig &)> &mutate);

private:
	bool SaveLocked();

	mutable std::mutex mutex_;
	PluginConfig config_;
	std::string path_;
};

PluginConfig DefaultConfig();

} // namespace pokeclip
