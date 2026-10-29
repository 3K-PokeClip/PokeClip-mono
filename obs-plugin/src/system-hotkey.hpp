#pragma once

#include <vector>

namespace pokeclip {

// POK-496 — macOS에서 「순간 표시」 조합을 시스템 전역 단축키(Carbon RegisterEventHotKey)로도 건다.
// OBS의 macOS 단축키는 입력 모니터링 권한이 있는 이벤트 탭이라 권한이 없거나 보안 입력(비밀번호 칸·터미널 Secure Keyboard
// Entry)이 켜지면 OBS가 뒤에 있을 때 키가 오지 않는다. 전역 단축키는 권한이 필요 없고 보안 입력과 무관하다.
// 다른 앱으로 그 조합이 가지 않는 독점 키라 우리 송출이 전송 중·재연결 중일 때만 건다. OBS 경로와 겹친 누름은
// MarkSender의 2초 연타 거르기가 한 번으로 친다. 모두 UI 스레드에서 부른다. macOS 밖에서는 아무것도 하지 않는다.
struct SystemHotkeyCombo {
	int virtualKey = -1; // obs_key_to_virtual_key — macOS 가상 키 코드
	bool control = false;
	bool alt = false;
	bool shift = false;
	bool command = false;

	bool operator==(const SystemHotkeyCombo &) const = default;
};

#ifdef __APPLE__
void SetSystemMarkHotkeys(const std::vector<SystemHotkeyCombo> &combos);
void SetSystemMarkHotkeysActive(bool active);
void ShutdownSystemMarkHotkeys();
#else
inline void SetSystemMarkHotkeys(const std::vector<SystemHotkeyCombo> &) {}
inline void SetSystemMarkHotkeysActive(bool) {}
inline void ShutdownSystemMarkHotkeys() {}
#endif

} // namespace pokeclip
