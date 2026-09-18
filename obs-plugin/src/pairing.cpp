#include "pairing.hpp"
#include "pairing-code.hpp"

#include "app-state.hpp"
#include "config.hpp"
#include "constants.hpp"
#include "srt-target.hpp"

#include <curl/curl.h>
#include <obs-module.h>
#include <plugin-support.h>


namespace pokeclip {

namespace {

size_t WriteToString(char *ptr, size_t size, size_t nmemb, void *userdata)
{
	auto *out = static_cast<std::string *>(userdata);
	size_t n = size * nmemb;
	if (out->size() + n > 64 * 1024)
		return 0; // 응답이 비정상적으로 크면 끊는다
	out->append(ptr, n);
	return n;
}

// OBS가 종료 중이면 전송을 끊는다. 이 요청은 브리지 워커나 폴백 패널 스레드에서 돌고,
// 브리지 Stop()은 워커를 join하므로 여기서 안 끊으면 종료가 타임아웃(최대 ~20초)만큼 멈춘다.
int AbortOnShutdown(void *, curl_off_t, curl_off_t, curl_off_t, curl_off_t)
{
	return AppState::Instance().IsShutdown() ? 1 : 0;
}

bool IsStreamingPhase(StreamPhase phase)
{
	return phase == StreamPhase::Starting || phase == StreamPhase::Live || phase == StreamPhase::Reconnecting ||
	       phase == StreamPhase::Stopping;
}

std::string ReasonForStatus(long status)
{
	switch (status) {
	case 400:
	case 404:
		return "not_found"; // 서버는 형식 오류도 NOT_FOUND로 답한다
	case 409:
		return "already_used";
	case 410:
		return "expired";
	case 429:
		return "rate_limited";
	default:
		return status >= 500 ? "server_error" : "bad_response";
	}
}

} // namespace

PairingResult PairWithCode(const std::string &rawCode)
{
	PairingResult result;

	std::string code = NormalizePairingCode(rawCode);
	if (code.empty()) {
		result.reason = "invalid_format";
		return result;
	}
	if (IsStreamingPhase(AppState::Instance().Snapshot().phase)) {
		// ADR-019: 방송 중에는 자격증명을 바꾸지 않는다.
		result.reason = "streaming";
		return result;
	}

	PluginConfig config = ConfigStore::Instance().Get();
	std::string url = config.apiBase;
	while (!url.empty() && url.back() == '/')
		url.pop_back();
	url += kPairingExchangePath;

	obs_data_t *body = obs_data_create();
	obs_data_set_string(body, "code", code.c_str());
	std::string bodyJson = obs_data_get_json(body);
	obs_data_release(body);

	CURL *curl = curl_easy_init();
	if (!curl) {
		result.reason = "network";
		return result;
	}

	std::string response;
	std::string userAgent = std::string("pokeclip-obs/") + PLUGIN_VERSION;
	struct curl_slist *headers = nullptr;
	headers = curl_slist_append(headers, "Content-Type: application/json");
	headers = curl_slist_append(headers, "Accept: application/json");

	curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
	curl_easy_setopt(curl, CURLOPT_POST, 1L);
	curl_easy_setopt(curl, CURLOPT_POSTFIELDS, bodyJson.c_str());
	curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(bodyJson.size()));
	curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
	curl_easy_setopt(curl, CURLOPT_USERAGENT, userAgent.c_str());
	curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteToString);
	curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
	curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 5L);
	curl_easy_setopt(curl, CURLOPT_TIMEOUT, 15L);
	curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
	curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
	curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
	curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, AbortOnShutdown);
#if defined(_WIN32) && LIBCURL_VERSION_NUM >= 0x074700
	curl_easy_setopt(curl, CURLOPT_SSL_OPTIONS, CURLSSLOPT_NATIVE_CA);
#endif

	CURLcode rc = curl_easy_perform(curl);
	curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &result.httpStatus);
	curl_slist_free_all(headers);
	curl_easy_cleanup(curl);

	if (rc != CURLE_OK) {
		obs_log(LOG_WARNING, "pairing exchange failed: network (%s)", curl_easy_strerror(rc));
		result.reason = "network";
		return result;
	}
	if (result.httpStatus != 200) {
		result.reason = ReasonForStatus(result.httpStatus);
		obs_log(LOG_INFO, "pairing exchange rejected: http %ld (%s)", result.httpStatus,
			result.reason.c_str());
		return result;
	}

	obs_data_t *parsed = obs_data_create_from_json(response.c_str());
	std::string streamId = parsed ? obs_data_get_string(parsed, "streamid") : "";
	std::string passphrase = parsed ? obs_data_get_string(parsed, "passphrase") : "";
	if (parsed)
		obs_data_release(parsed);

	if (!IsSafeStreamId(streamId) || passphrase.size() < 10 || passphrase.size() > 79) {
		obs_log(LOG_WARNING, "pairing exchange: unexpected response shape");
		result.reason = "bad_response";
		return result;
	}

	bool saved = ConfigStore::Instance().Update([&](PluginConfig &c) {
		c.streamId = streamId;
		c.passphrase = passphrase;
	});
	if (!saved) {
		result.reason = "save_failed";
		return result;
	}

	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.paired = true;
		s.keyHint = KeyHintOf(streamId);
		if (s.errorCode == "no_key") {
			s.errorCode.clear();
			s.errorDetail.clear();
		}
	});
	obs_log(LOG_INFO, "paired (key …%s)", KeyHintOf(streamId).c_str());
	result.ok = true;
	return result;
}

PairingResult Unpair()
{
	PairingResult result;
	if (IsStreamingPhase(AppState::Instance().Snapshot().phase)) {
		result.reason = "streaming";
		return result;
	}
	bool saved = ConfigStore::Instance().Update([](PluginConfig &c) {
		c.streamId.clear();
		c.passphrase.clear();
	});
	if (!saved) {
		result.reason = "save_failed";
		return result;
	}
	AppState::Instance().Mutate([](StateSnapshot &s) {
		s.paired = false;
		s.keyHint.clear();
	});
	obs_log(LOG_INFO, "unpaired");
	result.ok = true;
	return result;
}

} // namespace pokeclip
