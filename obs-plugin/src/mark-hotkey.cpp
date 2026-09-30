#include "mark-hotkey.hpp"

#include "app-state.hpp"
#include "config.hpp"
#include "mark-policy.hpp"
#include "mark-sender.hpp"
#include "ui-thread.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>
#include <util/config-file.h>
#include <util/dstr.h>

#include <atomic>
#include <string>

namespace pokeclip {

namespace {

// OBS가 이 이름으로 바인딩을 저장한다. 바꾸면 스트리머가 지정한 키가 사라진다.
constexpr const char *kHotkeyName = "PokeClip.MarkMoment";

std::atomic<obs_hotkey_id> g_hotkey{OBS_INVALID_HOTKEY_ID};

// OBS Studio는 핫키를 UI 스레드로 넘겨 핫키 락을 쥔 채 부른다 — 막지 않고 대기열에 넣기만 한다.
void OnHotkey(void *, obs_hotkey_id, obs_hotkey_t *, bool pressed)
{
	if (!pressed)
		return; // 뗄 때도 불린다
	MarkSender::Instance().Mark(MarkVia::Hotkey);
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
	obs_log(LOG_INFO, "mark hotkey from %s: %s", source, label.empty() ? "(none)" : label.c_str());
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
	signal_handler_disconnect(obs_get_signal_handler(), "hotkey_bindings_changed", OnBindingsChanged, nullptr);
	g_hotkey = OBS_INVALID_HOTKEY_ID;
	obs_hotkey_unregister(id);
}

} // namespace pokeclip
