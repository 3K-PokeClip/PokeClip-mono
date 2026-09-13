#include "config.hpp"

#include "constants.hpp"

#include <obs-module.h>
#include <plugin-support.h>
#include <util/platform.h>

#ifndef _WIN32
#include <sys/stat.h>
#endif

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
	config_.ingestHost = GetStringOr(data, "ingest_host", d.ingestHost);
	config_.ingestPort = GetIntOr(data, "ingest_port", d.ingestPort);
	config_.streamId = GetStringOr(data, "streamid", "");
	config_.passphrase = GetStringOr(data, "passphrase", "");
	config_.sendPassphrase = GetBoolOr(data, "send_passphrase", d.sendPassphrase);
	config_.latencyMs = GetIntOr(data, "latency_ms", d.latencyMs);
	config_.syncStart = GetBoolOr(data, "sync_start", d.syncStart);
	config_.forceFallback = GetBoolOr(data, "force_fallback", d.forceFallback);
	config_.dockIntroShown = GetBoolOr(data, "dock_intro_shown", d.dockIntroShown);
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

	bool ok = obs_data_save_json_pretty_safe(data, path_.c_str(), "tmp", "bak");
	obs_data_release(data);

#ifndef _WIN32
	if (ok)
		chmod(path_.c_str(), S_IRUSR | S_IWUSR);
#endif
	if (!ok)
		obs_log(LOG_WARNING, "config save failed");
	return ok;
}

} // namespace pokeclip
