#pragma once

#include <array>
#include <cstdint>
#include <set>
#include <string>
#include <vector>

// 오디오 트랙 자동 배정 — OBS 헤더 없이 도는 순수 로직(단위 시험 대상). libobs 글루는 audio-router.cpp.
//
// ADR-017: OBS 트랙 1~6 = 믹서 0~5. 믹서 0(트랙 1)은 건드리지 않는다(단순 출력에서는 곧 본방 믹스).
// 믹서 1~5(트랙 2~6)가 소스별 스템이고, 자동 배정은 이 다섯 자리에 소스를 앉힌다.
//
// 규칙
//  - 후보 = 오디오를 믹스로 넘기는 소스(오디오를 안 넘기는 브라우저·모니터 전용은 제외).
//  - 플러그인이 자리를 정하는 때는 둘뿐이다: 기억이 없는 새 소스가 방송 화면(프로그램)에 처음 나올 때, 그리고
//    기억한 자리가 본방 트랙이 됐을 때. 그 밖에는 설정 파일의 기억대로 앉힌다 — 독에서 옮기거나 뺀 자리를 플러그인이
//    되돌리지 않는다(POK-266). 한 번 앉은 소스는 화면에서 빠져도 기억으로 자리를 지킨다(ADR-070 트랙 이름의 전제).
//  - 새 소스는 우선순위(마이크 → 데스크탑 → 앱 → 미디어 → 브라우저 → 기타)로 줄 세워 가장 낮은 빈 트랙에 —
//    빈 트랙 가운데서도 아무 기억도 없는 트랙을 먼저(지금 없는 소스가 돌아올 자리를 비워 둔다).
//  - 자리가 남는 동안은 트랙당 소스 하나. 모자라면 트랙을 늘리는 대신 **비슷한 소스를 한 트랙에 묶는다**:
//    아직 자리가 없는 종류가 먼저 빈 트랙을 받고, 같은 종류가 이미 앉은 트랙이 있으면 거기에 합류한다.
//    같은 종류가 없으면 우선순위가 가장 낮은 소스들이 있는 트랙에 합류한다.
//  - 지금 없는 소스의 기억은 자리를 잡지 않는다 — 지우지 않고 남겨 두지만 그 자리는 다른 소스가 쓸 수 있다.
//    돌아오면 기억대로 앉으므로 그 사이 들어온 소스와 같은 트랙을 쓸 수 있다 — 독이 보여 주고, 옮길 수 있다.
//  - 독에서 뺀 소스(slot 0)는 어느 트랙에도 없이 본방 믹스에만 섞인다. 독·폴백이 「트랙 없음」으로 알린다.
//  - 본방(OBS 방송 출력)이 쓰는 트랙(고급 출력의 방송 트랙·VOD 트랙)은 자리로 쓰지 않고 비트도 건드리지 않는다 —
//    스트리머가 그 트랙에 짜 둔 믹스가 곧 시청자가 듣는 소리다.
namespace pokeclip {

inline constexpr int kStemSlots = 5;
inline constexpr uint32_t kStemMask = 0x3E;    // 믹서 1~5 비트
inline constexpr size_t kTrackMapCap = 64;     // 설정 파일에 기억하는 배정 수 상한
inline constexpr size_t kMixerBackupCap = 128; // 원래 체크를 남기는 소스 수 상한

enum class AudioKind { Mic, Desktop, App, Media, Browser, Other };

const char *AudioKindName(AudioKind kind);
int AudioKindPriority(AudioKind kind); // 작을수록 먼저 자리를 받는다
AudioKind ClassifyAudioSourceId(const std::string &unversionedId);
AudioKind ClassifyGlobalChannel(int channel); // 설정›오디오 전역 장치: 1·2 데스크탑, 3~6 마이크

// 오디오를 낼 수 있는 입력 소스 하나. 열쇠는 전역 장치면 "ch:<채널>", 그 외엔 "uuid:<uuid>" —
// 전역 장치는 설정에서 끄고 켜면 새로 만들어져 uuid가 바뀌므로 채널 번호로 기억한다.
struct AudioSourceInfo {
	std::string key;
	std::string name;
	AudioKind kind = AudioKind::Other;
	int order = 0;            // 동률 순서 — 전역 장치는 채널 번호, 그 외 100 + 열거 순서
	uint32_t mixers = 0;      // 지금 켜진 트랙 비트
	bool audioActive = false; // 오디오를 넘긴다 (obs_source_audio_active — 기본 참, 브라우저 등이 스스로 끈다)
	bool monitorOnly = false; // 모니터 전용 — 믹스에 안 들어간다
	bool showing = false;     // 지금 방송 화면(프로그램)에 나온다 (obs_source_active) — 전역 장치는 늘 참
	// 원래 체크 백업의 열쇠. 비우면 key. 전역 장치는 장면 컬렉션마다 따로 저장되므로(트랙 체크도 다르다)
	// "ch:3@<컬렉션>"처럼 나눈다 — 배정 기억(key)은 컬렉션을 넘어 같은 트랙을 쓰려고 나누지 않는다.
	std::string backupKey;

	bool Candidate() const { return audioActive && !monitorOnly; }
	const std::string &BackupKey() const { return backupKey.empty() ? key : backupKey; }
};

struct AudioTrackMapEntry {
	std::string key;
	int slot = 0; // 1~5 = 믹서 번호 (트랙 2~6). 0 = 트랙에 없음 — 독에서 뺐다
	std::string name;
	int64_t assignedAt = 0; // 유닉스 초. 독에서 옮겼으면 그 시각
	int64_t lastSeen = 0;

	bool operator==(const AudioTrackMapEntry &) const = default;
};

// 자동 배정이 처음 트랙 2~6을 쓰기 전의 체크(스트리머가 짜 둔 것). 자동 배정을 끄거나 연결을 해제하면,
// 또 어떤 트랙이 본방 트랙이 되면 그 트랙을 이 값으로 되돌린다 — 자동 배정이 스트리머의 구성을 지우지 않게.
// 소스마다 따로 둔다 — 장면 컬렉션을 오가도 그때 없던 소스는 돌아올 때 맞춘다.
struct AudioMixerBackup {
	std::string key; // AudioSourceInfo::BackupKey()
	uint32_t mixers = 0;
	// 이 소스에서 본방 트랙으로 맞춰 둔(=스트리머 몫인) 트랙 비트. 본방 트랙이 바뀌면 이것과 비교해 오간다.
	uint32_t restored = 0;

	bool operator==(const AudioMixerBackup &) const = default;
};

struct AssignmentInput {
	std::vector<AudioSourceInfo> sources;
	std::vector<AudioTrackMapEntry> map;
	std::vector<AudioMixerBackup> backup; // 이미 남긴 원래 체크 — 처음 쓰는 소스만 더한다
	uint32_t reserved = 0; // 본방이 쓰는 믹서 비트 — 그 트랙(2~6)은 자리로 쓰지 않고 비트도 그대로 둔다
	int64_t now = 0;
};

struct MixerWrite {
	std::string key;
	uint32_t mixers = 0;
};

struct AssignmentResult {
	std::array<std::vector<std::string>, kStemSlots + 1> slotKeys{}; // [1..5] → 그 자리의 열쇠들. [0]은 안 쓴다
	std::vector<MixerWrite> writes;                                  // 지금 비트와 다른 것만
	std::vector<AudioTrackMapEntry> mapNext;
	bool mapChanged = false; // 자리·이름이 바뀌었거나 기억을 버렸다 (lastSeen만 바뀐 것은 아님)
	std::vector<std::string> overflowKeys; // 트랙 2~6이 전부 본방 트랙이라 앉을 곳이 없는 후보
	std::vector<AudioMixerBackup> backupNext;
	bool backupChanged = false;
};

// 결정적·멱등: 같은 입력이면 같은 결과, mapNext와 쓰기 결과를 다시 넣으면 writes가 비고 mapChanged가 거짓.
AssignmentResult ComputeAssignment(const AssignmentInput &in);

// 트랙 2~6 비트만 바꾼다 — 트랙 1(최종 믹스)과 쓰지 않는 상위 비트, 본방 트랙(reserved)은 그대로.
// slot 0 = 스템 없음(믹스만). slot이 본방 트랙이면 그 비트도 켜지 않는다.
uint32_t DesiredMixers(uint32_t current, int slot, uint32_t reserved = 0);

// 원래 체크(original)에서 bits(트랙 2~6 가운데 되돌릴 것)만 가져온다. 나머지 비트는 지금 그대로.
uint32_t RestoredMixers(uint32_t current, uint32_t original, uint32_t bits);

// 지금 있는 소스 가운데 원래 체크가 남아 있는 것을 bits만큼 되돌리는 쓰기. 지금 비트와 같으면 뺀다.
std::vector<MixerWrite> RestoreWrites(const std::vector<AudioSourceInfo> &sources,
				      const std::vector<AudioMixerBackup> &backup, uint32_t bits);

struct ReservedSync {
	std::vector<MixerWrite> writes;
	std::vector<AudioMixerBackup> backupNext;
	bool backupChanged = false;
};

// 본방 트랙이 바뀐 만큼 지금 있는 소스의 원래 체크를 오간다. 새로 본방 트랙이 된 비트는 원래 값으로 되돌리고
// (자동 배정이 스템으로 바꿔 놨으면 시청자가 소스 하나만 듣는다), 본방에서 빠지는 비트는 지금 값 — 그동안 스트리머가
// 짠 믹스 — 을 원래 값으로 옮겨 둔다(곧 자동 배정이 스템으로 덮는다). 없는 소스는 돌아올 때 맞춘다.
ReservedSync SyncReservedTracks(const std::vector<AudioSourceInfo> &sources,
				const std::vector<AudioMixerBackup> &backup, uint32_t reserved);

// 장면 컬렉션 이름이 바뀌었다 — 전역 장치의 원래 체크 열쇠(ch:N@<이름>)를 새 이름으로 옮긴다. 같은 채널에 새 이름으로
// 이미 있던 것은 버린다: OBS는 새 이름으로 컬렉션을 다시 불러온 뒤에 이름 변경을 알리므로, 그 사이 계산이 지금(자동
// 배정된) 체크를 원래 값처럼 남겼을 수 있다. 옮긴 것이 있으면 true.
bool RenameBackupCollection(std::vector<AudioMixerBackup> &backup, const std::string &from, const std::string &to);

struct AudioSourceView {
	std::string name;
	AudioKind kind = AudioKind::Other;
	std::string key; // 독이 넣기·빼기를 보낼 때 쓰는 열쇠 ("ch:N" · "uuid:…")

	bool operator==(const AudioSourceView &) const = default;
};

struct AudioTrackView {
	int track = 0; // 2~6 (OBS 화면 번호)
	std::vector<AudioSourceView> sources;
	bool mainStream = false; // 본방이 이 트랙을 쓴다 — 스템이 아니라 스트리머가 짠 믹스다

	bool operator==(const AudioTrackView &) const = default;
};

// 독·폴백 패널이 그리는 배정 상태. 자동이든 수동이든 실제 트랙 비트에서 만든다 — 화면이 진실을 보여준다.
struct AudioRoutingView {
	bool known = false;     // 한 번이라도 열거했다
	bool autoAssign = true; // 설정 스위치
	bool applied = false;   // 실제로 자동 배정 중 (스위치 on ∧ 페어링됨 ∧ 보류 아님)
	bool deferred = false;  // 방송·녹화 중에 켜져 끝날 때까지 미뤘다 — 지금 나가는 트랙을 바꾸지 않는다
	bool prompt = false;    // 페어링 뒤 아직 자동 배정을 켤지 묻지 않았다 — 독이 한 번 묻는다
	bool customRouting = false; // 스트리머가 트랙 2~6을 직접 짜 둔 흔적이 있다 — 켜면 덮어쓴다고 알린다
	bool locked = false;        // 방송·녹화·리플레이 버퍼·송출 중 — 독이 손 배정 전에 한 번 확인한다
	std::array<AudioTrackView, kStemSlots> tracks{};
	// 트랙 없음 — 스템(본방 트랙 제외) 어디에도 없다: 화면에 나오는 후보와, 독에서 뺀 기억(slot 0)이 있는 소스
	std::vector<AudioSourceView> mixOnly;
	std::vector<std::string> monitorOnly; // 화면에 나오지만 모니터 전용이라 믹스에 없다

	bool operator==(const AudioRoutingView &) const = default;
};

struct RoutingViewOptions {
	bool autoAssign = true;
	bool applied = false;
	bool deferred = false;
	bool prompt = false;
	bool locked = false;
	uint32_t reserved = 0; // 본방이 쓰는 믹서 비트
	// 독에서 트랙에서 뺀 기억(slot 0)이 있는 열쇠 — 다른 장면에만 있어도 「트랙 없음」에 싣는다(안 보이면 되돌릴 수 없다)
	std::vector<std::string> offTrack;
};

// 트랙 2~6(본방 트랙 제외)을 스트리머가 직접 짠 흔적이 있는지. OBS는 새 소스를 트랙 전부에 켠 채 만들므로,
// 어떤 소스든 그 비트가 전부 켜져 있지 않으면(일부만·전부 끔) 손댄 것으로 본다.
bool HasCustomStemRouting(const std::vector<AudioSourceInfo> &sources, uint32_t reserved);

// 화면에 안 나오고 스템에도 없는 소스(다른 장면에만 있는 것)는 목록에 올리지 않는다 — 지금 소리를 안 낸다. 단, 독에서 뺀
// 기억이 있는 소스(options.offTrack)는 올린다 — 그 장면을 띄우지 않고도 다시 넣을 수 있어야 한다.
AudioRoutingView BuildRoutingView(const std::vector<AudioSourceInfo> &sources, const RoutingViewOptions &options);

// 로그 한 줄: "T2 마이크/보조(mic) · T3 BGM(media), 알림(browser) · T4 – · T5 – · T6 – · no-track 0"
std::string DescribeRouting(const AudioRoutingView &view);

} // namespace pokeclip
