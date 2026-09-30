#include "audio-router.hpp"

#include "app-state.hpp"
#include "audio-assign.hpp"
#include "config.hpp"
#include "stream-target.hpp"
#include "ui-thread.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>
#include <util/config-file.h>
#include <util/dstr.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <set>
#include <vector>

namespace pokeclip {

namespace {

// 설정›오디오의 전역 장치 채널: 1·2 데스크탑 오디오, 3~6 마이크/보조.
constexpr uint32_t kFirstGlobalChannel = 1;
constexpr uint32_t kLastGlobalChannel = 6;

// 방송·녹화 중에 켜진 자동 배정을 미룬 동안, 끝났는지 다시 보는 간격. 우리 SRT 출력은 정지 신호 뒤에도
// 잠시 active로 남아(stream-target.cpp ReleaseWhenStopped) 정지 이벤트 한 번으로는 놓칠 수 있다.
constexpr int kDeferredRetryMs = 2000;

// 목록이 바뀌는 신호는 전부 한 번의 계산으로 모은다. 소리를 내기 시작·멈춘 소스, 방송 화면에 들어오고 나간 소스도
// 후보가 바뀐다(장면 전환마다 여러 개가 오지만 Schedule이 한 번으로 모은다).
const char *const kChangeSignals[] = {"source_destroy",        "source_remove",           "source_rename",
				      "source_audio_activate", "source_audio_deactivate", "source_activate",
				      "source_deactivate"};

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

using MonitoringPending = std::map<std::string, int>;

AudioSourceInfo Describe(obs_source_t *s, const std::map<obs_source_t *, int> &channels, int index,
			 const MonitoringPending &monitoring)
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
	int monitoringType = obs_source_get_monitoring_type(s);
	const char *uuid = obs_source_get_uuid(s);
	auto pending = uuid ? monitoring.find(uuid) : monitoring.end();
	if (pending != monitoring.end())
		monitoringType = pending->second; // 시그널로 받았지만 아직 저장되지 않은 새 값
	info.monitorOnly = monitoringType == OBS_MONITORING_TYPE_MONITOR_ONLY;
	info.showing = obs_source_active(s);
	return info;
}

std::vector<AudioSourceInfo> Enumerate(const MonitoringPending &monitoring)
{
	struct Context {
		std::map<obs_source_t *, int> channels;
		std::set<obs_source_t *> seen;
		std::vector<AudioSourceInfo> out;
		const MonitoringPending *monitoring = nullptr;
		int index = 0;
	} ctx;
	ctx.channels = GlobalChannels();
	ctx.monitoring = &monitoring;

	obs_enum_sources(
		[](void *param, obs_source_t *s) {
			auto *c = static_cast<Context *>(param);
			if (!IsAudioInput(s))
				return true;
			AudioSourceInfo info = Describe(s, c->channels, c->index++, *c->monitoring);
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
			ctx.out.push_back(Describe(s, ctx.channels, 0, monitoring));
	}
	return ctx.out;
}

// 전역 장치는 장면 컬렉션마다 따로 저장된다(컬렉션 JSON의 DesktopAudioDevice1·AuxAudioDevice1 — 트랙 체크도 따로).
// 원래 체크 백업만 컬렉션으로 나눈다. uuid 소스는 이미 전역에서 유일하다.
void ScopeBackupKeys(std::vector<AudioSourceInfo> &sources)
{
	char *name = obs_frontend_get_current_scene_collection();
	std::string collection = name ? name : "";
	bfree(name);
	for (AudioSourceInfo &s : sources) {
		if (s.key.rfind("ch:", 0) == 0)
			s.backupKey = s.key + "@" + collection;
	}
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

// 스트리머의 녹화·리플레이 버퍼도 같은 믹서를 쓴다 — 그동안에는 지금 나가는 배정을 옮기지 않는다.
bool Locked()
{
	return obs_frontend_streaming_active() || obs_frontend_recording_active() ||
	       obs_frontend_replay_buffer_active() || StreamTarget::Instance().IsActive();
}

uint32_t TrackBit(int track)
{
	return track >= 1 && track <= 6 ? 1u << (track - 1) : 1u;
}

// OBS가 이 서비스에 VOD 트랙을 실제로 붙이는지. 사용자 지정 서버는 사용자 설정 스위치를, 그 밖은 Twitch만
// (OBS 32 frontend/utility의 ServiceSupportsVodTrack·VodTrackMixerIdx·IsVodTrackEnabled와 같은 판정).
bool VodTrackService(bool &custom)
{
	custom = false;
	obs_service_t *service = obs_frontend_get_streaming_service(); // 참조를 늘리지 않는다
	if (!service)
		return false;
	const char *id = obs_service_get_id(service);
	custom = id && std::strcmp(id, "rtmp_custom") == 0;
	if (custom) {
		config_t *user = obs_frontend_get_user_config();
		return user && config_get_bool(user, "General", "EnableCustomServerVodTrack");
	}
	obs_data_t *settings = obs_service_get_settings(service);
	const char *name = settings ? obs_data_get_string(settings, "service") : nullptr;
	bool twitch = name && astrcmpi(name, "Twitch") == 0;
	obs_data_release(settings);
	return twitch;
}

// 본방(OBS 방송 출력)이 쓰는 믹서 비트. 고급 출력은 방송 트랙을 1~6 중에서 고르고, Twitch VOD 트랙은 또 하나를 쓴다.
// 방송 전에도 알아야 하므로 프로필 설정에서 읽고(OBS가 방송 시작 때 같은 값으로 출력을 짠다),
// 방송 중이면 실제로 붙은 인코더의 믹서도 더한다.
uint32_t MainStreamMixers()
{
	uint32_t mixers = 1u; // 단순 출력은 트랙 1
	if (config_t *profile = obs_frontend_get_profile_config()) {
		const char *mode = config_get_string(profile, "Output", "Mode");
		bool advanced = mode && astrcmpi(mode, "Advanced") == 0;
		bool custom = false;
		bool vodService = VodTrackService(custom);
		if (advanced) {
			int track = static_cast<int>(config_get_int(profile, "AdvOut", "TrackIndex"));
			int vodTrack = static_cast<int>(config_get_int(profile, "AdvOut", "VodTrackIndex"));
			mixers = TrackBit(track);
			if (vodService && config_get_bool(profile, "AdvOut", "VodTrackEnabled") && vodTrack != track)
				mixers |= TrackBit(vodTrack);
		} else if (vodService && config_get_bool(profile, "SimpleOutput", "VodTrackEnabled") &&
			   (custom || config_get_bool(profile, "SimpleOutput", "UseAdvanced"))) {
			mixers |= TrackBit(2); // 단순 출력의 VOD 트랙은 트랙 2 고정
		}
	}
	if (obs_frontend_streaming_active()) {
		if (obs_output_t *out = obs_frontend_get_streaming_output()) {
			for (size_t i = 0; i < MAX_OUTPUT_AUDIO_ENCODERS; i++) {
				if (obs_encoder_t *enc = obs_output_get_audio_encoder(out, i))
					mixers |= 1u << obs_encoder_get_mixer_index(enc);
			}
			obs_output_release(out);
		}
	}
	return mixers;
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
	// 소스별 audio_mixers·audio_monitoring 연결은 소스와 함께 사라진다. 그 전에 오는 시그널은 shutdown_이 막는다.
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
	bool wanted = config.audioAutoAssign && config.HasKey();
	bool locked = Locked();
	// 방송·녹화 중에 자동 배정이 새로 켜졌다(스위치·페어링, 또는 그 상태로 OBS를 시작) — 처음 적용은 모든 소스의
	// 트랙 2~6을 다시 쓰므로 지금 나가는 트랙이 바뀐다. 끝날 때까지 미룬다. 이미 적용 중이던 배정은 방송 중에도
	// 잠금 규칙대로 새 소스만 빈 트랙에 앉힌다.
	bool deferred = wanted && locked && !applied_;
	bool applied = wanted && !deferred;
	applied_ = applied;
	uint32_t reserved = MainStreamMixers() & kStemMask;
	reserved_ = reserved;

	MonitoringPending monitoring;
	{
		std::lock_guard lock(monitoringMutex_);
		monitoring = monitoringPending_;
	}
	std::vector<AudioSourceInfo> sources = Enumerate(monitoring);
	ScopeBackupKeys(sources);
	SettleMonitoring(monitoring);

	auto apply = [&](const std::vector<MixerWrite> &writes) {
		applying_ = true;
		for (const MixerWrite &w : writes) {
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
	};

	// 원래 체크 오가기 — 모두 지금 불러온 소스만. 다른 장면 컬렉션의 소스는 돌아올 때 맞춘다.
	// ① 본방 트랙이 바뀐 만큼: 새로 본방 트랙이 된 트랙은 원래 체크로(자동 배정이 스템으로 바꿔 놨다면 시청자가
	//    들을 믹스가 소스 하나뿐이다 — 방송이 막 시작하는 참이어도 곧바로), 본방에서 빠지는 트랙은 그동안
	//    스트리머가 짠 체크를 원래 값으로 옮겨 둔다.
	// ② 자동 배정을 껐거나 연결을 해제했다: 트랙 2~6(본방 트랙 제외) 전부. 방송·녹화 중이면 지금 나가는 트랙이
	//    바뀌므로 끝날 때까지 미룬다. 되돌린 소스의 기억은 지운다 — 다시 켜면 그때의 체크를 새로 남긴다.
	std::vector<AudioMixerBackup> backup = config.audioMixerBackup;
	ReservedSync sync = SyncReservedTracks(sources, backup, reserved);
	apply(sync.writes);
	backup = sync.backupNext;
	if (!sync.writes.empty())
		obs_log(LOG_INFO, "audio routing: restored original main-stream track checks for %zu source(s)",
			sync.writes.size());

	std::set<std::string> present;
	for (const AudioSourceInfo &s : sources)
		present.insert(s.BackupKey());
	bool presentBackup = std::any_of(backup.begin(), backup.end(),
					 [&](const AudioMixerBackup &b) { return present.count(b.key) != 0; });
	if (!wanted && presentBackup && !locked) {
		std::vector<MixerWrite> writes = RestoreWrites(sources, backup, kStemMask & ~reserved);
		apply(writes);
		if (!writes.empty())
			obs_log(LOG_INFO,
				"audio routing: restored original track checks for %zu source(s) (auto-assign off)",
				writes.size());
		backup.erase(std::remove_if(backup.begin(), backup.end(),
					    [&](const AudioMixerBackup &b) { return present.count(b.key) != 0; }),
			     backup.end());
		presentBackup = false;
	}
	bool restorePending = !wanted && presentBackup;

	int overflow = 0;
	std::vector<AudioTrackMapEntry> mapNext = config.audioTrackMap;
	if (applied) {
		AssignmentInput in;
		in.sources = sources;
		in.map = config.audioTrackMap;
		in.backup = backup;
		in.locked = locked;
		in.reserved = reserved;
		in.now = static_cast<int64_t>(std::time(nullptr));
		AssignmentResult r = ComputeAssignment(in);
		apply(r.writes);
		if (r.mapChanged)
			mapNext = r.mapNext;
		backup = r.backupNext;
		overflow = static_cast<int>(r.overflowKeys.size());
	}
	if (!(mapNext == config.audioTrackMap) || !(backup == config.audioMixerBackup))
		ConfigStore::Instance().Update([&](PluginConfig &c) {
			c.audioTrackMap = mapNext;
			c.audioMixerBackup = backup;
		});

	if ((deferred || restorePending) && !deferRetryArmed_) {
		deferRetryArmed_ = true;
		RunInUiThreadAfter(kDeferredRetryMs, [this]() {
			deferRetryArmed_ = false;
			Schedule("deferred");
		});
	}

	RoutingViewOptions options;
	options.autoAssign = config.audioAutoAssign;
	options.applied = applied;
	options.deferred = deferred;
	options.overflow = overflow;
	options.reserved = reserved;
	AudioRoutingView view = BuildRoutingView(sources, options);
	const char *mode = applied ? "auto"
			   : deferred ? "deferred"
			   : restorePending ? "manual (restore after stream/recording)"
					    : "manual";
	std::string line = std::string(mode) + " — " + DescribeRouting(view);
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
	signal_handler_t *sh = obs_source_get_signal_handler(source);
	signal_handler_connect(sh, "audio_mixers", &AudioRouter::OnMixersChanged, self);
	signal_handler_connect(sh, "audio_monitoring", &AudioRouter::OnMonitoringChanged, self);

	// OBS는 새 소스를 트랙 1~6에 전부 켠 채 만든다. 계산이 도는 몇 ms 사이에도 스템에 섞이지 않게
	// 기억에 없는 새 소스는 곧바로 트랙 2~6(본방 트랙 제외)을 끈다.
	// 컬렉션을 읽는 중이면 저장값이 뒤이어 덮어쓴다(무해).
	// 자동 배정을 적용 중일 때만 — 끄거나 미룬 동안은 OBS 기본 동작 그대로 둔다.
	if (self->applied_) {
		PluginConfig config = ConfigStore::Instance().Get();
		const char *uuid = obs_source_get_uuid(source);
		std::string key = std::string("uuid:") + (uuid ? uuid : "");
		bool remembered = false;
		for (const AudioTrackMapEntry &e : config.audioTrackMap)
			remembered = remembered || e.key == key;
		uint32_t reserved = self->reserved_;
		uint32_t mixers = obs_source_get_audio_mixers(source);
		if (!remembered && (mixers & kStemMask & ~reserved) != 0)
			obs_source_set_audio_mixers(source, DesiredMixers(mixers, 0, reserved));
	}
	self->Schedule("source added");
}

void AudioRouter::OnSourceLoad(void *data, calldata_t *params)
{
	auto *self = static_cast<AudioRouter *>(data);
	auto *source = static_cast<obs_source_t *>(calldata_ptr(params, "source"));
	if (self->shutdown_ || !IsAudioInput(source))
		return;
	signal_handler_t *sh = obs_source_get_signal_handler(source);
	signal_handler_connect(sh, "audio_mixers", &AudioRouter::OnMixersChanged, self);
	signal_handler_connect(sh, "audio_monitoring", &AudioRouter::OnMonitoringChanged, self);
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

// 오디오 모니터링을 바꿨다 — 모니터 전용이 되면 자리를 비우고, 출력으로 돌아오면 자리를 받아야 한다.
// libobs는 이 시그널을 보낸 뒤 모니터 장치를 만들거나 지우고 나서야 새 값을 저장한다(obs-source.c
// obs_source_set_monitoring_type) — 그 사이에 계산이 돌면 옛 값을 읽으므로 시그널의 새 값을 들고 간다.
void AudioRouter::OnMonitoringChanged(void *data, calldata_t *params)
{
	auto *self = static_cast<AudioRouter *>(data);
	auto *source = static_cast<obs_source_t *>(calldata_ptr(params, "source"));
	const char *uuid = source ? obs_source_get_uuid(source) : nullptr;
	if (uuid) {
		std::lock_guard lock(self->monitoringMutex_);
		self->monitoringPending_[uuid] = static_cast<int>(calldata_int(params, "type"));
	}
	self->Schedule("monitoring changed");
}

// 저장이 끝난(또는 소스가 사라진) 새 값은 지운다. 아직이면 남겨 두고 다음 계산에서 다시 본다.
void AudioRouter::SettleMonitoring(const std::map<std::string, int> &seen)
{
	std::lock_guard lock(monitoringMutex_);
	for (const auto &[uuid, type] : seen) {
		auto it = monitoringPending_.find(uuid);
		if (it == monitoringPending_.end() || it->second != type)
			continue; // 그 사이 또 바뀌었다 — 새 값을 남긴다
		obs_source_t *s = obs_get_source_by_uuid(uuid.c_str());
		if (!s || obs_source_get_monitoring_type(s) == type)
			monitoringPending_.erase(it);
		obs_source_release(s);
	}
}

} // namespace pokeclip
