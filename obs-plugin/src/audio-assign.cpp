#include "audio-assign.hpp"

#include <algorithm>
#include <map>
#include <set>

namespace pokeclip {

const char *AudioKindName(AudioKind kind)
{
	switch (kind) {
	case AudioKind::Mic:
		return "mic";
	case AudioKind::Desktop:
		return "desktop";
	case AudioKind::App:
		return "app";
	case AudioKind::Media:
		return "media";
	case AudioKind::Browser:
		return "browser";
	case AudioKind::Other:
		return "other";
	}
	return "other";
}

int AudioKindPriority(AudioKind kind)
{
	// 편집 가치 순. 마이크 스템이 가장 쓸모 있고(BGM 없는 클립), 브라우저 소리(알림음)가 가장 덜하다.
	switch (kind) {
	case AudioKind::Mic:
		return 0;
	case AudioKind::Desktop:
		return 1;
	case AudioKind::App:
		return 2;
	case AudioKind::Media:
		return 3;
	case AudioKind::Browser:
		return 4;
	case AudioKind::Other:
		return 5;
	}
	return 5;
}

AudioKind ClassifyAudioSourceId(const std::string &id)
{
	struct Row {
		const char *id;
		AudioKind kind;
	};
	// 플랫폼마다 id가 다르다(macOS coreaudio·sck, Windows wasapi, Linux pulse/alsa). 모르는 id는 기타.
	static const Row rows[] = {
		{"coreaudio_input_capture", AudioKind::Mic},
		{"wasapi_input_capture", AudioKind::Mic},
		{"pulse_input_capture", AudioKind::Mic},
		{"alsa_input_capture", AudioKind::Mic},
		{"sndio_input_capture", AudioKind::Mic},
		{"oss_input_capture", AudioKind::Mic},
		{"coreaudio_output_capture", AudioKind::Desktop},
		{"wasapi_output_capture", AudioKind::Desktop},
		{"pulse_output_capture", AudioKind::Desktop},
		{"wasapi_process_output_capture", AudioKind::App},
		{"sck_audio_capture", AudioKind::App},
		{"game_capture", AudioKind::App},
		{"window_capture", AudioKind::App},
		{"screen_capture", AudioKind::App},
		{"ffmpeg_source", AudioKind::Media},
		{"vlc_source", AudioKind::Media},
		{"browser_source", AudioKind::Browser},
	};
	for (const Row &row : rows) {
		if (id == row.id)
			return row.kind;
	}
	return AudioKind::Other;
}

AudioKind ClassifyGlobalChannel(int channel)
{
	if (channel == 1 || channel == 2)
		return AudioKind::Desktop;
	if (channel >= 3 && channel <= 6)
		return AudioKind::Mic;
	return AudioKind::Other;
}

uint32_t DesiredMixers(uint32_t current, int slot)
{
	uint32_t next = current & ~kStemMask;
	if (slot >= 1 && slot <= kStemSlots)
		next |= 1u << slot;
	return next;
}

AssignmentResult ComputeAssignment(const AssignmentInput &in)
{
	AssignmentResult r;

	// 열쇠가 겹치는 소스는 첫 것만 본다(정상이면 없다).
	std::map<std::string, size_t> sourceIndex;
	for (size_t i = 0; i < in.sources.size(); i++)
		sourceIndex.emplace(in.sources[i].key, i);
	auto isFirst = [&](size_t i) { return sourceIndex.at(in.sources[i].key) == i; };
	auto candidateOf = [&](const std::string &key) -> const AudioSourceInfo * {
		auto it = sourceIndex.find(key);
		if (it == sourceIndex.end())
			return nullptr;
		const AudioSourceInfo &s = in.sources[it->second];
		return s.Candidate() ? &s : nullptr;
	};

	// 1. 기억 정리 — 빈 열쇠·범위 밖 자리·겹친 열쇠(뒤엣것)는 버린다. 손으로 고친 설정 파일 방어.
	std::vector<AudioTrackMapEntry> map;
	std::set<std::string> mapKeys;
	for (const AudioTrackMapEntry &e : in.map) {
		if (e.key.empty() || e.slot < 1 || e.slot > kStemSlots || !mapKeys.insert(e.key).second) {
			r.mapChanged = true;
			continue;
		}
		map.push_back(e);
	}

	// 2. 지금 있는 소스의 기억은 마지막으로 본 시각·이름을 갱신한다(후보가 아니어도 — 상한 정리에서 지키려고).
	for (AudioTrackMapEntry &e : map) {
		auto it = sourceIndex.find(e.key);
		if (it == sourceIndex.end())
			continue;
		e.lastSeen = in.now;
		const std::string &name = in.sources[it->second].name;
		if (e.name != name) {
			e.name = name;
			r.mapChanged = true;
		}
	}

	// 3. 기억한 자리 복원. 지금 소리를 내는 후보만 자리를 잡는다 — 없는 소스의 기억은 자리를 비워 둔다.
	std::array<int, kStemSlots + 1> holder{};
	holder.fill(-1);
	std::vector<std::string> losers;
	auto beats = [&](size_t a, size_t b, int slot) {
		if (in.locked) {
			// 방송·녹화 중에는 지금 그 트랙에서 나가고 있는 쪽을 옮기지 않는다.
			uint32_t bit = 1u << slot;
			bool aOn = (candidateOf(map[a].key)->mixers & bit) != 0;
			bool bOn = (candidateOf(map[b].key)->mixers & bit) != 0;
			if (aOn != bOn)
				return aOn;
		}
		if (map[a].assignedAt != map[b].assignedAt)
			return map[a].assignedAt < map[b].assignedAt; // 먼저 앉은 쪽
		return map[a].key < map[b].key;
	};
	for (size_t i = 0; i < map.size(); i++) {
		if (!candidateOf(map[i].key))
			continue;
		int slot = map[i].slot;
		if (holder[slot] < 0) {
			holder[slot] = static_cast<int>(i);
		} else if (beats(i, static_cast<size_t>(holder[slot]), slot)) {
			losers.push_back(map[static_cast<size_t>(holder[slot])].key);
			holder[slot] = static_cast<int>(i);
		} else {
			losers.push_back(map[i].key);
		}
	}

	std::map<std::string, int> slotOf;
	std::array<bool, kStemSlots + 1> taken{};
	for (int k = 1; k <= kStemSlots; k++) {
		if (holder[k] >= 0) {
			slotOf[map[static_cast<size_t>(holder[k])].key] = k;
			taken[k] = true;
		}
	}

	// 4. 자리 없는 후보(기억이 없거나 겹쳐서 밀렸다)를 우선순위로 줄 세워 가장 낮은 빈 자리에 앉힌다.
	std::vector<const AudioSourceInfo *> queue;
	for (const std::string &key : losers)
		queue.push_back(candidateOf(key));
	for (size_t i = 0; i < in.sources.size(); i++) {
		const AudioSourceInfo &s = in.sources[i];
		if (isFirst(i) && s.Candidate() && !mapKeys.count(s.key))
			queue.push_back(&s);
	}
	std::stable_sort(queue.begin(), queue.end(), [](const AudioSourceInfo *a, const AudioSourceInfo *b) {
		int pa = AudioKindPriority(a->kind), pb = AudioKindPriority(b->kind);
		if (pa != pb)
			return pa < pb;
		if (a->order != b->order)
			return a->order < b->order;
		if (a->name != b->name)
			return a->name < b->name;
		return a->key < b->key;
	});

	for (const AudioSourceInfo *s : queue) {
		int free = 0;
		for (int k = 1; k <= kStemSlots; k++) {
			if (!taken[k]) {
				free = k;
				break;
			}
		}
		auto entry = std::find_if(map.begin(), map.end(),
					  [&](const AudioTrackMapEntry &e) { return e.key == s->key; });
		if (free == 0) {
			r.overflowKeys.push_back(s->key);
			if (entry != map.end()) {
				map.erase(entry); // 밀렸는데 갈 자리도 없다 — 옛 기억을 버려 다음에 새 소스처럼 다룬다
				r.mapChanged = true;
			}
			continue;
		}
		taken[free] = true;
		slotOf[s->key] = free;
		if (entry != map.end()) {
			entry->slot = free;
			entry->assignedAt = in.now;
			entry->lastSeen = in.now;
		} else {
			map.push_back({s->key, free, s->name, in.now, in.now});
		}
		r.mapChanged = true;
	}
	for (const auto &[key, slot] : slotOf)
		r.slotKey[static_cast<size_t>(slot)] = key;

	// 5. 쓸 비트. 자리 없는 소스·후보가 아닌 소스도 트랙 2~6은 비운다 — 자동 배정이 스템을 맡는다.
	//    그래야 소리를 늦게 내기 시작한 소스(브라우저 소리 켜기 등)가 모든 스템에 섞여 들지 않는다.
	for (size_t i = 0; i < in.sources.size(); i++) {
		if (!isFirst(i))
			continue;
		const AudioSourceInfo &s = in.sources[i];
		auto it = slotOf.find(s.key);
		int slot = it != slotOf.end() ? it->second : 0;
		uint32_t desired = DesiredMixers(s.mixers, slot);
		if (desired != s.mixers)
			r.writes.push_back({s.key, desired});
	}

	// 6. 상한 — 지금 없는 기억 중 오래 안 보인 것부터 버린다. 지금 있는 소스의 기억은 버리지 않는다.
	if (map.size() > kTrackMapCap) {
		std::vector<size_t> absent;
		for (size_t i = 0; i < map.size(); i++) {
			if (!sourceIndex.count(map[i].key))
				absent.push_back(i);
		}
		std::sort(absent.begin(), absent.end(), [&](size_t a, size_t b) {
			if (map[a].lastSeen != map[b].lastSeen)
				return map[a].lastSeen < map[b].lastSeen;
			return map[a].key < map[b].key;
		});
		size_t excess = std::min(map.size() - kTrackMapCap, absent.size());
		std::set<size_t> drop(absent.begin(), absent.begin() + static_cast<std::ptrdiff_t>(excess));
		std::vector<AudioTrackMapEntry> kept;
		kept.reserve(map.size() - drop.size());
		for (size_t i = 0; i < map.size(); i++) {
			if (!drop.count(i))
				kept.push_back(map[i]);
		}
		if (!drop.empty())
			r.mapChanged = true;
		map.swap(kept);
	}

	r.mapNext = std::move(map);
	return r;
}

AudioRoutingView BuildRoutingView(const std::vector<AudioSourceInfo> &sources, bool autoAssign, bool applied,
				  int overflow)
{
	AudioRoutingView v;
	v.known = true;
	v.autoAssign = autoAssign;
	v.applied = applied;
	v.overflow = overflow;
	for (int i = 0; i < kStemSlots; i++)
		v.tracks[static_cast<size_t>(i)].track = i + 2;

	for (const AudioSourceInfo &s : sources) {
		if (!s.audioActive)
			continue;
		if (s.monitorOnly) {
			v.monitorOnly.push_back(s.name);
			continue;
		}
		bool onStem = false;
		for (int m = 1; m <= kStemSlots; m++) {
			if (s.mixers & (1u << m)) {
				v.tracks[static_cast<size_t>(m - 1)].sources.push_back({s.name, s.kind});
				onStem = true;
			}
		}
		if (!onStem)
			v.mixOnly.push_back(s.name);
	}
	return v;
}

std::string DescribeRouting(const AudioRoutingView &v)
{
	std::string out;
	for (const AudioTrackView &t : v.tracks) {
		if (!out.empty())
			out += " · ";
		out += "T" + std::to_string(t.track) + " ";
		if (t.sources.empty()) {
			out += "–";
			continue;
		}
		for (size_t j = 0; j < t.sources.size(); j++) {
			if (j)
				out += ", ";
			out += t.sources[j].name + "(" + AudioKindName(t.sources[j].kind) + ")";
		}
	}
	out += " · mix-only " + std::to_string(v.mixOnly.size());
	if (!v.monitorOnly.empty())
		out += " · monitor-only " + std::to_string(v.monitorOnly.size());
	return out;
}

} // namespace pokeclip
