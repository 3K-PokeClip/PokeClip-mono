#include "mark-policy.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>

namespace pokeclip {

std::string MarkUrl(const std::string &clipBase, const std::string &streamToken)
{
	std::string url = clipBase;
	while (!url.empty() && url.back() == '/')
		url.pop_back();
	url += kMarkPathPrefix;
	static const char *hex = "0123456789ABCDEF";
	for (char ch : streamToken) {
		unsigned char c = static_cast<unsigned char>(ch);
		if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
			url += ch;
		} else {
			url += '%';
			url += hex[c >> 4];
			url += hex[c & 0x0F];
		}
	}
	url += kMarkPathSuffix;
	return url;
}

namespace {

bool StartsWithNoCase(const std::string &s, const char *prefix)
{
	size_t n = std::char_traits<char>::length(prefix);
	if (s.size() < n)
		return false;
	for (size_t i = 0; i < n; i++) {
		if (std::tolower(static_cast<unsigned char>(s[i])) != prefix[i])
			return false;
	}
	return true;
}

bool AllOf(const std::string &s, const char *allowed)
{
	return s.find_first_not_of(allowed) == std::string::npos;
}

} // namespace

bool IsSecureMarkBase(const std::string &base)
{
	if (StartsWithNoCase(base, "https://"))
		return base.size() > 8;
	if (!StartsWithNoCase(base, "http://"))
		return false;

	// 평문 HTTP는 이 PC 안(루프백)만 — 로컬 목 서버·auth 직결 개발용.
	size_t end = base.find_first_of("/?#", 7);
	std::string authority = base.substr(7, end == std::string::npos ? std::string::npos : end - 7);
	if (authority.find('@') != std::string::npos)
		return false; // http://localhost@다른호스트 — 실제로는 뒤쪽 호스트로 간다
	std::string host;
	std::string port;
	if (!authority.empty() && authority[0] == '[') {
		size_t close = authority.find(']');
		if (close == std::string::npos)
			return false;
		host = authority.substr(1, close - 1);
		port = authority.substr(close + 1);
	} else {
		size_t colon = authority.find(':');
		host = authority.substr(0, colon);
		port = colon == std::string::npos ? "" : authority.substr(colon);
	}
	if (!port.empty() && (port[0] != ':' || !AllOf(port.substr(1), "0123456789")))
		return false;
	std::transform(host.begin(), host.end(), host.begin(), [](unsigned char c) { return std::tolower(c); });
	if (host == "localhost" || host == "::1")
		return true;
	return host.rfind("127.", 0) == 0 && AllOf(host, "0123456789.") &&
	       std::count(host.begin(), host.end(), '.') == 3;
}

std::string MarkBodyJson(const std::string &eventId, int64_t pressedAt, int64_t sentAt)
{
	// eventId는 FormatUuidV4가 만든 16진수·하이픈뿐이라 이스케이프할 문자가 없다.
	return "{\"eventId\":\"" + eventId + "\",\"pressedAt\":" + std::to_string(pressedAt) +
	       ",\"sentAt\":" + std::to_string(sentAt) + "}";
}

std::string FormatUuidV4(std::array<uint8_t, 16> b)
{
	b[6] = static_cast<uint8_t>((b[6] & 0x0F) | 0x40); // 버전 4
	b[8] = static_cast<uint8_t>((b[8] & 0x3F) | 0x80); // RFC 4122 변형
	char out[37];
	std::snprintf(out, sizeof(out), "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x", b[0],
		      b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
	return out;
}

int64_t MarkRetryDelayMs(int attempt, int64_t retryAfterMs)
{
	int64_t backoff = kMarkRetryFirstMs;
	for (int i = 1; i < attempt && backoff < kMarkRetryMaxMs; i++)
		backoff *= 2;
	backoff = std::min(backoff, kMarkRetryMaxMs);
	return std::max(backoff, std::min(retryAfterMs, kMarkRetryAfterMaxMs));
}

int64_t ParseRetryAfterMs(const std::string &header)
{
	size_t i = 0;
	while (i < header.size() && std::isspace(static_cast<unsigned char>(header[i])))
		i++;
	int64_t seconds = 0;
	size_t digits = 0;
	for (; i < header.size() && std::isdigit(static_cast<unsigned char>(header[i])); i++, digits++) {
		if (digits >= 6)
			return kMarkRetryAfterMaxMs; // 터무니없이 길다 — 상한으로
		seconds = seconds * 10 + (header[i] - '0');
	}
	// HTTP 날짜 형식은 읽지 않는다(서버 제안 계약은 초 단위).
	return digits ? seconds * 1000 : 0;
}

MarkVerdict ClassifyMarkResponse(bool transportOk, long status, const std::string &body)
{
	auto has = [&](const char *code) { return body.find(code) != std::string::npos; };
	if (!transportOk)
		return {MarkOutcome::Retry, "network"};
	if (status >= 200 && status < 300)
		return {MarkOutcome::Delivered, ""};
	switch (status) {
	case 400:
		return {MarkOutcome::Drop, "mark_rejected"};
	case 401:
	case 403:
		// 계약의 401은 사유(invalid_stream_key)를 단다. 사유 없는 401은 Clip 기본 보안 체인이 JWT가 아닌
		// Bearer를 막은 것 — 마크 경로가 아직 열리지 않았다(2026-09-30 develop: anyRequest().authenticated()).
		return {MarkOutcome::Drop, has("invalid_stream_key") ? "mark_unauthorized" : "mark_unsupported"};
	case 404:
		if (has("broadcast_not_found"))
			return {MarkOutcome::Retry, "mark_no_broadcast"}; // 방송 시작 신호(계약9)가 아직 안 닿았다
		return {MarkOutcome::Drop, "mark_unsupported"};
	case 405:
		return {MarkOutcome::Drop, "mark_unsupported"};
	case 408:
		return {MarkOutcome::Retry, "network"};
	case 429:
		// 페어링의 rate_limited(「1분 뒤 다시 시도하세요」)와 달리 자동으로 다시 보낸다 — 사유도 따로.
		return {MarkOutcome::Retry, "mark_rate_limited"};
	case 503:
		if (has("timeline_not_ready"))
			return {MarkOutcome::Retry, "mark_not_ready"}; // 첫 조각이 아직 없어 시각 기준점이 없다
		return {MarkOutcome::Retry, "server_error"};
	default:
		if (status >= 500)
			return {MarkOutcome::Retry, "server_error"};
		return {MarkOutcome::Drop, "bad_response"}; // 3xx(리다이렉트는 따라가지 않는다)·그 밖의 4xx
	}
}

std::string FormatHotkeyLabel(const HotkeyLabelParts &p, bool mac)
{
	std::string key;
	static const std::string prefix = "OBS_KEY_";
	if (p.keyName.rfind(prefix, 0) == 0 && p.keyName.size() > prefix.size()) {
		std::string rest = p.keyName.substr(prefix.size());
		bool single = rest.size() == 1 && std::isalnum(static_cast<unsigned char>(rest[0]));
		bool function = rest.size() >= 2 && rest.size() <= 3 && rest[0] == 'F' &&
				std::all_of(rest.begin() + 1, rest.end(),
					    [](char c) { return std::isdigit(static_cast<unsigned char>(c)); });
		if (single || function)
			key = rest;
		else
			key = p.keyText.empty() ? rest : p.keyText;
	} else {
		key = p.keyText;
	}

	std::string out;
	if (mac) {
		// OBS macOS 순서: ⌃⌥⇧⌘ (MSVC 코드 페이지에 휘둘리지 않게 UTF-8 바이트로 적는다)
		if (p.control)
			out += "\xE2\x8C\x83"; // ⌃
		if (p.alt)
			out += "\xE2\x8C\xA5"; // ⌥
		if (p.shift)
			out += "\xE2\x87\xA7"; // ⇧
		if (p.command)
			out += "\xE2\x8C\x98"; // ⌘
		return out + key;
	}
	auto add = [&](const char *name) {
		out += name;
		out += '+';
	};
	if (p.control)
		add("Ctrl");
	if (p.alt)
		add("Alt");
	if (p.shift)
		add("Shift");
	if (p.command)
		add("Win");
	if (key.empty() && !out.empty())
		out.pop_back();
	return out + key;
}

bool MarkExpired(int64_t pressedSteadyMs, int64_t nowSteadyMs)
{
	return nowSteadyMs - pressedSteadyMs >= kMarkGiveUpMs;
}

bool MarkDebouncer::Accept(int64_t nowSteadyMs)
{
	if (hasLast_ && nowSteadyMs - lastMs_ < kMarkDebounceMs)
		return false;
	hasLast_ = true;
	lastMs_ = nowSteadyMs;
	return true;
}

} // namespace pokeclip
