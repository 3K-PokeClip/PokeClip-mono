#include "server-env.hpp"

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

} // namespace

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
	return host == kProdServers.ingestHost || host == kDevServers.ingestHost || host == kLegacyDefaultIngestHost;
}

ServerEnv InferServerEnv(const PluginConfig &c, ServerEnv fallback)
{
	if (SameBase(c.apiBase, kDevServers.apiBase) || SameBase(c.apiBase, kLegacyDefaultApiBase))
		return ServerEnv::Dev;
	if (SameBase(c.apiBase, kProdServers.apiBase))
		return ServerEnv::Prod;
	// api_base를 직접 적었다(로컬 auth 등) — 수신 호스트가 한 환경의 기본값이면 그쪽으로 본다. 옛 기본 수신
	// 호스트(ingest.pokeclip.com)는 dev 키와 운영 수신을 섞던 값이라 어느 환경의 근거도 되지 않는다.
	if (c.ingestHost == kDevServers.ingestHost)
		return ServerEnv::Dev;
	if (c.ingestHost == kProdServers.ingestHost)
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

bool ApplyPutServerEnv(PluginConfig &c, const PluginConfig &before, std::optional<ServerEnv> requested)
{
	c.serverEnv = before.serverEnv;
	c.apiBase = before.apiBase;
	c.streamId = before.streamId;
	c.passphrase = before.passphrase;
	const ServerEnv target = requested.value_or(before.serverEnv);
	if (target == before.serverEnv) {
		ApplyServerEnv(c, target);
		return false;
	}
	c.ingestHost = before.ingestHost;
	c.ingestPort = before.ingestPort;
	ApplyServerEnv(c, target);
	c.streamId.clear();
	c.passphrase.clear();
	return true;
}

bool SameKeyIssuer(const PluginConfig &a, const PluginConfig &b)
{
	return a.serverEnv == b.serverEnv && a.apiBase == b.apiBase;
}

} // namespace pokeclip
