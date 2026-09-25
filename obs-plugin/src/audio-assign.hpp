#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <vector>

// 오디오 트랙 자동 배정 — OBS 헤더 없이 도는 순수 로직(단위 시험 대상). libobs 글루는 audio-router.cpp.
//
// ADR-017: OBS 트랙 1~6 = 믹서 0~5. 믹서 0(트랙 1)은 최종 믹스로 본방과 같은 것이라 건드리지 않는다.
// 믹서 1~5(트랙 2~6)가 소스별 스템이고, 자동 배정은 이 다섯 자리에 소스를 하나씩 앉힌다.
//
// 규칙
//  - 후보 = 실제로 소리를 내는(OBS 오디오 믹서에 보이는) 소스. 모니터 전용은 믹스에 안 들어가 제외.
//  - 한 번 앉힌 자리는 설정 파일에 기억해 방송이 바뀌어도 그대로다(ADR-070 트랙 이름의 전제).
//  - 새 후보는 우선순위(마이크 → 데스크탑 → 앱 → 미디어 → 브라우저 → 기타)로 줄 세워 가장 낮은 빈 자리에.
//  - 지금 없는 소스의 기억은 자리를 잡지 않는다 — 지우지 않고 남겨 두지만 그 자리는 다른 소스가 쓸 수 있다.
//  - 두 소스가 같은 자리를 기억하면 먼저 앉은 쪽이 이긴다. 방송·녹화 중에는 지금 그 트랙에서 나가는 쪽이 이긴다.
//  - 자리가 모자라면 믹스 전용(트랙 2~6 어디에도 없음).
namespace pokeclip {

inline constexpr int kStemSlots = 5;
inline constexpr uint32_t kStemMask = 0x3E; // 믹서 1~5 비트
inline constexpr size_t kTrackMapCap = 64;  // 설정 파일에 기억하는 배정 수 상한

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
	bool audioActive = false; // OBS 오디오 믹서에 보이는 소스 = 실제로 소리를 낸다
	bool monitorOnly = false; // 모니터 전용 — 믹스에 안 들어간다

	bool Candidate() const { return audioActive && !monitorOnly; }
};

struct AudioTrackMapEntry {
	std::string key;
	int slot = 0; // 1~5 = 믹서 번호 (트랙 2~6)
	std::string name;
	int64_t assignedAt = 0; // 유닉스 초
	int64_t lastSeen = 0;

	bool operator==(const AudioTrackMapEntry &) const = default;
};

struct AssignmentInput {
	std::vector<AudioSourceInfo> sources;
	std::vector<AudioTrackMapEntry> map;
	bool locked = false; // 방송·녹화 중 — 겹칠 때 지금 그 트랙에서 나가는 쪽을 남긴다
	int64_t now = 0;
};

struct MixerWrite {
	std::string key;
	uint32_t mixers = 0;
};

struct AssignmentResult {
	std::array<std::string, kStemSlots + 1> slotKey{}; // [1..5] → 열쇠. [0]은 안 쓴다
	std::vector<MixerWrite> writes;                    // 지금 비트와 다른 것만
	std::vector<AudioTrackMapEntry> mapNext;
	bool mapChanged = false;               // 자리·이름이 바뀌었거나 기억을 버렸다 (lastSeen만 바뀐 것은 아님)
	std::vector<std::string> overflowKeys; // 자리가 모자라 믹스에만 들어가는 후보
};

// 결정적·멱등: 같은 입력이면 같은 결과, mapNext와 쓰기 결과를 다시 넣으면 writes가 비고 mapChanged가 거짓.
AssignmentResult ComputeAssignment(const AssignmentInput &in);

// 트랙 2~6 비트만 바꾼다 — 트랙 1(최종 믹스)과 쓰지 않는 상위 비트는 그대로. slot 0 = 스템 없음(믹스만).
uint32_t DesiredMixers(uint32_t current, int slot);

struct AudioSourceView {
	std::string name;
	AudioKind kind = AudioKind::Other;

	bool operator==(const AudioSourceView &) const = default;
};

struct AudioTrackView {
	int track = 0; // 2~6 (OBS 화면 번호)
	std::vector<AudioSourceView> sources;

	bool operator==(const AudioTrackView &) const = default;
};

// 독·폴백 패널이 그리는 배정 상태. 자동이든 수동이든 실제 트랙 비트에서 만든다 — 화면이 진실을 보여준다.
struct AudioRoutingView {
	bool known = false;     // 한 번이라도 열거했다
	bool autoAssign = true; // 설정 스위치
	bool applied = false;   // 실제로 자동 배정 중 (스위치 on ∧ 페어링됨)
	std::array<AudioTrackView, kStemSlots> tracks{};
	std::vector<std::string> mixOnly;     // 후보인데 트랙 2~6 어디에도 없다
	std::vector<std::string> monitorOnly; // 소리는 나지만 모니터 전용이라 믹스에 없다
	int overflow = 0;                     // 자동 배정에서 자리가 모자란 수

	bool operator==(const AudioRoutingView &) const = default;
};

AudioRoutingView BuildRoutingView(const std::vector<AudioSourceInfo> &sources, bool autoAssign, bool applied,
				  int overflow);

// 로그 한 줄: "T2 마이크/보조(mic) · T3 데스크탑 오디오(desktop) · T4 – · T5 – · T6 – · mix-only 0"
std::string DescribeRouting(const AudioRoutingView &view);

} // namespace pokeclip
