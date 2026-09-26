#include "bridge-server.hpp"

#include <httplib.h>

#include <array>
#include <random>

namespace pokeclip {

std::string GenerateToken()
{
	std::random_device rd; // macOS: arc4random, Windows: rand_s — 둘 다 CSPRNG
	static const char *hex = "0123456789abcdef";
	std::string token;
	token.reserve(64);
	for (int i = 0; i < 32; i++) {
		unsigned v = rd() & 0xff;
		token.push_back(hex[v >> 4]);
		token.push_back(hex[v & 0xf]);
	}
	return token;
}

bool ConstantTimeEquals(const std::string &a, const std::string &b)
{
	if (a.size() != b.size())
		return false;
	unsigned char diff = 0;
	for (size_t i = 0; i < a.size(); i++)
		diff |= static_cast<unsigned char>(a[i] ^ b[i]);
	return diff == 0;
}

BridgeServer::BridgeServer() = default;

BridgeServer::~BridgeServer()
{
	Stop();
}

std::string BridgeServer::DockUrl() const
{
	return "http://127.0.0.1:" + std::to_string(port_) + "/#token=" + token_;
}

bool BridgeServer::Start(const std::string &staticDir, BridgeCallbacks callbacks)
{
	if (running_)
		return true;

	callbacks_ = std::move(callbacks);
	token_ = GenerateToken();
	server_ = std::make_unique<httplib::Server>();
	auto &svr = *server_;

	svr.set_payload_max_length(16 * 1024);
	svr.set_read_timeout(5, 0);
	svr.set_write_timeout(5, 0);
	svr.set_keep_alive_max_count(50);

	for (auto [ext, mime] : std::array<std::pair<const char *, const char *>, 6>{{
		     {"woff2", "font/woff2"},
		     {"js", "text/javascript"},
		     {"mjs", "text/javascript"},
		     {"css", "text/css"},
		     {"svg", "image/svg+xml"},
		     {"json", "application/json"},
	     }})
		svr.set_file_extension_and_mimetype_mapping(ext, mime);

	if (!svr.set_mount_point("/", staticDir))
		return false;

	svr.set_pre_routing_handler([this](const httplib::Request &req, httplib::Response &res) {
		// DNS 리바인딩 방어: Host는 정확히 우리 루프백 주소여야 한다.
		if (req.get_header_value("Host") != expectedHost_) {
			res.status = 403;
			return httplib::Server::HandlerResponse::Handled;
		}
		// 다른 오리진에서 온 요청은 거절한다 (같은 오리진 GET은 Origin을 안 보낼 수 있다).
		std::string origin = req.get_header_value("Origin");
		if (!origin.empty() && origin != expectedOrigin_) {
			res.status = 403;
			return httplib::Server::HandlerResponse::Handled;
		}
		if (req.path.rfind("/api/", 0) == 0) {
			std::string auth = req.get_header_value("Authorization");
			if (!ConstantTimeEquals(auth, "Bearer " + token_)) {
				res.status = 401;
				res.set_content(R"({"error":"unauthorized"})", "application/json");
				return httplib::Server::HandlerResponse::Handled;
			}
		}
		return httplib::Server::HandlerResponse::Unhandled;
	});

	svr.set_post_routing_handler([](const httplib::Request &, httplib::Response &res) {
		res.set_header("Cache-Control", "no-store");
		res.set_header("X-Content-Type-Options", "nosniff");
		res.set_header("Referrer-Policy", "no-referrer");
		res.set_header("Content-Security-Policy",
			       "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
			       "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
			       "base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
	});

	svr.Get("/api/hello", [this](const httplib::Request &, httplib::Response &res) {
		if (!helloSeen_.exchange(true) && callbacks_.onFirstHello)
			callbacks_.onFirstHello();
		res.set_content(callbacks_.helloJson(), "application/json");
	});

	svr.Get("/api/state", [this](const httplib::Request &, httplib::Response &res) {
		res.set_content(callbacks_.stateJson(), "application/json");
	});

	svr.Get("/api/events", [this](const httplib::Request &, httplib::Response &res) {
		auto lastVersion = std::make_shared<uint64_t>(0);
		res.set_chunked_content_provider(
			"text/event-stream", [this, lastVersion](size_t, httplib::DataSink &sink) {
				if (stopping_)
					return false;
				std::string json;
				uint64_t version = 0;
				bool changed = callbacks_.waitState(*lastVersion, 15000, json, version);
				if (stopping_ || !sink.is_writable())
					return false;
				std::string frame;
				if (changed) {
					*lastVersion = version;
					frame = "event: state\ndata: " + json + "\n\n";
				} else {
					frame = ": keep-alive\n\n";
				}
				return sink.write(frame.data(), frame.size());
			});
	});

	svr.Post("/api/pair", [this](const httplib::Request &req, httplib::Response &res) {
		auto [status, body] = callbacks_.pair(req.body);
		res.status = status;
		res.set_content(body, "application/json");
	});

	svr.Post("/api/unpair", [this](const httplib::Request &, httplib::Response &res) {
		auto [status, body] = callbacks_.unpair();
		res.status = status;
		res.set_content(body, "application/json");
	});

	svr.Get("/api/config", [this](const httplib::Request &, httplib::Response &res) {
		res.set_content(callbacks_.getConfig(), "application/json");
	});

	svr.Put("/api/config", [this](const httplib::Request &req, httplib::Response &res) {
		auto [status, body] = callbacks_.putConfig(req.body);
		res.status = status;
		res.set_content(body, "application/json");
	});

	port_ = svr.bind_to_any_port("127.0.0.1");
	if (port_ <= 0) {
		server_.reset();
		return false;
	}
	expectedHost_ = "127.0.0.1:" + std::to_string(port_);
	expectedOrigin_ = "http://" + expectedHost_;

	stopping_ = false;
	running_ = true;
	thread_ = std::thread([this]() {
		server_->listen_after_bind();
		running_ = false;
	});
	return true;
}

void BridgeServer::Stop()
{
	if (!server_)
		return;
	stopping_ = true;
	server_->stop();
	if (thread_.joinable())
		thread_.join();
	server_.reset();
	running_ = false;
}

} // namespace pokeclip
