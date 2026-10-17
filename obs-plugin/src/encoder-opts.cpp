#include "encoder-opts.hpp"

#include <sstream>

namespace pokeclip {

std::string StripKeyintOverrides(const std::string &opts)
{
	std::istringstream in(opts);
	std::string token;
	std::string out;
	while (in >> token) {
		std::string key = token.substr(0, token.find('='));
		if (key == "keyint" || key == "min-keyint" || key == "keyint_min" || key == "gop" || key == "idrint")
			continue;
		if (!out.empty())
			out.push_back(' ');
		out += token;
	}
	return out;
}

} // namespace pokeclip
