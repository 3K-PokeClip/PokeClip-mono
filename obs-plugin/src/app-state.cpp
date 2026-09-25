#include "app-state.hpp"

#include <obs-data.h>

#include <vector>

namespace pokeclip {

const char *PhaseName(StreamPhase phase)
{
	switch (phase) {
	case StreamPhase::Idle:
		return "idle";
	case StreamPhase::Starting:
		return "starting";
	case StreamPhase::Live:
		return "live";
	case StreamPhase::Reconnecting:
		return "reconnecting";
	case StreamPhase::Stopping:
		return "stopping";
	case StreamPhase::Error:
		return "error";
	}
	return "idle";
}

AppState &AppState::Instance()
{
	static AppState state;
	return state;
}

StateSnapshot AppState::Snapshot() const
{
	std::lock_guard lock(mutex_);
	return state_;
}

void AppState::Mutate(const std::function<void(StateSnapshot &)> &mutate)
{
	StateSnapshot copy;
	std::vector<Listener> listeners;
	{
		std::lock_guard lock(mutex_);
		mutate(state_);
		state_.version++;
		copy = state_;
		listeners.reserve(listeners_.size());
		for (auto &[id, l] : listeners_)
			listeners.push_back(l);
	}
	changed_.notify_all();
	for (auto &l : listeners)
		l(copy);
}

uint64_t AppState::AddListener(Listener listener)
{
	std::lock_guard lock(mutex_);
	uint64_t id = nextListenerId_++;
	listeners_[id] = std::move(listener);
	return id;
}

void AppState::RemoveListener(uint64_t id)
{
	std::lock_guard lock(mutex_);
	listeners_.erase(id);
}

bool AppState::WaitForChange(uint64_t sinceVersion, std::chrono::milliseconds timeout, StateSnapshot &out)
{
	std::unique_lock lock(mutex_);
	bool ready = changed_.wait_for(lock, timeout,
				       [&] { return shutdown_ || state_.version > sinceVersion; });
	if (!ready || shutdown_)
		return false;
	out = state_;
	return true;
}

void AppState::Shutdown()
{
	{
		std::lock_guard lock(mutex_);
		shutdown_ = true;
	}
	changed_.notify_all();
}

bool AppState::IsShutdown() const
{
	std::lock_guard lock(mutex_);
	return shutdown_;
}

static void SetOptionalBool(obs_data_t *data, const char *key, const std::optional<bool> &value)
{
	if (value.has_value())
		obs_data_set_bool(data, key, *value);
	else
		obs_data_set_string(data, key, "unknown");
}

// obs_data 배열은 객체만 담는다 — 이름 목록도 [{name}]로 싣는다.
static obs_data_array_t *NameArray(const std::vector<std::string> &names)
{
	obs_data_array_t *array = obs_data_array_create();
	for (const std::string &name : names) {
		obs_data_t *item = obs_data_create();
		obs_data_set_string(item, "name", name.c_str());
		obs_data_array_push_back(array, item);
		obs_data_release(item);
	}
	return array;
}

static obs_data_t *AudioToData(const AudioRoutingView &v)
{
	obs_data_t *audio = obs_data_create();
	obs_data_set_bool(audio, "known", v.known);
	obs_data_set_bool(audio, "autoAssign", v.autoAssign);
	obs_data_set_bool(audio, "applied", v.applied);
	obs_data_set_int(audio, "overflow", v.overflow);

	obs_data_array_t *tracks = obs_data_array_create();
	for (const AudioTrackView &t : v.tracks) {
		obs_data_t *track = obs_data_create();
		obs_data_set_int(track, "track", t.track);
		obs_data_array_t *sources = obs_data_array_create();
		for (const AudioSourceView &src : t.sources) {
			obs_data_t *item = obs_data_create();
			obs_data_set_string(item, "name", src.name.c_str());
			obs_data_set_string(item, "kind", AudioKindName(src.kind));
			obs_data_array_push_back(sources, item);
			obs_data_release(item);
		}
		obs_data_set_array(track, "sources", sources);
		obs_data_array_release(sources);
		obs_data_array_push_back(tracks, track);
		obs_data_release(track);
	}
	obs_data_set_array(audio, "tracks", tracks);
	obs_data_array_release(tracks);

	obs_data_array_t *mixOnly = NameArray(v.mixOnly);
	obs_data_set_array(audio, "mixOnly", mixOnly);
	obs_data_array_release(mixOnly);
	obs_data_array_t *monitorOnly = NameArray(v.monitorOnly);
	obs_data_set_array(audio, "monitorOnly", monitorOnly);
	obs_data_array_release(monitorOnly);
	return audio;
}

std::string AppState::ToJson(const StateSnapshot &s)
{
	obs_data_t *root = obs_data_create();
	obs_data_set_int(root, "version", (long long)s.version);
	obs_data_set_bool(root, "paired", s.paired);
	obs_data_set_string(root, "keyHint", s.keyHint.c_str());
	obs_data_set_string(root, "ingest", s.ingest.c_str());
	obs_data_set_string(root, "apiBase", s.apiBase.c_str());
	obs_data_set_string(root, "phase", PhaseName(s.phase));
	obs_data_set_string(root, "errorCode", s.errorCode.c_str());
	obs_data_set_string(root, "errorDetail", s.errorDetail.c_str());
	obs_data_set_bool(root, "obsStreaming", s.obsStreaming);
	obs_data_set_string(root, "theme", s.darkTheme ? "dark" : "light");

	obs_data_t *stats = obs_data_create();
	obs_data_set_double(stats, "bitrateKbps", s.stats.bitrateKbps);
	obs_data_set_int(stats, "totalFrames", (long long)s.stats.totalFrames);
	obs_data_set_int(stats, "droppedFrames", s.stats.droppedFrames);
	obs_data_set_int(stats, "uptimeSec", s.stats.uptimeSec);
	obs_data_set_obj(root, "stats", stats);
	obs_data_release(stats);

	obs_data_t *checks = obs_data_create();
	SetOptionalBool(checks, "gop2s", s.checks.gop2s);
	SetOptionalBool(checks, "res1080p", s.checks.res1080p);
	SetOptionalBool(checks, "sharedEncoder", s.checks.sharedEncoder);
	obs_data_set_int(checks, "keyintSec", s.checks.keyintSec);
	obs_data_set_int(checks, "width", s.checks.width);
	obs_data_set_int(checks, "height", s.checks.height);
	obs_data_set_double(checks, "fps", s.checks.fps);
	SetOptionalBool(checks, "audioTracks", s.checks.audioTracks);
	obs_data_set_int(checks, "audioTrackCount", s.checks.audioTrackCount);
	obs_data_set_obj(root, "checks", checks);
	obs_data_release(checks);

	obs_data_t *audio = AudioToData(s.audio);
	obs_data_set_obj(root, "audio", audio);
	obs_data_release(audio);

	std::string json = obs_data_get_json(root);
	obs_data_release(root);
	return json;
}

} // namespace pokeclip
