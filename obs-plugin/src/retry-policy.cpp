#include "retry-policy.hpp"

namespace pokeclip {

RetryDecision NextRetry(int64_t elapsedMs)
{
	if (elapsedMs < kRetryWindowMs)
		return {true, kRetryWindowIntervalMs};
	if (elapsedMs < kRetryWindowMs + kRetryMidSpanMs)
		return {true, kRetryMidIntervalMs};
	if (elapsedMs < kRetryGiveUpMs)
		return {true, kRetrySlowIntervalMs};
	return {false, 0};
}

bool IsRetryableStop(const std::string &codeName)
{
	return codeName == "disconnected" || codeName == "connect_failed" || codeName == "bad_path" ||
	       codeName == "timeout" || codeName == "output_error";
}

void RejectStreak::Record(const std::string &codeName, int64_t attemptMs)
{
	if (codeName == "connect_failed" && attemptMs < kFastRejectMs)
		streak_++;
	else
		streak_ = 0;
}

} // namespace pokeclip
