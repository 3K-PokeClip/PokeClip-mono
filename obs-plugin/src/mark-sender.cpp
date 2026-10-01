#include "mark-sender.hpp"

#include "app-state.hpp"
#include "config.hpp"
#include "srt-url.hpp"

#include <curl/curl.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <random>

namespace pokeclip {

namespace {

int64_t NowSteadyMs()
{
	using namespace std::chrono;
	return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

int64_t NowEpochMs()
{
	using namespace std::chrono;
	return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

std::string NewEventId()
{
	std::random_device rd;
	std::array<uint8_t, 16> bytes{};
	for (size_t i = 0; i < bytes.size(); i += 4) {
		uint32_t v = rd();
		for (size_t j = 0; j < 4; j++)
			bytes[i + j] = static_cast<uint8_t>(v >> (8 * j));
	}
	return FormatUuidV4(bytes);
}

const char *ViaName(MarkVia via)
{
	switch (via) {
	case MarkVia::Hotkey:
		return "hotkey";
	case MarkVia::Dock:
		return "dock";
	case MarkVia::Panel:
		return "panel";
	}
	return "?";
}

// 헤더 값으로 넣으므로 제어 문자·공백이 있으면 쓰지 않는다(헤더 주입 방지).
bool IsHeaderSafe(const std::string &value)
{
	return !value.empty() && std::all_of(value.begin(), value.end(), [](char ch) {
		unsigned char c = static_cast<unsigned char>(ch);
		return c > 0x20 && c < 0x7F;
	});
}

size_t WriteToString(char *ptr, size_t size, size_t nmemb, void *userdata)
{
	constexpr size_t kMaxBody = 16 * 1024;
	auto *out = static_cast<std::string *>(userdata);
	size_t n = size * nmemb;
	// 사유 코드만 보면 된다 — 넘치는 부분은 버리되 받기는 끝까지 한다. 0을 돌려주면 curl이 쓰기 오류로 끝나
	// 받은 HTTP 상태를 잃고 네트워크 실패로 다시 보낸다(큰 HTML 404를 10분 재전송).
	if (out->size() < kMaxBody)
		out->append(ptr, std::min(n, kMaxBody - out->size()));
	return n;
}

size_t ReadRetryAfter(char *buffer, size_t size, size_t nitems, void *userdata)
{
	size_t n = size * nitems;
	static const char name[] = "retry-after:";
	constexpr size_t len = sizeof(name) - 1;
	if (n > len) {
		bool match = true;
		for (size_t i = 0; i < len && match; i++)
			match = std::tolower(static_cast<unsigned char>(buffer[i])) == name[i];
		if (match)
			*static_cast<int64_t *>(userdata) = ParseRetryAfterMs(std::string(buffer + len, n - len));
	}
	return n;
}

// OBS 종료(Stop) 때 진행 중인 전송을 끊는다 — 안 그러면 종료가 전송 타임아웃만큼 멈춘다.
int AbortOnStop(void *clientp, curl_off_t, curl_off_t, curl_off_t, curl_off_t)
{
	return static_cast<std::atomic<bool> *>(clientp)->load() ? 1 : 0;
}

} // namespace

MarkSender &MarkSender::Instance()
{
	static MarkSender sender;
	return sender;
}

void MarkSender::Start()
{
	std::lock_guard lock(mutex_);
	if (started_)
		return;
	started_ = true;
	stopping_ = false;
	worker_ = std::thread([this]() { Run(); });
}

void MarkSender::Stop()
{
	{
		std::lock_guard lock(mutex_);
		if (!started_)
			return;
		started_ = false;
		stopping_ = true;
	}
	wake_.notify_all();
	if (worker_.joinable())
		worker_.join();
	std::lock_guard lock(mutex_);
	if (!queue_.empty())
		obs_log(LOG_WARNING, "mark: %zu unsent mark(s) dropped at exit", queue_.size());
	queue_.clear();
}

void MarkSender::ResetCounters()
{
	std::lock_guard lock(mutex_);
	sent_ = 0;
	failed_ = 0;
	// 지난 결과·사유도 지운다 — 폴백 패널이 새 방송에 옛 거절 사유를 붙이지 않게. seq는 그대로라 토스트는 없다.
	PublishLocked("", "", true);
}

void MarkSender::PublishLocked(const char *result, const std::string &reason, bool clearResult)
{
	int pending = static_cast<int>(queue_.size()) + (inFlight_ ? 1 : 0);
	int sent = sent_, failed = failed_;
	int64_t lastAt = lastAt_;
	std::string res = result;
	AppState::Instance().Mutate([&](StateSnapshot &s) {
		s.marks.sent = sent;
		s.marks.pending = pending;
		s.marks.failed = failed;
		s.marks.lastAt = lastAt;
		if (!res.empty()) {
			s.marks.seq++;
			s.marks.result = res;
			s.marks.reason = reason;
		} else if (clearResult) {
			s.marks.result.clear();
			s.marks.reason.clear();
		}
	});
}

MarkAccept MarkSender::Mark(MarkVia via)
{
	// 누른 시각을 가장 먼저 찍는다.
	int64_t pressedAt = NowEpochMs();
	int64_t pressedSteady = NowSteadyMs();

	StreamPhase phase = AppState::Instance().Snapshot().phase;
	PluginConfig config = ConfigStore::Instance().Get();
	std::string token = StreamTokenOf(config.streamId);

	std::lock_guard lock(mutex_);
	if (!started_)
		return {false, "mark_not_live"};

	// 우리 송출이 켜져 있을 때만 — 녹화가 없으면 표시할 자리가 없다. 재연결 중은 방송이 이어지는 것으로 본다.
	std::string rejected;
	if (phase != StreamPhase::Live && phase != StreamPhase::Reconnecting)
		rejected = "mark_not_live";
	else if (token.empty())
		rejected = "no_key";
	else if (!IsHeaderSafe(config.passphrase))
		rejected = "invalid_key";
	else if (!IsSecureMarkBase(config.ClipBase()))
		rejected = "mark_insecure";
	if (!rejected.empty()) {
		obs_log(LOG_INFO, "mark via %s ignored: %s", ViaName(via), rejected.c_str());
		PublishLocked("rejected", rejected);
		return {false, rejected};
	}

	// 연타·키 튐은 한 번으로. 핫키는 조용히 넘기고, 버튼은 호출한 쪽이 사유를 보여준다.
	if (!debouncer_.Accept(pressedSteady))
		return {false, "mark_too_soon"};

	if (queue_.size() >= kMarkQueueCap) {
		// 재시도는 뒤로 다시 들어가므로 맨 앞이 가장 오래된 누름이 아니다 — 누른 시각으로 고른다.
		auto oldest = std::min_element(queue_.begin(), queue_.end(), [](const Pending &a, const Pending &b) {
			return a.pressedSteadyMs < b.pressedSteadyMs;
		});
		obs_log(LOG_WARNING, "mark: queue full — dropping oldest %.8s", oldest->eventId.c_str());
		queue_.erase(oldest);
		failed_++;
	}

	Pending p;
	p.eventId = NewEventId();
	p.pressedAt = pressedAt;
	p.pressedSteadyMs = pressedSteady;
	p.dueSteadyMs = pressedSteady;
	p.url = MarkUrl(config.ClipBase(), token);
	p.passphrase = config.passphrase;
	obs_log(LOG_INFO, "mark %.8s queued via %s", p.eventId.c_str(), ViaName(via));
	queue_.push_back(std::move(p));
	lastAt_ = pressedAt;
	PublishLocked("", "");
	wake_.notify_one();
	return {true, ""};
}

void MarkSender::Run()
{
	std::unique_lock lock(mutex_);
	while (!stopping_) {
		if (queue_.empty()) {
			wake_.wait(lock, [this]() { return stopping_ || !queue_.empty(); });
			continue;
		}
		auto next = std::min_element(queue_.begin(), queue_.end(), [](const Pending &a, const Pending &b) {
			return a.dueSteadyMs < b.dueSteadyMs;
		});
		int64_t now = NowSteadyMs();
		if (next->dueSteadyMs > now) {
			wake_.wait_for(lock, std::chrono::milliseconds(next->dueSteadyMs - now));
			continue;
		}
		Pending p = std::move(*next);
		queue_.erase(next);
		// 예약할 때는 10분 안이었어도 앞선 전송(한 번에 최대 10초)이 밀리면 넘길 수 있다.
		// 보내기 직전에 다시 본다.
		if (MarkExpired(p.pressedSteadyMs, now)) {
			failed_++;
			obs_log(LOG_WARNING, "mark %.8s expired in queue after %d attempts", p.eventId.c_str(),
				p.attempts);
			PublishLocked("failed", "mark_expired");
			continue;
		}
		inFlight_ = true;
		p.attempts++;

		lock.unlock();
		SendResult r = Send(p);
		lock.lock();
		inFlight_ = false;
		if (stopping_)
			break; // 끊긴 전송의 결과는 따지지 않는다

		MarkVerdict v = ClassifyMarkResponse(r.transportOk, r.status, r.body);
		now = NowSteadyMs();
		switch (v.outcome) {
		case MarkOutcome::Delivered:
			sent_++;
			obs_log(LOG_INFO, "mark %.8s delivered (http %ld, attempt %d)", p.eventId.c_str(), r.status,
				p.attempts);
			PublishLocked("sent", "");
			break;
		case MarkOutcome::Drop:
			failed_++;
			obs_log(LOG_WARNING, "mark %.8s dropped: %s (http %ld)", p.eventId.c_str(), v.reason.c_str(),
				r.status);
			PublishLocked("failed", v.reason);
			break;
		case MarkOutcome::Retry: {
			int64_t delay = MarkRetryDelayMs(p.attempts, r.retryAfterMs);
			if (MarkExpired(p.pressedSteadyMs, now + delay)) {
				failed_++;
				obs_log(LOG_WARNING, "mark %.8s expired after %d attempts (last: %s)", p.eventId.c_str(),
					p.attempts, v.reason.c_str());
				PublishLocked("failed", "mark_expired");
				break;
			}
			obs_log(LOG_INFO, "mark %.8s retry in %lld ms: %s (http %ld)", p.eventId.c_str(),
				static_cast<long long>(delay), v.reason.c_str(), r.status);
			p.dueSteadyMs = now + delay;
			bool first = p.attempts == 1;
			queue_.push_back(std::move(p));
			// 처음 실패만 알린다 — 재시도마다 토스트를 띄우지 않는다.
			PublishLocked(first ? "retrying" : "", v.reason);
			break;
		}
		}
	}
}

MarkSender::SendResult MarkSender::Send(const Pending &p)
{
	SendResult r;
	CURL *curl = curl_easy_init();
	if (!curl)
		return r;

	std::string body = MarkBodyJson(p.eventId, p.pressedAt, NowEpochMs());
	std::string auth = "Authorization: Bearer " + p.passphrase;
	std::string userAgent = std::string("pokeclip-obs/") + PLUGIN_VERSION;
	struct curl_slist *headers = nullptr;
	headers = curl_slist_append(headers, "Content-Type: application/json");
	headers = curl_slist_append(headers, "Accept: application/json");
	headers = curl_slist_append(headers, auth.c_str());

	curl_easy_setopt(curl, CURLOPT_URL, p.url.c_str());
	curl_easy_setopt(curl, CURLOPT_POST, 1L);
	curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
	curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(body.size()));
	curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
	curl_easy_setopt(curl, CURLOPT_USERAGENT, userAgent.c_str());
	curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteToString);
	curl_easy_setopt(curl, CURLOPT_WRITEDATA, &r.body);
	curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, ReadRetryAfter);
	curl_easy_setopt(curl, CURLOPT_HEADERDATA, &r.retryAfterMs);
	curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 5L);
	curl_easy_setopt(curl, CURLOPT_TIMEOUT, 10L);
	curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
	curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
	curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
	curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, AbortOnStop);
	curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &stopping_);
#if defined(_WIN32) && LIBCURL_VERSION_NUM >= 0x074700
	curl_easy_setopt(curl, CURLOPT_SSL_OPTIONS, CURLSSLOPT_NATIVE_CA);
#endif

	CURLcode rc = curl_easy_perform(curl);
	curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &r.status);
	curl_slist_free_all(headers);
	curl_easy_cleanup(curl);

	r.transportOk = rc == CURLE_OK;
	if (!r.transportOk && !stopping_)
		obs_log(LOG_INFO, "mark %.8s: network (%s)", p.eventId.c_str(), curl_easy_strerror(rc));
	return r;
}

} // namespace pokeclip
