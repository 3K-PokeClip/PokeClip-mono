#include "system-hotkey.hpp"

#include "mark-hotkey.hpp"
#include "mark-sender.hpp"

#include <obs-module.h>
#include <plugin-support.h>

#include <Carbon/Carbon.h>

namespace pokeclip {

namespace {

constexpr OSType kSignature = 'PkCl';

std::vector<SystemHotkeyCombo> g_combos;
std::vector<EventHotKeyRef> g_refs;
bool g_active = false;
EventHandlerRef g_handler = nullptr;

// 메인 스레드의 이벤트 루프에서 온다. 누른 순간 한 번(떼는 것은 받지 않는다).
OSStatus OnHotKey(EventHandlerCallRef, EventRef event, void *)
{
	EventHotKeyID id{};
	if (GetEventParameter(event, kEventParamDirectObject, typeEventHotKeyID, nullptr, sizeof(id), nullptr, &id) !=
		    noErr ||
	    id.signature != kSignature)
		return eventNotHandledErr;
	if (!MarkHotkeyCapturing())
		MarkSender::Instance().Mark(MarkVia::Hotkey);
	return noErr;
}

void UnregisterAll()
{
	for (EventHotKeyRef ref : g_refs)
		UnregisterEventHotKey(ref);
	g_refs.clear();
}

void RegisterAll()
{
	if (!g_handler) {
		EventTypeSpec spec{kEventClassKeyboard, kEventHotKeyPressed};
		InstallApplicationEventHandler(NewEventHandlerUPP(OnHotKey), 1, &spec, nullptr, &g_handler);
	}
	UInt32 n = 0;
	for (const SystemHotkeyCombo &c : g_combos) {
		if (c.virtualKey < 0 || c.virtualKey > 0x7F)
			continue;
		UInt32 mods = (c.control ? controlKey : 0) | (c.alt ? optionKey : 0) | (c.shift ? shiftKey : 0) |
			      (c.command ? cmdKey : 0);
		EventHotKeyRef ref = nullptr;
		OSStatus st = RegisterEventHotKey(static_cast<UInt32>(c.virtualKey), mods, EventHotKeyID{kSignature, ++n},
						  GetApplicationEventTarget(), 0, &ref);
		if (st == noErr)
			g_refs.push_back(ref);
		else // 다른 앱이 이미 같은 조합을 전역으로 잡았다 — OBS 경로(이벤트 탭)만 남는다
			obs_log(LOG_WARNING, "system mark hotkey: register failed (%d)", static_cast<int>(st));
	}
}

void Apply()
{
	UnregisterAll();
	if (g_active)
		RegisterAll();
}

} // namespace

void SetSystemMarkHotkeys(const std::vector<SystemHotkeyCombo> &combos)
{
	if (combos == g_combos)
		return;
	g_combos = combos;
	Apply();
}

void SetSystemMarkHotkeysActive(bool active)
{
	if (active == g_active)
		return;
	g_active = active;
	Apply();
	obs_log(LOG_INFO, "system mark hotkey %s (%zu)", active ? "on" : "off", g_refs.size());
}

void ShutdownSystemMarkHotkeys()
{
	g_active = false;
	UnregisterAll();
	if (g_handler) {
		RemoveEventHandler(g_handler);
		g_handler = nullptr;
	}
}

} // namespace pokeclip
