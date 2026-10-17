#include "pairing-code.hpp"

#include <cctype>

namespace pokeclip {

namespace {
const std::string kCrockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
}

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

} // namespace pokeclip
