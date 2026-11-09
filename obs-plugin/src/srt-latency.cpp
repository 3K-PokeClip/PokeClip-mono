#include "srt-latency.hpp"

#include "constants.hpp"

namespace pokeclip {

bool IsValidLatencyMs(int64_t ms)
{
	return ms >= kMinLatencyMs && ms <= kMaxLatencyMs;
}

int LoadedLatencyMs(std::optional<int64_t> stored, std::optional<bool> marker)
{
	if (!stored || !IsValidLatencyMs(*stored))
		return kDefaultLatencyMs;
	// 범위 안이라 int로 좁혀도 값이 그대로다.
	const int ms = static_cast<int>(*stored);
	if (!marker.has_value() && ms == kLegacyDefaultLatencyMs)
		return kDefaultLatencyMs;
	return ms;
}

} // namespace pokeclip
