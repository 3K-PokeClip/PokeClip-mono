package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 60일 지난 방송 목록을 1번(영상 서버)에게 주는 내부 창구(POK-335, 계약-세그먼트인덱스 6-2).
 *
 * <p>🔴 <b>녹화 조각은 방송 번호가 아니라 물리 키 + 시각으로 쌓인다.</b> 한 스트리머의 여러 방송이 같은 물리 키를 나눠 쓰므로
 * 방송 번호만 주면 1번이 안 만료된 방송 조각까지 지울 수 있다. 그래서 창구는 「지워도 되는 구간」을 함께 준다: 같은 물리 키의
 * 방송을 시간 순으로 나란히 놓고 자르는 점 하나(뒤 방송 자리 − 2분)로 칸을 나눈다. 구간 = [max(앞 방송 끝, 이 방송 자리 − 2분),
 * 뒤 방송 자리 − 2분)이고, 앞이 없으면 처음부터(null), 뒤가 없으면 끝 + 10분. 방송 사이 빈 시간은 앞 방송 몫이다.
 * 아래 경계 시험은 「조각 하나가 정확히 한 방송에만 속한다」를 실제 시각으로 잰다.
 */
@AutoConfigureMockMvc
class ExpiredBroadcastControllerTest extends IntegrationTestSupport {

    private static final String 창구 = "/internal/broadcasts/expired";

    /** application-test.yml의 pokeclip.internal-api.token과 같은 값. */
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    /** 1번이 넘기는 「지금」. 아래 방송들의 기한(끝 + 60일)보다 늦다 */
    private static final Instant 지금 = Instant.parse("2026-10-01T00:00:00Z");

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    ExpiredBroadcastControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    @AfterEach
    void 방송을_비운다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── 문 ────────────────────────────────────────────────────────

    @Test
    void 내부_토큰이_없거나_틀리거나_사람_토큰이면_401이다() throws Exception {
        String url = 창구 + "?before=" + 지금;
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Authorization", "Bearer " + TestTokens.access(TestIds.STREAMER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void before가_없거나_시각이_아니면_400이다() throws Exception {
        mvc.perform(get(창구).header("X-Internal-Token", INTERNAL))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("before"));
        mvc.perform(get(창구 + "?before=어제").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("before"));
        mvc.perform(get(창구 + "?before=" + 지금 + "&cursor=없는표시").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cursor"));
    }

    /** 형식은 맞는데 기한이 시각 범위 밖인 표시는 DB까지 가면 500이다: 그 전에 400으로 접는다(PR #348 claude) */
    @Test
    void 기한이_시각_범위_밖인_표시는_400이다() throws Exception {
        String 표시 = CursorCodec.encode(CursorCodec.Kind.EXPIRED, Long.MAX_VALUE, 1);
        mvc.perform(get(창구 + "?before=" + 지금 + "&cursor=" + 표시).header("X-Internal-Token", INTERNAL))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cursor"));
    }

    // ── 무엇이 나오나 ───────────────────────────────────────────

    /** 기한이 비었거나(안 끝남) before와 같거나 뒤인 방송은 안 나온다. 「같으면 안 냄」이 경계다 */
    @Test
    void 기한이_before보다_앞인_방송만_나온다() throws Exception {
        Instant 경계 = Instant.parse("2026-09-01T00:00:00Z");
        방송("S-live", "k-1", T0, null, null);
        방송("S-past", "k-2", T0, T0.plusSeconds(3600), 경계.minusMillis(1));
        방송("S-same", "k-3", T0, T0.plusSeconds(3600), 경계);
        방송("S-future", "k-4", T0, T0.plusSeconds(3600), 경계.plusSeconds(1));

        JsonNode body = 부른다(경계, null);
        assertThat(번호들(body)).containsExactly("S-past");
        assertThat(body.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void 칸_일곱을_준다_물리_키가_비면_방송_번호다() throws Exception {
        Instant 시작 = T0;
        Instant 끝 = T0.plusSeconds(3600);
        방송("S-1", null, 시작, 끝, 끝.plus(Duration.ofDays(60)));

        JsonNode 줄 = 부른다(지금, null).get("broadcasts").get(0);
        Set<String> 칸 = new HashSet<>();
        줄.propertyNames().forEach(칸::add);
        assertThat(칸).containsExactlyInAnyOrder(
                "streamId", "ingestStreamId", "startedAt", "endedAt", "vodExpiresAt", "segmentsFrom", "segmentsUntil");
        assertThat(줄.get("ingestStreamId").asString()).isEqualTo("S-1");
        assertThat(Instant.parse(줄.get("startedAt").asString())).isEqualTo(시작);
        assertThat(Instant.parse(줄.get("endedAt").asString())).isEqualTo(끝);
        assertThat(Instant.parse(줄.get("vodExpiresAt").asString())).isEqualTo(끝.plus(Duration.ofDays(60)));
    }

    /** 같은 물리 키의 방송 둘 중 만료된 것만 나오고, 그 구간은 안 만료된 뒤 방송 시작 2분 전에서 멈춘다 */
    @Test
    void 같은_물리_키의_방송_둘_중_만료된_것만_구간과_함께_나온다() throws Exception {
        Instant 옛_시작 = T0;
        Instant 옛_끝 = T0.plusSeconds(3600);
        Instant 새_시작 = Instant.parse("2026-09-20T00:00:00Z");
        방송("S-old", "key", 옛_시작, 옛_끝, 옛_끝.plus(Duration.ofDays(60)));
        방송("S-new", "key", 새_시작, 새_시작.plusSeconds(3600), 새_시작.plusSeconds(3600).plus(Duration.ofDays(60)));

        JsonNode body = 부른다(지금, null);
        assertThat(번호들(body)).containsExactly("S-old");
        JsonNode 줄 = body.get("broadcasts").get(0);
        assertThat(줄.get("segmentsFrom").isNull()).as("앞 방송이 없으면 처음부터").isTrue();
        assertThat(Instant.parse(줄.get("segmentsUntil").asString())).isEqualTo(새_시작.minus(Duration.ofMinutes(2)));
    }

    // ── 구간 규칙(ⓔ) ───────────────────────────────────────────

    /**
     * 🔴 같은 물리 키의 방송 셋(앞 · 가운데 · 뒤)이 다 만료됐을 때 구간이 빈틈 · 겹침 없이 칸을 나누는지, 조각을 실제 시각으로 심어
     * 각 조각이 정확히 한 구간에만 드는지 잰다. 경계 바로 앞 · 위 · 뒤 조각과, 방송 사이 빈 시간의 조각(시험 송출)을 넣는다.
     */
    @Test
    void 조각_하나는_정확히_한_방송의_구간에만_든다() throws Exception {
        Instant a시작 = T0, a끝 = T0.plusSeconds(3600);
        Instant b시작 = T0.plusSeconds(7200), b끝 = T0.plusSeconds(10800);
        Instant c시작 = T0.plusSeconds(14400), c끝 = T0.plusSeconds(18000);
        방송("S-a", "key", a시작, a끝, a끝.plus(Duration.ofDays(60)));
        방송("S-b", "key", b시작, b끝, b끝.plus(Duration.ofDays(60)));
        방송("S-c", "key", c시작, c끝, c끝.plus(Duration.ofDays(60)));

        List<long[]> 구간들 = new ArrayList<>();
        for (JsonNode 줄 : 부른다(지금, null).get("broadcasts")) 구간들.add(구간(줄));
        assertThat(구간들).hasSize(3);

        // 경계 앞뒤 1초 · 경계 위 · 방송 사이 빈 시간 · 첫 방송 훨씬 앞 · 마지막 방송 끝 + 10분 직전
        List<Instant> 조각들 = List.of(
                a시작.minusSeconds(86_400), a시작, a끝, a끝.plusSeconds(1),
                b시작.minus(Duration.ofMinutes(2)).minusSeconds(1), b시작.minus(Duration.ofMinutes(2)), b시작,
                b끝, b끝.plusSeconds(1800), c시작.minus(Duration.ofMinutes(2)), c시작, c끝,
                c끝.plus(Duration.ofMinutes(10)).minusSeconds(1));
        for (Instant 조각 : 조각들) {
            long 든_구간 = 구간들.stream().filter(r -> r[0] <= 조각.toEpochMilli() && 조각.toEpochMilli() < r[1]).count();
            assertThat(든_구간).as("조각 %s", 조각).isEqualTo(1);
        }
        // 마지막 방송의 구간 끝은 끝 + 10분이고 그 위는 아무 방송에도 안 든다(오른쪽 제외)
        long 끝_밖 = c끝.plus(Duration.ofMinutes(10)).toEpochMilli();
        assertThat(구간들.stream().noneMatch(r -> r[0] <= 끝_밖 && 끝_밖 < r[1])).isTrue();
    }

    /** 인접한 두 만료 방송의 구간은 자르는 점 하나에서 맞물린다: 앞 구간의 끝 = 뒤 구간의 시작(빈틈 · 겹침 없음) */
    @Test
    void 인접한_두_구간은_빈틈_겹침_없이_맞물린다() throws Exception {
        Instant a끝 = T0.plusSeconds(3600);
        Instant b시작 = T0.plusSeconds(7200), b끝 = T0.plusSeconds(10800);
        Instant c시작 = T0.plusSeconds(14400), c끝 = T0.plusSeconds(18000);
        방송("S-a", "key", T0, a끝, a끝.plus(Duration.ofDays(60)));
        방송("S-b", "key", b시작, b끝, b끝.plus(Duration.ofDays(60)));
        방송("S-c", "key", c시작, c끝, c끝.plus(Duration.ofDays(60)));

        JsonNode 줄들 = 부른다(지금, null).get("broadcasts");
        long[] a = 구간(줄들.get(0)), b = 구간(줄들.get(1)), c = 구간(줄들.get(2));
        assertThat(a[1]).isEqualTo(b[0]).isEqualTo(b시작.minus(Duration.ofMinutes(2)).toEpochMilli());
        assertThat(b[1]).isEqualTo(c[0]).isEqualTo(c시작.minus(Duration.ofMinutes(2)).toEpochMilli());
    }

    /** 뒤 방송이 안 만료면 그 자리 − 2분 이후의 조각은 어떤 만료 구간에도 안 든다(안 만료된 방송 조각을 절대 안 지운다) */
    @Test
    void 안_만료된_뒤_방송의_자리_2분_전_이후_조각은_어느_만료_구간에도_안_든다() throws Exception {
        Instant a끝 = T0.plusSeconds(3600);
        Instant b끝 = T0.plusSeconds(10800);
        Instant 새_시작 = Instant.parse("2026-09-20T00:00:00Z");
        방송("S-a", "key", T0, a끝, a끝.plus(Duration.ofDays(60)));
        방송("S-b", "key", T0.plusSeconds(7200), b끝, b끝.plus(Duration.ofDays(60)));
        방송("S-new", "key", 새_시작, 새_시작.plusSeconds(3600), 새_시작.plusSeconds(3600).plus(Duration.ofDays(60)));

        List<long[]> 구간들 = new ArrayList<>();
        for (JsonNode 줄 : 부른다(지금, null).get("broadcasts")) 구간들.add(구간(줄));
        assertThat(구간들).hasSize(2);
        for (Instant 조각 : List.of(새_시작.minus(Duration.ofMinutes(2)), 새_시작.minusSeconds(1), 새_시작,
                새_시작.plusSeconds(1800), 새_시작.plusSeconds(3600 + 300))) {
            assertThat(구간들.stream().noneMatch(r -> r[0] <= 조각.toEpochMilli() && 조각.toEpochMilli() < r[1]))
                    .as("안 만료된 방송 조각 %s", 조각).isTrue();
        }
    }

    /** 뒤 방송이 지금 방송 중이어도(끝 · 기한 없음) 구간은 그 시작 2분 전에서 멈춘다 */
    @Test
    void 뒤_방송이_방송_중이면_그_시작_2분_전에서_멈춘다() throws Exception {
        Instant 끝 = T0.plusSeconds(3600);
        Instant 라이브_시작 = Instant.parse("2026-09-30T23:00:00Z");
        방송("S-old", "key", T0, 끝, 끝.plus(Duration.ofDays(60)));
        방송("S-live", "key", 라이브_시작, null, null);

        JsonNode 줄 = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(Instant.parse(줄.get("segmentsUntil").asString())).isEqualTo(라이브_시작.minus(Duration.ofMinutes(2)));
    }

    /** 앞 방송이 안 끝났으면(종료 편지를 놓친 줄) 그 방송 조각을 안 건드리게 이 방송 자리 2분 전부터다 */
    @Test
    void 앞_방송이_안_끝났으면_이_방송_자리_2분_전부터다() throws Exception {
        Instant 시작 = T0.plusSeconds(7200);
        Instant 끝 = 시작.plusSeconds(3600);
        방송("S-stale", "key", T0, null, null);
        방송("S-mine", "key", 시작, 끝, 끝.plus(Duration.ofDays(60)));

        JsonNode 줄 = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(줄.get("streamId").asString()).isEqualTo("S-mine");
        assertThat(Instant.parse(줄.get("segmentsFrom").asString())).isEqualTo(시작.minus(Duration.ofMinutes(2)));
    }

    /**
     * 시작 시각을 모르는 방송(자리표시)도 나오고(startedAt null), 그 자리는 끝 시각이다. 뒤 방송은 자리표시의 끝 시각에서 칸을
     * 나누지만, 자리표시 바로 앞 방송은 그 끝을 경계로 믿지 않고 자기 끝 + 10분에서 멈춘다(자리표시가 실제로 언제 시작했는지 모른다,
     * PR #348 codex P1). 그 사이는 어느 구간에도 안 든다(적게 지우는 쪽)
     */
    @Test
    void 자리표시_방송도_나오고_끝_시각을_자리로_삼는다() throws Exception {
        Instant a끝 = T0.plusSeconds(3600);
        Instant p끝 = T0.plusSeconds(10800);
        Instant c시작 = T0.plusSeconds(14400), c끝 = T0.plusSeconds(18000);
        방송("S-a", "key", T0, a끝, a끝.plus(Duration.ofDays(60)));
        방송("S-place", "key", null, p끝, p끝.plus(Duration.ofDays(60)));
        방송("S-c", "key", c시작, c끝, c끝.plus(Duration.ofDays(60)));

        JsonNode body = 부른다(지금, null);
        assertThat(번호들(body)).containsExactly("S-a", "S-place", "S-c");
        JsonNode a = body.get("broadcasts").get(0);
        JsonNode p = body.get("broadcasts").get(1);
        JsonNode c = body.get("broadcasts").get(2);
        assertThat(p.get("startedAt").isNull()).isTrue();
        assertThat(Instant.parse(a.get("segmentsUntil").asString())).isEqualTo(a끝.plus(Duration.ofMinutes(10)));
        assertThat(Instant.parse(p.get("segmentsFrom").asString())).isEqualTo(p끝.minus(Duration.ofMinutes(2)));
        assertThat(Instant.parse(p.get("segmentsUntil").asString())).isEqualTo(c시작.minus(Duration.ofMinutes(2)));
        assertThat(Instant.parse(c.get("segmentsFrom").asString())).isEqualTo(c시작.minus(Duration.ofMinutes(2)));
    }

    /**
     * 뒤가 아직 안 만료된 자리표시면 그 방송의 조각(시작을 모름)을 앞 방송 몫으로 지우지 않는다: 자리표시 끝 − 2분까지 늘리면
     * 그 방송의 실제 시작부터 끝 2분 전까지가 일찍 지워진다(PR #348 codex P1)
     */
    @Test
    void 뒤가_시작을_모르는_방송이면_앞_방송은_자기_끝_10분_뒤에서_멈춘다() throws Exception {
        Instant a끝 = T0.plusSeconds(3600);
        Instant p끝 = T0.plusSeconds(10800);
        방송("S-a", "key", T0, a끝, a끝.plus(Duration.ofDays(60)));
        방송("S-place", "key", null, p끝, 지금.plus(Duration.ofDays(1)));

        JsonNode a = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(Instant.parse(a.get("segmentsUntil").asString())).isEqualTo(a끝.plus(Duration.ofMinutes(10)));
    }

    /** 둘 중 이른 쪽의 다른 갈래: 자리표시가 앞 방송 끝 + 10분보다 일찍 끝났으면 그 자리 − 2분에서 멈춘다 */
    @Test
    void 뒤_자리표시가_일찍_끝났으면_그_자리_2분_전에서_멈춘다() throws Exception {
        Instant a끝 = T0.plusSeconds(3600);
        Instant p끝 = a끝.plus(Duration.ofMinutes(5));
        방송("S-a", "key", T0, a끝, a끝.plus(Duration.ofDays(60)));
        방송("S-place", "key", null, p끝, 지금.plus(Duration.ofDays(1)));

        JsonNode a = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(Instant.parse(a.get("segmentsUntil").asString())).isEqualTo(p끝.minus(Duration.ofMinutes(2)));
    }

    /** 다른 물리 키의 방송은 이웃이 아니다 */
    @Test
    void 다른_물리_키의_방송은_구간에_영향이_없다() throws Exception {
        Instant 끝 = T0.plusSeconds(3600);
        방송("S-mine", "key-a", T0, 끝, 끝.plus(Duration.ofDays(60)));
        방송("S-other", "key-b", T0.plusSeconds(600), T0.plusSeconds(1200), null);

        JsonNode 줄 = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(줄.get("segmentsFrom").isNull()).isTrue();
        assertThat(Instant.parse(줄.get("segmentsUntil").asString())).isEqualTo(끝.plus(Duration.ofMinutes(10)));
    }

    /** 두 방송이 겹치면(앞 끝이 뒤 시작 − 2분보다 늦음) 빈 구간이다: 아무것도 안 지운다 */
    @Test
    void 겹친_방송의_구간은_비어_있다() throws Exception {
        Instant 앞_끝 = T0.plusSeconds(7200);
        방송("S-a", "key", T0, 앞_끝, null);
        방송("S-b", "key", T0.plusSeconds(3600), T0.plusSeconds(5400), T0.plusSeconds(5400).plus(Duration.ofDays(60)));

        JsonNode 줄 = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(줄.get("streamId").asString()).isEqualTo("S-b");
        long[] r = 구간(줄);
        assertThat(r[1]).as("빈 구간").isEqualTo(r[0]);
    }

    // ── 이어받기 ──────────────────────────────────────────────

    /** 501줄이면 두 장이고, 이어 불러도 중복 · 누락이 없다. 같은 기한 여럿은 줄 번호로 갈린다 */
    @Test
    void 한_장_상한을_넘으면_다음_표시로_이어_받고_중복_누락이_없다() throws Exception {
        Instant 같은_기한 = Instant.parse("2026-08-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, ended_at, vod_expires_at, last_sequence)
                SELECT 'S-' || g, 'u', 'ended', ?, ?, ?, 1 FROM generate_series(1, 501) g""",
                OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), OffsetDateTime.ofInstant(T0.plusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(같은_기한, ZoneOffset.UTC));

        JsonNode 첫_장 = 부른다(지금, null);
        assertThat(첫_장.get("broadcasts")).hasSize(500);
        String 표시 = 첫_장.get("nextCursor").asString();
        assertThat(첫_장.get("hasMore").asBoolean()).isTrue();
        JsonNode 둘째_장 = 부른다(지금, 표시);
        assertThat(둘째_장.get("broadcasts")).hasSize(1);
        assertThat(둘째_장.get("hasMore").asBoolean()).isFalse();

        Set<String> 모두 = new HashSet<>(번호들(첫_장));
        모두.addAll(번호들(둘째_장));
        assertThat(모두).hasSize(501);
    }

    /**
     * 끝 장에도 마지막 줄의 표시를 준다: 1번이 저장해 10분 뒤 이어 부르면 새로 만료된 것만 받는다(PR #348 codex).
     * 그때 새것이 없으면 빈 장과 받은 표시 그대로다(저장한 지점을 잃지 않는다)
     */
    @Test
    void 끝_장에도_표시를_주고_이어_부르면_새로_만료된_것만_온다() throws Exception {
        Instant 처음 = Instant.parse("2026-09-01T00:00:00Z");
        방송("S-1", "k-1", T0, T0.plusSeconds(60), 처음.minusSeconds(60));
        JsonNode 첫 = 부른다(처음, null);
        assertThat(번호들(첫)).containsExactly("S-1");
        assertThat(첫.get("hasMore").asBoolean()).isFalse();
        String 저장 = 첫.get("nextCursor").asString();
        assertThat(저장).isNotNull();

        JsonNode 빈 = 부른다(처음.plusSeconds(600), 저장);
        assertThat(빈.get("broadcasts")).isEmpty();
        assertThat(빈.get("nextCursor").asString()).isEqualTo(저장);

        방송("S-2", "k-2", T0, T0.plusSeconds(60), 처음.plusSeconds(300));
        JsonNode 다음 = 부른다(처음.plusSeconds(600), 저장);
        assertThat(번호들(다음)).containsExactly("S-2");
    }

    /** 끝이 빈 채 기한만 찬 줄(손 보정 등)은 「끝없이」가 아니라 자리 + 10분까지다(PR #348 claude) */
    @Test
    void 끝이_빈_줄은_자리에서_10분까지만_지운다() throws Exception {
        방송("S-noend", "k-1", T0, null, 지금.minusSeconds(60));
        JsonNode 줄 = 부른다(지금, null).get("broadcasts").get(0);
        assertThat(줄.get("segmentsUntil").asString()).isEqualTo(T0.plus(Duration.ofMinutes(10)).toString());
    }

    /** 딱 상한만큼이면 지금 더 없다(hasMore 거짓) */
    @Test
    void 딱_상한만큼이면_다음_표시가_없다() throws Exception {
        jdbc.update("""
                INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, ended_at, vod_expires_at, last_sequence)
                SELECT 'S-' || g, 'u', 'ended', ?, ?, ?, 1 FROM generate_series(1, 500) g""",
                OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), OffsetDateTime.ofInstant(T0.plusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        JsonNode body = 부른다(지금, null);
        assertThat(body.get("broadcasts")).hasSize(500);
        assertThat(body.get("hasMore").asBoolean()).isFalse();
        assertThat(부른다(지금, body.get("nextCursor").asString()).get("broadcasts")).isEmpty();
    }

    // ── 도우미 ────────────────────────────────────────────────

    private JsonNode 부른다(Instant before, String cursor) throws Exception {
        String url = 창구 + "?before=" + before + (cursor == null ? "" : "&cursor=" + cursor);
        MvcResult r = mvc.perform(get(url).header("X-Internal-Token", INTERNAL)).andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString());
    }

    private static List<String> 번호들(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("broadcasts").forEach(n -> out.add(n.get("streamId").asString()));
        return out;
    }

    /** [from, until) ms. from이 null이면 처음부터 */
    private static long[] 구간(JsonNode 줄) {
        long from = 줄.get("segmentsFrom").isNull() ? Long.MIN_VALUE : Instant.parse(줄.get("segmentsFrom").asString()).toEpochMilli();
        return new long[] {from, Instant.parse(줄.get("segmentsUntil").asString()).toEpochMilli()};
    }

    private void 방송(String streamId, String ingest, Instant startedAt, Instant endedAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, ended_at, vod_expires_at, ingest_stream_id, last_sequence)
                VALUES (?, 'u', ?, ?, ?, ?, ?, 1)""",
                streamId, endedAt == null ? "live" : "ended", at(startedAt), at(endedAt), at(expiresAt), ingest);
    }

    private static OffsetDateTime at(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
