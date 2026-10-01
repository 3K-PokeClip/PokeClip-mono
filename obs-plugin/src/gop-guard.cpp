#include "gop-guard.hpp"
#include "encoder-opts.hpp"

#include "constants.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>


namespace pokeclip {

GopGuardResult EnforceStreamEncoderPolicy()
{
	GopGuardResult r;

	obs_output_t *streamOutput = obs_frontend_get_streaming_output();
	if (!streamOutput) {
		r.errorCode = "no_stream_output";
		return r;
	}

	// 멀티트랙 비디오(Twitch 향상된 방송)는 인코더 객체가 따로고 셋업이 비동기다 — 공유 대상이 아니다.
	if (obs_output_get_video_encoder2(streamOutput, 1) != nullptr) {
		obs_output_release(streamOutput);
		r.errorCode = "multitrack_video";
		return r;
	}

	obs_encoder_t *venc = obs_output_get_video_encoder(streamOutput);
	obs_encoder_t *aenc = obs_output_get_audio_encoder(streamOutput, 0);
	obs_output_release(streamOutput);

	if (!venc) {
		r.errorCode = "no_video_encoder";
		return r;
	}
	if (!aenc) {
		r.errorCode = "no_audio_encoder"; // ffmpeg_mpegts_muxer는 오디오 트랙 0 인코더가 반드시 있어야 한다
		return r;
	}
	r.checks.sharedEncoder = true;

	obs_data_t *settings = obs_encoder_get_settings(venc);
	int before = (int)obs_data_get_int(settings, "keyint_sec");
	std::string x264opts = obs_data_get_string(settings, "x264opts");
	std::string nvencOpts = obs_data_get_string(settings, "opts");
	obs_data_release(settings);

	std::string x264Stripped = StripKeyintOverrides(x264opts);
	std::string nvencStripped = StripKeyintOverrides(nvencOpts);
	bool needsUpdate = before != kForcedKeyintSec || x264Stripped != x264opts || nvencStripped != nvencOpts;

	if (needsUpdate) {
		if (obs_encoder_active(venc)) {
			// 녹화가 방송 인코더를 공유해 이미 돌고 있다. x264는 실행 중 GOP를 바꾸지 못한다.
			r.checks.keyintSec = before;
			r.checks.gop2s = false;
			r.errorCode = "encoder_active";
			obs_log(LOG_WARNING, "stream encoder already active with keyint_sec=%d — cannot force %ds", before,
				kForcedKeyintSec);
			return r;
		}
		obs_data_t *delta = obs_data_create();
		obs_data_set_int(delta, "keyint_sec", kForcedKeyintSec);
		if (x264Stripped != x264opts)
			obs_data_set_string(delta, "x264opts", x264Stripped.c_str());
		if (nvencStripped != nvencOpts)
			obs_data_set_string(delta, "opts", nvencStripped.c_str());
		obs_encoder_update(venc, delta);
		obs_data_release(delta);
	}

	settings = obs_encoder_get_settings(venc);
	r.checks.keyintSec = (int)obs_data_get_int(settings, "keyint_sec");
	obs_data_release(settings);
	r.checks.gop2s = r.checks.keyintSec == kForcedKeyintSec;

	r.checks.width = (int)obs_encoder_get_width(venc);
	r.checks.height = (int)obs_encoder_get_height(venc);
	struct obs_video_info ovi = {};
	if (obs_get_video_info(&ovi) && ovi.fps_den > 0)
		r.checks.fps = static_cast<double>(ovi.fps_num) / ovi.fps_den;
	// ADR-020: 1080p 고정은 M1에서 경고만 한다.
	r.checks.res1080p = r.checks.width == 1920 && r.checks.height == 1080;

	obs_log(LOG_INFO, "stream encoder '%s' (%s): keyint_sec %d -> %d, %dx%d @ %.3f fps", obs_encoder_get_name(venc),
		obs_encoder_get_id(venc), before, r.checks.keyintSec, r.checks.width, r.checks.height, r.checks.fps);

	r.ok = r.checks.gop2s.value_or(false);
	if (!r.ok)
		r.errorCode = "keyint_not_applied";
	return r;
}

} // namespace pokeclip
