// 의존성 없는 최소 테스트 러너. OBS를 띄우지 않고 검증할 수 있는 것만 여기서 잰다:
// 페어링 코드 정규화 · keyint 옵션 제거 · streamid 파싱 · SRT URL · 브리지 보안 규칙(Host·Origin·토큰)·SSE ·
// 오디오 트랙 자동 배정(A2) · 핫키 마킹 규칙(A4) · 재시도 정책과 「다시 연결」 조건(A5) · 종료 신호 규칙(A3·4D).
#include "app-state.hpp"
#include "audio-assign.hpp"
#include "bridge-server.hpp"
#include "config.hpp"
#include "private-file.hpp"
#include "encoder-opts.hpp"
#include "end-signal-policy.hpp"
#include "mark-policy.hpp"
#include "pairing-code.hpp"
#include "retry-policy.hpp"
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
#include <iterator>
#include <string>
#include <vector>

#ifndef _WIN32
#include <sys/stat.h>
#endif

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

TEST(only_gop_gates_the_send_and_resolution_is_advisory)
{
	EncoderChecks c;
	CHECK(!ChecksAllowSend(c)); // 아직 점검 전

	c.gop2s = true;
	c.res1080p = false; // 720p 본방 — 경고만 하고 보낸다 (ADR-020, POK-268)
	c.width = 1280;
	c.height = 720;
	CHECK(ChecksAllowSend(c));

	c.gop2s = false; // GOP 2초를 못 맞추면 1080p여도 보내지 않는다
	c.res1080p = true;
	CHECK(!ChecksAllowSend(c));
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

TEST(ingest_host_rejects_port_outside_brackets)
{
	CHECK(IsSafeIngestHost("ingest.pokeclip.com"));
	CHECK(IsSafeIngestHost("127.0.0.1"));
	CHECK(IsSafeIngestHost("[::1]"));
	CHECK(IsSafeIngestHost("[2001:db8::7]"));
	CHECK(!IsSafeIngestHost("ingest.pokeclip.com:8890")); // srt://ingest.pokeclip.com:8890:8890 이 된다
	CHECK(!IsSafeIngestHost("::1"));                      // IPv6는 대괄호로만
	CHECK(!IsSafeIngestHost("[::1]:8890"));
	CHECK(!IsSafeIngestHost("[]"));
	CHECK(!IsSafeIngestHost(""));
	CHECK(!IsSafeIngestHost("ingest pokeclip"));
	CHECK(!IsSafeIngestHost("host?x=1"));
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
	std::atomic<int> markCalls{0};
	std::atomic<int> sendNowCalls{0};
	std::atomic<int> stopRetryCalls{0};
	std::string assignAudioBody; // 마지막 손 배정 요청 본문
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
		cb.mark = [this]() -> BridgeCallbacks::Reply {
			markCalls++;
			return {409, R"({"ok":false,"reason":"mark_not_live"})"};
		};
		cb.sendNow = [this]() -> BridgeCallbacks::Reply {
			sendNowCalls++;
			return {202, R"({"ok":true})"};
		};
		cb.assignAudio = [this](const std::string &body) -> BridgeCallbacks::Reply {
			assignAudioBody = body;
			return {202, "{\"ok\":true}"};
		};
		cb.stopRetry = [this]() -> BridgeCallbacks::Reply {
			stopRetryCalls++;
			return {409, R"({"ok":false,"reason":"not_retrying"})"};
		};
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

TEST(bridge_mark_requires_token_and_passes_status)
{
	BridgeFixture f;
	auto cli = f.Client();
	auto anon = cli.Post("/api/mark", "", "application/json");
	CHECK(anon && anon->status == 401);
	CHECK_EQ(f.markCalls.load(), 0);

	auto res = cli.Post("/api/mark", f.Auth(), "", "application/json");
	CHECK(res);
	if (res) {
		CHECK_EQ(res->status, 409);
		CHECK(res->body.find("mark_not_live") != std::string::npos);
	}
	CHECK_EQ(f.markCalls.load(), 1);
}

TEST(bridge_audio_assign_requires_token_and_passes_body)
{
	BridgeFixture f;
	auto cli = f.Client();
	const std::string body = R"({"key":"uuid:bgm","track":4})";
	auto anon = cli.Post("/api/audio/assign", body, "application/json");
	CHECK(anon && anon->status == 401);
	CHECK(f.assignAudioBody.empty());

	auto res = cli.Post("/api/audio/assign", f.Auth(), body, "application/json");
	CHECK(res);
	if (res) {
		CHECK_EQ(res->status, 202);
		CHECK(res->body.find("\"ok\":true") != std::string::npos);
	}
	CHECK_EQ(f.assignAudioBody, body); // 본문이 그대로 콜백에 간다 — 열쇠·트랙 해석은 plugin-main 쪽
}

TEST(bridge_send_now_and_stop_retry_require_token_and_pass_status)
{
	BridgeFixture f;
	auto cli = f.Client();
	auto anonSend = cli.Post("/api/send-now", "", "application/json");
	CHECK(anonSend && anonSend->status == 401);
	auto anonStop = cli.Post("/api/stop-retry", "", "application/json");
	CHECK(anonStop && anonStop->status == 401);
	CHECK_EQ(f.sendNowCalls.load(), 0);
	CHECK_EQ(f.stopRetryCalls.load(), 0);

	auto sent = cli.Post("/api/send-now", f.Auth(), "", "application/json");
	CHECK(sent && sent->status == 202);
	CHECK_EQ(f.sendNowCalls.load(), 1);

	auto stopped = cli.Post("/api/stop-retry", f.Auth(), "", "application/json");
	CHECK(stopped);
	if (stopped) {
		CHECK_EQ(stopped->status, 409);
		CHECK(stopped->body.find("not_retrying") != std::string::npos);
	}
	CHECK_EQ(f.stopRetryCalls.load(), 1);

	// 읽기 요청으로는 시작되지 않는다
	auto viaGet = cli.Get("/api/send-now", f.Auth());
	CHECK(viaGet && viaGet->status == 404);
	CHECK_EQ(f.sendNowCalls.load(), 1);
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


// ---------------------------------------------------------------- 오디오 트랙 자동 배정 (A2)

AudioSourceInfo Src(const std::string &key, const std::string &name, AudioKind kind, int order,
		    uint32_t mixers = 0x3F, bool active = true, bool monitorOnly = false, bool showing = true)
{
	AudioSourceInfo s;
	s.key = key;
	s.name = name;
	s.kind = kind;
	s.order = order;
	s.mixers = mixers;
	s.audioActive = active;
	s.monitorOnly = monitorOnly;
	s.showing = showing;
	return s;
}

// 다른 장면에만 있는 소스 — 오디오는 넘기지만 지금 방송 화면에 없다.
AudioSourceInfo Offscreen(const std::string &key, const std::string &name, AudioKind kind, int order,
			  uint32_t mixers = 0x3F)
{
	return Src(key, name, kind, order, mixers, true, false, false);
}

AudioTrackMapEntry Entry(const std::string &key, int slot, int64_t assignedAt, int64_t lastSeen = 0)
{
	return {key, slot, key, assignedAt, lastSeen ? lastSeen : assignedAt};
}

int SlotOf(const AssignmentResult &r, const std::string &key)
{
	for (int k = 1; k <= kStemSlots; k++) {
		const std::vector<std::string> &keys = r.slotKeys[static_cast<size_t>(k)];
		if (std::find(keys.begin(), keys.end(), key) != keys.end())
			return k;
	}
	return 0;
}

uint32_t WriteOf(const AssignmentResult &r, const std::string &key, uint32_t unchanged)
{
	for (const MixerWrite &w : r.writes) {
		if (w.key == key)
			return w.mixers;
	}
	return unchanged;
}

// 쓰기가 없으면 빈 값 — 앞선 개수 검사가 실패해도 러너가 죽지 않고 요약까지 가게.
MixerWrite FirstWrite(const std::vector<MixerWrite> &writes)
{
	return writes.empty() ? MixerWrite{} : writes[0];
}

bool MapHas(const AssignmentResult &r, const std::string &key)
{
	for (const AudioTrackMapEntry &e : r.mapNext) {
		if (e.key == key)
			return true;
	}
	return false;
}

// 결과를 소스 비트에 반영한다 — 다음 계산의 입력이 된다.
std::vector<AudioSourceInfo> Applied(std::vector<AudioSourceInfo> sources, const AssignmentResult &r)
{
	for (AudioSourceInfo &s : sources)
		s.mixers = WriteOf(r, s.key, s.mixers);
	return sources;
}

TEST(audio_classify_source_ids)
{
	CHECK(ClassifyAudioSourceId("coreaudio_input_capture") == AudioKind::Mic);
	CHECK(ClassifyAudioSourceId("wasapi_input_capture") == AudioKind::Mic);
	CHECK(ClassifyAudioSourceId("wasapi_output_capture") == AudioKind::Desktop);
	CHECK(ClassifyAudioSourceId("wasapi_process_output_capture") == AudioKind::App);
	CHECK(ClassifyAudioSourceId("sck_audio_capture") == AudioKind::App);
	CHECK(ClassifyAudioSourceId("ffmpeg_source") == AudioKind::Media);
	CHECK(ClassifyAudioSourceId("browser_source") == AudioKind::Browser);
	CHECK(ClassifyAudioSourceId("dshow_input") == AudioKind::Other);
	CHECK(ClassifyGlobalChannel(1) == AudioKind::Desktop);
	CHECK(ClassifyGlobalChannel(2) == AudioKind::Desktop);
	CHECK(ClassifyGlobalChannel(3) == AudioKind::Mic);
	CHECK(ClassifyGlobalChannel(6) == AudioKind::Mic);
	CHECK(AudioKindPriority(AudioKind::Mic) < AudioKindPriority(AudioKind::Desktop));
	CHECK(AudioKindPriority(AudioKind::Browser) < AudioKindPriority(AudioKind::Other));
}

// 트랙 1(믹서 0)과 쓰지 않는 상위 비트(6·7)는 절대 바꾸지 않는다.
TEST(audio_desired_mixers_keeps_track1_and_high_bits)
{
	CHECK_EQ(DesiredMixers(0xFF, 2), 0xC5u);
	CHECK_EQ(DesiredMixers(0x3F, 0), 0x01u);
	CHECK_EQ(DesiredMixers(0x01, 5), 0x21u);
	CHECK_EQ(DesiredMixers(0x00, 1), 0x02u);
	CHECK_EQ(DesiredMixers(0x3E, 0), 0x00u); // 트랙 1이 꺼져 있던 소스는 꺼진 채
}

// 본방 트랙(여기서는 트랙 2 = 믹서 1)은 켜진 채든 꺼진 채든 그대로 두고, 그 자리를 스템으로 쓰지 않는다.
TEST(audio_desired_mixers_keeps_main_stream_track)
{
	CHECK_EQ(DesiredMixers(0x3F, 2, 0x02), 0x07u); // 트랙 1 + 본방 트랙 2 그대로 + 스템 트랙 3
	CHECK_EQ(DesiredMixers(0x3F, 1, 0x02), 0x03u); // 본방 트랙을 자리로 받아도 켜지 않는다
	CHECK_EQ(DesiredMixers(0x01, 3, 0x02), 0x09u); // 본방 트랙에 없던 소스는 없는 채
}

TEST(audio_first_run_orders_by_priority)
{
	AssignmentInput in;
	in.now = 100;
	in.sources = {Src("uuid:bgm", "BGM", AudioKind::Media, 101), Src("uuid:alert", "알림", AudioKind::Browser, 100),
		      Src("ch:1", "데스크탑 오디오", AudioKind::Desktop, 1), Src("ch:3", "마이크/보조", AudioKind::Mic, 3)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "ch:3"), 1);
	CHECK_EQ(SlotOf(r, "ch:1"), 2);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 3);
	CHECK_EQ(SlotOf(r, "uuid:alert"), 4);
	CHECK(r.slotKeys[5].empty());
	CHECK(r.overflowKeys.empty());
	CHECK_EQ(r.mapNext.size(), 4u);
	CHECK(r.mapChanged);
	CHECK_EQ(WriteOf(r, "ch:3", 0), 0x03u); // 트랙 1 + 트랙 2
	CHECK_EQ(WriteOf(r, "uuid:alert", 0), 0x11u);
}

TEST(audio_assignment_is_idempotent)
{
	AssignmentInput in;
	in.now = 100;
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3), Src("uuid:bgm", "BGM", AudioKind::Media, 100)};
	AssignmentResult first = ComputeAssignment(in);

	AssignmentInput again;
	again.now = 200;
	again.sources = Applied(in.sources, first);
	again.map = first.mapNext;
	AssignmentResult second = ComputeAssignment(again);
	CHECK(second.writes.empty());
	CHECK(!second.mapChanged);
	CHECK_EQ(SlotOf(second, "ch:3"), 1);
	CHECK_EQ(SlotOf(second, "uuid:bgm"), 2);
}

// 기억한 자리가 우선순위보다 앞선다 — 방송 간에 트랙이 바뀌지 않는 것이 이 기능의 핵심이다.
TEST(audio_remembered_slot_beats_priority)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:bgm", 1, 10)};
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3), Src("uuid:bgm", "BGM", AudioKind::Media, 100)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 1);
	CHECK_EQ(SlotOf(r, "ch:3"), 2);
}

// 소스가 자리보다 많으면(7개) 믹스만으로 밀어내지 않고 묶는다 — 아직 자리 없는 종류가 먼저 빈 트랙을 받고,
// 같은 종류가 있는 소스(마이크 2)는 그 트랙에, 같은 종류가 없는 소스(캡처보드)는 우선순위가 가장 낮은 트랙(브라우저)에.
TEST(audio_overflow_groups_by_kind)
{
	AssignmentInput in;
	in.now = 100;
	in.sources = {Src("uuid:other", "캡처보드", AudioKind::Other, 100), Src("uuid:alert", "알림", AudioKind::Browser, 101),
		      Src("uuid:bgm", "BGM", AudioKind::Media, 102),       Src("uuid:discord", "Discord", AudioKind::App, 103),
		      Src("ch:1", "데스크탑", AudioKind::Desktop, 1),       Src("ch:3", "마이크", AudioKind::Mic, 3),
		      Src("ch:4", "마이크 2", AudioKind::Mic, 4)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK(r.overflowKeys.empty());
	CHECK_EQ(SlotOf(r, "ch:3"), 1);
	CHECK_EQ(SlotOf(r, "ch:4"), 1); // 마이크 트랙에 묶인다
	CHECK_EQ(SlotOf(r, "ch:1"), 2);
	CHECK_EQ(SlotOf(r, "uuid:discord"), 3);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 4);
	CHECK_EQ(SlotOf(r, "uuid:alert"), 5);
	CHECK_EQ(SlotOf(r, "uuid:other"), 5); // 같은 종류가 없다 — 가장 덜 가치 있는 트랙에
	CHECK_EQ(WriteOf(r, "ch:4", 0x3F), 0x03u);
	CHECK_EQ(WriteOf(r, "uuid:other", 0x3F), 0x21u);
	CHECK(MapHas(r, "uuid:other"));
	CHECK_EQ(r.slotKeys[1].size(), 2u);

	// 같은 입력을 다시 넣으면 그대로다 — 묶인 소스가 매번 줄을 다시 서도 자리는 같다.
	AssignmentInput again = in;
	again.sources = Applied(in.sources, r);
	again.map = r.mapNext;
	AssignmentResult r2 = ComputeAssignment(again);
	CHECK(r2.writes.empty());
	CHECK(!r2.mapChanged);
}

// 자리가 남는 동안은 같은 종류라도 제 트랙을 받는다 — 단, 아직 자리 없는 종류가 먼저다.
TEST(audio_new_kind_gets_free_track_before_same_kind_doubles_up)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("ch:3", 1, 10, 90), Entry("ch:1", 2, 10, 90), Entry("uuid:discord", 3, 10, 90),
		  Entry("uuid:bgm", 4, 10, 90)};
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3),         Src("ch:1", "데스크탑", AudioKind::Desktop, 1),
		      Src("uuid:discord", "Discord", AudioKind::App, 103), Src("uuid:bgm", "BGM", AudioKind::Media, 102),
		      Src("ch:4", "마이크 2", AudioKind::Mic, 4),           Src("uuid:alert", "알림", AudioKind::Browser, 101)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "ch:4"), 1);       // 빈 트랙이 하나뿐 — 브라우저(새 종류) 몫이라 마이크 트랙에 묶인다
	CHECK_EQ(SlotOf(r, "uuid:alert"), 5); // 새 종류가 빈 트랙을 받는다

	AssignmentInput roomy = in;
	roomy.sources.pop_back(); // 알림이 없으면 마이크 2가 빈 트랙을 받는다
	AssignmentResult r2 = ComputeAssignment(roomy);
	CHECK_EQ(SlotOf(r2, "ch:4"), 5);
}

// 같은 자리를 기억하는 소스는 빈 트랙이 있어도 함께 앉는다 — 독에서 묶어 둔 것을 플러그인이 가르지 않는다.
TEST(audio_grouped_sources_stay_together_even_with_room)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("ch:3", 1, 10, 90), Entry("ch:4", 1, 50, 90)};
	in.map[0].name = "마이크"; // 이름 갱신이 mapChanged를 켜지 않게
	in.map[1].name = "마이크 2";
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x03), Src("ch:4", "마이크 2", AudioKind::Mic, 4, 0x03)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "ch:3"), 1);
	CHECK_EQ(SlotOf(r, "ch:4"), 1);
	CHECK(r.writes.empty());
	CHECK(!r.mapChanged);
}

// 지금 없는 소스의 기억은 자리를 잡지 않지만 버리지도 않는다. 새 소스는 아무 기억도 없는 트랙을 먼저 받아
// 돌아올 자리를 비워 두고, 돌아오면 기억대로 앉는다.
TEST(audio_absent_memory_frees_slot_and_comes_back)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:a", 1, 10), Entry("uuid:b", 2, 10)};
	in.sources = {Src("uuid:b", "B", AudioKind::Media, 100), Src("uuid:c", "C", AudioKind::Media, 101)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:b"), 2);
	CHECK_EQ(SlotOf(r, "uuid:c"), 3); // 트랙 2는 없는 A의 기억이 적어 둔 자리 — 비워 둔다
	CHECK(MapHas(r, "uuid:a"));

	AssignmentInput back;
	back.now = 200;
	back.map = r.mapNext;
	back.sources = Applied(in.sources, r);
	back.sources.push_back(Src("uuid:a", "A", AudioKind::Media, 102, 0x01));
	AssignmentResult r2 = ComputeAssignment(back);
	CHECK_EQ(SlotOf(r2, "uuid:a"), 1);
	CHECK_EQ(SlotOf(r2, "uuid:c"), 3);
	CHECK_EQ(SlotOf(r2, "uuid:b"), 2);

	// 적어 둔 자리뿐이면 그 자리를 쓴다 — 없는 소스가 트랙을 영영 막지는 않는다.
	AssignmentInput full;
	full.now = 100;
	for (int k = 1; k <= kStemSlots; k++)
		full.map.push_back(Entry("uuid:gone" + std::to_string(k), k, 10));
	full.sources = {Src("uuid:new", "New", AudioKind::Media, 100)};
	CHECK_EQ(SlotOf(ComputeAssignment(full), "uuid:new"), 1);
}

// 처음 쓰기 전의 체크를 남긴다 — 이미 남긴 것은 덮지 않고, 쓰지 않은 소스는 남기지 않는다.
TEST(audio_backup_keeps_checks_from_before_first_write)
{
	AssignmentInput in;
	in.now = 100;
	// 솔로는 이미 자기 자리(트랙 4)에만 있어 쓸 것이 없다
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x07),
		      Src("uuid:game", "게임", AudioKind::App, 100, 0x03),
		      Src("uuid:solo", "솔로", AudioKind::Media, 101, 0x09)};
	in.map = {Entry("uuid:solo", 3, 5)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK(r.backupChanged);
	CHECK_EQ(r.backupNext.size(), 2u);
	CHECK((r.backupNext[0] == AudioMixerBackup{"ch:3", 0x07}));
	CHECK((r.backupNext[1] == AudioMixerBackup{"uuid:game", 0x03}));

	AssignmentInput again;
	again.now = 200;
	again.map = r.mapNext;
	again.backup = r.backupNext;
	again.sources = Applied(in.sources, r);
	again.sources[0].mixers = 0x3F; // OBS에서 손으로 바꿨다 — 되돌아가지만 원래 값은 처음 것 그대로
	AssignmentResult r2 = ComputeAssignment(again);
	CHECK(!r2.backupChanged);
	CHECK((r2.backupNext == r.backupNext));
}

// 본방 트랙이 바뀌면 소스마다 원래 체크를 오간다: 들어오는 트랙은 되돌리고, 빠지는 트랙은 그동안 스트리머가
// 고친 체크를 원래 값으로 옮긴다 — 다시 본방 트랙이 되면 처음 스냅샷이 아니라 고친 값이 돌아온다.
TEST(audio_reserved_sync_restores_entering_and_keeps_edits_of_leaving)
{
	std::vector<AudioSourceInfo> sources = {Src("uuid:bgm", "BGM", AudioKind::Media, 100, 0x05)};
	std::vector<AudioMixerBackup> backup = {{"uuid:bgm", 0x3F, 0}};

	ReservedSync on = SyncReservedTracks(sources, backup, 0x02); // VOD 트랙(트랙 2)을 켰다
	CHECK_EQ(on.writes.size(), 1u);
	CHECK_EQ(FirstWrite(on.writes).mixers, 0x07u); // 트랙 2를 원래대로(켜짐)
	CHECK((on.backupNext[0] == AudioMixerBackup{"uuid:bgm", 0x3F, 0x02}));

	sources[0].mixers = 0x05; // 스트리머가 VOD 트랙에서 BGM을 뺐다
	ReservedSync off = SyncReservedTracks(sources, on.backupNext, 0x00); // VOD 트랙을 껐다
	CHECK(off.writes.empty());
	CHECK((off.backupNext[0] == AudioMixerBackup{"uuid:bgm", 0x3D, 0x00})); // 뺀 것을 원래 값으로

	sources[0].mixers = 0x09;                                             // 그동안 자동 배정이 트랙 4 스템으로
	ReservedSync again = SyncReservedTracks(sources, off.backupNext, 0x02); // 다시 VOD 트랙
	CHECK(again.writes.empty()); // BGM은 VOD에 다시 실리지 않는다
	CHECK_EQ(again.backupNext[0].restored, 0x02u);
}

// 그때 없던(다른 장면 컬렉션의) 소스는 건드리지 않고, 돌아왔을 때 맞춘다.
TEST(audio_reserved_sync_waits_for_absent_source)
{
	std::vector<AudioMixerBackup> backup = {{"uuid:a", 0x3F, 0}, {"uuid:b", 0x3F, 0}};
	std::vector<AudioSourceInfo> onlyA = {Src("uuid:a", "A", AudioKind::Media, 100, 0x05)};
	ReservedSync first = SyncReservedTracks(onlyA, backup, 0x02);
	CHECK_EQ(first.writes.size(), 1u);
	CHECK_EQ(first.backupNext[1].restored, 0u); // B는 아직

	std::vector<AudioSourceInfo> onlyB = {Src("uuid:b", "B", AudioKind::Media, 100, 0x09)};
	ReservedSync later = SyncReservedTracks(onlyB, first.backupNext, 0x02);
	CHECK_EQ(later.writes.size(), 1u);
	CHECK_EQ(FirstWrite(later.writes).mixers, 0x0Bu);
	CHECK_EQ(later.backupNext[1].restored, 0x02u);
}

// 전역 장치는 컬렉션마다 따로 저장된다 — 백업 열쇠를 나눠, 다른 컬렉션 마이크의 원래 체크를 쓰지 않는다.
TEST(audio_backup_key_scopes_global_device_by_collection)
{
	AssignmentInput in;
	in.now = 100;
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x3F)};
	in.sources[0].backupKey = "ch:3@방송용";
	in.backup = {{"ch:3@녹화용", 0x03, 0}};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(r.backupNext.size(), 2u);
	CHECK((r.backupNext[1] == AudioMixerBackup{"ch:3@방송용", 0x3F, 0}));

	std::vector<MixerWrite> writes = RestoreWrites(in.sources, {{"ch:3@녹화용", 0x03, 0}}, 0x3E);
	CHECK(writes.empty());
}

TEST(audio_backup_follows_renamed_collection)
{
	// OBS는 새 이름으로 컬렉션을 다시 불러온 뒤에 RENAMED를 보낸다 — 그 사이 계산이 자동 배정된 체크(0x09)를
	// 새 이름의 원래 값처럼 남겼다.
	std::vector<AudioMixerBackup> backup = {{"ch:3@방송용", 0x03, 0},
						{"ch:3@새이름", 0x09, 0},
						{"ch:1@새이름", 0x11, 0},
						{"ch:1@녹화용", 0x07, 0},
						{"uuid:game", 0x05, 0}};
	CHECK(RenameBackupCollection(backup, "방송용", "새이름"));
	CHECK_EQ(backup.size(), 4u);
	CHECK((backup[0] == AudioMixerBackup{"ch:3@새이름", 0x03, 0}));
	CHECK((backup[1] == AudioMixerBackup{"ch:1@새이름", 0x11, 0})); // 옮겨 오지 않는 채널은 그대로
	CHECK((backup[2] == AudioMixerBackup{"ch:1@녹화용", 0x07, 0})); // 다른 컬렉션도 그대로
	CHECK((backup[3] == AudioMixerBackup{"uuid:game", 0x05, 0}));   // uuid 소스는 컬렉션을 안 단다

	// 옮긴 열쇠로 되돌리기가 이름을 바꾸기 전의 원래 체크를 찾는다.
	std::vector<AudioSourceInfo> sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x09)};
	sources[0].backupKey = "ch:3@새이름";
	std::vector<MixerWrite> writes = RestoreWrites(sources, backup, 0x3E);
	CHECK_EQ(writes.size(), 1u);
	CHECK_EQ(FirstWrite(writes).mixers, 0x03u);

	std::vector<AudioMixerBackup> untouched = {{"ch:1@새이름", 0x07, 0}, {"ch:3@다른이름", 0x03, 0}};
	CHECK(!RenameBackupCollection(untouched, "방송용", "새이름")); // 옮길 것이 없다
	CHECK_EQ(untouched.size(), 2u);
	CHECK(!RenameBackupCollection(backup, "새이름", "새이름"));
}

TEST(audio_restore_puts_back_original_checks)
{
	CHECK_EQ(RestoredMixers(0x03, 0x2D, 0x3E), 0x2Du); // 트랙 2~6 전부
	CHECK_EQ(RestoredMixers(0x05, 0x03, 0x02), 0x07u); // 트랙 2만 — 새 본방 트랙
	CHECK_EQ(RestoredMixers(0x81, 0x3E, 0xFF), 0xBFu); // 트랙 1·상위 비트는 bits에 있어도 안 건드린다

	std::vector<AudioSourceInfo> sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x03),
						Src("uuid:game", "게임", AudioKind::App, 100, 0x05),
						Src("uuid:new", "새 소스", AudioKind::Media, 101, 0x09)};
	std::vector<AudioMixerBackup> backup = {{"ch:3", 0x07}, {"uuid:game", 0x05}, {"uuid:gone", 0x3F}};
	std::vector<MixerWrite> writes = RestoreWrites(sources, backup, 0x3E);
	CHECK_EQ(writes.size(), 1u); // 게임은 이미 원래대로, 새 소스는 원래 값이 없다, 없는 소스는 건너뛴다
	CHECK_EQ(FirstWrite(writes).key, std::string("ch:3"));
	CHECK_EQ(FirstWrite(writes).mixers, 0x07u);
}

// 소리를 안 내는 소스(오디오를 넘기지 않는 브라우저 등)는 자리를 잡지 않고 스템 비트도 비운다.
TEST(audio_silent_or_monitor_only_sources_get_no_stem)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:overlay", 1, 10)};
	in.sources = {Src("uuid:overlay", "채팅창", AudioKind::Browser, 100, 0x3F, false),
		      Src("uuid:monitor", "효과음", AudioKind::Media, 101, 0x3F, true, true),
		      Src("uuid:bgm", "BGM", AudioKind::Media, 102)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:overlay"), 0);
	CHECK_EQ(SlotOf(r, "uuid:monitor"), 0);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 2); // 트랙 2는 조용한 채팅창의 기억이 적어 둔 자리 — 비워 둔다
	CHECK_EQ(WriteOf(r, "uuid:overlay", 0x3F), 0x01u);
	CHECK_EQ(WriteOf(r, "uuid:monitor", 0x3F), 0x01u);
	CHECK(MapHas(r, "uuid:overlay")); // 소리를 다시 내면 자리를 되찾을 수 있게 남긴다
}

TEST(audio_global_device_keyed_by_channel)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("ch:3", 2, 10)};
	in.sources = {Src("ch:3", "새 이름 마이크", AudioKind::Mic, 3)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "ch:3"), 2);
	CHECK(r.mapChanged); // 이름만 갱신됐다
	CHECK_EQ(r.mapNext[0].name, std::string("새 이름 마이크"));
}

// 같은 자리를 기억하는 자동 소스 둘 — 먼저 앉은 쪽이 지키고, 밀린 쪽은 갈 자리가 없으면 같은 종류 트랙(바로 그 자리)에
// 묶인 채 남는다. 기억은 버리지 않는다.
// 같은 자리 기억 + 빈 트랙 없음 — 묶음은 그대로, 새 소스가 나머지를 채운다.
TEST(audio_contested_memory_stays_grouped_without_room)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:a", 1, 10), Entry("uuid:b", 1, 20)};
	in.sources = {Src("uuid:a", "A", AudioKind::Other, 100), Src("uuid:b", "B", AudioKind::Other, 101),
		      Src("ch:3", "M1", AudioKind::Mic, 3),        Src("ch:4", "M2", AudioKind::Mic, 4),
		      Src("ch:5", "M3", AudioKind::Mic, 5),        Src("ch:6", "M4", AudioKind::Mic, 6)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:a"), 1);
	CHECK_EQ(SlotOf(r, "uuid:b"), 1);
	CHECK(MapHas(r, "uuid:b"));
	CHECK(r.overflowKeys.empty());
	CHECK_EQ(SlotOf(r, "ch:3"), 2);
	CHECK_EQ(SlotOf(r, "ch:6"), 5);
}

// 트랙 2~6이 전부 본방 트랙이면 앉을 곳이 없다 — 그때만 믹스 전용으로 남고 기억을 버린다.
TEST(audio_overflow_only_when_every_stem_is_main_stream)
{
	AssignmentInput in;
	in.now = 100;
	in.reserved = kStemMask;
	in.map = {Entry("uuid:a", 1, 10)};
	in.sources = {Src("uuid:a", "A", AudioKind::Other, 100)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:a"), 0);
	CHECK(!MapHas(r, "uuid:a"));
	CHECK_EQ(r.overflowKeys.size(), 1u);
}

// ---- 손 배정(POK-266) — 독이 기억을 고쳐 쓰고, 계산은 기억대로 앉힌다 ----

// 독에서 찬 트랙으로 옮기면 밀어내지 않고 묶인다 — 방송 중에 지금 나가는 소스와도. 비트는 다음 계산이 맞춘다.
TEST(audio_moved_source_joins_occupied_track_without_displacing)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("ch:1", 2, 10, 90), Entry("uuid:bgm", 2, 50)}; // 독이 BGM을 트랙 3(믹서 2)으로 옮겼다
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x01), Src("ch:1", "데스크탑", AudioKind::Desktop, 1, 0x05),
		      Src("uuid:bgm", "BGM", AudioKind::Media, 100, 0x01)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "ch:1"), 2);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 2);
	CHECK_EQ(SlotOf(r, "ch:3"), 1); // 기억 없는 새 소스는 빈 자리로
	CHECK_EQ(WriteOf(r, "uuid:bgm", 0x01), 0x05u);
	CHECK_EQ(WriteOf(r, "ch:1", 0x05), 0x05u); // 그대로

	// 다시 넣으면 그대로 — 멱등.
	AssignmentInput again = in;
	again.sources = Applied(in.sources, r);
	again.map = r.mapNext;
	AssignmentResult r2 = ComputeAssignment(again);
	CHECK(r2.writes.empty());
	CHECK(!r2.mapChanged);
}

// 독에서 뺀 소스(slot 0)는 어느 트랙에도 없이 믹스에만 남는다 — 자리를 받지도, 기억을 잃지도 않는다.
TEST(audio_removed_source_stays_off_tracks)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:a", 0, 10)};
	in.map[0].name = "A";
	in.sources = {Src("uuid:a", "A", AudioKind::Media, 100), Src("ch:3", "마이크", AudioKind::Mic, 3)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:a"), 0);
	CHECK_EQ(WriteOf(r, "uuid:a", 0x3F), 0x01u); // 트랙 1만
	CHECK_EQ(SlotOf(r, "ch:3"), 1);
	CHECK(MapHas(r, "uuid:a"));
	CHECK(r.overflowKeys.empty());

	AssignmentInput again = in;
	again.sources = Applied(in.sources, r);
	again.map = r.mapNext;
	AssignmentResult r2 = ComputeAssignment(again);
	CHECK(r2.writes.empty());
	CHECK(!r2.mapChanged);
	CHECK_EQ(SlotOf(r2, "uuid:a"), 0);
}

// 기억한 자리가 본방 트랙이 되면 새 자리를 받는다 — 플러그인이 자리를 옮기는 유일한 경우. 없는 소스의 기억은 그대로.
TEST(audio_memory_on_main_stream_track_is_reseated)
{
	AssignmentInput in;
	in.now = 100;
	in.reserved = 0x04; // 트랙 3 = 믹서 2
	in.map = {Entry("uuid:a", 2, 10), Entry("uuid:gone", 2, 10)};
	in.map[0].name = "A";
	in.sources = {Src("uuid:a", "A", AudioKind::Media, 100)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:a"), 1);
	CHECK(r.mapChanged);
	CHECK(MapHas(r, "uuid:gone"));
	for (const AudioTrackMapEntry &e : r.mapNext) {
		if (e.key == "uuid:a")
			CHECK_EQ(e.assignedAt, 100);
		if (e.key == "uuid:gone")
			CHECK_EQ(e.slot, 2);
	}
}

// 독 뷰 — 열쇠·잠금이 실리고, 트랙에 없는 소스는 열쇠와 함께 mixOnly에 오른다(독이 「트랙 없음」으로 알린다).
TEST(audio_routing_view_lists_keys_and_sources_off_tracks)
{
	std::vector<AudioSourceInfo> sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x03),
						Src("uuid:bgm", "BGM", AudioKind::Media, 100, 0x09),
						Src("uuid:alert", "알림", AudioKind::Browser, 101, 0x09),
						Src("uuid:off", "효과음", AudioKind::Media, 102, 0x01)};
	RoutingViewOptions options;
	options.autoAssign = true;
	options.applied = true;
	options.locked = true;
	AudioRoutingView v = BuildRoutingView(sources, options);
	CHECK(v.locked);
	CHECK(v.tracks[0].sources[0] == (AudioSourceView{"마이크", AudioKind::Mic, "ch:3"}));
	CHECK_EQ(v.tracks[2].sources.size(), 2u);
	CHECK_EQ(v.tracks[2].sources[1].key, std::string("uuid:alert"));
	CHECK_EQ(v.mixOnly.size(), 1u);
	CHECK(v.mixOnly[0] == (AudioSourceView{"효과음", AudioKind::Media, "uuid:off"}));
	std::string line = DescribeRouting(v);
	CHECK(line.find("BGM(media), 알림(browser)") != std::string::npos);
	CHECK(line.find("no-track 1") != std::string::npos);
	CHECK(BuildRoutingView(sources, options) == v);
}

TEST(audio_prunes_absent_memories_beyond_cap)
{
	AssignmentInput in;
	in.now = 1000;
	for (int i = 0; i < 70; i++)
		in.map.push_back(Entry("uuid:gone" + std::to_string(i), 1 + i % kStemSlots, 1, 1 + i));
	in.map.push_back(Entry("uuid:here", 1, 1, 1));
	in.sources = {Src("uuid:here", "here", AudioKind::Mic, 100)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(r.mapNext.size(), kTrackMapCap);
	CHECK(MapHas(r, "uuid:here"));
	CHECK(!MapHas(r, "uuid:gone0")); // 가장 오래 안 보인 것부터
	CHECK(MapHas(r, "uuid:gone69"));
	CHECK(r.mapChanged);
}

TEST(audio_drops_corrupt_memories)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:minus", -1, 1), Entry("uuid:six", 6, 1), Entry("", 1, 1), Entry("uuid:dup", 2, 1),
		  Entry("uuid:dup", 3, 1), Entry("uuid:zero", 0, 1)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(r.mapNext.size(), 2u);
	CHECK_EQ(r.mapNext[0].slot, 2);
	CHECK_EQ(r.mapNext[1].slot, 0); // 0은 「트랙에 없음」 — 유효하다
	CHECK(r.mapChanged);
}

// 고급 출력에서 방송 트랙을 2로 둔 스트리머 — 트랙 2는 시청자가 듣는 믹스라 자동 배정이 손대지 않는다.
// 트랙 2를 기억하던 소스는 다른 빈 자리로 옮긴다(트랙 2의 체크는 스트리머가 짠 대로 남는다).
TEST(audio_main_stream_track_is_not_a_slot)
{
	AssignmentInput in;
	in.now = 100;
	in.reserved = 0x02;
	in.map = {Entry("uuid:bgm", 1, 10)};
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3), Src("ch:1", "데스크탑", AudioKind::Desktop, 1),
		      Src("uuid:bgm", "BGM", AudioKind::Media, 100)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK(r.slotKeys[1].empty());
	CHECK_EQ(SlotOf(r, "ch:3"), 2);
	CHECK_EQ(SlotOf(r, "ch:1"), 3);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 4);
	CHECK_EQ(WriteOf(r, "ch:3", 0x3F), 0x07u);     // 트랙 2(본방)는 켜진 그대로
	CHECK_EQ(WriteOf(r, "uuid:bgm", 0x3F), 0x13u); // 트랙 1 + 트랙 2(본방) + 트랙 5
	for (const AudioTrackMapEntry &e : r.mapNext)
		CHECK(e.slot != 1);

	AssignmentInput again;
	again.now = 200;
	again.reserved = 0x02;
	again.map = r.mapNext;
	again.sources = Applied(in.sources, r);
	AssignmentResult second = ComputeAssignment(again);
	CHECK(second.writes.empty());
	CHECK(!second.mapChanged);
}

// 다른 장면에만 있는 소스는 트랙을 먼저 채우지 않는다 — 방송 화면에 나올 때 처음 자리를 받는다.
TEST(audio_offscreen_source_waits_for_program)
{
	AssignmentInput in;
	in.now = 100;
	in.sources = {Src("ch:3", "마이크", AudioKind::Mic, 3),
		      Offscreen("uuid:intro", "인트로 영상", AudioKind::Media, 100),
		      Offscreen("uuid:ending", "엔딩 영상", AudioKind::Media, 101),
		      Src("uuid:bgm", "BGM", AudioKind::Media, 102)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "ch:3"), 1);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 2); // 열거 순서가 앞선 인트로·엔딩보다 먼저
	CHECK_EQ(SlotOf(r, "uuid:intro"), 0);
	CHECK_EQ(WriteOf(r, "uuid:intro", 0x3F), 0x01u); // 스템에 섞여 있지 않게 비운다
	CHECK(!MapHas(r, "uuid:intro"));
	CHECK(r.overflowKeys.empty());

	AssignmentInput shown;
	shown.now = 200;
	shown.map = r.mapNext;
	shown.sources = Applied(in.sources, r);
	shown.sources[1].showing = true; // 인트로 장면으로 넘어갔다
	AssignmentResult r2 = ComputeAssignment(shown);
	CHECK_EQ(SlotOf(r2, "uuid:intro"), 3);
	CHECK_EQ(SlotOf(r2, "uuid:bgm"), 2);
}

// 한 번 앉은 소스는 화면에서 빠져도 자리를 지킨다 — 장면을 바꿀 때마다 트랙 주인이 바뀌면 트랙 이름이 무의미해진다.
TEST(audio_remembered_offscreen_source_keeps_slot)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:bgm", 1, 10)};
	in.sources = {Offscreen("uuid:bgm", "BGM", AudioKind::Media, 100, 0x03),
		      Src("ch:3", "마이크", AudioKind::Mic, 3, 0x01)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 1);
	CHECK_EQ(SlotOf(r, "ch:3"), 2);
	CHECK_EQ(WriteOf(r, "uuid:bgm", 0x03), 0x03u);
}

// 수동 모드(스위치 꺼짐)에서도 화면은 실제 비트를 그대로 보여준다 — 한 트랙에 여럿, 여러 트랙에 하나.
TEST(audio_routing_view_reflects_actual_bits)
{
	std::vector<AudioSourceInfo> sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x07),
						Src("ch:1", "데스크탑", AudioKind::Desktop, 1, 0x01),
						Src("uuid:game", "게임", AudioKind::App, 100, 0x05),
						Src("uuid:fx", "효과음", AudioKind::Media, 101, 0x3F, true, true),
						Src("uuid:overlay", "채팅창", AudioKind::Browser, 102, 0x3F, false)};
	RoutingViewOptions options;
	options.autoAssign = false;
	AudioRoutingView v = BuildRoutingView(sources, options);
	CHECK(v.known);
	CHECK(!v.autoAssign);
	CHECK_EQ(v.tracks[0].track, 2);
	CHECK_EQ(v.tracks[0].sources.size(), 1u); // 트랙 2: 마이크
	CHECK_EQ(v.tracks[1].sources.size(), 2u); // 트랙 3: 마이크 + 게임
	CHECK_EQ(v.mixOnly.size(), 1u);           // 데스크탑
	CHECK_EQ(v.monitorOnly.size(), 1u);       // 효과음 — 소리 없는 채팅창은 안 보인다
	std::string line = DescribeRouting(v);
	CHECK(line.find("T2 마이크(mic)") != std::string::npos);
	CHECK(line.find("T4 –") != std::string::npos);
	CHECK(line.find("no-track 1") != std::string::npos);
}

// 자동 배정을 켤지 처음 물을 때, 스트리머가 트랙 2~6을 직접 짜 뒀으면 덮어쓴다고 알린다.
// OBS는 새 소스를 트랙 전부에 켠 채 만들므로 「전부 켜짐」이 아니면 손댄 것이다. 본방 트랙은 안 건드리니 빼고 본다.
TEST(audio_custom_routing_detects_hand_made_tracks)
{
	std::vector<AudioSourceInfo> fresh = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x3F),
					      Src("uuid:game", "게임", AudioKind::App, 100, 0xFF)};
	CHECK(!HasCustomStemRouting(fresh, 0));

	std::vector<AudioSourceInfo> custom = fresh;
	custom[1].mixers = 0x05; // 트랙 1·3만
	CHECK(HasCustomStemRouting(custom, 0));
	custom[1].mixers = 0x01; // 트랙 2~6 전부 끔
	CHECK(HasCustomStemRouting(custom, 0));

	std::vector<AudioSourceInfo> mainOnly = fresh;
	mainOnly[1].mixers = 0x3D; // 본방 트랙 2만 꺼 뒀다 — 자동 배정이 덮어쓸 것이 아니다
	CHECK(!HasCustomStemRouting(mainOnly, 0x02));

	RoutingViewOptions options;
	options.autoAssign = false;
	options.prompt = true;
	AudioRoutingView v = BuildRoutingView(custom, options);
	CHECK(v.prompt);
	CHECK(v.customRouting);
	options.applied = true; // 이미 자동 배정 중이면 알릴 것이 없다
	CHECK(!BuildRoutingView(custom, options).customRouting);
}

// 본방 트랙은 표시만 하고 스템으로 치지 않는다. 화면에 없고 스템에도 없는 소스는 목록에 올리지 않는다.
TEST(audio_routing_view_marks_main_stream_and_hides_offscreen)
{
	std::vector<AudioSourceInfo> sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x03),
						Src("uuid:game", "게임", AudioKind::App, 100, 0x05),
						Offscreen("uuid:intro", "인트로 영상", AudioKind::Media, 101, 0x01),
						Src("uuid:fx", "효과음", AudioKind::Media, 102, 0x3F, true, true,
						    false)};
	RoutingViewOptions options;
	options.applied = true;
	options.deferred = false;
	options.reserved = 0x02;
	AudioRoutingView v = BuildRoutingView(sources, options);
	CHECK(v.tracks[0].mainStream);
	CHECK(!v.tracks[1].mainStream);
	CHECK_EQ(v.tracks[0].sources.size(), 1u); // 본방 트랙 2에 마이크
	CHECK_EQ(v.mixOnly.size(), 1u);           // 마이크 — 본방 트랙에만 있고 스템에는 없다
	CHECK_EQ(v.mixOnly[0].name, std::string("마이크"));
	CHECK(v.monitorOnly.empty()); // 화면에 없는 효과음
	CHECK(DescribeRouting(v).find("T2 main-stream 마이크(mic)") != std::string::npos);

	options.applied = false;
	options.deferred = true;
	AudioRoutingView deferred = BuildRoutingView(sources, options);
	CHECK(deferred.deferred);
	CHECK(DescribeRouting(deferred).find("deferred") != std::string::npos);
}

TEST(audio_routing_view_keeps_removed_offscreen_source_listed)
{
	// 독에서 뺀(slot 0) 소스는 다른 장면에만 있어도 「트랙 없음」에 남는다 — 안 보이면 되돌릴 수 없다.
	// 기억 없는 화면 밖 소스(인트로)는 그대로 숨긴다.
	std::vector<AudioSourceInfo> sources = {Src("uuid:game", "게임", AudioKind::App, 100, 0x05),
						Offscreen("uuid:bgm", "BGM", AudioKind::Media, 101, 0x01),
						Offscreen("uuid:intro", "인트로 영상", AudioKind::Media, 102, 0x01)};
	RoutingViewOptions options;
	options.applied = true;
	options.offTrack = {"uuid:bgm"};
	AudioRoutingView v = BuildRoutingView(sources, options);
	CHECK_EQ(v.mixOnly.size(), 1u);
	CHECK_EQ(v.mixOnly[0].key, std::string("uuid:bgm"));
	CHECK(DescribeRouting(v).find("no-track 1") != std::string::npos);

	options.offTrack.clear();
	CHECK(BuildRoutingView(sources, options).mixOnly.empty());
}

// ---------------------------------------------------------------- 설정

TEST(config_audio_switches_are_not_output_settings)
{
	PluginConfig a;
	a.ingestHost = "ingest.example";
	a.ingestPort = 9000;
	a.latencyMs = 120;
	PluginConfig b = a;
	b.audioAutoAssign = !a.audioAutoAssign;
	b.audioAssignPrompted = !a.audioAssignPrompted;
	CHECK(b.SameOutputSettings(a)); // 방송 중에도 받는다 — 처음 안내 카드의 답
	b.ingestPort = 9001;
	CHECK(!b.SameOutputSettings(a));
	PluginConfig c = a;
	c.syncStart = !a.syncStart;
	CHECK(!c.SameOutputSettings(a));
}

#ifndef _WIN32
TEST(private_file_is_owner_only_from_the_start)
{
	namespace fs = std::filesystem;
	auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
	fs::path dir = fs::temp_directory_path() / ("pokeclip-private-test-" + std::to_string(stamp));
	fs::create_directories(dir);
	const std::string path = (dir / "pokeclip.json").string();
	auto modeOf = [](const std::string &p) {
		struct stat st {};
		return stat(p.c_str(), &st) == 0 ? static_cast<unsigned>(st.st_mode & 0777) : 0u;
	};
	auto read = [](const std::string &p) {
		std::ifstream in(p);
		return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
	};
	mode_t oldMask = umask(022); // 흔한 기본값 — libobs 저장이면 tmp가 0644로 생긴다

	CHECK(WritePrivateFileAtomic(path, "{\"passphrase\":\"first\"}"));
	CHECK_EQ(modeOf(path), 0600u);
	CHECK_EQ(read(path), std::string("{\"passphrase\":\"first\"}"));
	CHECK(!fs::exists(path + ".tmp"));

	// 지난 실행이 남긴 0644 tmp가 있어도 새 파일은 0600이다. 이전 파일은 .bak으로 남고(예전 판의 0644였어도) 0600이다.
	std::ofstream(path + ".tmp") << "stale";
	chmod((path + ".tmp").c_str(), 0644);
	chmod(path.c_str(), 0644);
	CHECK(WritePrivateFileAtomic(path, "second"));
	CHECK_EQ(modeOf(path), 0600u);
	CHECK_EQ(read(path), std::string("second"));
	CHECK_EQ(read(path + ".bak"), std::string("{\"passphrase\":\"first\"}"));
	CHECK_EQ(modeOf(path + ".bak"), 0600u);

	// tmp 자리의 심볼릭 링크는 따라가지 않는다 — 쓰기가 실패하고 링크 대상·원래 파일은 그대로다.
	fs::path victim = dir / "victim";
	std::ofstream(victim) << "keep";
	fs::create_symlink(victim, path + ".tmp");
	CHECK(!WritePrivateFileAtomic(path, "third"));
	CHECK_EQ(read(victim.string()), std::string("keep"));
	CHECK_EQ(read(path), std::string("second"));

	umask(oldMask);
	std::error_code ec;
	fs::remove_all(dir, ec);
}
#endif

// ---------------------------------------------------------------- 핫키 마킹 (A4)

TEST(mark_url_encodes_token_and_trims_base)
{
	CHECK_EQ(MarkUrl("http://dev.pokeclip.com/", "abc_DEF-9"), "http://dev.pokeclip.com/api/clip/streams/abc_DEF-9/marks");
	CHECK_EQ(MarkUrl("https://x.test//", "a/b?c"), "https://x.test/api/clip/streams/a%2Fb%3Fc/marks");
}

// passphrase를 싣는 주소 — https이거나 이 PC 안의 http만.
TEST(mark_secure_base_allows_https_and_loopback_only)
{
	CHECK(IsSecureMarkBase("https://dev.pokeclip.com"));
	CHECK(IsSecureMarkBase("HTTPS://x.test/"));
	CHECK(IsSecureMarkBase("http://localhost:8082"));
	CHECK(IsSecureMarkBase("http://127.0.0.1:9999/"));
	CHECK(IsSecureMarkBase("http://[::1]:80"));
	CHECK(IsSecureMarkBase("http://LOCALHOST"));
	CHECK(!IsSecureMarkBase("http://dev.pokeclip.com"));
	CHECK(!IsSecureMarkBase("http://localhost.evil.test"));
	CHECK(!IsSecureMarkBase("http://localhost@evil.test"));
	CHECK(!IsSecureMarkBase("http://127.0.0.1.nip.io"));
	CHECK(!IsSecureMarkBase("http://localhost:80x"));
	CHECK(!IsSecureMarkBase("ftp://localhost"));
	CHECK(!IsSecureMarkBase("https://"));
	CHECK(!IsSecureMarkBase(""));
}

TEST(mark_body_has_three_fields)
{
	CHECK_EQ(MarkBodyJson("0f8fad5b-d9cb-469f-a165-70867728950e", 1759212000123, 1759212000456),
		 R"({"eventId":"0f8fad5b-d9cb-469f-a165-70867728950e","pressedAt":1759212000123,"sentAt":1759212000456})");
}

TEST(mark_uuid_v4_sets_version_and_variant)
{
	std::array<uint8_t, 16> ones{};
	ones.fill(0xFF);
	std::string id = FormatUuidV4(ones);
	CHECK_EQ(id.size(), 36u);
	CHECK_EQ(id, "ffffffff-ffff-4fff-bfff-ffffffffffff");
	std::array<uint8_t, 16> zeros{};
	CHECK_EQ(FormatUuidV4(zeros), "00000000-0000-4000-8000-000000000000");
}

TEST(mark_retry_backoff_doubles_to_cap)
{
	CHECK_EQ(MarkRetryDelayMs(1), 1000);
	CHECK_EQ(MarkRetryDelayMs(2), 2000);
	CHECK_EQ(MarkRetryDelayMs(3), 4000);
	CHECK_EQ(MarkRetryDelayMs(5), 16000);
	CHECK_EQ(MarkRetryDelayMs(6), 30000);
	CHECK_EQ(MarkRetryDelayMs(40), 30000);
	// Retry-After가 더 길면 그것을, 단 60초까지
	CHECK_EQ(MarkRetryDelayMs(1, 5000), 5000);
	CHECK_EQ(MarkRetryDelayMs(3, 1000), 4000);
	CHECK_EQ(MarkRetryDelayMs(1, 600000), 60000);
}

TEST(mark_retry_after_header)
{
	CHECK_EQ(ParseRetryAfterMs(" 5\r\n"), 5000);
	CHECK_EQ(ParseRetryAfterMs("0"), 0);
	CHECK_EQ(ParseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"), 0);
	CHECK_EQ(ParseRetryAfterMs(""), 0);
	CHECK_EQ(ParseRetryAfterMs("99999999"), 60000);
}

TEST(mark_response_classification)
{
	auto is = [](const MarkVerdict &v, MarkOutcome o, const char *reason) { return v.outcome == o && v.reason == reason; };
	CHECK(is(ClassifyMarkResponse(false, 0, ""), MarkOutcome::Retry, "network"));
	CHECK(is(ClassifyMarkResponse(true, 201, "{}"), MarkOutcome::Delivered, ""));
	CHECK(is(ClassifyMarkResponse(true, 200, "{}"), MarkOutcome::Delivered, "")); // 같은 eventId 재전송
	CHECK(is(ClassifyMarkResponse(true, 400, R"({"reason":"invalid_request"})"), MarkOutcome::Drop, "mark_rejected"));
	CHECK(is(ClassifyMarkResponse(true, 401, R"({"reason":"invalid_stream_key"})"), MarkOutcome::Drop,
		 "mark_unauthorized"));
	// 사유 없는 401 = Clip 기본 체인이 JWT 아닌 Bearer를 막았다 → 창구가 아직 없다
	CHECK(is(ClassifyMarkResponse(true, 401, ""), MarkOutcome::Drop, "mark_unsupported"));
	CHECK(is(ClassifyMarkResponse(true, 404, R"({"reason":"broadcast_not_found"})"), MarkOutcome::Retry,
		 "mark_no_broadcast"));
	CHECK(is(ClassifyMarkResponse(true, 404, R"({"status":404,"error":"Not Found","path":"/api/clip/streams/x/marks"})"),
		 MarkOutcome::Drop, "mark_unsupported"));
	CHECK(is(ClassifyMarkResponse(true, 429, ""), MarkOutcome::Retry, "mark_rate_limited"));
	CHECK(is(ClassifyMarkResponse(true, 503, R"({"reason":"timeline_not_ready"})"), MarkOutcome::Retry,
		 "mark_not_ready"));
	CHECK(is(ClassifyMarkResponse(true, 502, "<html>"), MarkOutcome::Retry, "server_error"));
	CHECK(is(ClassifyMarkResponse(true, 301, ""), MarkOutcome::Drop, "bad_response"));
}

TEST(mark_expires_after_ten_minutes)
{
	CHECK(!MarkExpired(1000, 1000 + kMarkGiveUpMs - 1));
	CHECK(MarkExpired(1000, 1000 + kMarkGiveUpMs));
}

TEST(mark_hotkey_label_ignores_keyboard_layout)
{
	HotkeyLabelParts p;
	p.control = true;
	p.shift = true;
	p.keyName = "OBS_KEY_M";
	p.keyText = "\xE3\x85\xA1"; // 한글 입력 소스의 macOS 표기 「ㅡ」
	CHECK_EQ(FormatHotkeyLabel(p, true), "\xE2\x8C\x83\xE2\x87\xA7" "M");
	CHECK_EQ(FormatHotkeyLabel(p, false), "Ctrl+Shift+M");

	HotkeyLabelParts f;
	f.alt = true;
	f.keyName = "OBS_KEY_F10";
	CHECK_EQ(FormatHotkeyLabel(f, false), "Alt+F10");

	HotkeyLabelParts other;
	other.command = true;
	other.keyName = "OBS_KEY_SPACE";
	other.keyText = "Space";
	CHECK_EQ(FormatHotkeyLabel(other, true), "\xE2\x8C\x98" "Space");
	CHECK_EQ(FormatHotkeyLabel(other, false), "Win+Space");

	HotkeyLabelParts modsOnly;
	modsOnly.control = true;
	modsOnly.shift = true;
	CHECK_EQ(FormatHotkeyLabel(modsOnly, false), "Ctrl+Shift");
}

TEST(mark_debounce_two_seconds)
{
	MarkDebouncer d;
	CHECK(d.Accept(10000));
	CHECK(!d.Accept(10001));
	CHECK(!d.Accept(11999));
	CHECK(d.Accept(12000));
	CHECK(!d.Accept(13000)); // 기준은 마지막으로 받아들인 누름
	CHECK(d.Accept(14000));
}

// ───────────────────────── A5 재시도 정책 ─────────────────────────

TEST(retry_interval_follows_policy_table)
{
	// 300초 창 안: 5초
	CHECK(NextRetry(0).retry);
	CHECK_EQ(NextRetry(0).delayMs, 5000);
	CHECK_EQ(NextRetry(299999).delayMs, 5000);
	// 창을 넘긴 뒤 30분: 30초
	CHECK_EQ(NextRetry(300000).delayMs, 30000);
	CHECK_EQ(NextRetry(35 * 60000 - 1).delayMs, 30000);
	// 그 뒤 30분: 60초
	CHECK_EQ(NextRetry(35 * 60000).delayMs, 60000);
	CHECK(NextRetry(65 * 60000 - 1).retry);
	CHECK_EQ(NextRetry(65 * 60000 - 1).delayMs, 60000);
	// 65분: 포기
	CHECK(!NextRetry(65 * 60000).retry);
	CHECK(!NextRetry(24 * 3600000).retry);
	// 시계가 거꾸로 갈 일은 없지만(단조 시계) 음수여도 창 안으로 친다
	CHECK_EQ(NextRetry(-1).delayMs, 5000);
}

TEST(retry_only_for_network_side_stop_codes)
{
	for (const char *code : {"disconnected", "connect_failed", "bad_path", "timeout", "output_error"})
		CHECK(IsRetryableStop(code));
	// 다시 해도 같은 것 — 인코더·스트림·디스크, 그리고 성공 정지(빈 이름)
	for (const char *code : {"", "invalid_stream", "encode_error", "unsupported", "no_space", "unknown"})
		CHECK(!IsRetryableStop(code));
	// 플러그인이 그 자리에서 내는 사유는 정지 코드가 아니다
	CHECK(!IsRetryableStop("no_shared_encoder"));
	CHECK(!IsRetryableStop("main_stream_failed"));
}

TEST(reject_streak_flags_key_after_three_fast_rejects)
{
	RejectStreak s;
	CHECK(!s.KeySuspect());
	s.Record("connect_failed", 40);
	s.Record("connect_failed", 999);
	CHECK(!s.KeySuspect());
	s.Record("connect_failed", 12);
	CHECK(s.KeySuspect());

	// 무응답(접속 타임아웃을 다 씀)은 서버가 죽은 것이지 키 문제가 아니다
	s.Record("connect_failed", 3000);
	CHECK(!s.KeySuspect());

	// 다른 코드가 끼면 다시 센다
	s.Record("connect_failed", 10);
	s.Record("connect_failed", 10);
	s.Record("bad_path", 10);
	s.Record("connect_failed", 10);
	CHECK(!s.KeySuspect());

	s.Record("connect_failed", 10);
	s.Record("connect_failed", 10);
	CHECK(s.KeySuspect());
	s.Reset();
	CHECK(!s.KeySuspect());
}

// 본방은 나가는데 우리 송출이 멈춘 상태 — 버튼이 뜨는 기준선.
StateSnapshot StoppedWhileMainLive()
{
	StateSnapshot s;
	s.obsStreaming = true;
	s.paired = true;
	s.syncStart = true;
	s.phase = StreamPhase::Error;
	s.errorCode = "connect_failed";
	return s;
}

TEST(send_now_offered_only_when_main_is_live_and_we_are_stopped)
{
	StateSnapshot base = StoppedWhileMainLive();
	CHECK(CanSendNow(base));

	// 방송 중에 페어링한 직후 — 오류 없이 대기 단계다
	StateSnapshot pairedMidStream = base;
	pairedMidStream.phase = StreamPhase::Idle;
	pairedMidStream.errorCode.clear();
	CHECK(CanSendNow(pairedMidStream));

	StateSnapshot s = base;
	s.obsStreaming = false; // 본방이 없으면 우리만 보내지 않는다
	CHECK(!CanSendNow(s));
	s = base;
	s.paired = false;
	CHECK(!CanSendNow(s));
	s = base;
	s.syncStart = false; // 스트리머가 동기화를 껐다
	CHECK(!CanSendNow(s));

	for (StreamPhase phase : {StreamPhase::Starting, StreamPhase::Live, StreamPhase::Reconnecting, StreamPhase::Stopping}) {
		s = base;
		s.phase = phase;
		CHECK(!CanSendNow(s));
	}

	// 본방 인코더를 다시 띄워야 풀린다 — 눌러도 같은 결과
	for (const char *code : {"encoder_active", "keyint_not_applied", "multitrack_video"}) {
		s = base;
		s.errorCode = code;
		CHECK(!CanSendNow(s));
	}

	// 저장된 키가 규칙에 어긋나도 눌러 봐야 같은 결과다 — 새 코드를 넣어야 풀린다
	s = base;
	s.errorCode = "invalid_key";
	CHECK(!CanSendNow(s));
}

TEST(send_now_rejection_reasons)
{
	StateSnapshot base = StoppedWhileMainLive();
	CHECK_EQ(std::string(SendNowRejection(base)), "");

	StateSnapshot s = base;
	s.obsStreaming = false;
	CHECK_EQ(std::string(SendNowRejection(s)), "main_not_live");
	s = base;
	s.paired = false;
	CHECK_EQ(std::string(SendNowRejection(s)), "no_key");
	s = base;
	s.phase = StreamPhase::Live;
	CHECK_EQ(std::string(SendNowRejection(s)), "send_unavailable");

	// 다음 재시도를 기다리는 중이면 받는다(기다림만 건너뛴다) — 단계는 송출 중이다
	s = base;
	s.phase = StreamPhase::Reconnecting;
	s.retry.attempt = 3;
	s.retry.nextAt = 1791234567890;
	CHECK_EQ(std::string(SendNowRejection(s)), "");
	// 지금 접속 중인 시도는 건너뛸 기다림이 없다
	s.retry.nextAt = 0;
	CHECK_EQ(std::string(SendNowRejection(s)), "send_unavailable");
}

TEST(sync_on_start_rechecks_main_stream_sync_and_phase)
{
	StateSnapshot base;
	base.obsStreaming = true;
	base.syncStart = true;
	CHECK(CanStartOnSyncEnabled(base)); // 대기 단계

	// 키·GOP는 보지 않는다 — 시작 경로가 보고 사유를 남긴다
	StateSnapshot s = base;
	s.paired = false;
	CHECK(CanStartOnSyncEnabled(s));
	s = base;
	s.phase = StreamPhase::Error;
	s.errorCode = "encoder_active";
	CHECK(CanStartOnSyncEnabled(s));

	s = base;
	s.obsStreaming = false; // 큐에서 기다리는 사이 본방이 멈췄다 — 시작하면 멈춰 줄 이벤트가 없다
	CHECK(!CanStartOnSyncEnabled(s));
	s = base;
	s.syncStart = false; // 그새 동기화를 다시 껐다
	CHECK(!CanStartOnSyncEnabled(s));

	// 이미 도는 송출은 건드리지 않는다(접속 중인 출력을 새로 만들면 접속 결과를 기다리느라 UI 스레드가 멈춘다)
	for (StreamPhase phase : {StreamPhase::Starting, StreamPhase::Live, StreamPhase::Reconnecting, StreamPhase::Stopping}) {
		s = base;
		s.phase = phase;
		CHECK(!CanStartOnSyncEnabled(s));
	}
}

// ── A3 종료 신호(계약4 4D) ──

std::string Hex32(const std::array<uint8_t, 32> &bytes)
{
	std::string s;
	char buf[3];
	for (uint8_t b : bytes) {
		std::snprintf(buf, sizeof(buf), "%02x", b);
		s += buf;
	}
	return s;
}

TEST(end_signal_sha256_known_vectors)
{
	// FIPS 180-4 · NIST 예시 벡터 — 빈 입력, 한 블록, 두 블록에 걸치는 입력, 블록 수천 개(길이 인코딩).
	CHECK_EQ(Hex32(Sha256("")), std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
	CHECK_EQ(Hex32(Sha256("abc")), std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
	CHECK_EQ(Hex32(Sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
		 std::string("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"));
	CHECK_EQ(Hex32(Sha256(std::string(1000000, 'a'))),
		 std::string("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"));
}

TEST(end_signal_hmac_rfc4231_vectors)
{
	// 짧은 키(1·2) · 블록보다 긴 키(6·7 — passphrase가 64바이트를 넘을 수 있어 키 해시 경로가 맞아야 한다).
	CHECK_EQ(HmacSha256Hex(std::string(20, '\x0b'), "Hi There"),
		 std::string("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"));
	CHECK_EQ(HmacSha256Hex("Jefe", "what do ya want for nothing?"),
		 std::string("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"));
	CHECK_EQ(HmacSha256Hex(std::string(20, '\xaa'), std::string(50, '\xdd')),
		 std::string("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"));
	CHECK_EQ(HmacSha256Hex(std::string(131, '\xaa'), "Test Using Larger Than Block-Size Key - Hash Key First"),
		 std::string("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"));
	CHECK_EQ(HmacSha256Hex(std::string(131, '\xaa'),
			       "This is a test using a larger than block-size key and a larger than block-size data. The "
			       "key needs to be hashed before being used by the HMAC algorithm."),
		 std::string("9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2"));
}

TEST(end_signal_derived_value_matches_contract_vector)
{
	// 계약4 3-1절 시험 벡터(예시 값 — 실제 키가 아니다).
	CHECK_EQ(EndSignalDerivedValue("EXAMPLE-4d-test-vector-not-a-key"),
		 std::string("bf127d0f99ed7397ea578d019fb6e83e0c15d36032f24420fef4d0294ca3e398"));
	CHECK_EQ(EndSignalDerivedValue("EXAMPLE-4d-test-vector-not-a-key").size(), size_t(64));
}

TEST(end_signal_url_is_fixed_unless_override_is_loopback)
{
	const std::string fixed = "https://media.pokeclip.com/api/ingest/end-signal";
	CHECK_EQ(EndSignalUrl(""), fixed);
	CHECK_EQ(EndSignalUrl("https://evil.example"), fixed);
	CHECK_EQ(EndSignalUrl("http://dev.pokeclip.com"), fixed);
	CHECK_EQ(EndSignalUrl("https://127.0.0.1.evil.example"), fixed);
	CHECK_EQ(EndSignalUrl("http://localhost@evil.example"), fixed);
	CHECK_EQ(EndSignalUrl("http://127.0.0.1:80x"), fixed);
	CHECK_EQ(EndSignalUrl("http://127.0.0.1:"), fixed);
	CHECK_EQ(EndSignalUrl("http://127.0.0"), fixed);
	CHECK_EQ(EndSignalUrl("ftp://127.0.0.1"), fixed);
	CHECK_EQ(EndSignalUrl("http://127.0.0.1:8099/"), std::string("http://127.0.0.1:8099/api/ingest/end-signal"));
	CHECK_EQ(EndSignalUrl("http://localhost:8099"), std::string("http://localhost:8099/api/ingest/end-signal"));
	CHECK_EQ(EndSignalUrl("HTTP://LocalHost:8099"), std::string("HTTP://LocalHost:8099/api/ingest/end-signal"));
	CHECK_EQ(EndSignalUrl("https://[::1]:8443"), std::string("https://[::1]:8443/api/ingest/end-signal"));
	CHECK(IsLoopbackBase("http://127.255.0.9"));
	CHECK(!IsLoopbackBase("http://"));
}

TEST(end_signal_body_fields_and_escaping)
{
	CHECK_EQ(EndSignalBodyJson("#!::r=ABCDEFGHJKMNPQRSTVWXYZ0123,m=publish", 420, 1834000),
		 std::string(R"({"streamid":"#!::r=ABCDEFGHJKMNPQRSTVWXYZ0123,m=publish","elapsedSinceStopMs":420,)"
			     R"("connectionDurationMs":1834000})"));
	CHECK_EQ(EndSignalBodyJson("a\"b\\c", 0, 0),
		 std::string(R"({"streamid":"a\"b\\c","elapsedSinceStopMs":0,"connectionDurationMs":0})"));
}

TEST(end_signal_response_classification)
{
	auto is = [](const EndSignalVerdict &v, EndSignalOutcome o, const char *reason) {
		return v.outcome == o && std::string(v.reason) == reason;
	};
	CHECK(is(ClassifyEndSignalResponse(false, 0), EndSignalOutcome::Retry, "network"));
	CHECK(is(ClassifyEndSignalResponse(true, 0), EndSignalOutcome::Retry, "network")); // 응답 없이 끝난 전송
	CHECK(is(ClassifyEndSignalResponse(true, 202), EndSignalOutcome::Done, "accepted"));
	CHECK(is(ClassifyEndSignalResponse(true, 200), EndSignalOutcome::Done, "accepted_unexpected_2xx"));
	CHECK(is(ClassifyEndSignalResponse(true, 204), EndSignalOutcome::Done, "accepted_unexpected_2xx"));
	CHECK(is(ClassifyEndSignalResponse(true, 301), EndSignalOutcome::Stop, "redirect"));
	CHECK(is(ClassifyEndSignalResponse(true, 307), EndSignalOutcome::Stop, "redirect"));
	CHECK(is(ClassifyEndSignalResponse(true, 400), EndSignalOutcome::Stop, "bad_request"));
	CHECK(is(ClassifyEndSignalResponse(true, 401), EndSignalOutcome::Stop, "unauthorized"));
	CHECK(is(ClassifyEndSignalResponse(true, 404), EndSignalOutcome::Stop, "rejected")); // 수신부 배포 전
	CHECK(is(ClassifyEndSignalResponse(true, 429), EndSignalOutcome::Stop, "rate_limited"));
	CHECK(is(ClassifyEndSignalResponse(true, 500), EndSignalOutcome::Retry, "server_error"));
	CHECK(is(ClassifyEndSignalResponse(true, 503), EndSignalOutcome::Retry, "server_error"));
}

TEST(end_signal_retry_is_short_and_bounded)
{
	CHECK_EQ(EndSignalRetryDelayMs(1), int64_t(1000));
	CHECK_EQ(EndSignalRetryDelayMs(2), int64_t(2000));
	CHECK_EQ(EndSignalRetryDelayMs(3), int64_t(-1)); // 셋째 시도까지 — 그 뒤는 포기
	CHECK_EQ(EndSignalRetryDelayMs(0), int64_t(-1));
	CHECK(!EndSignalExpired(0));
	CHECK(!EndSignalExpired(kEndSignalExpireMs));
	CHECK(EndSignalExpired(kEndSignalExpireMs + 1));
	CHECK(EndSignalExpired(-1));
}

TEST(end_signal_sent_only_for_intentional_stop_after_a_connection)
{
	CHECK(EndSignalSkipReason(true, true) == EndSignalSkip::None);
	CHECK(EndSignalSkipReason(false, true) == EndSignalSkip::NotIntentional); // 오류·포기·재시도 중지·본방 자력 종료
	CHECK(EndSignalSkipReason(true, false) == EndSignalSkip::NeverConnected); // 한 번도 붙지 못한 송출
	CHECK(EndSignalSkipReason(false, false) == EndSignalSkip::NotIntentional);
	CHECK_EQ(std::string(EndSignalSkipName(EndSignalSkip::None)), std::string("send"));
	CHECK_EQ(std::string(EndSignalSkipName(EndSignalSkip::NotIntentional)), std::string("not_intentional"));
	CHECK_EQ(std::string(EndSignalSkipName(EndSignalSkip::NeverConnected)), std::string("never_connected"));
}

TEST(end_signal_connection_duration_uses_last_connection)
{
	// 멈출 때 붙어 있었다 — 성립 1000 → 멈춤 5000
	CHECK_EQ(ConnectionDurationMs(1000, true, 0, 5000), int64_t(4000));
	// 멈추기 전에 끊겨 다시 붙는 중이었다 — 성립 1000 → 끊김 3000, 멈춤은 9000이지만 끊긴 연결까지만
	CHECK_EQ(ConnectionDurationMs(1000, false, 3000, 9000), int64_t(2000));
	// 시계 어긋남은 0으로
	CHECK_EQ(ConnectionDurationMs(5000, true, 0, 4000), int64_t(0));
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
