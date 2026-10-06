// 의존성 없는 최소 테스트 러너. OBS를 띄우지 않고 검증할 수 있는 것만 여기서 잰다:
// 페어링 코드 정규화 · keyint 옵션 제거 · streamid 파싱 · SRT URL · 브리지 보안 규칙(Host·Origin·토큰)·SSE ·
// 오디오 트랙 자동 배정(A2) · 핫키 마킹 규칙(A4) · 재시도 정책(A5).
#include "audio-assign.hpp"
#include "bridge-server.hpp"
#include "config.hpp"
#include "private-file.hpp"
#include "encoder-opts.hpp"
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
		if (r.slotKey[static_cast<size_t>(k)] == key)
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
	CHECK(r.slotKey[5].empty());
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

TEST(audio_overflow_goes_mix_only_lowest_priority)
{
	AssignmentInput in;
	in.now = 100;
	in.sources = {Src("uuid:other", "캡처보드", AudioKind::Other, 100), Src("uuid:alert", "알림", AudioKind::Browser, 101),
		      Src("uuid:bgm", "BGM", AudioKind::Media, 102),       Src("uuid:discord", "Discord", AudioKind::App, 103),
		      Src("ch:1", "데스크탑", AudioKind::Desktop, 1),       Src("ch:3", "마이크", AudioKind::Mic, 3),
		      Src("ch:4", "마이크 2", AudioKind::Mic, 4)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(r.overflowKeys.size(), 2u);
	CHECK_EQ(SlotOf(r, "uuid:alert"), 0);
	CHECK_EQ(SlotOf(r, "uuid:other"), 0);
	CHECK_EQ(SlotOf(r, "ch:3"), 1);
	CHECK_EQ(SlotOf(r, "ch:4"), 2);
	CHECK_EQ(WriteOf(r, "uuid:alert", 0x3F), 0x01u); // 믹스에만 남는다
	CHECK(!MapHas(r, "uuid:alert"));
}

// 지금 없는 소스의 기억은 자리를 잡지 않지만 버리지도 않는다. 돌아오면 먼저 앉은 쪽이 자리를 되찾는다.
TEST(audio_absent_memory_frees_slot_and_comes_back)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:a", 1, 10), Entry("uuid:b", 2, 10)};
	in.sources = {Src("uuid:b", "B", AudioKind::Media, 100), Src("uuid:c", "C", AudioKind::Media, 101)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:b"), 2);
	CHECK_EQ(SlotOf(r, "uuid:c"), 1);
	CHECK(MapHas(r, "uuid:a"));

	AssignmentInput back;
	back.now = 200;
	back.map = r.mapNext;
	back.sources = Applied(in.sources, r);
	back.sources.push_back(Src("uuid:a", "A", AudioKind::Media, 102, 0x01));
	AssignmentResult r2 = ComputeAssignment(back);
	CHECK_EQ(SlotOf(r2, "uuid:a"), 1); // assignedAt 10 < 100
	CHECK_EQ(SlotOf(r2, "uuid:c"), 3);
	CHECK_EQ(SlotOf(r2, "uuid:b"), 2);
}

TEST(audio_locked_keeps_the_source_currently_on_the_track)
{
	AssignmentInput in;
	in.now = 100;
	in.locked = true;
	in.map = {Entry("uuid:a", 1, 10), Entry("uuid:c", 1, 50)};
	in.sources = {Src("uuid:a", "A", AudioKind::Media, 100, 0x01), Src("uuid:c", "C", AudioKind::Media, 101, 0x03)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:c"), 1); // 지금 트랙 2에서 나가고 있다 — 방송 중에는 옮기지 않는다
	CHECK_EQ(SlotOf(r, "uuid:a"), 2);

	in.locked = false;
	AssignmentResult free = ComputeAssignment(in);
	CHECK_EQ(SlotOf(free, "uuid:a"), 1); // 방송이 아니면 먼저 앉은 쪽
}

// 방송 중 A를 지우자 C가 그 트랙을 받았고, 이어서 A를 되살리면 A도 저장값대로 그 트랙이 켜진 채 온다.
// 둘 다 켜져 있으면 그 사이 트랙을 받아 나가고 있던 C를 남긴다.
TEST(audio_locked_keeps_owner_when_restored_source_returns_with_bits)
{
	AssignmentInput in;
	in.now = 100;
	in.locked = true;
	in.map = {Entry("uuid:a", 1, 10), Entry("uuid:c", 1, 50)};
	in.sources = {Src("uuid:a", "A", AudioKind::Media, 100, 0x03), Src("uuid:c", "C", AudioKind::Media, 101, 0x03)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:c"), 1);
	CHECK_EQ(SlotOf(r, "uuid:a"), 2);
	CHECK_EQ(WriteOf(r, "uuid:c", 0x03), 0x03u);
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
	CHECK_EQ(SlotOf(r, "uuid:bgm"), 1);
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

TEST(audio_loser_without_room_is_forgotten)
{
	AssignmentInput in;
	in.now = 100;
	in.map = {Entry("uuid:a", 1, 10), Entry("uuid:b", 1, 20)};
	in.sources = {Src("uuid:a", "A", AudioKind::Other, 100), Src("uuid:b", "B", AudioKind::Other, 101),
		      Src("ch:3", "M1", AudioKind::Mic, 3),        Src("ch:4", "M2", AudioKind::Mic, 4),
		      Src("ch:5", "M3", AudioKind::Mic, 5),        Src("ch:6", "M4", AudioKind::Mic, 6)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(SlotOf(r, "uuid:a"), 1);
	CHECK_EQ(SlotOf(r, "uuid:b"), 0);
	CHECK(!MapHas(r, "uuid:b"));
	CHECK_EQ(r.overflowKeys.size(), 1u);
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
	in.map = {Entry("uuid:zero", 0, 1), Entry("uuid:six", 6, 1), Entry("", 1, 1), Entry("uuid:dup", 2, 1),
		  Entry("uuid:dup", 3, 1)};
	AssignmentResult r = ComputeAssignment(in);
	CHECK_EQ(r.mapNext.size(), 1u);
	CHECK_EQ(r.mapNext[0].slot, 2);
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
	CHECK(r.slotKey[1].empty());
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
	in.locked = true;
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
	CHECK(line.find("mix-only 1") != std::string::npos);
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
	CHECK_EQ(v.mixOnly[0], std::string("마이크"));
	CHECK(v.monitorOnly.empty()); // 화면에 없는 효과음
	CHECK(DescribeRouting(v).find("T2 main-stream 마이크(mic)") != std::string::npos);

	options.applied = false;
	options.deferred = true;
	AudioRoutingView deferred = BuildRoutingView(sources, options);
	CHECK(deferred.deferred);
	CHECK(DescribeRouting(deferred).find("deferred") != std::string::npos);
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
