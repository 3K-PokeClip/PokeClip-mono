// 의존성 없는 최소 테스트 러너. OBS를 띄우지 않고 검증할 수 있는 것만 여기서 잰다:
// 페어링 코드 정규화 · keyint 옵션 제거 · streamid 파싱 · SRT URL · 브리지 보안 규칙(Host·Origin·토큰)·SSE ·
// 오디오 트랙 자동 배정(A2).
#include "audio-assign.hpp"
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


// ---------------------------------------------------------------- 오디오 트랙 자동 배정 (A2)

AudioSourceInfo Src(const std::string &key, const std::string &name, AudioKind kind, int order,
		    uint32_t mixers = 0x3F, bool active = true, bool monitorOnly = false)
{
	AudioSourceInfo s;
	s.key = key;
	s.name = name;
	s.kind = kind;
	s.order = order;
	s.mixers = mixers;
	s.audioActive = active;
	s.monitorOnly = monitorOnly;
	return s;
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

// 수동 모드(스위치 꺼짐)에서도 화면은 실제 비트를 그대로 보여준다 — 한 트랙에 여럿, 여러 트랙에 하나.
TEST(audio_routing_view_reflects_actual_bits)
{
	std::vector<AudioSourceInfo> sources = {Src("ch:3", "마이크", AudioKind::Mic, 3, 0x07),
						Src("ch:1", "데스크탑", AudioKind::Desktop, 1, 0x01),
						Src("uuid:game", "게임", AudioKind::App, 100, 0x05),
						Src("uuid:fx", "효과음", AudioKind::Media, 101, 0x3F, true, true),
						Src("uuid:overlay", "채팅창", AudioKind::Browser, 102, 0x3F, false)};
	AudioRoutingView v = BuildRoutingView(sources, false, false, 0);
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
