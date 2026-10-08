#include "end-signal-policy.hpp"

#include <algorithm>
#include <cctype>
#include <cstring>

namespace pokeclip {

namespace {

// ── SHA-256 (FIPS 180-4) ──
// 서명·암호가 아니라 해시다. 우리는 보내는 쪽이라 상수 시간 비교도 필요 없다. 정확성은 RFC 4231·FIPS 벡터로 잰다.

constexpr uint32_t kK[64] = {
	0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
	0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
	0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
	0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
	0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
	0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
	0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
	0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

inline uint32_t Rotr(uint32_t x, int n)
{
	return (x >> n) | (x << (32 - n));
}

struct Sha256State {
	uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
			 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
	uint8_t block[64] = {};
	size_t blockLen = 0;
	uint64_t totalBytes = 0;

	void Compress(const uint8_t *p)
	{
		uint32_t w[64];
		for (int i = 0; i < 16; i++) {
			w[i] = (static_cast<uint32_t>(p[4 * i]) << 24) | (static_cast<uint32_t>(p[4 * i + 1]) << 16) |
			       (static_cast<uint32_t>(p[4 * i + 2]) << 8) | static_cast<uint32_t>(p[4 * i + 3]);
		}
		for (int i = 16; i < 64; i++) {
			uint32_t s0 = Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
			uint32_t s1 = Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
			w[i] = w[i - 16] + s0 + w[i - 7] + s1;
		}
		uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
		for (int i = 0; i < 64; i++) {
			uint32_t S1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
			uint32_t ch = (e & f) ^ (~e & g);
			uint32_t t1 = hh + S1 + ch + kK[i] + w[i];
			uint32_t S0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
			uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
			uint32_t t2 = S0 + maj;
			hh = g;
			g = f;
			f = e;
			e = d + t1;
			d = c;
			c = b;
			b = a;
			a = t1 + t2;
		}
		h[0] += a;
		h[1] += b;
		h[2] += c;
		h[3] += d;
		h[4] += e;
		h[5] += f;
		h[6] += g;
		h[7] += hh;
	}

	void Update(const uint8_t *data, size_t len)
	{
		totalBytes += len;
		while (len > 0) {
			size_t n = std::min(len, sizeof(block) - blockLen);
			std::memcpy(block + blockLen, data, n);
			blockLen += n;
			data += n;
			len -= n;
			if (blockLen == sizeof(block)) {
				Compress(block);
				blockLen = 0;
			}
		}
	}

	std::array<uint8_t, 32> Final()
	{
		const uint64_t bits = totalBytes * 8; // 패딩을 더하기 전의 길이
		const uint8_t one = 0x80;
		const uint8_t zero = 0;
		Update(&one, 1);
		while (blockLen != 56)
			Update(&zero, 1);
		uint8_t len[8];
		for (int i = 0; i < 8; i++)
			len[i] = static_cast<uint8_t>(bits >> (56 - 8 * i));
		Update(len, 8);
		std::array<uint8_t, 32> out{};
		for (int i = 0; i < 8; i++) {
			out[4 * i] = static_cast<uint8_t>(h[i] >> 24);
			out[4 * i + 1] = static_cast<uint8_t>(h[i] >> 16);
			out[4 * i + 2] = static_cast<uint8_t>(h[i] >> 8);
			out[4 * i + 3] = static_cast<uint8_t>(h[i]);
		}
		return out;
	}
};

std::string ToLowerHex(const std::array<uint8_t, 32> &bytes)
{
	static const char *hex = "0123456789abcdef";
	std::string out;
	out.reserve(64);
	for (uint8_t b : bytes) {
		out += hex[b >> 4];
		out += hex[b & 0x0F];
	}
	return out;
}

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

std::array<uint8_t, 32> Sha256(const std::string &data)
{
	Sha256State st;
	st.Update(reinterpret_cast<const uint8_t *>(data.data()), data.size());
	return st.Final();
}

std::string HmacSha256Hex(const std::string &key, const std::string &message)
{
	constexpr size_t kBlock = 64;
	std::array<uint8_t, kBlock> k{};
	if (key.size() > kBlock) {
		std::array<uint8_t, 32> hashed = Sha256(key);
		std::copy(hashed.begin(), hashed.end(), k.begin());
	} else {
		std::copy(key.begin(), key.end(), k.begin());
	}
	std::string ipad(kBlock, '\0'), opad(kBlock, '\0');
	for (size_t i = 0; i < kBlock; i++) {
		ipad[i] = static_cast<char>(k[i] ^ 0x36);
		opad[i] = static_cast<char>(k[i] ^ 0x5c);
	}
	std::array<uint8_t, 32> inner = Sha256(ipad + message);
	std::string outerInput = opad;
	outerInput.append(reinterpret_cast<const char *>(inner.data()), inner.size());
	return ToLowerHex(Sha256(outerInput));
}

std::string EndSignalDerivedValue(const std::string &passphrase)
{
	return HmacSha256Hex(passphrase, kEndSignalHmacMessage);
}

bool IsLoopbackBase(const std::string &base)
{
	size_t schemeEnd;
	if (StartsWithNoCase(base, "https://"))
		schemeEnd = 8;
	else if (StartsWithNoCase(base, "http://"))
		schemeEnd = 7;
	else
		return false;

	size_t end = base.find_first_of("/?#", schemeEnd);
	std::string authority = base.substr(schemeEnd, end == std::string::npos ? std::string::npos : end - schemeEnd);
	if (authority.empty() || authority.find('@') != std::string::npos)
		return false; // http://localhost@다른호스트 — 실제로는 뒤쪽 호스트로 간다
	std::string host;
	std::string port;
	if (authority[0] == '[') {
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
	if (!port.empty() && (port[0] != ':' || port.size() == 1 || !AllOf(port.substr(1), "0123456789")))
		return false;
	std::transform(host.begin(), host.end(), host.begin(), [](unsigned char c) { return std::tolower(c); });
	if (host == "localhost" || host == "::1")
		return true;
	return host.rfind("127.", 0) == 0 && AllOf(host, "0123456789.") &&
	       std::count(host.begin(), host.end(), '.') == 3;
}

std::string EndSignalUrl(const std::string &baseOverride)
{
	std::string url = IsLoopbackBase(baseOverride) ? baseOverride : std::string(kEndSignalBase);
	while (!url.empty() && url.back() == '/')
		url.pop_back();
	url += kEndSignalPath;
	return url;
}

std::string EndSignalBodyJson(const std::string &streamId, int64_t elapsedSinceStopMs, int64_t connectionDurationMs)
{
	// streamid는 IsSafeStreamId를 지났지만(제어 문자·'&'·'+'·공백 없음) 따옴표·역슬래시는 거르지 않으므로 이스케이프한다.
	std::string escaped;
	escaped.reserve(streamId.size());
	for (char ch : streamId) {
		if (ch == '"' || ch == '\\')
			escaped += '\\';
		escaped += ch;
	}
	return "{\"streamid\":\"" + escaped + "\",\"elapsedSinceStopMs\":" + std::to_string(elapsedSinceStopMs) +
	       ",\"connectionDurationMs\":" + std::to_string(connectionDurationMs) + "}";
}

EndSignalVerdict ClassifyEndSignalResponse(bool transportOk, long status)
{
	if (!transportOk || status <= 0)
		return {EndSignalOutcome::Retry, "network"};
	if (status == 202)
		return {EndSignalOutcome::Done, "accepted"};
	if (status >= 200 && status < 300)
		return {EndSignalOutcome::Done, "accepted_unexpected_2xx"}; // 규약 밖 응답 — Media는 202만 쓴다
	if (status >= 300 && status < 400)
		return {EndSignalOutcome::Stop, "redirect"};
	switch (status) {
	case 400:
		return {EndSignalOutcome::Stop, "bad_request"};
	case 401:
		return {EndSignalOutcome::Stop, "unauthorized"};
	case 429:
		return {EndSignalOutcome::Stop, "rate_limited"};
	default:
		break;
	}
	if (status >= 400 && status < 500)
		return {EndSignalOutcome::Stop, "rejected"}; // 예: 수신부 배포 전의 404
	return {EndSignalOutcome::Retry, "server_error"};
}

int64_t EndSignalRetryDelayMs(int attempt)
{
	if (attempt < 1 || attempt >= kEndSignalMaxAttempts)
		return -1;
	return kEndSignalRetryDelayMs[attempt - 1];
}

bool EndSignalExpired(int64_t elapsedSinceStopMs)
{
	return elapsedSinceStopMs < 0 || elapsedSinceStopMs > kEndSignalExpireMs;
}

EndSignalSkip EndSignalSkipReason(bool intentional, bool everConnected)
{
	if (!intentional)
		return EndSignalSkip::NotIntentional;
	if (!everConnected)
		return EndSignalSkip::NeverConnected;
	return EndSignalSkip::None;
}

const char *EndSignalSkipName(EndSignalSkip skip)
{
	switch (skip) {
	case EndSignalSkip::None:
		return "send";
	case EndSignalSkip::NotIntentional:
		return "not_intentional";
	case EndSignalSkip::NeverConnected:
		return "never_connected";
	}
	return "?";
}

int64_t ConnectionDurationMs(int64_t connectedAtMs, bool connectedAtStop, int64_t outageAtMs, int64_t stopAtMs)
{
	const int64_t end = connectedAtStop ? stopAtMs : outageAtMs;
	return std::max<int64_t>(0, end - connectedAtMs);
}

} // namespace pokeclip
