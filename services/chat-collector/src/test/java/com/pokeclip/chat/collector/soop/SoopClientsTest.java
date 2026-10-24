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

    private SoopLiveClient liveClient(boolean diagnose) {
        return new SoopLiveClient(jdk(), fake.baseUrl(), diagnose);
    }

    /** 운영과 같은 JDK 스택(Apache 5의 wire 로거가 본문을 찍는다). */
    private static RestClient.Builder jdk() {
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory());
    }
}
