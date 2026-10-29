#include "mark-hotkey.hpp"

#include "app-state.hpp"
#include "config.hpp"
#include "mark-policy.hpp"
#include "mark-sender.hpp"
#include "system-hotkey.hpp"
#include "ui-thread.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>
#include <util/config-file.h>
#include <util/dstr.h>

#include <atomic>
#include <chrono>
#include <string>
#include <vector>

namespace pokeclip {

namespace {

// OBS가 이 이름으로 바인딩을 저장한다. 바꾸면 스트리머가 지정한 키가 사라진다.
constexpr const char *kHotkeyName = "PokeClip.MarkMoment";

std::atomic<obs_hotkey_id> g_hotkey{OBS_INVALID_HOTKEY_ID};
std::atomic<int64_t> g_captureUntilMs{0}; // 독이 새 키를 받는 중 — 이 시각(단조 ms)까지 마크를 막는다
std::atomic<bool> g_sending{false};        // 우리 송출이 전송 중·재연결 중 — 시스템 전역 단축키를 건다(POK-496)
uint64_t g_stateListener = 0;

int64_t NowSteadyMs()
{
	using namespace std::chrono;
	return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// 핫키 락을 쥔 채 불린다(핫키 스레드 또는 OBS가 앞일 때 UI 스레드) — 막지 않고 대기열에 넣기만 한다.
void OnHotkey(void *, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (!pressed || MarkHotkeyCapturing())
		return; // 뗄 때도 불린다
	MarkSender::Instance().Mark(MarkVia::Hotkey);
}

// 지금 바인딩을 시스템 전역 단축키(macOS)로 옮긴다. UI 스레드.
void SyncSystemHotkeys()
{
	std::vector<SystemHotkeyCombo> combos;
	obs_hotkey_id id = g_hotkey;
	if (id != OBS_INVALID_HOTKEY_ID) {
		struct Ctx {
			obs_hotkey_id id;
			std::vector<SystemHotkeyCombo> *out;
		} ctx{id, &combos};
		obs_enum_hotkey_bindings(
			[](void *data, size_t, obs_hotkey_binding_t *binding) {
				auto *c = static_cast<Ctx *>(data);
				if (obs_hotkey_binding_get_hotkey_id(binding) != c->id)
					return true;
				obs_key_combination_t combo = obs_hotkey_binding_get_key_combination(binding);
				if (combo.key == OBS_KEY_NONE)
					return true;
				SystemHotkeyCombo h;
				h.virtualKey = obs_key_to_virtual_key(combo.key);
				h.control = (combo.modifiers & INTERACT_CONTROL_KEY) != 0;
				h.alt = (combo.modifiers & INTERACT_ALT_KEY) != 0;
				h.shift = (combo.modifiers & INTERACT_SHIFT_KEY) != 0;
				h.command = (combo.modifiers & INTERACT_COMMAND_KEY) != 0;
				c->out->push_back(h);
				return true;
			},
			&ctx);
	}
	SetSystemMarkHotkeys(combos);
}

obs_data_array_t *DefaultBindings()
{
	// Ctrl+Shift+M — macOS에서도 Control 키다(Command 아님).
	obs_data_array_t *bindings = obs_data_array_create();
	obs_data_t *combo = obs_data_create();
	obs_data_set_bool(combo, "control", true);
	obs_data_set_bool(combo, "shift", true);
	obs_data_set_string(combo, "key", "OBS_KEY_M");
	obs_data_array_push_back(bindings, combo);
	obs_data_release(combo);
	return bindings;
}

// {"bindings":[...]}에서 배열을 꺼낸다. 형식이 아니면 nullptr. 빈 배열(스트리머가 키를 지웠다)은 그대로 돌려준다.
obs_data_array_t *BindingsFromJson(const char *json)
{
	if (!json || !*json)
		return nullptr;
	obs_data_t *data = obs_data_create_from_json(json);
	if (!data)
		return nullptr;
	obs_data_array_t *bindings = obs_data_get_array(data, "bindings");
	obs_data_release(data);
	return bindings;
}

// 이 프로필의 basic.ini [Hotkeys](OBS 설정 창이 쓴 자리) → pokeclip.json 사본 → 기본 Ctrl+Shift+M.
obs_data_array_t *ResolveBindings(const char *&source)
{
	if (config_t *profile = obs_frontend_get_profile_config()) {
		if (obs_data_array_t *b = BindingsFromJson(config_get_string(profile, "Hotkeys", kHotkeyName))) {
			source = "profile";
			return b;
		}
	}
	std::string copy = ConfigStore::Instance().Get().markHotkey;
	if (obs_data_array_t *b = BindingsFromJson(copy.c_str())) {
		source = "copy";
		return b;
	}
	source = "default";
	return DefaultBindings();
}

// 독에 보여줄 표기(macOS ⌃⇧M · 그 밖 Ctrl+Shift+M — mark-policy FormatHotkeyLabel). 여럿이면 " / "로 잇는다.
std::string DescribeBindings(obs_hotkey_id id)
{
	struct Ctx {
		obs_hotkey_id id;
		std::string out;
	} ctx{id, {}};
	obs_enum_hotkey_bindings(
		[](void *data, size_t, obs_hotkey_binding_t *binding) {
			auto *c = static_cast<Ctx *>(data);
			if (obs_hotkey_binding_get_hotkey_id(binding) != c->id)
				return true;
			obs_key_combination_t combo = obs_hotkey_binding_get_key_combination(binding);
			HotkeyLabelParts parts;
			parts.control = (combo.modifiers & INTERACT_CONTROL_KEY) != 0;
			parts.alt = (combo.modifiers & INTERACT_ALT_KEY) != 0;
			parts.shift = (combo.modifiers & INTERACT_SHIFT_KEY) != 0;
			parts.command = (combo.modifiers & INTERACT_COMMAND_KEY) != 0;
			if (combo.key != OBS_KEY_NONE) {
				const char *name = obs_key_to_name(combo.key);
				parts.keyName = name ? name : "";
				struct dstr str = {0};
				obs_key_to_str(combo.key, &str);
				if (str.len)
					parts.keyText = str.array;
				dstr_free(&str);
			}
#ifdef __APPLE__
			std::string label = FormatHotkeyLabel(parts, true);
#else
			std::string label = FormatHotkeyLabel(parts, false);
#endif
			if (!label.empty()) {
				if (!c->out.empty())
					c->out += " / ";
				c->out += label;
			}
			return true;
		},
		&ctx);
	return ctx.out;
}

std::string RefreshLabel()
{
	obs_hotkey_id id = g_hotkey;
	std::string label = id == OBS_INVALID_HOTKEY_ID ? std::string() : DescribeBindings(id);
	AppState::Instance().Mutate([&](StateSnapshot &s) { s.marks.hotkey = label; });
	return label;
}

void SaveCopy()
{
	obs_hotkey_id id = g_hotkey;
	if (id == OBS_INVALID_HOTKEY_ID)
		return;
	obs_data_array_t *bindings = obs_hotkey_save(id);
	if (!bindings)
		return;
	obs_data_t *data = obs_data_create();
	obs_data_set_array(data, "bindings", bindings);
	std::string json = obs_data_get_json(data);
	obs_data_release(data);
	obs_data_array_release(bindings);
	if (ConfigStore::Instance().Get().markHotkey != json)
		ConfigStore::Instance().Update([&](PluginConfig &c) { c.markHotkey = json; });
}

void LoadBindings()
{
	const char *source = "";
	obs_data_array_t *bindings = ResolveBindings(source);
	obs_hotkey_load(g_hotkey, bindings);
	obs_data_array_release(bindings);
	std::string label = RefreshLabel();
	SyncSystemHotkeys();
	obs_log(LOG_INFO, "mark hotkey from %s: %s", source, label.empty() ? "(none)" : label.c_str());
}

// OBS 설정 창이 쓰는 자리 — 설정 창을 열면 이 값이 보이고, 다음 실행·프로필 전환 때 ResolveBindings가 먼저 읽는다.
void SaveToProfile()
{
	obs_hotkey_id id = g_hotkey;
	config_t *profile = obs_frontend_get_profile_config();
	if (id == OBS_INVALID_HOTKEY_ID || !profile)
		return;
	obs_data_array_t *bindings = obs_hotkey_save(id);
	if (!bindings)
		return;
	obs_data_t *data = obs_data_create();
	obs_data_set_array(data, "bindings", bindings);
	config_set_string(profile, "Hotkeys", kHotkeyName, obs_data_get_json(data));
	obs_data_release(data);
	obs_data_array_release(bindings);
	if (config_save_safe(profile, "tmp", nullptr) != CONFIG_SUCCESS)
		obs_log(LOG_WARNING, "mark hotkey: profile save failed");
}

// 같은 조합을 쓰는 다른 OBS 단축키 — 그쪽도 같이 눌리니 독이 알린다.
std::string FindConflict(obs_hotkey_id self, obs_key_combination_t want)
{
	struct Ctx {
		obs_hotkey_id self;
		obs_key_combination_t want;
		std::string out;
	} ctx{self, want, {}};
	obs_enum_hotkey_bindings(
		[](void *data, size_t, obs_hotkey_binding_t *binding) {
			auto *c = static_cast<Ctx *>(data);
			obs_key_combination_t combo = obs_hotkey_binding_get_key_combination(binding);
			if (obs_hotkey_binding_get_hotkey_id(binding) == c->self || combo.key != c->want.key ||
			    combo.modifiers != c->want.modifiers)
				return true;
			const char *desc = obs_hotkey_get_description(obs_hotkey_binding_get_hotkey(binding));
			c->out = desc && *desc ? desc : "?";
			return false;
		},
		&ctx);
	return ctx.out;
}

// OBS 설정 창에서 키를 바꿨다. 핫키 락 안에서 오므로 여기서 바인딩을 읽지 않고 UI 스레드로 미룬다.
void OnBindingsChanged(void *, calldata_t *cd)
{
	auto *key = static_cast<obs_hotkey_t *>(calldata_ptr(cd, "key"));
	if (!key || obs_hotkey_get_id(key) != g_hotkey)
		return;
	RunInUiThread([]() {
		RefreshLabel();
		SaveCopy();
		SyncSystemHotkeys();
	});
}

} // namespace

void RegisterMarkHotkey()
{
	if (g_hotkey != OBS_INVALID_HOTKEY_ID)
		return;
	obs_hotkey_id id = obs_hotkey_register_frontend(kHotkeyName, obs_module_text("Hotkey.Mark"), OnHotkey, nullptr);
	if (id == OBS_INVALID_HOTKEY_ID) {
		obs_log(LOG_WARNING, "mark hotkey registration failed");
		return;
	}
	g_hotkey = id;
	signal_handler_connect(obs_get_signal_handler(), "hotkey_bindings_changed", OnBindingsChanged, nullptr);
	LoadBindings();
	// 송출 단계는 여러 스레드에서 바뀐다 — 바뀐 것만 UI 스레드로 넘기고, 거기서 지금 값을 다시 읽는다(순서가 뒤집혀도 맞다).
	g_stateListener = AppState::Instance().AddListener([](const StateSnapshot &s) {
		bool sending = s.phase == StreamPhase::Live || s.phase == StreamPhase::Reconnecting;
		if (g_sending.exchange(sending) != sending)
			RunInUiThread([]() { SetSystemMarkHotkeysActive(g_sending.load()); });
	});
}

void SaveMarkHotkeyCopy()
{
	SaveCopy();
}

void ReloadMarkHotkey()
{
	if (g_hotkey != OBS_INVALID_HOTKEY_ID)
		LoadBindings();
}

void UnregisterMarkHotkey()
{
	obs_hotkey_id id = g_hotkey;
	if (id == OBS_INVALID_HOTKEY_ID)
		return;
	SaveCopy();
	if (g_stateListener) {
		AppState::Instance().RemoveListener(g_stateListener);
		g_stateListener = 0;
	}
	ShutdownSystemMarkHotkeys();
	signal_handler_disconnect(obs_get_signal_handler(), "hotkey_bindings_changed", OnBindingsChanged, nullptr);
	g_hotkey = OBS_INVALID_HOTKEY_ID;
	obs_hotkey_unregister(id);
}

MarkHotkeyChange SetMarkHotkey(const std::string &obsKeyName, const HotkeyLabelParts &mods)
{
	MarkHotkeyChange r;
	obs_hotkey_id id = g_hotkey;
	if (id == OBS_INVALID_HOTKEY_ID) {
		r.reason = "hotkey_unavailable";
		return r;
	}
	r.reason = HotkeyComboRejection(obsKeyName, mods);
	obs_key_t key = obs_key_from_name(obsKeyName.c_str());
	if (r.reason.empty() && key == OBS_KEY_NONE)
		r.reason = "hotkey_bad_key";
	if (!r.reason.empty())
		return r;

	obs_data_array_t *bindings = obs_data_array_create();
	obs_data_t *combo = obs_data_create();
	obs_data_set_bool(combo, "control", mods.control);
	obs_data_set_bool(combo, "alt", mods.alt);
	obs_data_set_bool(combo, "shift", mods.shift);
	obs_data_set_bool(combo, "command", mods.command);
	obs_data_set_string(combo, "key", obsKeyName.c_str());
	obs_data_array_push_back(bindings, combo);
	obs_data_release(combo);
	obs_hotkey_load(id, bindings); // 지금 바인딩을 갈아 끼운다(여럿이었어도 하나로)
	obs_data_array_release(bindings);

	SaveToProfile();
	SaveCopy();
	r.label = RefreshLabel();
	SyncSystemHotkeys();
	uint32_t modifiers = (mods.control ? INTERACT_CONTROL_KEY : 0) | (mods.alt ? INTERACT_ALT_KEY : 0) |
			     (mods.shift ? INTERACT_SHIFT_KEY : 0) | (mods.command ? INTERACT_COMMAND_KEY : 0);
	r.conflict = FindConflict(id, {modifiers, key});
	r.ok = true;
	obs_log(LOG_INFO, "mark hotkey set from dock: %s%s", r.label.c_str(), r.conflict.empty() ? "" : " (shared)");
	return r;
}

void SetMarkHotkeyCapture(bool on)
{
	g_captureUntilMs = HotkeyCaptureDeadlineMs(on, NowSteadyMs());
}

bool MarkHotkeyCapturing()
{
	int64_t until = g_captureUntilMs.load();
	return until != 0 && NowSteadyMs() < until;
}

} // namespace pokeclip
