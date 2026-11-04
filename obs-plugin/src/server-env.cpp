#include "server-env.hpp"

#include <algorithm>
#include <cctype>

namespace pokeclip {

namespace {

// 끝의 '/'는 같은 주소로 본다(https://dev.pokeclip.com/ = https://dev.pokeclip.com).
std::string_view TrimSlash(std::string_view s)
{
	while (!s.empty() && s.back() == '/')
		s.remove_suffix(1);
	return s;
}

bool SameBase(const std::string &value, const char *known)
{
	return TrimSlash(value) == TrimSlash(known);
}

bool IsKnownDefaultIngestPort(int port)
{
	return port == kProdServers.ingestPort || port == kDevServers.ingestPort || port == kLegacyDefaultIngestPort;
}

bool StartsWithNoCase(std::string_view s, std::string_view prefix)
{
	if (s.size() < prefix.size())
		return false;
	for (size_t i = 0; i < prefix.size(); i++) {
		if (std::tolower(static_cast<unsigned char>(s[i])) != prefix[i])
			return false;
	}
	return true;
}

bool AllDigits(std::string_view s)
{
	return !s.empty() && std::all_of(s.begin(), s.end(), [](unsigned char ch) { return std::isdigit(ch); });
}

// 127.0.0.0/8 — 점으로 나뉜 숫자 넷, 첫 칸이 127, 칸마다 0~255.
bool IsLoopbackIpv4(std::string_view host)
{
	int parts = 0;
	while (true) {
		const size_t dot = host.find('.');
		const std::string_view part = host.substr(0, dot);
		if (!AllDigits(part) || part.size() > 3 || std::stoi(std::string(part)) > 255)
			return false;
		if (parts == 0 && part != "127")
			return false;
		parts++;
		if (dot == std::string_view::npos)
			break;
		host.remove_prefix(dot + 1);
	}
	return parts == 4;
}

} // namespace

std::string NormalizeApiBase(std::string_view text)
{
	auto space = [](char ch) { return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'; };
	while (!text.empty() && space(text.front()))
		text.remove_prefix(1);
	while (!text.empty() && (space(text.back()) || text.back() == '/'))
		text.remove_suffix(1);
	return std::string(text);
}

bool IsLoopbackApiBase(std::string_view base)
{
	if (StartsWithNoCase(base, "https://"))
		base.remove_prefix(8);
	else if (StartsWithNoCase(base, "http://"))
		base.remove_prefix(7);
	else
		return false;

	// 경로·물음표·#·사용자 정보(http://localhost@다른호스트 — 실제로는 뒤쪽 호스트로 간다)는 받지 않는다.
	if (base.empty() || base.find_first_of("/?#@\\") != std::string_view::npos)
		return false;
	std::string_view port;
	bool loopback = false;
	if (base.front() == '[') {
		const size_t close = base.find(']');
		if (close == std::string_view::npos)
			return false;
		loopback = base.substr(1, close - 1) == "::1";
		port = base.substr(close + 1);
	} else {
		const size_t colon = base.find(':');
		std::string host(base.substr(0, colon));
		std::transform(host.begin(), host.end(), host.begin(),
			       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
		loopback = host == "localhost" || IsLoopbackIpv4(host);
		port = colon == std::string_view::npos ? std::string_view{} : base.substr(colon);
	}
	if (!loopback)
		return false;
	if (port.empty())
		return true;
	if (port.front() != ':')
		return false;
	port.remove_prefix(1);
	return AllDigits(port) && port.size() <= 5 && std::stoi(std::string(port)) >= 1 &&
	       std::stoi(std::string(port)) <= 65535;
}

bool IsAcceptedDockApiBase(const std::string &normalized)
{
	return normalized.empty() || IsKnownDefaultApiBase(normalized) || IsLoopbackApiBase(normalized);
}

const ServerDefaults &ServersFor(ServerEnv env)
{
	return env == ServerEnv::Dev ? kDevServers : kProdServers;
}

const char *ServerEnvName(ServerEnv env)
{
	return ServersFor(env).name;
}

bool ParseServerEnv(std::string_view text, ServerEnv &out)
{
	if (text == kProdServers.name) {
		out = ServerEnv::Prod;
		return true;
	}
	if (text == kDevServers.name) {
		out = ServerEnv::Dev;
		return true;
	}
	return false;
}

bool IsKnownDefaultApiBase(const std::string &base)
{
	return SameBase(base, kProdServers.apiBase) || SameBase(base, kDevServers.apiBase) ||
	       SameBase(base, kLegacyDefaultApiBase);
}

bool IsKnownDefaultIngestHost(const std::string &host)
{
	return host == kProdServers.ingestHost || host == kDevServers.ingestHost || host == kLegacyDefaultIngestHost ||
	       host == kLegacyProdIngestHost;
}

ServerEnv InferServerEnv(const PluginConfig &c, ServerEnv fallback)
{
	if (SameBase(c.apiBase, kDevServers.apiBase) || SameBase(c.apiBase, kLegacyDefaultApiBase))
		return ServerEnv::Dev;
	if (SameBase(c.apiBase, kProdServers.apiBase))
		return ServerEnv::Prod;
	// api_base를 직접 적었다(로컬 auth 등) — 수신 호스트가 한 환경의 기본값이면 그쪽으로 본다. POK-267 이전 기본
	// 수신 호스트(ingest.pokeclip.com)는 dev 키와 운영 수신을 섞던 값이라 어느 환경의 근거도 되지 않는다 — 지금 운영
	// 값과 이름이 같아도 그렇다. 그래서 맨 먼저 본다.
	if (c.ingestHost == kLegacyDefaultIngestHost)
		return fallback;
	if (c.ingestHost == kDevServers.ingestHost)
		return ServerEnv::Dev;
	// 0.2.1 이전 운영 짝(media.pokeclip.com)은 운영에만 쓰였다 — 운영 흔적으로 본다. kProdServers 비교는 지금은
	// 위 옛 값과 같아 닿지 않는다 — 운영 호스트가 다시 바뀌어도 규칙이 값과 무관하게 읽히게 둔다.
	if (c.ingestHost == kProdServers.ingestHost || c.ingestHost == kLegacyProdIngestHost)
		return ServerEnv::Prod;
	return fallback;
}

ServerEnvChange ApplyServerEnv(PluginConfig &c, ServerEnv target)
{
	const ServerDefaults &to = ServersFor(target);
	ServerEnvChange change;
	change.env = c.serverEnv != target;
	c.serverEnv = target;

	if (IsKnownDefaultApiBase(c.apiBase) && c.apiBase != to.apiBase) {
		c.apiBase = to.apiBase;
		change.api = true;
	}
	if (IsKnownDefaultIngestHost(c.ingestHost)) {
		// 포트는 호스트가 기본값일 때만, 그리고 알려진 기본 포트일 때만 맞춘다 — 직접 고친 포트는 남긴다.
		const int port = IsKnownDefaultIngestPort(c.ingestPort) ? to.ingestPort : c.ingestPort;
		if (c.ingestHost != to.ingestHost || c.ingestPort != port) {
			c.ingestHost = to.ingestHost;
			c.ingestPort = port;
			change.ingest = true;
		}
	}
	return change;
}

bool ApplyPutServerEnv(PluginConfig &c, const PluginConfig &before, std::optional<ServerEnv> requested,
		       const std::optional<std::string> &requestedApiBase)
{
	c.serverEnv = before.serverEnv;
	c.apiBase = before.apiBase;
	c.streamId = before.streamId;
	c.passphrase = before.passphrase;
	const ServerEnv target = requested.value_or(before.serverEnv);
	const bool envSwitch = target != before.serverEnv;
	if (envSwitch) {
		c.ingestHost = before.ingestHost;
		c.ingestPort = before.ingestPort;
	}
	if (requestedApiBase && IsAcceptedDockApiBase(*requestedApiBase)) {
		// 비움·알려진 기본 주소는 환경의 기본 주소로 — 바로 아래 ApplyServerEnv가 target 짝으로 맞춘다.
		const std::string next = IsLoopbackApiBase(*requestedApiBase) ? *requestedApiBase : ServersFor(target).apiBase;
		// 끝 '/'만 다른 같은 주소면 저장된 값을 둔다 — Load는 직접 적은 주소(http://localhost:8082/)를 정리하지 않고,
		// 교환 요청은 끝 '/'를 떼어 목적지가 같다. 바꾼 것으로 치면 같은 서버인데 키를 지운다.
		if (NormalizeApiBase(next) != NormalizeApiBase(c.apiBase))
			c.apiBase = next;
	}
	ApplyServerEnv(c, target);
	if (!envSwitch && c.apiBase == before.apiBase)
		return false;
	c.streamId.clear();
	c.passphrase.clear();
	return true;
}

bool SameKeyIssuer(const PluginConfig &a, const PluginConfig &b)
{
	return a.serverEnv == b.serverEnv && a.apiBase == b.apiBase;
}

} // namespace pokeclip
