#include "config.hpp"

#include "constants.hpp"
#include "private-file.hpp"

#include <obs-module.h>
#include <plugin-support.h>
#include <util/platform.h>

namespace pokeclip {

PluginConfig DefaultConfig()
{
	PluginConfig c;
	c.apiBase = kDefaultApiBase;
	c.ingestHost = kDefaultIngestHost;
	c.ingestPort = kDefaultIngestPort;
	c.latencyMs = kDefaultLatencyMs;
	return c;
}

ConfigStore &ConfigStore::Instance()
{
	static ConfigStore store;
	return store;
}

static std::string GetStringOr(obs_data_t *data, const char *key, const std::string &fallback)
{
	if (!obs_data_has_user_value(data, key))
		return fallback;
	const char *v = obs_data_get_string(data, key);
	return v ? std::string(v) : fallback;
}

static int GetIntOr(obs_data_t *data, const char *key, int fallback)
{
	return obs_data_has_user_value(data, key) ? (int)obs_data_get_int(data, key) : fallback;
}

static bool GetBoolOr(obs_data_t *data, const char *key, bool fallback)
{
	return obs_data_has_user_value(data, key) ? obs_data_get_bool(data, key) : fallback;
}

void ConfigStore::Load()
{
	std::lock_guard lock(mutex_);

	char *dir = obs_module_config_path("");
	if (dir) {
		os_mkdirs(dir);
		bfree(dir);
	}
	char *path = obs_module_config_path("pokeclip.json");
	if (!path) {
		obs_log(LOG_WARNING, "config path unavailable — using defaults");
		config_ = DefaultConfig();
		return;
	}
	path_ = path;
	bfree(path);

	config_ = DefaultConfig();
	obs_data_t *data = obs_data_create_from_json_file_safe(path_.c_str(), "bak");
	if (!data) {
		obs_log(LOG_INFO, "no config yet — defaults");
		return;
	}

	PluginConfig d = config_;
	config_.apiBase = GetStringOr(data, "api_base", d.apiBase);
	if (config_.apiBase == kLegacyDefaultApiBase)
		config_.apiBase = kDefaultApiBase; // 손대지 않은 옛 기본값 — dev는 HTTP를 HTTPS로 돌려보낸다
	config_.ingestHost = GetStringOr(data, "ingest_host", d.ingestHost);
	config_.ingestPort = GetIntOr(data, "ingest_port", d.ingestPort);
	config_.streamId = GetStringOr(data, "streamid", "");
	config_.passphrase = GetStringOr(data, "passphrase", "");
	config_.sendPassphrase = GetBoolOr(data, "send_passphrase", d.sendPassphrase);
	config_.latencyMs = GetIntOr(data, "latency_ms", d.latencyMs);
	config_.syncStart = GetBoolOr(data, "sync_start", d.syncStart);
	config_.forceFallback = GetBoolOr(data, "force_fallback", d.forceFallback);
	config_.dockIntroShown = GetBoolOr(data, "dock_intro_shown", d.dockIntroShown);
	config_.audioAutoAssign = GetBoolOr(data, "audio_auto_assign", d.audioAutoAssign);
	config_.audioAssignPrompted = GetBoolOr(data, "audio_assign_prompted", d.audioAssignPrompted);
	config_.audioTrackMap.clear();
	if (obs_data_array_t *map = obs_data_get_array(data, "audio_track_map")) {
		// 손상 항목(자리 범위 밖·빈 열쇠)은 배정 계산이 걸러 낸다(ComputeAssignment 1단계).
		size_t count = obs_data_array_count(map);
		for (size_t i = 0; i < count && i < kTrackMapCap * 2; i++) {
			obs_data_t *item = obs_data_array_item(map, i);
			AudioTrackMapEntry e;
			e.key = obs_data_get_string(item, "key");
			e.slot = (int)obs_data_get_int(item, "slot");
			e.name = obs_data_get_string(item, "name");
			e.assignedAt = obs_data_get_int(item, "assigned_at");
			e.lastSeen = obs_data_get_int(item, "last_seen");
			obs_data_release(item);
			config_.audioTrackMap.push_back(std::move(e));
		}
		obs_data_array_release(map);
	}
	config_.audioMixerBackup.clear();
	// 리뷰 2판 형식은 되돌린 본방 트랙을 계정 하나로(audio_reserved_mask) 기억했다.
	// 항목별 값이 없으면 그것을 쓴다.
	uint32_t legacyReserved = static_cast<uint32_t>(GetIntOr(data, "audio_reserved_mask", 0)) & kStemMask;
	if (obs_data_array_t *backup = obs_data_get_array(data, "audio_mixer_backup")) {
		size_t count = obs_data_array_count(backup);
		for (size_t i = 0; i < count && i < kMixerBackupCap * 2; i++) {
			obs_data_t *item = obs_data_array_item(backup, i);
			AudioMixerBackup b;
			b.key = obs_data_get_string(item, "key");
			b.mixers = static_cast<uint32_t>(obs_data_get_int(item, "mixers"));
			b.restored = obs_data_has_user_value(item, "restored")
					     ? static_cast<uint32_t>(obs_data_get_int(item, "restored")) & kStemMask
					     : legacyReserved;
			obs_data_release(item);
			if (!b.key.empty())
				config_.audioMixerBackup.push_back(std::move(b));
		}
		obs_data_array_release(backup);
	}
	config_.clipApiBase = GetStringOr(data, "clip_api_base", "");
	config_.markHotkey.clear();
	if (obs_data_t *hotkey = obs_data_get_obj(data, "mark_hotkey")) {
		config_.markHotkey = obs_data_get_json(hotkey);
		obs_data_release(hotkey);
	}
	obs_data_release(data);

	obs_log(LOG_INFO, "config loaded (paired=%s, ingest=%s:%d)", config_.HasKey() ? "yes" : "no",
		config_.ingestHost.c_str(), config_.ingestPort);
}

PluginConfig ConfigStore::Get() const
{
	std::lock_guard lock(mutex_);
	return config_;
}

bool ConfigStore::Update(const std::function<void(PluginConfig &)> &mutate)
{
	std::lock_guard lock(mutex_);
	mutate(config_);
	return SaveLocked();
}

bool ConfigStore::Commit(const std::function<void(PluginConfig &)> &mutate)
{
	std::lock_guard lock(mutex_);
	PluginConfig previous = config_;
	mutate(config_);
	if (SaveLocked())
		return true;
	config_ = std::move(previous);
	return false;
}

bool ConfigStore::SaveLocked()
{
	if (path_.empty())
		return false;

	obs_data_t *data = obs_data_create();
	obs_data_set_string(data, "api_base", config_.apiBase.c_str());
	obs_data_set_string(data, "ingest_host", config_.ingestHost.c_str());
	obs_data_set_int(data, "ingest_port", config_.ingestPort);
	obs_data_set_string(data, "streamid", config_.streamId.c_str());
	obs_data_set_string(data, "passphrase", config_.passphrase.c_str());
	obs_data_set_bool(data, "send_passphrase", config_.sendPassphrase);
	obs_data_set_int(data, "latency_ms", config_.latencyMs);
	obs_data_set_bool(data, "sync_start", config_.syncStart);
	obs_data_set_bool(data, "force_fallback", config_.forceFallback);
	obs_data_set_bool(data, "dock_intro_shown", config_.dockIntroShown);
	obs_data_set_bool(data, "audio_auto_assign", config_.audioAutoAssign);
	obs_data_set_bool(data, "audio_assign_prompted", config_.audioAssignPrompted);
	obs_data_array_t *map = obs_data_array_create();
	for (const AudioTrackMapEntry &e : config_.audioTrackMap) {
		obs_data_t *item = obs_data_create();
		obs_data_set_string(item, "key", e.key.c_str());
		obs_data_set_int(item, "slot", e.slot);
		obs_data_set_string(item, "name", e.name.c_str());
		obs_data_set_int(item, "assigned_at", e.assignedAt);
		obs_data_set_int(item, "last_seen", e.lastSeen);
		obs_data_array_push_back(map, item);
		obs_data_release(item);
	}
	obs_data_set_array(data, "audio_track_map", map);
	obs_data_array_release(map);
	obs_data_array_t *backup = obs_data_array_create();
	for (const AudioMixerBackup &b : config_.audioMixerBackup) {
		obs_data_t *item = obs_data_create();
		obs_data_set_string(item, "key", b.key.c_str());
		obs_data_set_int(item, "mixers", b.mixers);
		obs_data_set_int(item, "restored", b.restored);
		obs_data_array_push_back(backup, item);
		obs_data_release(item);
	}
	obs_data_set_array(data, "audio_mixer_backup", backup);
	obs_data_array_release(backup);
	obs_data_set_string(data, "clip_api_base", config_.clipApiBase.c_str());
	if (!config_.markHotkey.empty()) {
		if (obs_data_t *hotkey = obs_data_create_from_json(config_.markHotkey.c_str())) {
			obs_data_set_obj(data, "mark_hotkey", hotkey);
			obs_data_release(hotkey);
		}
	}

#ifdef _WIN32
	bool ok = obs_data_save_json_pretty_safe(data, path_.c_str(), "tmp", "bak");
#else
	// streamid·passphrase가 든다 — 처음부터 0600으로 쓴다. libobs 저장은 tmp를 umask 권한(보통 0644)으로 만들어
	// rename하므로, 뒤에 chmod를 걸어도 그 사이 남이 읽을 틈이 있다.
	const char *json = obs_data_get_json_pretty(data);
	bool ok = json && *json && WritePrivateFileAtomic(path_, json);
#endif
	obs_data_release(data);

	if (!ok)
		obs_log(LOG_WARNING, "config save failed");
	return ok;
}

} // namespace pokeclip
