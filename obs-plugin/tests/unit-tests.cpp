// 의존성 없는 최소 테스트 러너. OBS를 띄우지 않고 검증할 수 있는 것만 여기서 잰다:
// 페어링 코드 정규화 · keyint 옵션 제거 · streamid 파싱 · SRT URL · 브리지 보안 규칙(Host·Origin·토큰)·SSE.
#include "bridge-server.hpp"
#include "encoder-opts.hpp"
#include "pairing-code.hpp"
#include "srt-url.hpp"

#include <httplib.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <functional>
#include <string>
#include <vector>

namespace {

int g_failures = 0;
int g_checks = 0;

void Check(bool ok, const char *expr, const char *file, int line)
{
	g_checks++;
	if (!ok) {
		g_failures++;
		std::fprintf(stderr, "FAIL %s:%d  %s\n", file, line, expr);
	}
}

#define CHECK(expr) Check(static_cast<bool>(expr), #expr, __FILE__, __LINE__)
#define CHECK_EQ(a, b) Check((a) == (b), #a " == " #b, __FILE__, __LINE__)

struct TestCase {
	const char *name;
	std::function<void()> fn;
};

std::vector<TestCase> &Registry()
{
	static std::vector<TestCase> tests;
	return tests;
}

struct Register {
	Register(const char *name, std::function<void()> fn) { Registry().push_back({name, std::move(fn)}); }
};

#define TEST(name) \
	static void name(); \
	static Register reg_##name(#name, name); \
	static void name()

using namespace pokeclip;

// ---------------------------------------------------------------- 페어링 코드

TEST(pairing_code_matches_server_normalization)
{
	CHECK_EQ(NormalizePairingCode("abcd-efgh"), "ABCDEFGH");
	CHECK_EQ(NormalizePairingCode("4nh0-grxd"), "4NH0GRXD");
	CHECK_EQ(NormalizePairingCode("ILO0 ab12"), "1100AB12"); // I·L→1, O→0, 공백 제거
}

TEST(pairing_code_rejects_bad_input)
{
	CHECK_EQ(NormalizePairingCode(""), "");
	CHECK_EQ(NormalizePairingCode("ABCDEFG"), "");   // 7자
	CHECK_EQ(NormalizePairingCode("ABCDEFGHJ"), ""); // 9자
	CHECK_EQ(NormalizePairingCode("ABCD-EFGU"), ""); // U는 Crockford 알파벳 밖
	CHECK_EQ(NormalizePairingCode("ABCD_EFGH"), "");
}

// ---------------------------------------------------------------- keyint 옵션

TEST(strip_keyint_overrides_keeps_other_options)
{
	// 치지직 프리셋이 넣는 옵션은 건드리지 않는다
	CHECK_EQ(StripKeyintOverrides("tune=zerolatency scenecut=0"), "tune=zerolatency scenecut=0");
	CHECK_EQ(StripKeyintOverrides("keyint=250 tune=zerolatency min-keyint=25"), "tune=zerolatency");
	CHECK_EQ(StripKeyintOverrides("  gop=120   bf=2  idrint=60 keyint_min=10 "), "bf=2");
	CHECK_EQ(StripKeyintOverrides(""), "");
	CHECK_EQ(StripKeyintOverrides("keyintx=5"), "keyintx=5"); // 키 이름이 정확히 같을 때만
}

// ---------------------------------------------------------------- streamid · SRT URL

const std::string kStreamId = "#!::r=H44ZA5QEAN81PBDDSN3BZPWPPV,m=publish";

TEST(stream_token_and_hint)
{
	CHECK_EQ(StreamTokenOf(kStreamId), "H44ZA5QEAN81PBDDSN3BZPWPPV");
	CHECK_EQ(KeyHintOf(kStreamId), "WPPV");
	CHECK_EQ(StreamTokenOf("#!::m=publish,r=ABC123"), "ABC123"); // 순서 무관
	CHECK_EQ(StreamTokenOf("publish:legacy"), "");
	CHECK_EQ(KeyHintOf("#!::r=AB,m=publish"), "");
}

TEST(safe_stream_id)
{
	CHECK(IsSafeStreamId(kStreamId));
	CHECK(!IsSafeStreamId(""));
	CHECK(!IsSafeStreamId("#!::r=ABC&passphrase=x,m=publish")); // '&'는 URL 파서가 값을 끊는다
	CHECK(!IsSafeStreamId("#!::r=AB+C,m=publish"));             // '+'는 공백으로 바뀐다
	CHECK(!IsSafeStreamId("#!::r=ABC,m=request"));              // 송출 모드가 아니다
	CHECK(!IsSafeStreamId("#!::m=publish"));                    // r= 없음
}

TEST(srt_server_url_has_no_secrets)
{
	PluginConfig c;
	c.ingestHost = "ingest.pokeclip.com";
	c.ingestPort = 8890;
	c.latencyMs = 1000;
	c.streamId = kStreamId;
	c.passphrase = "0123456789abcdef0123456789abcdef";
	c.sendPassphrase = true;
	std::string url = BuildSrtServerUrl(c);
	CHECK_EQ(url, "srt://ingest.pokeclip.com:8890?latency=1000000&pkt_size=1316&pbkeylen=32");
	CHECK(url.find(c.passphrase) == std::string::npos);
	CHECK(url.find("streamid") == std::string::npos);

	c.sendPassphrase = false;
	CHECK_EQ(BuildSrtServerUrl(c), "srt://ingest.pokeclip.com:8890?latency=1000000&pkt_size=1316");
}

// ---------------------------------------------------------------- 토큰

TEST(token_is_64_hex_and_unique)
{
	std::string a = GenerateToken();
	std::string b = GenerateToken();
	CHECK_EQ(a.size(), 64u);
	CHECK(a.find_first_not_of("0123456789abcdef") == std::string::npos);
	CHECK(a != b);
	CHECK(ConstantTimeEquals(a, a));
	CHECK(!ConstantTimeEquals(a, b));
	CHECK(!ConstantTimeEquals(a, a.substr(1)));
}

// ---------------------------------------------------------------- 브리지 서버 (실제 HTTP)

struct BridgeFixture {
	std::filesystem::path dir;
	BridgeServer server;
	std::string token;
	std::atomic<int> pairCalls{0};
	std::string lastPairBody;
	std::atomic<uint64_t> stateVersion{1};
	std::mutex mutex;
	std::condition_variable changed;
	bool shutdown = false;

	BridgeFixture()
	{
		dir = std::filesystem::temp_directory_path() /
		      ("pokeclip-bridge-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
		std::filesystem::create_directories(dir);
		std::ofstream(dir / "index.html") << "<!doctype html><title>dock</title>";

		BridgeCallbacks cb;
		// 실제 AppState::WaitForChange처럼 새 버전·종료 신호가 올 때까지 막힌다.
		cb.waitState = [this](uint64_t since, int timeoutMs, std::string &json, uint64_t &version) {
			std::unique_lock lock(mutex);
			bool ready = changed.wait_for(lock, std::chrono::milliseconds(timeoutMs),
						      [&] { return shutdown || stateVersion > since; });
			if (!ready || shutdown)
				return false;
			version = stateVersion;
			json = R"({"phase":"idle","version":)" + std::to_string(version) + "}";
			return true;
		};
		cb.stateJson = [] { return std::string(R"({"phase":"idle"})"); };
		cb.helloJson = [] { return std::string(R"({"plugin":"pokeclip-obs"})"); };
		cb.pair = [this](const std::string &body) -> BridgeCallbacks::Reply {
			pairCalls++;
			lastPairBody = body;
			return {410, R"({"ok":false,"reason":"expired"})"};
		};
		cb.unpair = []() -> BridgeCallbacks::Reply { return {200, R"({"ok":true})"}; };
		cb.getConfig = [] { return std::string("{}"); };
		cb.putConfig = [](const std::string &) -> BridgeCallbacks::Reply { return {200, "{}"}; };
		server.Start(dir.string(), cb);

		std::string url = server.DockUrl();
		token = url.substr(url.find("#token=") + 7);
	}

	void Shutdown()
	{
		{
			std::lock_guard lock(mutex);
			shutdown = true;
		}
		changed.notify_all();
	}

	~BridgeFixture()
	{
		Shutdown();
		server.Stop();
		std::error_code ec;
		std::filesystem::remove_all(dir, ec);
	}

	httplib::Client Client() const
	{
		httplib::Client cli("127.0.0.1", server.Port());
		cli.set_connection_timeout(2, 0);
		cli.set_read_timeout(3, 0);
		return cli;
	}

	httplib::Headers Auth() const { return {{"Authorization", "Bearer " + token}}; }
};

TEST(bridge_binds_loopback_and_serves_static)
{
	BridgeFixture f;
	CHECK(f.server.Running());
	CHECK(f.server.Port() > 0);
	CHECK(f.server.DockUrl().rfind("http://127.0.0.1:", 0) == 0);
	CHECK_EQ(f.token.size(), 64u);

	auto cli = f.Client();
	auto res = cli.Get("/");
	CHECK(res);
	if (res) {
		CHECK_EQ(res->status, 200);
		CHECK(res->body.find("dock") != std::string::npos);
		CHECK(res->get_header_value("Content-Security-Policy").find("default-src 'self'") != std::string::npos);
		CHECK_EQ(res->get_header_value("Cache-Control"), "no-store");
	}
}

TEST(bridge_rejects_foreign_host_and_origin)
{
	BridgeFixture f;
	auto cli = f.Client();

	auto badHost = cli.Get("/", httplib::Headers{{"Host", "evil.example:" + std::to_string(f.server.Port())}});
	CHECK(badHost && badHost->status == 403);

	auto badOrigin = cli.Get("/api/hello", httplib::Headers{{"Authorization", "Bearer " + f.token},
								 {"Origin", "http://evil.example"}});
	CHECK(badOrigin && badOrigin->status == 403);

	std::string sameOrigin = "http://127.0.0.1:" + std::to_string(f.server.Port());
	auto good = cli.Get("/api/hello", httplib::Headers{{"Authorization", "Bearer " + f.token}, {"Origin", sameOrigin}});
	CHECK(good && good->status == 200);
}

TEST(bridge_api_requires_token)
{
	BridgeFixture f;
	auto cli = f.Client();

	CHECK(!f.server.HelloSeen());
	auto none = cli.Get("/api/hello");
	CHECK(none && none->status == 401);
	auto wrong = cli.Get("/api/hello", httplib::Headers{{"Authorization", "Bearer " + std::string(64, '0')}});
	CHECK(wrong && wrong->status == 401);
	auto unpairNoAuth = cli.Post("/api/unpair", "", "application/json");
	CHECK(unpairNoAuth && unpairNoAuth->status == 401);
	CHECK(!f.server.HelloSeen()); // 거절된 요청은 페이지 연결로 치지 않는다

	auto ok = cli.Get("/api/hello", f.Auth());
	CHECK(ok && ok->status == 200);
	CHECK(f.server.HelloSeen());
}

TEST(bridge_pair_passes_body_and_status_through)
{
	BridgeFixture f;
	auto cli = f.Client();
	auto res = cli.Post("/api/pair", f.Auth(), R"({"code":"ABCD-EFGH"})", "application/json");
	CHECK(res);
	if (res) {
		CHECK_EQ(res->status, 410);
		CHECK(res->body.find("expired") != std::string::npos);
	}
	CHECK_EQ(f.pairCalls.load(), 1);
	CHECK_EQ(f.lastPairBody, R"({"code":"ABCD-EFGH"})");

	std::string big(32 * 1024, 'x'); // 16KB 상한 초과
	auto tooBig = cli.Post("/api/pair", f.Auth(), big, "application/json");
	CHECK(tooBig && tooBig->status == 413);
	CHECK_EQ(f.pairCalls.load(), 1);
}

TEST(bridge_sse_sends_state_first)
{
	BridgeFixture f;
	auto cli = f.Client();
	std::string received;
	auto headers = f.Auth();
	auto res = cli.Get("/api/events", headers, [&](const char *data, size_t len) {
		received.append(data, len);
		return received.find("\n\n") == std::string::npos; // 첫 프레임을 받으면 끊는다
	});
	CHECK(received.rfind("event: state\ndata: {", 0) == 0);
	CHECK(received.find(R"("version":1)") != std::string::npos);
}

TEST(bridge_stop_is_prompt_with_open_sse)
{
	auto f = std::make_unique<BridgeFixture>();
	std::thread client([&] {
		auto cli = f->Client();
		cli.set_read_timeout(20, 0);
		cli.Get("/api/events", f->Auth(), [](const char *, size_t) { return true; });
	});
	std::this_thread::sleep_for(std::chrono::milliseconds(500)); // 첫 프레임 뒤 15초 대기에 들어간 상태
	auto start = std::chrono::steady_clock::now();
	// 플러그인 종료 순서: AppState::Shutdown()으로 대기를 깨운 뒤 브리지를 멈춘다 (plugin-main.cpp EXIT·unload).
	f->Shutdown();
	f->server.Stop();
	auto elapsed = std::chrono::steady_clock::now() - start;
	client.join();
	// 대기를 깨우지 않으면 워커가 keep-alive 15초를 다 채워야 풀려 OBS 종료가 그만큼 멈춘다.
	CHECK(elapsed < std::chrono::seconds(2));
	CHECK(!f->server.Running());
	std::printf("  (bridge stop with open SSE took %lld ms)\n",
		    static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count()));
}

} // namespace

int main()
{
	for (auto &t : Registry()) {
		int before = g_failures;
		std::printf("[ RUN  ] %s\n", t.name);
		t.fn();
		std::printf("[ %s ] %s\n", g_failures == before ? " OK " : "FAIL", t.name);
	}
	std::printf("\n%zu tests, %d checks, %d failures\n", Registry().size(), g_checks, g_failures);
	return g_failures == 0 ? 0 : 1;
}
