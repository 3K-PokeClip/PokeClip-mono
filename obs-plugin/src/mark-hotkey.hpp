#pragma once

#include "mark-policy.hpp"

#include <string>

namespace pokeclip {

// A4 — OBS 설정 › 단축키에 「PokeClip: 순간 표시」를 등록한다(기본 Ctrl+Shift+M). 모두 UI 스레드에서 부른다.
// OBS 설정 창은 바꾼 키를 프로필 basic.ini [Hotkeys]에 적지만 플러그인 단축키를 다시 읽어 주지는 않는다 —
// 등록·프로필 전환 때 우리가 읽는다.
void RegisterMarkHotkey();   // FINISHED_LOADING
void SaveMarkHotkeyCopy();   // PROFILE_CHANGING — 지금 키를 pokeclip.json에 사본으로
void ReloadMarkHotkey();     // PROFILE_CHANGED — 새 프로필의 키 (없으면 사본)
void UnregisterMarkHotkey(); // EXIT — 사본 저장 후 해제 (obs_module_unload는 핫키 정리 뒤라 늦다)

// POK-494 — 독·폴백에서 새 키로 바꾼다(UI 스레드). OBS 설정 창이 쓰는 자리(프로필 [Hotkeys])와 사본에 같이 적어
// 설정 창·다음 실행이 같은 키를 쓴다. obsKeyName은 OBS 키 이름("OBS_KEY_M"), mods는 조합 키만 본다.
struct MarkHotkeyChange {
	bool ok = false;
	std::string reason;   // hotkey_bad_key · hotkey_needs_modifier · hotkey_unavailable
	std::string label;    // 바꾼 뒤 독 표기
	std::string conflict; // 같은 조합을 쓰는 다른 OBS 단축키 이름(그쪽도 같이 눌린다) — 없으면 빈 문자열
	bool saved = true;    // 프로필에 적었다 — false면 지금은 바뀌었지만 다음 실행 때 예전 키로 돌아갈 수 있다
};
MarkHotkeyChange SetMarkHotkey(const std::string &obsKeyName, const HotkeyLabelParts &mods);

// 독이 새 키를 받는 동안 지금 키로 마크가 나가지 않게 막는다(아무 스레드). 끄지 않아도 30초 뒤 풀린다.
// 꺼도 1초 더 막는다 — 새 키로 바꾼 직후 아직 누르고 있는 그 키가 마크로 잡히지 않게(kHotkeyCaptureReleaseMs).
// 막는 동안은 다른 OBS 단축키 입력도 멈춘다(UI 스레드에서 건다).
void SetMarkHotkeyCapture(bool on);
bool MarkHotkeyCapturing();

} // namespace pokeclip
