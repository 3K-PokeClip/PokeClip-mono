#pragma once

#include <string>

namespace pokeclip {

struct PairingResult {
	bool ok = false;
	// invalid_format · not_found · expired · already_used · rate_limited · streaming
	// · network · server_error · bad_response · save_failed
	std::string reason;
	long httpStatus = 0;
};

// 코드 → exchange API → 성공 시 설정 저장·상태 갱신. 블로킹(최대 ~15초) — UI 스레드에서 부르지 않는다.
PairingResult PairWithCode(const std::string &rawCode);

// 저장된 키를 지운다. 송출 중이면 거절.
PairingResult Unpair();

} // namespace pokeclip
