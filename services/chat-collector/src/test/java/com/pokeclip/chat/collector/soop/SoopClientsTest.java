package com.pokeclip.chat.collector.soop;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static com.pokeclip.chat.collector.soop.FakeSoopHttp.granted;
import static com.pokeclip.chat.collector.soop.FakeSoopHttp.ok;
import static com.pokeclip.chat.collector.soop.FakeSoopHttp.rejected;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOOP 연동 조회(auth)와 SOOP 방송 여부 체크(POK-397). 응답 칸 이름은 아직 실방송으로 확정 전이라 여기서 고정하는 것은 이 서버가 지금
 * 읽는 이름이다({@link SoopLiveClient} 주석). 거부와 일시를 가르는 규칙은 auth {@code SoopOAuthClient}와 같다.
 */
class SoopClientsTest {

    private static final String TOKEN = "soop-access-secret-1";
    private static final String SOOP_ID = "soopid1";

    private FakeSoopHttp fake;

    @BeforeEach
    void start() {
        fake = FakeSoopHttp.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    // ── 연동 조회 ──

    @Test
    void 연동이_있으면_아이디_토큰_만료를_받는다() {
        fake.resolveAnswers(ok(granted(SOOP_ID, TOKEN, "2026-10-24T08:00:00Z")));

        SoopLink link = linkClient().resolve(42L);

        assertThat(link.usable()).isTrue();
        assertThat(link.soopUserId()).isEqualTo(SOOP_ID);
        assertThat(link.accessToken()).isEqualTo(TOKEN);
        assertThat(link.expiresAt()).isEqualTo(Instant.parse("2026-10-24T08:00:00Z"));
        FakeSoopHttp.Received request = fake.requests(FakeSoopHttp.RESOLVE).getFirst();
        assertThat(request.internalToken()).isEqualTo("internal-token");
        assertThat(request.body()).isEqualTo("{\"userId\":42}");
    }

    /** 영구 사유 셋만 다시 안 묻는다. 갱신 일시 실패·5xx·모르는 사유는 다시 묻는다(치지직과 같은 규칙). */
    @Test
    void 영구_사유만_다시_안_묻고_나머지는_다시_묻는다() {
        for (String reason : new String[]{"NOT_LINKED", "UNLINKED", "BROKEN"}) {
            fake.resolveAnswers(ok(rejected(reason)));
            SoopLink link = linkClient().resolve(42L);
            assertThat(link.usable()).as(reason).isFalse();
            assertThat(link.retryable()).as(reason).isFalse();
        }
        fake.resolveAnswers(ok(rejected("REFRESH_UNAVAILABLE")));
        assertThat(linkClient().resolve(42L).retryable()).isTrue();
        fake.resolveAnswers(ok(rejected("NEW_REASON")));
        assertThat(linkClient().resolve(42L).retryable()).isTrue();
        fake.resolveAnswers(new FakeSoopHttp.Response(503, "{}"));
        SoopLink down = linkClient().resolve(42L);
        assertThat(down.usable()).isFalse();
        assertThat(down.retryable()).isTrue();
    }

    @Test
    void 허락인데_칸이_빠지면_영구로_둔다() {
        fake.resolveAnswers(ok("{\"valid\":true,\"accessToken\":\"" + TOKEN + "\",\"expiresAt\":\"2026-10-24T08:00:00Z\"}"));

        SoopLink link = linkClient().resolve(42L);

        assertThat(link.usable()).isFalse();
        assertThat(link.retryable()).isFalse();
    }

    /** record를 로그 {@code {}}에 넣어도 토큰·아이디가 안 나온다(계획 검증 L11). */
    @Test
    void 연동_record의_글자에는_토큰과_아이디가_없다() {
        String text = SoopLink.granted(SOOP_ID, TOKEN, Instant.EPOCH).toString();

        assertThat(text).doesNotContain(TOKEN).doesNotContain(SOOP_ID).contains("usable=true");
    }

    // ── 방송 여부 체크 ──

    /**
     * 실측 2026-10-24(관리 세션 진단 로그, 값 없이 키 이름만). 방송 중 data의 키:
     * {@code broad_cate_no, broad_no, broad_start, broad_thumb, broad_title, current_view_cnt, language, link, paid_promotion, status, user_id},
     * status는 {@code streaming}. 방송 아님은 {@code broad_no, status}뿐이고 status는 {@code streamend}. 아래 응답은 그 키 목록 그대로다(값은 지어냈다).
     */
    private static final String 실측_방송_중 = "{\"result\":1,\"data\":{\"broad_cate_no\":\"00040019\",\"broad_no\":\"281234567\","
            + "\"broad_start\":\"2026-10-24 10:00:00\",\"broad_thumb\":\"//liveimg.example/thumb.jpg\",\"broad_title\":\"숲 방송\","
            + "\"current_view_cnt\":321,\"language\":\"ko_KR\",\"link\":\"https://play.example/x\",\"paid_promotion\":0,"
            + "\"status\":\"streaming\",\"user_id\":\"" + SOOP_ID + "\"}}";

    /** 실측 키 목록 그대로의 방송 중 응답: 제목·시청자 수를 읽고, 카테고리는 번호(broad_cate_no)만 와서 null로 둔다. 토큰은 form 본문에만. */
    @Test
    void 방송_중이면_제목과_시청자_수를_읽고_토큰은_form_본문에만_싣는다() {
        fake.statusAnswers(ok(실측_방송_중));

        SoopLiveStatus status = liveClient(false).check(TOKEN);

        assertThat(status.outcome()).isEqualTo(SoopLiveStatus.Outcome.ON_AIR);
        assertThat(status.title()).isEqualTo("숲 방송");
        assertThat(status.viewers()).isEqualTo(321);
        assertThat(status.category()).as("번호만 오고 이름 표가 없다").isNull();
        FakeSoopHttp.Received request = fake.requests(FakeSoopHttp.STATUS).getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.rawQuery()).isNull();
        assertThat(request.contentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(request.body()).isEqualTo("access_token=" + TOKEN);
    }

    @Test
    void 시청자_수가_글자_숫자여도_읽고_못_읽으면_null이다() {
        fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"streaming\",\"current_view_cnt\":\"45\"}}"));
        assertThat(liveClient(false).check(TOKEN).viewers()).isEqualTo(45);

        fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"streaming\",\"current_view_cnt\":\"많음\"}}"));
        SoopLiveStatus odd = liveClient(false).check(TOKEN);
        assertThat(odd.outcome()).isEqualTo(SoopLiveStatus.Outcome.ON_AIR);
        assertThat(odd.viewers()).isNull();
        assertThat(odd.title()).isNull();
    }

    /** 실측(2026-10-23 auth · 2026-10-24 진단): 방송 중이 아니면 data가 broad_no·status(streamend)뿐이다. 거부가 아니고 경고도 없다. */
    @Test
    void 방송이_아니면_방송_아님이다() {
        fake.statusAnswers(ok("{\"result\":1,\"data\":{\"broad_no\":\"0\",\"status\":\"streamend\"}}"));

        try (LogCaptor captor = new LogCaptor()) {
            assertThat(liveClient(false).check(TOKEN).outcome()).isEqualTo(SoopLiveStatus.Outcome.OFF_AIR);
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("soop.live.unknown_status"));
        }
    }

    /** 문서와 다른 낱말 onair(첫 판의 잠정 값)는 방송 중이 아니다. 모르는 값이라 경고가 남는다. */
    @Test
    void onair는_방송_중이_아니고_모르는_값으로_경고한다() {
        fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"onair\",\"current_view_cnt\":3}}"));

        try (LogCaptor captor = new LogCaptor()) {
            assertThat(liveClient(false).check(TOKEN).outcome()).isEqualTo(SoopLiveStatus.Outcome.OFF_AIR);
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("soop.live.unknown_status value=onair"));
        }
    }

    /**
     * 감사 2회차 n3: 모르는 status 값이면 「방송 아님」으로 두되 그 값마다 첫 번만 WARN 한 줄을 남긴다. 값 낱말이 다르면 SOOP 줄이 하나도 안 생기는데
     * 아무 데도 안 남던 자리다. status는 방송 상태 낱말이라 값을 싣되 32자로 자른다. 아는 값(streaming·streamend)은 안 남긴다.
     */
    @Test
    void 모르는_status는_값마다_첫_번만_경고한다() {
        SoopLiveClient client = liveClient(false);
        try (LogCaptor captor = new LogCaptor()) {
            fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"streamend\"}}"));
            client.check(TOKEN);
            fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"live_now\"}}"));
            assertThat(client.check(TOKEN).outcome()).isEqualTo(SoopLiveStatus.Outcome.OFF_AIR);
            client.check(TOKEN);
            String longValue = "x".repeat(50);
            fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"" + longValue + "\"}}"));
            client.check(TOKEN);
            fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":1}}"));
            client.check(TOKEN);

            List<String> warns = captor.messages().stream().filter(m -> m.startsWith("soop.live.unknown_status")).toList();
            assertThat(warns).hasSize(3);
            assertThat(warns.get(0)).contains("value=live_now").contains("length=8");
            assertThat(warns.get(1)).contains("value=" + "x".repeat(32) + " ").contains("length=50").doesNotContain("x".repeat(33));
            assertThat(warns.get(2)).contains("type=Integer");
            assertThat(captor.levelOf("soop.live.unknown_status")).isEqualTo(Level.WARN);
        }
    }

    /** 거부는 HTTP 200 + result 음수와 400·401·403이다. 나머지 상태·못 읽는 본문은 일시다(auth SoopOAuthClient와 같다). */
    @Test
    void 거부와_일시를_가른다() {
        fake.statusAnswers(ok("{\"result\":-1,\"msg\":\"bad token\"}"));
        assertThat(liveClient(false).check(TOKEN).outcome()).isEqualTo(SoopLiveStatus.Outcome.REJECTED);
        for (int code : new int[]{400, 401, 403}) {
            fake.statusAnswers(new FakeSoopHttp.Response(code, "{}"));
            assertThat(liveClient(false).check(TOKEN).outcome()).as("" + code).isEqualTo(SoopLiveStatus.Outcome.REJECTED);
        }
        for (int code : new int[]{404, 429, 500, 503}) {
            fake.statusAnswers(new FakeSoopHttp.Response(code, "{}"));
            assertThat(liveClient(false).check(TOKEN).outcome()).as("" + code).isEqualTo(SoopLiveStatus.Outcome.UNAVAILABLE);
        }
        fake.statusAnswers(ok("{\"data\":{}}"));
        assertThat(liveClient(false).check(TOKEN).outcome()).isEqualTo(SoopLiveStatus.Outcome.UNAVAILABLE);
        fake.statusAnswers(ok("{\"result\":1,\"data\":\"x\"}"));
        assertThat(liveClient(false).check(TOKEN).outcome()).isEqualTo(SoopLiveStatus.Outcome.UNAVAILABLE);
    }

    /**
     * 진단 스위치를 켜면 data의 <b>키 이름과 status 값만</b> INFO 한 줄로 남긴다. 아이디·제목·토큰 같은 값은 안 찍는다. 꺼져 있으면 안 남긴다.
     */
    @Test
    void 진단은_키_이름과_status_값만_남긴다() {
        fake.statusAnswers(ok("{\"result\":1,\"data\":{\"status\":\"streaming\",\"user_id\":\"" + SOOP_ID
                + "\",\"broad_title\":\"비밀 제목\",\"current_view_cnt\":9}}"));

        try (LogCaptor captor = new LogCaptor()) {
            liveClient(false).check(TOKEN);
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("soop.live.diagnose"));

            liveClient(true).check(TOKEN);
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.live.diagnose")).singleElement()
                    .satisfies(m -> assertThat(m)
                            .contains("keys=[broad_title, current_view_cnt, status, user_id]")
                            .contains("status=streaming")
                            .doesNotContain(SOOP_ID).doesNotContain("비밀 제목").doesNotContain(TOKEN));
            assertThat(captor.levelOf("soop.live.diagnose")).isEqualTo(Level.INFO);
        }
    }

    private SoopLinkClient linkClient() {
        return new SoopLinkClient(jdk(), new LinkProperties(fake.baseUrl(), "internal-token"));
    }

    /**
     * SOOP 카테고리 리스트(POK-462). 문서의 응답 모양 그대로: 대카테고리 아래 {@code child}가 있다. 자식까지 번호 → 이름으로 편다.
     * 앱 아이디는 {@code client_id} 쿼리로 싣는다(문서 표는 {@code access_token}이라 적었지만 예시와 실서버가 {@code client_id}다:
     * 인자 없이 부르면 -1100, 틀린 값이면 -1104 「invalid client」. 2026-10-25 실측).
     */
    @Test
    void 카테고리_리스트를_자식까지_번호_이름으로_편다() {
        fake.categoryAnswers(ok("""
                {"broad_category":[{"cate_name":"토크/캠방","cate_no":"00130000","child":[]},
                 {"cate_name":"게임","cate_no":"00040000","child":[{"cate_name":"LOL","cate_no":"00040019"},
                  {"cate_name":"TFT","cate_no":"00040075"}]}]}"""));

        java.util.Map<String, String> names = new SoopCategoryClient(jdk(), fake.baseUrl(), "app-id").fetch();

        assertThat(names).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "00130000", "토크/캠방", "00040000", "게임", "00040019", "LOL", "00040075", "TFT"));
        FakeSoopHttp.Received request = fake.requests(FakeSoopHttp.CATEGORIES).getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.rawQuery()).isEqualTo("client_id=app-id&locale=ko_KR");
    }

    /** 결과 코드가 음수거나(앱 아이디가 틀림) 목록이 없거나 HTTP가 실패하면 던진다. 부르는 쪽이 앞 표를 지키고 다시 묻는다. */
    @Test
    void 카테고리_리스트가_거절되거나_비면_던진다() {
        SoopCategoryClient client = new SoopCategoryClient(jdk(), fake.baseUrl(), "app-id");
        for (FakeSoopHttp.Response answer : List.of(ok("{\"result\":-1104,\"msg\":\"invalid client\"}"),
                ok("{\"broad_category\":[]}"), new FakeSoopHttp.Response(503, "{}"))) {
            fake.categoryAnswers(answer);
            org.assertj.core.api.Assertions.assertThatThrownBy(client::fetch).as(answer.toString())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    /** 방송 중 응답의 카테고리 번호를 이름 표로 바꾼다(POK-462). 번호가 숫자로 와도(문서 예시 40075) 8자리 글자로 맞춘다. */
    @Test
    void 방송_중이면_카테고리_번호를_이름으로_바꾼다() {
        java.util.Map<String, String> names = java.util.Map.of("00040019", "LOL", "00040075", "TFT");
        SoopLiveClient client = new SoopLiveClient(jdk(), fake.baseUrl(), false,
                raw -> names.get(SoopCategoryNames.normalize(raw)), name -> "LOL".equals(name) ? "https://img.example/lol.jpg" : null);

        fake.statusAnswers(ok(실측_방송_중));
        SoopLiveStatus lol = client.check(TOKEN);
        assertThat(lol.category()).isEqualTo("LOL");
        assertThat(lol.categoryImageUrl()).as("이름으로 빌린 사진").isEqualTo("https://img.example/lol.jpg");
        fake.statusAnswers(ok(실측_방송_중.replace("\"broad_cate_no\":\"00040019\"", "\"broad_cate_no\":40075")));
        assertThat(client.check(TOKEN).category()).isEqualTo("TFT");
        fake.statusAnswers(ok(실측_방송_중.replace("00040019", "00099999")));
        SoopLiveStatus unknown = client.check(TOKEN);
        assertThat(unknown.category()).as("표에 없는 번호").isNull();
        assertThat(unknown.categoryImageUrl()).as("이름이 없으면 사진도 안 묻는다").isNull();
    }

    /**
     * 앱 값이 비면 이름 표 받기 · 사진 검색을 아예 안 만든다(POK-462). 앱 값 없는 환경(CI · 팀원 로컬)이 빈 client_id로 SOOP · 치지직을
     * 10분마다 두드리지 않게. 받기는 단일 스레드 실행기에서 도므로 그 뒤에 표지 작업을 넣고 끝나기를 기다리면 앞서 맡긴 것은 다 돈 뒤다.
     * 양성 대조: 앱 값이 있으면 같은 길로 실제로 묻는다.
     */
    @Test
    void 앱_값이_비면_이름_표와_사진_빌리기를_안_만든다() throws Exception {
        fake.categoryAnswers(ok("{\"broad_category\":[{\"cate_name\":\"LOL\",\"cate_no\":\"00040019\"}]}"));

        assertThat(SoopWatchConfiguration.Enabled.categoryNames(jdk(), fake.baseUrl(), " ").apply(40019)).isNull();
        assertThat(SoopWatchConfiguration.Enabled.categoryImages(jdk(), fake.baseUrl(), "id", "").apply("LOL")).isNull();
        SoopWatchConfiguration.CATEGORY_LOOKUPS.submit(() -> { }).get();
        assertThat(fake.requests(FakeSoopHttp.CATEGORIES)).isEmpty();

        SoopWatchConfiguration.Enabled.categoryNames(jdk(), fake.baseUrl(), "app-id").apply(40019);
        SoopWatchConfiguration.CATEGORY_LOOKUPS.submit(() -> { }).get();
        assertThat(fake.requests(FakeSoopHttp.CATEGORIES)).as("앱 값이 있으면 묻는다").hasSize(1);
    }

    private SoopLiveClient liveClient(boolean diagnose) {
        return new SoopLiveClient(jdk(), fake.baseUrl(), diagnose);
    }

    /** 운영과 같은 JDK 스택(Apache 5의 wire 로거가 본문을 찍는다). */
    private static RestClient.Builder jdk() {
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory());
    }
}
