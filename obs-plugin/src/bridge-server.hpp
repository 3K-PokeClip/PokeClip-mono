#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>
#include <utility>

namespace httplib {
class Server;
}

namespace pokeclip {

// 브라우저 독 페이지 ↔ 플러그인 사이의 루프백 HTTP 브리지.
// 127.0.0.1 임의 포트, /api/*는 Bearer 토큰 필수, Host·Origin 검사. OBS·Qt 헤더에 의존하지 않는다.
struct BridgeCallbacks {
	using Reply = std::pair<int, std::string>; // HTTP status, JSON body

	// sinceVersion보다 새 상태가 오면 true와 JSON·버전. 타임아웃·종료면 false.
	std::function<bool(uint64_t sinceVersion, int timeoutMs, std::string &json, uint64_t &version)> waitState;
	std::function<std::string()> stateJson;
	std::function<std::string()> helloJson;
	std::function<void()> onFirstHello;
	std::function<Reply(const std::string &body)> pair;
	std::function<Reply()> unpair;
	std::function<std::string()> getConfig;
	std::function<Reply(const std::string &body)> putConfig;
};

class BridgeServer {
public:
	BridgeServer();
	~BridgeServer();

	bool Start(const std::string &staticDir, BridgeCallbacks callbacks);
	void Stop();

	bool Running() const { return running_.load(); }
	int Port() const { return port_; }
	bool HelloSeen() const { return helloSeen_.load(); }
	void ResetHello() { helloSeen_ = false; }

	// 토큰은 URL 조각(#)으로 넘긴다 — 서버로 전송되지 않고, 페이지가 읽은 뒤 주소창에서 지운다.
	std::string DockUrl() const;

private:
	std::unique_ptr<httplib::Server> server_;
	std::thread thread_;
	BridgeCallbacks callbacks_;
	std::string token_;
	std::string expectedHost_;
	std::string expectedOrigin_;
	int port_ = 0;
	std::atomic<bool> running_{false};
	std::atomic<bool> stopping_{false};
	std::atomic<bool> helloSeen_{false};
};

std::string GenerateToken();
bool ConstantTimeEquals(const std::string &a, const std::string &b);

} // namespace pokeclip
