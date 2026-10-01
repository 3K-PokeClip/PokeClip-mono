#pragma once

#include "app-state.hpp"

#include <string>

namespace pokeclip {

struct GopGuardResult {
	bool ok = false;
	// no_stream_output · multitrack_video · no_video_encoder · no_audio_encoder · encoder_active
	std::string errorCode;
	EncoderChecks checks;
};

// OBS_FRONTEND_EVENT_STREAMING_STARTING 안에서만 부른다.
// 이 시점은 프로필 인코더 설정이 이미 적용됐고 인코더는 아직 초기화 전이다 (OBS 32.2.1 OBSBasic_Streaming.cpp).
// 방송 비디오 인코더의 keyint_sec를 2로 강제하고, 공유 가능 여부·해상도를 점검한다.
GopGuardResult EnforceStreamEncoderPolicy();

} // namespace pokeclip
