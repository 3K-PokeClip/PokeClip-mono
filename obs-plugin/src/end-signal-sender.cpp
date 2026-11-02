#include "end-signal-sender.hpp"

#include "end-signal-policy.hpp"

#include <curl/curl.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <algorithm>
#include <chrono>

namespace pokeclip {

namespace {

int64_t NowSteadyMs()
{
	using namespace std::chrono;
	return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// 응답 본문은 사람용 참고라 읽지 않는다 — 받기만 끝까지 한다(0을 돌려주면 curl이 쓰기 오류로 끝나 상태 코드를 잃는다).
size_t DiscardBody(char *, size_t size, size_t nmemb, void *)
{
	return size * nmemb;
}

// OBS 종료(Stop) 때 진행 중인 전송을 끊는다 — 안 그러면 종료가 전송 타임아웃만큼 멈춘다.
int AbortOnStop(void *clientp, curl_off_t, curl_off_t, curl_off_t, curl_off_t)
{
	return static_cast<std::atomic<bool> *>(clientp)->load() ? 1 : 0;
}

} // namespace

EndSignalSender &EndSignalSender::Instance()
{
	static EndSignalSender sender;
	return sender;
}

void EndSignalSender::Start()
{
	std::lock_guard lock(mutex_);
	if (started_)
		return;
	started_ = true;
	stopping_ = false;
	worker_ = std::thread([this]() { Run(); });
}

void EndSignalSender::Stop()
{
	{
		std::lock_guard lock(mutex_);
		if (!started_)
			return;
		started_ = false;
		draining_ = true; // 큐에 든 첫 시도는 보내되 재시도는 예약하지 않는다
	}
	wake_.notify_all();
	{
		// 진행 중이거나 막 큐에 들어간 첫 시도(방송 중 OBS 닫기)를 짧게 기다린다 — 곧바로 끊으면 TLS 접속 시간 안에
		// 닿지 못한다. 상한이 지나면 끊는다(계약4 3-1 「남은 시도는 버린다」, 종료를 오래 늦추지 않는다).
		std::unique_lock lock(mutex_);
		drained_.wait_for(lock, std::chrono::milliseconds(kEndSignalExitGraceMs),
				  [this]() { return !inFlight_ && queue_.empty(); });
	}
	stopping_ = true;
	wake_.notify_all();
	if (worker_.joinable())
		worker_.join();
	std::lock_guard lock(mutex_);
	if (!queue_.empty())
		obs_log(LOG_INFO, "end-signal: %zu pending attempt(s) dropped at exit", queue_.size());
	queue_.clear();
}

void EndSignalSender::Submit(EndSignalRequest request)
{
	Pending p;
	p.derived = EndSignalDerivedValue(request.passphrase);
	request.passphrase.clear(); // 큐에는 파생 값만 남긴다
	p.req = std::move(request);
	p.dueSteadyMs = NowSteadyMs();

	std::lock_guard lock(mutex_);
	if (!started_) {
		obs_log(LOG_WARNING, "end-signal (key …%s): sender not running — not sent", p.req.keyHint.c_str());
		return;
	}
	if (IsLoopbackBase(p.req.baseOverride))
		obs_log(LOG_INFO, "end-signal: using loopback base override");
	obs_log(LOG_INFO, "end-signal (key …%s): queued (%s, connection %lld ms)", p.req.keyHint.c_str(),
		p.req.trigger, static_cast<long long>(p.req.connectionDurationMs));
	queue_.push_back(std::move(p));
	wake_.notify_one();
}

void EndSignalSender::Run()
{
	std::unique_lock lock(mutex_);
	while (!stopping_) {
		if (queue_.empty()) {
			if (draining_)
				break; // 종료 중이고 보낼 것이 없다
			wake_.wait(lock, [this]() { return stopping_ || draining_ || !queue_.empty(); });
			continue;
		}
		auto next = std::min_element(queue_.begin(), queue_.end(), [](const Pending &a, const Pending &b) {
			return a.dueSteadyMs < b.dueSteadyMs;
		});
		int64_t now = NowSteadyMs();
		if (next->dueSteadyMs > now) {
			if (draining_) {
				// 종료 중 — 재시도 대기는 하지 않는다(남은 시도는 버린다).
				obs_log(LOG_INFO, "end-signal (key …%s): retry dropped at exit after %d attempt(s)",
					next->req.keyHint.c_str(), next->attempts);
				queue_.erase(next);
				drained_.notify_all();
				continue;
			}
			wake_.wait_for(lock, std::chrono::milliseconds(next->dueSteadyMs - now));
			continue;
		}
		Pending p = std::move(*next);
		queue_.erase(next);

		// 보내기 직전에 다시 잰다 — 앞선 전송이 밀려 60초를 넘겼으면 첫 시도든 재시도든 보내지 않는다(400이 된다).
		const int64_t elapsed = now - p.req.stopAtSteadyMs;
		if (EndSignalExpired(elapsed)) {
			obs_log(LOG_WARNING, "end-signal (key …%s): not sent — %lld ms after stop (limit %lld), %d attempt(s)",
				p.req.keyHint.c_str(), static_cast<long long>(elapsed),
				static_cast<long long>(kEndSignalExpireMs), p.attempts);
			continue;
		}
		p.attempts++;
		const std::string body = EndSignalBodyJson(p.req.streamId, elapsed, p.req.connectionDurationMs);

		inFlight_ = true;
		lock.unlock();
		SendResult r = Send(p, body);
		lock.lock();
		inFlight_ = false;
		drained_.notify_all();
		if (stopping_)
			break; // 끊긴 전송의 결과는 따지지 않는다

		const EndSignalVerdict v = ClassifyEndSignalResponse(r.transportOk, r.status);
		switch (v.outcome) {
		case EndSignalOutcome::Done:
			obs_log(LOG_INFO, "end-signal (key …%s): http %ld after %d attempt(s) — %s", p.req.keyHint.c_str(),
				r.status, p.attempts, v.reason);
			break;
		case EndSignalOutcome::Stop:
			obs_log(LOG_WARNING, "end-signal (key …%s): http %ld after %d attempt(s) — %s, not retrying",
				p.req.keyHint.c_str(), r.status, p.attempts, v.reason);
			break;
		case EndSignalOutcome::Retry: {
			const int64_t delay = EndSignalRetryDelayMs(p.attempts);
			if (delay < 0) {
				obs_log(LOG_WARNING, "end-signal (key …%s): giving up after %d attempt(s) — %s (http %ld)",
					p.req.keyHint.c_str(), p.attempts, v.reason, r.status);
				break;
			}
			if (draining_) {
				obs_log(LOG_WARNING, "end-signal (key …%s): attempt %d failed — %s (http %ld), not retried at exit",
					p.req.keyHint.c_str(), p.attempts, v.reason, r.status);
				break;
			}
			obs_log(LOG_INFO, "end-signal (key …%s): attempt %d failed — %s (http %ld), retry in %lld ms",
				p.req.keyHint.c_str(), p.attempts, v.reason, r.status, static_cast<long long>(delay));
			p.dueSteadyMs = NowSteadyMs() + delay;
			queue_.push_back(std::move(p));
			break;
		}
		}
	}
}

EndSignalSender::SendResult EndSignalSender::Send(const Pending &p, const std::string &body)
{
	SendResult r;
	CURL *curl = curl_easy_init();
	if (!curl)
		return r;

	const std::string url = EndSignalUrl(p.req.baseOverride);
	const std::string auth = "Authorization: Bearer " + p.derived;
	const std::string userAgent = std::string("pokeclip-obs/") + PLUGIN_VERSION;
	struct curl_slist *headers = nullptr;
	headers = curl_slist_append(headers, "Content-Type: application/json");
	headers = curl_slist_append(headers, auth.c_str());

	curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
	curl_easy_setopt(curl, CURLOPT_POST, 1L);
	curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
	curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(body.size()));
	curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
	curl_easy_setopt(curl, CURLOPT_USERAGENT, userAgent.c_str());
	curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, DiscardBody);
	curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, kEndSignalAttemptTimeoutSec);
	curl_easy_setopt(curl, CURLOPT_TIMEOUT, kEndSignalAttemptTimeoutSec);
	curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
	curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L); // 3xx는 따르지 않는다(계약4 3-1)
	curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
	curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, AbortOnStop);
	curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &stopping_);
	// 인증서 검증은 curl 기본값(켜짐)을 그대로 둔다 — 끄지 않는다. CURLOPT_VERBOSE도 켜지 않는다(헤더가 찍힌다).
#if defined(_WIN32) && LIBCURL_VERSION_NUM >= 0x074700
	curl_easy_setopt(curl, CURLOPT_SSL_OPTIONS, CURLSSLOPT_NATIVE_CA);
#endif

	CURLcode rc = curl_easy_perform(curl);
	curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &r.status);
	curl_slist_free_all(headers);
	curl_easy_cleanup(curl);

	r.transportOk = rc == CURLE_OK;
	if (!r.transportOk && !stopping_)
		obs_log(LOG_INFO, "end-signal (key …%s): network — %s", p.req.keyHint.c_str(), curl_easy_strerror(rc));
	else if (!r.transportOk)
		// OBS 종료가 진행 중인 전송을 끊었다 — 「보낼 수 있을 때만」의 그 경우다. 흔적을 남긴다(운영 HTTPS는 TLS 접속만으로
		// 수백 ms라 빠른 종료에서는 여기로 올 수 있다. 종료를 늦추지는 않는다 — 계약4 3-1).
		obs_log(LOG_WARNING, "end-signal (key …%s): attempt %d aborted by OBS exit — not sent (%s)",
			p.req.keyHint.c_str(), p.attempts, curl_easy_strerror(rc));
	return r;
}

} // namespace pokeclip
