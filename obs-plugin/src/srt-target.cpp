#include "srt-target.hpp"
#include "srt-url.hpp"

#include <obs-data.h>

namespace pokeclip {

obs_data *BuildSrtServiceSettings(const PluginConfig &config)
{
	obs_data_t *settings = obs_data_create();
	obs_data_set_string(settings, "server", BuildSrtServerUrl(config).c_str());
	obs_data_set_string(settings, "key", config.streamId.c_str());
	bool usePassphrase = config.sendPassphrase && !config.passphrase.empty();
	obs_data_set_bool(settings, "use_auth", usePassphrase);
	obs_data_set_string(settings, "username", "");
	obs_data_set_string(settings, "password", usePassphrase ? config.passphrase.c_str() : "");
	return settings;
}

} // namespace pokeclip
