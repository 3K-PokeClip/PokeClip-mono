#pragma once

namespace pokeclip {

// A4 — OBS 설정 › 단축키에 「PokeClip: 지금 이 순간 표시」를 등록한다(기본 Ctrl+Shift+M). 모두 UI 스레드에서 부른다.
// OBS 설정 창은 바꾼 키를 프로필 basic.ini [Hotkeys]에 적지만 플러그인 단축키를 다시 읽어 주지는 않는다 —
// 등록·프로필 전환 때 우리가 읽는다.
void RegisterMarkHotkey();   // FINISHED_LOADING
void SaveMarkHotkeyCopy();   // PROFILE_CHANGING — 지금 키를 pokeclip.json에 사본으로
void ReloadMarkHotkey();     // PROFILE_CHANGED — 새 프로필의 키 (없으면 사본)
void UnregisterMarkHotkey(); // EXIT — 사본 저장 후 해제 (obs_module_unload는 핫키 정리 뒤라 늦다)

} // namespace pokeclip
