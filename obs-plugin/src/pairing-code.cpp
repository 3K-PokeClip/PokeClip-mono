#include "pairing-code.hpp"

#include <algorithm>
#include <cctype>
#include <utility>
#include <vector>

namespace pokeclip {

namespace {
const std::string kCrockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

bool IsAlnum(char c)
{
	return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

// 영숫자 토막 하나와 그 뒤 구분 글(다음 토막 전까지). 영숫자가 아닌 바이트(한글·기호의 UTF-8 포함)는 모두 구분 글이다.
struct Run {
	std::string text;
	std::string gap;
};

bool IsSpace(char c)
{
	return c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

// 공백을 뺀 나머지가 대시 하나 — '-' · en dash(U+2013) · em dash(U+2014)
bool IsDashGap(const std::string &gap)
{
	std::string core;
	for (char c : gap) {
		if (!IsSpace(c))
			core.push_back(c);
	}
	return core == "-" || core == "\xE2\x80\x93" || core == "\xE2\x80\x94";
}
} // namespace

std::string NormalizePairingCode(const std::string &input)
{
	std::string out;
	for (char raw : input) {
		if (raw == '-' || raw == ' ')
			continue;
		char c = static_cast<char>(std::toupper(static_cast<unsigned char>(raw)));
		if (c == 'I' || c == 'L')
			c = '1';
		else if (c == 'O')
			c = '0';
		if (kCrockford.find(c) == std::string::npos)
			return {};
		out.push_back(c);
	}
	return out.size() == 8 ? out : std::string{};
}

std::string ExtractPairingCode(const std::string &text)
{
	constexpr size_t kMaxScan = 4096;
	std::vector<Run> runs;
	for (size_t i = 0; i < text.size() && i < kMaxScan; i++) {
		char c = text[i];
		if (IsAlnum(c)) {
			if (runs.empty() || !runs.back().gap.empty())
				runs.push_back({});
			runs.back().text.push_back(c);
		} else if (!runs.empty()) {
			runs.back().gap.push_back(c);
		}
	}
	for (size_t i = 0; i + 1 < runs.size(); i++) {
		if (runs[i].text.size() == 4 && runs[i + 1].text.size() == 4 && IsDashGap(runs[i].gap)) {
			std::string code = NormalizePairingCode(runs[i].text + runs[i + 1].text);
			if (!code.empty())
				return code;
		}
	}
	// 숫자 판정은 원문으로 한다 — 정규화하면 낱말 「PokeClip」의 O·I·L이 숫자가 된다.
	std::vector<std::pair<std::string, std::string>> loose; // {원문, 코드}
	for (size_t i = 0; i < runs.size(); i++) {
		std::string raw;
		if (runs[i].text.size() == 8)
			raw = runs[i].text;
		else if (runs[i].text.size() == 4 && i + 1 < runs.size() && runs[i].gap == " " &&
			 runs[i + 1].text.size() == 4)
			raw = runs[i].text + runs[i + 1].text;
		std::string code = raw.empty() ? std::string{} : NormalizePairingCode(raw);
		if (!code.empty())
			loose.emplace_back(raw, code);
	}
	for (const auto &[raw, code] : loose) {
		if (std::any_of(raw.begin(), raw.end(), [](char c) { return c >= '0' && c <= '9'; }))
			return code;
	}
	return loose.empty() ? std::string{} : loose.back().second;
}

} // namespace pokeclip
