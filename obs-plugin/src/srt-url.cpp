#include "srt-url.hpp"

namespace pokeclip {

std::string StreamTokenOf(const std::string &streamId)
{
	static const std::string prefix = "#!::";
	if (streamId.rfind(prefix, 0) != 0)
		return {};
	size_t pos = prefix.size();
	while (pos < streamId.size()) {
		size_t comma = streamId.find(',', pos);
		std::string kv = streamId.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
		if (kv.rfind("r=", 0) == 0)
			return kv.substr(2);
		if (comma == std::string::npos)
			break;
		pos = comma + 1;
	}
	return {};
}

std::string KeyHintOf(const std::string &streamId)
{
	std::string token = StreamTokenOf(streamId);
	if (token.size() < 4)
		return {};
	return token.substr(token.size() - 4);
}

bool IsSafeStreamId(const std::string &streamId)
{
	if (streamId.empty() || streamId.size() > 512)
		return false;
	if (StreamTokenOf(streamId).empty())
		return false;
	if (streamId.find("m=publish") == std::string::npos)
		return false;
	for (char c : streamId) {
		if (c == '&' || c == '+' || c == ' ' || static_cast<unsigned char>(c) < 0x20)
			return false;
	}
	return true;
}

std::string BuildSrtServerUrl(const PluginConfig &config)
{
	std::string url = "srt://" + config.ingestHost + ":" + std::to_string(config.ingestPort);
	// FFmpeg SRT 규약: latency는 마이크로초.
	url += "?latency=" + std::to_string(static_cast<long long>(config.latencyMs) * 1000);
	url += "&pkt_size=1316";
	if (config.sendPassphrase && !config.passphrase.empty())
		url += "&pbkeylen=32"; // ADR-019 AES-256
	return url;
}

} // namespace pokeclip
