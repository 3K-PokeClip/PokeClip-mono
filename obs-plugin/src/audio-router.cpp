#include "audio-router.hpp"

#include "app-state.hpp"
#include "audio-assign.hpp"
#include "config.hpp"
#include "stream-target.hpp"
#include "ui-thread.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <cstdlib>
#include <ctime>
#include <map>
#include <set>
#include <vector>

namespace pokeclip {

namespace {

// 설정›오디오의 전역 장치 채널: 1·2 데스크탑 오디오, 3~6 마이크/보조.
constexpr uint32_t kFirstGlobalChannel = 1;
constexpr uint32_t kLastGlobalChannel = 6;

// 목록이 바뀌는 신호는 전부 한 번의 계산으로 모은다. 소리를 내기 시작·멈춘 소스도 후보가 바뀐다.
const char *const kChangeSignals[] = {"source_destroy", "source_remove", "source_rename", "source_audio_activate",
				      "source_audio_deactivate"};

bool IsAudioInput(obs_source_t *source)
{
	return source && obs_source_get_type(source) == OBS_SOURCE_TYPE_INPUT && !obs_obj_is_private(source) &&
	       (obs_source_get_output_flags(source) & OBS_SOURCE_AUDIO) != 0;
}

// 전역 장치 포인터 → 채널. 출력 채널이 참조를 쥐고 있어 같은 UI 스레드 작업 안에서는 포인터가 유효하다.
std::map<obs_source_t *, int> GlobalChannels()
{
	std::map<obs_source_t *, int> channels;
	for (uint32_t ch = kFirstGlobalChannel; ch <= kLastGlobalChannel; ch++) {
		obs_source_t *s = obs_get_output_source(ch);
		if (!s)
			continue;
		channels[s] = static_cast<int>(ch);
		obs_source_release(s);
	}
	return channels;
}

AudioSourceInfo Describe(obs_source_t *s, const std::map<obs_source_t *, int> &channels, int index)
{
	AudioSourceInfo info;
	auto it = channels.find(s);
	if (it != channels.end()) {
		info.key = "ch:" + std::to_string(it->second);
		info.kind = ClassifyGlobalChannel(it->second);
		info.order = it->second;
	} else {
		const char *uuid = obs_source_get_uuid(s);
		const char *id = obs_source_get_unversioned_id(s);
		info.key = std::string("uuid:") + (uuid ? uuid : "");
		info.kind = ClassifyAudioSourceId(id ? id : "");
		info.order = 100 + index;
	}
	const char *name = obs_source_get_name(s);
	info.name = name ? name : "";
	info.mixers = obs_source_get_audio_mixers(s);
	info.audioActive = obs_source_audio_active(s);
	info.monitorOnly = obs_source_get_monitoring_type(s) == OBS_MONITORING_TYPE_MONITOR_ONLY;
	return info;
}

std::vector<AudioSourceInfo> Enumerate()
{
	struct Context {
		std::map<obs_source_t *, int> channels;
		std::set<obs_source_t *> seen;
		std::vector<AudioSourceInfo> out;
		int index = 0;
	} ctx;
	ctx.channels = GlobalChannels();

	obs_enum_sources(
		[](void *param, obs_source_t *s) {
			auto *c = static_cast<Context *>(param);
			if (!IsAudioInput(s))
				return true;
			AudioSourceInfo info = Describe(s, c->channels, c->index++);
			if (info.key != "uuid:") {
				c->out.push_back(std::move(info));
				c->seen.insert(s);
			}
			return true;
		},
		&ctx);

	// 전역 장치가 공개 열거에 안 나오는 빌드를 대비한다.
	for (const auto &[s, ch] : ctx.channels) {
		if (!ctx.seen.count(s) && IsAudioInput(s))
			ctx.out.push_back(Describe(s, ctx.channels, 0));
	}
	return ctx.out;
}

// 새 참조를 돌려준다.
obs_source_t *SourceByKey(const std::string &key)
{
	if (key.rfind("ch:", 0) == 0) {
		int ch = std::atoi(key.c_str() + 3);
		return ch >= 1 && ch <= static_cast<int>(kLastGlobalChannel) ? obs_get_output_source(static_cast<uint32_t>(ch))
									      : nullptr;
	}
	if (key.rfind("uuid:", 0) == 0)
		return obs_get_source_by_uuid(key.c_str() + 5);
	return nullptr;
}

// 스트리머의 녹화 트랙도 같은 믹서를 쓴다 — 방송·녹화 중에는 지금 나가는 배정을 옮기지 않는다.
bool Locked()
{
	return obs_frontend_streaming_active() || obs_frontend_recording_active() || StreamTarget::Instance().IsActive();
}

} // namespace

AudioRouter &AudioRouter::Instance()
{
	static AudioRouter router;
	return router;
}

void AudioRouter::Init()
{
	if (initialized_)
		return;
	signal_handler_t *sh = obs_get_signal_handler();
	signal_handler_connect(sh, "source_create", &AudioRouter::OnSourceCreate, this);
	signal_handler_connect(sh, "source_load", &AudioRouter::OnSourceLoad, this);
	for (const char *name : kChangeSignals)
		signal_handler_connect(sh, name, &AudioRouter::OnSourceChanged, this);
	initialized_ = true;
}

void AudioRouter::Shutdown()
{
	shutdown_ = true;
	if (!initialized_)
		return;
	// 소스별 audio_mixers 연결은 소스와 함께 사라진다. 그 전에 오는 시그널은 shutdown_이 막는다.
	signal_handler_t *sh = obs_get_signal_handler();
	signal_handler_disconnect(sh, "source_create", &AudioRouter::OnSourceCreate, this);
	signal_handler_disconnect(sh, "source_load", &AudioRouter::OnSourceLoad, this);
	for (const char *name : kChangeSignals)
		signal_handler_disconnect(sh, name, &AudioRouter::OnSourceChanged, this);
	initialized_ = false;
}

void AudioRouter::SetLoading(bool loading)
{
	loading_ = loading;
	if (!loading)
		Reconcile("collection");
}

void AudioRouter::Schedule(const char *reason)
{
	if (shutdown_ || pending_.exchange(true))
		return;
	std::string why = reason;
	RunInUiThread([this, why]() {
		if (pending_)
			Reconcile(why.c_str());
	});
}

void AudioRouter::Reconcile(const char *reason)
{
	if (shutdown_ || loading_)
		return; // 컬렉션 전환 중이면 SetLoading(false)가 한 번 계산한다
	pending_ = false;

	PluginConfig config = ConfigStore::Instance().Get();
	// 페어링 안 한 OBS의 녹화 트랙은 건드리지 않는다 — PokeClip과 연결된 OBS만 자동 배정한다.
	bool applied = config.audioAutoAssign && config.HasKey();
	std::vector<AudioSourceInfo> sources = Enumerate();

	int overflow = 0;
	if (applied) {
		AssignmentInput in;
		in.sources = sources;
		in.map = config.audioTrackMap;
		in.locked = Locked();
		in.now = static_cast<int64_t>(std::time(nullptr));
		AssignmentResult r = ComputeAssignment(in);

		applying_ = true;
		for (const MixerWrite &w : r.writes) {
			obs_source_t *s = SourceByKey(w.key);
			if (!s)
				continue;
			obs_source_set_audio_mixers(s, w.mixers);
			obs_source_release(s);
			for (AudioSourceInfo &info : sources) {
				if (info.key == w.key)
					info.mixers = w.mixers;
			}
		}
		applying_ = false;

		if (r.mapChanged)
			ConfigStore::Instance().Update([&](PluginConfig &c) { c.audioTrackMap = r.mapNext; });
		overflow = static_cast<int>(r.overflowKeys.size());
	}

	AudioRoutingView view = BuildRoutingView(sources, config.audioAutoAssign, applied, overflow);
	std::string line = std::string(applied ? "auto" : "manual") + " — " + DescribeRouting(view);
	if (line != lastLogged_) {
		obs_log(LOG_INFO, "audio routing (%s): %s", reason, line.c_str());
		lastLogged_ = line;
	}
	if (!(AppState::Instance().Snapshot().audio == view))
		AppState::Instance().Mutate([&](StateSnapshot &s) { s.audio = view; });
}

// 아래 시그널 핸들러는 libobs 스레드에서 올 수 있다 — UI 스레드로 모아 넘기기만 한다.

void AudioRouter::OnSourceCreate(void *data, calldata_t *params)
{
	auto *self = static_cast<AudioRouter *>(data);
	auto *source = static_cast<obs_source_t *>(calldata_ptr(params, "source"));
	if (self->shutdown_ || !IsAudioInput(source))
		return;
	signal_handler_connect(obs_source_get_signal_handler(source), "audio_mixers", &AudioRouter::OnMixersChanged,
			       self);

	// OBS는 새 소스를 트랙 1~6에 전부 켠 채 만든다. 계산이 도는 몇 ms 사이에도 스템에 섞이지 않게
	// 기억에 없는 새 소스는 곧바로 트랙 2~6을 끈다. 컬렉션을 읽는 중이면 저장값이 뒤이어 덮어쓴다(무해).
	PluginConfig config = ConfigStore::Instance().Get();
	if (config.audioAutoAssign && config.HasKey()) {
		const char *uuid = obs_source_get_uuid(source);
		std::string key = std::string("uuid:") + (uuid ? uuid : "");
		bool remembered = false;
		for (const AudioTrackMapEntry &e : config.audioTrackMap)
			remembered = remembered || e.key == key;
		uint32_t mixers = obs_source_get_audio_mixers(source);
		if (!remembered && (mixers & kStemMask) != 0)
			obs_source_set_audio_mixers(source, DesiredMixers(mixers, 0));
	}
	self->Schedule("source added");
}

void AudioRouter::OnSourceLoad(void *data, calldata_t *params)
{
	auto *self = static_cast<AudioRouter *>(data);
	auto *source = static_cast<obs_source_t *>(calldata_ptr(params, "source"));
	if (self->shutdown_ || !IsAudioInput(source))
		return;
	signal_handler_connect(obs_source_get_signal_handler(source), "audio_mixers", &AudioRouter::OnMixersChanged,
			       self);
	self->Schedule("source loaded");
}

void AudioRouter::OnSourceChanged(void *data, calldata_t *)
{
	static_cast<AudioRouter *>(data)->Schedule("sources changed");
}

// 스트리머가 OBS 고급 오디오 속성에서 트랙 체크를 바꿨다. 이 시그널은 값이 적용되기 전에 오므로
// 여기서 읽지 않고 UI 스레드의 계산이 적용된 값을 다시 읽는다. 자동 배정이 켜져 있으면 트랙 2~6은 되돌아간다.
void AudioRouter::OnMixersChanged(void *data, calldata_t *)
{
	auto *self = static_cast<AudioRouter *>(data);
	if (self->applying_)
		return;
	self->Schedule("mixers changed");
}

} // namespace pokeclip
