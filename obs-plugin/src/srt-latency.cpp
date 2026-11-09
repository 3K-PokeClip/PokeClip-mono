#include "srt-latency.hpp"

#include "constants.hpp"

namespace pokeclip {

bool IsValidLatencyMs(int ms)
{
	return ms >= kMinLatencyMs && ms <= kMaxLatencyMs;
}

int LoadedLatencyMs(std::optional<int> stored, bool migrated)
{
	if (!stored || !IsValidLatencyMs(*stored))
		return kDefaultLatencyMs;
	if (!migrated && *stored == kLegacyDefaultLatencyMs)
		return kDefaultLatencyMs;
	return *stored;
}

} // namespace pokeclip
