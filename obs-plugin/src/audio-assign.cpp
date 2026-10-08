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

uint32_t DesiredMixers(uint32_t current, int slot, uint32_t reserved)
{
	uint32_t owned = kStemMask & ~reserved; // 자동 배정이 맡는 비트 — 본방 트랙은 스트리머 몫
	uint32_t next = current & ~owned;
	if (slot >= 1 && slot <= kStemSlots && (owned & (1u << slot)) != 0)
		next |= 1u << slot;
	return next;
}

uint32_t RestoredMixers(uint32_t current, uint32_t original, uint32_t bits)
{
	bits &= kStemMask;
	return (current & ~bits) | (original & bits);
}

std::vector<MixerWrite> RestoreWrites(const std::vector<AudioSourceInfo> &sources,
				      const std::vector<AudioMixerBackup> &backup, uint32_t bits)
{
	std::map<std::string, uint32_t> original;
	for (const AudioMixerBackup &b : backup)
		original.emplace(b.key, b.mixers);
	std::vector<MixerWrite> writes;
	std::set<std::string> seen;
	for (const AudioSourceInfo &s : sources) {
		auto it = original.find(s.BackupKey());
		if (it == original.end() || !seen.insert(s.BackupKey()).second)
			continue;
		uint32_t restored = RestoredMixers(s.mixers, it->second, bits);
		if (restored != s.mixers)
			writes.push_back({s.key, restored});
	}
	return writes;
}

ReservedSync SyncReservedTracks(const std::vector<AudioSourceInfo> &sources,
				const std::vector<AudioMixerBackup> &backup, uint32_t reserved)
{
	ReservedSync r;
	r.backupNext = backup;
	reserved &= kStemMask;
	std::map<std::string, size_t> index;
	for (size_t i = 0; i < r.backupNext.size(); i++)
		index.emplace(r.backupNext[i].key, i);
	std::set<std::string> seen;
	for (const AudioSourceInfo &s : sources) {
		auto it = index.find(s.BackupKey());
		if (it == index.end() || !seen.insert(s.BackupKey()).second)
			continue;
		AudioMixerBackup &b = r.backupNext[it->second];
		uint32_t entering = reserved & ~b.restored;
		uint32_t leaving = b.restored & ~reserved & kStemMask;
		AudioMixerBackup next = b;
		next.mixers = RestoredMixers(b.mixers, s.mixers, leaving);
		next.restored = reserved;
		uint32_t restored = RestoredMixers(s.mixers, b.mixers, entering);
		if (restored != s.mixers)
			r.writes.push_back({s.key, restored});
		if (!(next == b)) {
			b = next;
			r.backupChanged = true;
		}
	}
	return r;
}

bool RenameBackupCollection(std::vector<AudioMixerBackup> &backup, const std::string &from, const std::string &to)
{
	// 열쇠는 "ch:<번호>@<컬렉션>" — 번호에는 @가 없으므로 첫 @ 뒤가 컬렉션 이름이다. uuid 소스는 컬렉션을 안 단다.
	auto split = [](const std::string &key, std::string &channel, std::string &collection) {
		size_t at = key.find('@');
		if (key.rfind("ch:", 0) != 0 || at == std::string::npos)
			return false;
		channel = key.substr(0, at);
		collection = key.substr(at + 1);
		return true;
	};
	if (from == to)
		return false;

	std::string channel;
	std::string collection;
	std::set<std::string> moving;
	for (const AudioMixerBackup &b : backup) {
		if (split(b.key, channel, collection) && collection == from)
			moving.insert(channel);
	}
	if (moving.empty())
		return false;

	std::vector<AudioMixerBackup> next;
	for (AudioMixerBackup b : backup) {
		if (split(b.key, channel, collection) && moving.count(channel)) {
			if (collection == to)
				continue; // 이름 변경 중 다시 불러오며 생긴 것 — 옮겨 오는 원래 값이 맞다
			if (collection == from)
				b.key = channel + "@" + to;
		}
		next.push_back(std::move(b));
	}
	backup = std::move(next);
	return true;
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
	//    slot 0은 「트랙에 없음」(독에서 뺐다)이라 유효하다.
	std::vector<AudioTrackMapEntry> map;
	std::set<std::string> mapKeys;
	for (const AudioTrackMapEntry &e : in.map) {
		if (e.key.empty() || e.slot < 0 || e.slot > kStemSlots || !mapKeys.insert(e.key).second) {
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

	auto isReserved = [&](int slot) { return (in.reserved & (1u << slot)) != 0; };

	// 3. 기억한 자리대로 앉는다. 지금 소리를 내는 후보만 자리를 잡는다 — 없는 소스의 기억은 자리를 비워 둔다.
	//    화면에 안 나와도 기억이 있으면 자리를 지킨다. 같은 자리를 기억하는 소스는 함께 앉는다(묶음) — 플러그인이
	//    가르지 않는다. 트랙에서 뺀 소스(slot 0)는 자리도 줄도 없다. 기억한 자리가 본방 트랙이 됐으면 새 자리를 찾는다.
	std::map<std::string, int> slotOf;
	std::array<std::vector<const AudioSourceInfo *>, kStemSlots + 1> occupants{}; // 자리별 점유자 — 합류 근거
	std::array<bool, kStemSlots + 1> claimed{}; // 어떤 기억이든(지금 없는 소스 것도) 적어 둔 자리
	auto seat = [&](const AudioSourceInfo *s, int slot) {
		slotOf[s->key] = slot;
		occupants[static_cast<size_t>(slot)].push_back(s);
	};
	std::vector<std::string> losers;
	for (const AudioTrackMapEntry &e : map) {
		if (e.slot == 0)
			continue;
		claimed[static_cast<size_t>(e.slot)] = true;
		const AudioSourceInfo *s = candidateOf(e.key);
		if (!s)
			continue;
		if (isReserved(e.slot))
			losers.push_back(e.key);
		else
			seat(s, e.slot);
	}

	// 4. 자리 없는 후보(기억이 없거나 본방 트랙에 밀렸다)를 우선순위로 줄 세운다. 기억 없는 소스는 방송 화면에
	//    나올 때 처음 자리를 받는다 — 다른 장면에만 있는 소스는 기다린다.
	std::vector<const AudioSourceInfo *> queue;
	for (const std::string &key : losers)
		queue.push_back(candidateOf(key));
	for (size_t i = 0; i < in.sources.size(); i++) {
		const AudioSourceInfo &s = in.sources[i];
		if (isFirst(i) && s.Candidate() && s.showing && !mapKeys.count(s.key))
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

	//    빈 자리가 남는 동안은 자리를 하나씩 — 단, 아직 자리가 없는 종류가 먼저 받는다. 같은 종류가 이미 앉은
	//    소스는 빈 자리가 「자리 없는 종류 수」보다 많이 남을 때만 제 자리를 받고, 아니면 그 종류의 트랙에 묶인다.
	//    같은 종류가 없고 빈 자리도 없으면 우선순위가 가장 낮은 소스들이 있는 트랙에 묶인다.
	auto kindSlot = [&](AudioKind kind) {
		for (int k = 1; k <= kStemSlots; k++) {
			for (const AudioSourceInfo *o : occupants[static_cast<size_t>(k)]) {
				if (o->kind == kind)
					return k;
			}
		}
		return 0;
	};
	auto freeSlots = [&]() {
		int n = 0;
		for (int k = 1; k <= kStemSlots; k++) {
			if (!isReserved(k) && occupants[static_cast<size_t>(k)].empty())
				n++;
		}
		return n;
	};
	auto lowestFree = [&]() { // 빈 트랙 가운데 아무 기억도 없는 트랙 먼저 — 지금 없는 소스가 돌아올 자리를 비워 둔다
		for (int k = 1; k <= kStemSlots; k++) {
			if (!isReserved(k) && occupants[static_cast<size_t>(k)].empty() && !claimed[static_cast<size_t>(k)])
				return k;
		}
		for (int k = 1; k <= kStemSlots; k++) {
			if (!isReserved(k) && occupants[static_cast<size_t>(k)].empty())
				return k;
		}
		return 0;
	};
	auto lowestPriorityTrack = [&]() { // 점유자 중 가장 가치 있는 것이 가장 덜 가치 있는 트랙. 동률이면 높은 번호
		int best = 0, bestWorth = -1;
		for (int k = 1; k <= kStemSlots; k++) {
			const auto &occ = occupants[static_cast<size_t>(k)];
			if (isReserved(k) || occ.empty())
				continue;
			int worth = 99;
			for (const AudioSourceInfo *o : occ)
				worth = std::min(worth, AudioKindPriority(o->kind));
			if (worth >= bestWorth) {
				bestWorth = worth;
				best = k;
			}
		}
		return best;
	};
	std::set<AudioKind> pendingKinds; // 줄에 있는데 아직 어느 트랙에도 같은 종류가 없는 종류
	for (const AudioSourceInfo *s : queue) {
		if (kindSlot(s->kind) == 0)
			pendingKinds.insert(s->kind);
	}

	for (const AudioSourceInfo *s : queue) {
		int same = kindSlot(s->kind);
		int slot = 0;
		if (same == 0) {
			slot = lowestFree();
			if (slot == 0)
				slot = lowestPriorityTrack();
			pendingKinds.erase(s->kind);
		} else if (freeSlots() > static_cast<int>(pendingKinds.size())) {
			slot = lowestFree();
		} else {
			slot = same;
		}
		auto entry = std::find_if(map.begin(), map.end(),
					  [&](const AudioTrackMapEntry &e) { return e.key == s->key; });
		if (slot == 0) {
			r.overflowKeys.push_back(s->key);
			if (entry != map.end()) {
				map.erase(entry); // 갈 자리가 없다 — 옛 기억을 버려 다음에 새 소스처럼 다룬다
				r.mapChanged = true;
			}
			continue;
		}
		seat(s, slot);
		claimed[static_cast<size_t>(slot)] = true;
		if (entry != map.end()) {
			if (entry->slot != slot) {
				entry->slot = slot;
				entry->assignedAt = in.now;
				r.mapChanged = true;
			}
			entry->lastSeen = in.now;
		} else {
			map.push_back({s->key, slot, s->name, in.now, in.now});
			r.mapChanged = true;
		}
	}
	for (const auto &[key, slot] : slotOf)
		r.slotKeys[static_cast<size_t>(slot)].push_back(key);

	// 5. 쓸 비트. 자리 없는 소스·후보가 아닌 소스도 트랙 2~6은 비운다 — 자동 배정이 스템을 맡는다.
	//    그래야 소리를 늦게 내기 시작한 소스(브라우저 소리 켜기 등)가 모든 스템에 섞여 들지 않는다.
	//    본방 트랙은 그대로 둔다.
	for (size_t i = 0; i < in.sources.size(); i++) {
		if (!isFirst(i))
			continue;
		const AudioSourceInfo &s = in.sources[i];
		auto it = slotOf.find(s.key);
		int slot = it != slotOf.end() ? it->second : 0;
		uint32_t desired = DesiredMixers(s.mixers, slot, in.reserved);
		if (desired != s.mixers)
			r.writes.push_back({s.key, desired});
	}

	// 5-1. 처음 쓰는 소스는 쓰기 전 체크를 남긴다 — 쓴 적 없는 소스의 지금 비트가 곧 스트리머가 짠 원래 값이다.
	//      본방 트랙 비트는 쓰지 않으므로 그 자리에서 스트리머 몫으로 친다(restored).
	r.backupNext = in.backup;
	std::set<std::string> backedUp;
	for (const AudioMixerBackup &b : r.backupNext)
		backedUp.insert(b.key);
	std::set<std::string> presentBackupKeys;
	for (const AudioSourceInfo &s : in.sources)
		presentBackupKeys.insert(s.BackupKey());
	for (const MixerWrite &w : r.writes) {
		const AudioSourceInfo &s = in.sources[sourceIndex.at(w.key)];
		if (backedUp.insert(s.BackupKey()).second) {
			r.backupNext.push_back({s.BackupKey(), s.mixers, in.reserved & kStemMask});
			r.backupChanged = true;
		}
	}
	// 상한 — 지금 없는 소스의 것부터(먼저 남긴 순) 버린다.
	for (size_t i = 0; r.backupNext.size() > kMixerBackupCap && i < r.backupNext.size();) {
		if (!presentBackupKeys.count(r.backupNext[i].key)) {
			r.backupNext.erase(r.backupNext.begin() + static_cast<std::ptrdiff_t>(i));
			r.backupChanged = true;
		} else {
			i++;
		}
	}

	// 6. 상한 — 지금 없는 소스의 기억 중 오래 안 보인 것부터 버린다. 지금 있는 소스의 기억은 버리지 않는다.
	//    (독에서 옮긴 자리도 소스가 오래 없으면 밀려날 수 있다 — 돌아오면 새 소스처럼 다시 앉는다.)
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

bool HasCustomStemRouting(const std::vector<AudioSourceInfo> &sources, uint32_t reserved)
{
	uint32_t owned = kStemMask & ~reserved;
	return std::any_of(sources.begin(), sources.end(),
			   [&](const AudioSourceInfo &s) { return (s.mixers & owned) != owned; });
}

AudioRoutingView BuildRoutingView(const std::vector<AudioSourceInfo> &sources, const RoutingViewOptions &options)
{
	AudioRoutingView v;
	v.known = true;
	v.autoAssign = options.autoAssign;
	v.applied = options.applied;
	v.deferred = options.deferred;
	v.prompt = options.prompt;
	v.customRouting = !options.applied && HasCustomStemRouting(sources, options.reserved);
	v.locked = options.locked;
	for (int i = 0; i < kStemSlots; i++) {
		AudioTrackView &t = v.tracks[static_cast<size_t>(i)];
		t.track = i + 2;
		t.mainStream = (options.reserved & (1u << (i + 1))) != 0;
	}

	for (const AudioSourceInfo &s : sources) {
		if (!s.audioActive)
			continue;
		if (s.monitorOnly) {
			if (s.showing)
				v.monitorOnly.push_back(s.name);
			continue;
		}
		AudioSourceView view{s.name, s.kind, s.key};
		bool onStem = false; // 본방 트랙은 스템이 아니다 — 거기에만 있으면 스템에는 없는 것
		for (int m = 1; m <= kStemSlots; m++) {
			uint32_t bit = 1u << m;
			if (s.mixers & bit) {
				v.tracks[static_cast<size_t>(m - 1)].sources.push_back(view);
				onStem = onStem || (options.reserved & bit) == 0;
			}
		}
		bool offTrack = std::find(options.offTrack.begin(), options.offTrack.end(), s.key) != options.offTrack.end();
		if (!onStem && (s.showing || offTrack))
			v.mixOnly.push_back(view);
	}
	return v;
}

std::string DescribeRouting(const AudioRoutingView &v)
{
	std::string out;
	for (const AudioTrackView &t : v.tracks) {
		if (!out.empty())
			out += " · ";
		out += "T" + std::to_string(t.track) + (t.mainStream ? " main-stream " : " ");
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
	out += " · no-track " + std::to_string(v.mixOnly.size());
	if (!v.monitorOnly.empty())
		out += " · monitor-only " + std::to_string(v.monitorOnly.size());
	if (v.deferred)
		out += " · deferred until stream/recording stops";
	return out;
}

} // namespace pokeclip
