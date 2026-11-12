package com.pokeclip.clip.mark;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.CardPositionReader;
import com.pokeclip.clip.jumpcard.CardPositionReader.ThumbnailOrder;
import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TimelineNotReadyException;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot.PlayerWindow;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.segment.OffsetOrigin;
import com.pokeclip.clip.segment.ServerClockPosition;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P31(설계 r7 16.2 · 계획 8.2) — 카드 · 마크 ↔ 플레이어 · 편집기 · 썸네일. 한 카드의 썸네일 주문 값 · {@code playerAtMs} ·
 * {@code playerWindow} 가 같은 장면에서 나오고, 마크는 회차 귀속 행에서 행별로 저장되는지 시드 (a)–(g) 와 999ms 겹침 사례로
 * 잰다. 썸네일은 {@link CardPositionReader}의 주문 값으로 단언한다 — 실제 주문서는 {@code thumbnail.CardThumbnailSceneTest}.
 *
 * <p>시각은 {@link LedgerSeed#BASE}에서 잰 ms다. 응답의 두 칸은 절대 시각(epoch ms)이라 {@link #abs}로 맞댄다.
 */
class MarkSceneP31Test extends IntegrationTestSupport {

    private static final String PASSPHRASE = "EXAMPLE-4b-test-vector-not-a-key";
    private static final String BEARER = "Bearer 136ef57bb5b3eaa8c35485438c358ff1eb392d925714b76f1e2eb756d2a04315";
    private static final String STREAMID = "#!::r=EXAMPLESTREAMKEYTOKEN00001,m=publish";
    private static final String 주인 = TestIds.STREAMER;
    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** 플러그인 · 브라우저 시계의 보낸 시각 = 누른 시각 — 받은 시각이 곧 서버 시계 T 가 된다. */
    private static final long SENT = 1_000_000_000_000L;

    /**
     * 카드 ms 상한(방송 시작 뒤 48시간 · POK-562)을 제품 상수({@link MarkService#MAX_BROADCAST_MS})와 따로 적은 값 — 상수를
     * 바꾸는 변이도 경계 시험이 잡게 한다.
     */
    private static final long CAP_MS = Duration.ofHours(48).toMillis();

    private final StreamKeyResolveClient keys;
    private final BroadcastRepository broadcasts;
    private final BroadcastAccessGuard guard;
    private final TimelineOriginReader origins;
    private final ServerClockPosition positions;
    private final JumpCardService cards;
    private final CardPositionReader scenes;
    private final JdbcTemplate jdbc;
    private final LedgerSeed seed;

    MarkSceneP31Test(StreamKeyResolveClient keys, BroadcastRepository broadcasts, BroadcastAccessGuard guard,
                     TimelineOriginReader origins, ServerClockPosition positions, JumpCardService cards,
                     CardPositionReader scenes, JdbcTemplate jdbc) {
        this.keys = keys;
        this.broadcasts = broadcasts;
        this.guard = guard;
        this.origins = origins;
        this.positions = positions;
        this.cards = cards;
        this.scenes = scenes;
        this.jdbc = jdbc;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        AUTH.respondWith("/internal/stream-keys/resolve", 200,
                "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + PASSPHRASE + "\"}");
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    // ── (a) 판 NULL 1시간 회차 — 끝 쪽 행의 PDT · 위치가 벽시각보다 176,000ms 앞섬 ────────────

    /** 단언 ① ② ③ ④. 옛 식(T − 기준점)이면 3,345,000 → 44 조각(176초) 이른 장면이다. */
    @Test
    void a_OBS_마크는_회차_벽시각_floor_조각에_서고_썸네일_편집기_플레이어가_같은_조각이다() {
        시드_a();

        JumpCardSnapshot card = obs("S-a", at(3_345_000)).card();

        assertThat(card.streamTimestampMs()).as("① 저장 위치 = start_pts(881) + 1000").isEqualTo(3_521_000);
        assertThat(thumbnailOf("S-a", card)).as("② 썸네일 주문 값")
                .contains(new ThumbnailOrder(881, 1_000, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).as("③ 플레이어 = PDT(881) + 1000").isEqualTo(abs(3_521_000));
        assertThat(card.playerWindow()).as("④ 창 두 끝 = 끝마다 장면 행 PDT + 거리(871 · 886)")
                .isEqualTo(new PlayerWindow(abs(3_481_000), abs(3_541_000)));
        assertThat(목록_카드("S-a")).as("목록 응답도 같은 두 칸").isEqualTo(card);
    }

    // ── (b) 회차 첫 행이 앞 회차 래칫을 이어받음(앞섬 120,000) ─────────────────────────

    @Test
    void b_앞_회차_래칫을_이어받은_회차의_라이브_마크도_벽시각_floor로_선다() {
        시드_b();

        JumpCardSnapshot card = webLive("S-b2", at(2_481_000)).card();

        assertThat(card.streamTimestampMs()).as("① R2 j=50 행 + 1000(옛 식이면 120초 이른 2,481,000)")
                .isEqualTo(2_601_000);
        assertThat(card.playerAtMs()).isEqualTo(abs(2_601_000));
        assertThat(card.playerWindow()).isEqualTo(new PlayerWindow(abs(2_561_000), abs(2_621_000)));
    }

    // ── (c) 판 2 회차(장 잔차 700ms) ────────────────────────────────────────────

    /** 단언 ① ⑨. 벽시각 floor 면 행 9 · 3,600 → 39,600 으로 장 잔차만큼 다르다. */
    @Test
    void c_판_2_회차는_PDT로_고르고_플레이어_값이_기준점_더하기_카드_ms와_같다() {
        시드_c();

        JumpCardSnapshot card = webLive("S-c", at(40_300)).card();

        assertThat(card.streamTimestampMs()).as("① PDT floor 행 11 + 300").isEqualTo(40_300);
        Instant origin = origins.originsOf(List.of(방송_찾기("S-c"))).get("S-c");
        assertThat(card.playerAtMs()).as("⑨ B9 등식 — 판 2 는 행별 값이 기준점 + 카드 ms 와 같다")
                .isEqualTo(origin.toEpochMilli() + card.streamTimestampMs());
        assertThat(thumbnailOf("S-c", card)).contains(new ThumbnailOrder(11, 300, OffsetOrigin.VIDEO_START));
    }

    // ── (d) 출시 이미지 식 판 NULL 회차 ─────────────────────────────────────────

    /**
     * 단언 ① ② ③ ④. 기준점 + 카드 ms = 120,500 은 조각 5(설계 예의 조각 4)다. 창 끝도 같다 — 기준점 + 창 ms 면 140,500,
     * 카드 값에 창 ms 차를 더해 늘리면 145,000 이고, 끝 장면 행(조각 9 첫 화면)에서 풀어야 146,500 이다.
     */
    @Test
    void d_출시_이미지_식_회차의_마크는_행별로_서고_플레이어가_같은_조각을_가리킨다() {
        시드_d();

        JumpCardSnapshot card = obs("S-d", at(125_000)).card();

        assertThat(card.streamTimestampMs()).as("① 조각 6 + 500").isEqualTo(20_500);
        assertThat(card.playerAtMs()).as("③ PDT(6) + 500").isEqualTo(abs(125_000));
        assertThat(thumbnailOf("S-d", card)).as("②").contains(new ThumbnailOrder(6, 500, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerWindow()).as("④").isEqualTo(new PlayerWindow(abs(100_000), abs(146_500)));
    }

    /** 단언 ⑤(조각 안) — 웹이 「playerAtMs − 기준점」 을 보내면 같은 숫자로 저장된다. 그대로 저장하면 25,000 = 틈 → 조각 7. */
    @Test
    void d_지난_방송_마크는_목록_PDT를_되살려_같은_숫자로_저장한다() {
        시드_d();
        끝낸다("S-d");
        long origin = origins.originsOf(List.of(방송_찾기("S-d"))).get("S-d").toEpochMilli();

        JumpCardSnapshot card = webPast("S-d", abs(125_000) - origin).card();

        assertThat(card.streamTimestampMs()).isEqualTo(20_500);
        assertThat(thumbnailOf("S-d", card)).as("②").contains(new ThumbnailOrder(6, 500, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).isEqualTo(abs(125_000));
    }

    /** 단언 ⑧ — 라이브 머리(마지막 귀속 행 끝 뒤)는 늘린다. 장면이 아직 없어 두 칸 · 썸네일 주문이 없다. */
    @Test
    void d_라이브_머리_마크는_늘리고_장면이_없다() {
        시드_d();

        try (LogCaptor logs = new LogCaptor()) {
            JumpCardSnapshot card = webLive("S-d", at(204_500)).card();

            assertThat(card.streamTimestampMs()).as("조각 20 시작 86,000 + 14,000 — 접으면 90,000").isEqualTo(100_000);
            assertThat(card.playerWindow()).isNull();
            assertThat(card.playerAtMs()).isNull();
            assertThat(thumbnailOf("S-d", card)).isEmpty();
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("clip.mark.positioned streamId=S-d source=web_live")
                    && m.contains("axis=wall") && m.contains("seq=20") && m.contains("rounds=members")
                    && m.contains("extended=true") && m.contains("folded=false"));
        }
    }

    /** 단언 ⑥ — 채팅 위치(스트림 전체 floor 라 비귀속 u 안일 수 있다) 카드는 뒤 귀속 행 첫 화면으로 셋이 같다. */
    @Test
    void d_비귀속_자리_채팅_카드는_뒤_귀속_행_첫_화면이다() {
        시드_d();

        JumpCardSnapshot card = 채팅_카드("S-d", 38_500);

        assertThat(thumbnailOf("S-d", card)).contains(new ThumbnailOrder(9, 0, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).isEqualTo(abs(146_500));
        assertThat(목록_카드("S-d")).isEqualTo(card);
    }

    /** 단언 ⑦ — 옛 식(T − 기준점)으로 저장된 카드는 값을 안 고치고, 응답 · 썸네일이 그 값의 장면으로 같은 조각이다. */
    @Test
    void d_옛_식으로_저장된_카드는_저장_값이_그대로이고_셋이_같은_조각이다() {
        시드_d();

        JumpCardSnapshot card = cards.recordHotkey("S-d", 주인, UUID.randomUUID().toString(), 25_000L).card();

        assertThat(card.streamTimestampMs()).isEqualTo(25_000);
        assertThat(thumbnailOf("S-d", card)).contains(new ThumbnailOrder(7, 0, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).isEqualTo(abs(138_500));
    }

    // ── (e) 하한 거부 꼴 비귀속 u ─────────────────────────────────────────────

    /**
     * 단언 ⑩ — 회차로 자르면 u 를 안 고른다. q · u 는 위치 · PDT 가 같아 플레이어 값으로는 못 가르고, 장면 조각 seq 로 가른다(시간창이면
     * u = seq 11).
     */
    @Test
    void e_하한_거부_꼴_비귀속_행은_장면에_안_고른다() {
        시드_e();

        JumpCardSnapshot card = cards.recordHotkey("S-e", 주인, UUID.randomUUID().toString(), 201_000L).card();

        assertThat(thumbnailOf("S-e", card)).as("⑩ 장면 조각 = q").contains(new ThumbnailOrder(10, 1_000, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).isEqualTo(abs(101_000));
    }

    // ── (f) 재접속 틈 안 라이브 마크 ───────────────────────────────────────────

    /** 단언 ⑪ — A 끝으로 접힌 위치 204,000 은 썸네일 · 플레이어 둘 모두 재접속 뒤 첫 화면(B 시작)이다(kty 11:27). */
    @Test
    void f_재접속_틈_안_마크는_A_끝으로_접히고_둘_모두_B_첫_화면이다() {
        시드_f();

        JumpCardSnapshot card = webLive("S-f", at(106_000)).card();

        assertThat(card.streamTimestampMs()).isEqualTo(204_000);
        assertThat(thumbnailOf("S-f", card)).contains(new ThumbnailOrder(11, 0, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).isEqualTo(abs(110_000));
    }

    /** 단언 ⑤(틈) — 틈에서는 숫자가 아니라 장면이 보존된다: 204,000 → PDT 110,000 → 새 마크 210,000. */
    @Test
    void f_틈_장면의_플레이어_값으로_보낸_지난_방송_마크는_같은_장면으로_저장된다() {
        시드_f();
        끝낸다("S-f");
        long origin = origins.originsOf(List.of(방송_찾기("S-f"))).get("S-f").toEpochMilli();

        JumpCardSnapshot card = webPast("S-f", abs(110_000) - origin).card();

        assertThat(card.streamTimestampMs()).isEqualTo(210_000);
        assertThat(thumbnailOf("S-f", card)).isEqualTo(sceneOrderOf("S-f", 204_000));
    }

    /** 단언 ⑬ — 첫 귀속 행 앞 마크는 그 행 시작으로 접는다(0 이 아니다). 세 값이 선다. */
    @Test
    void f_첫_귀속_행_앞_마크는_첫_행_시작으로_저장되고_세_값이_선다() {
        시드_f();

        JumpCardSnapshot card = webLive("S-f", at(99_000)).card();

        assertThat(card.streamTimestampMs()).isEqualTo(200_000);
        assertThat(card.playerAtMs()).isEqualTo(abs(100_000));
        assertThat(card.playerWindow()).isEqualTo(new PlayerWindow(abs(100_000), abs(120_000)));
        assertThat(thumbnailOf("S-f", card)).contains(new ThumbnailOrder(10, 0, OffsetOrigin.FILE_HEAD));
    }

    /** 창 전체가 한 틈 안이면 두 끝이 같은 값(뒤 조각 첫 화면)이다. */
    @Test
    void f_창_전체가_틈_안이면_세_값이_뒤_조각_첫_화면이다() {
        시드_f();

        JumpCardSnapshot card = cards.record("S-f", new HighlightRequest(UUID.randomUUID().toString(), "auto",
                205_000L, new HighlightRequest.Window(204_500L, 209_000L), 90, null)).card();

        assertThat(card.playerAtMs()).isEqualTo(abs(110_000));
        assertThat(card.playerWindow()).isEqualTo(new PlayerWindow(abs(110_000), abs(110_000)));
    }

    // ── (g) 비귀속 옆 채팅 카드 ─────────────────────────────────────────────

    /** 단언 ⑫. 「Gap = (j, dur(j))」(r6 S-c4) 면 썸네일이 j 끝이 된다. */
    @Test
    void g_비귀속_옆_채팅_카드는_둘_모두_k_프라임_시작이다() {
        시드_g();

        JumpCardSnapshot card = 채팅_카드("S-g", 305_500);

        assertThat(thumbnailOf("S-g", card)).contains(new ThumbnailOrder(22, 0, OffsetOrigin.FILE_HEAD));
        assertThat(card.playerAtMs()).isEqualTo(abs(308_000));
    }

    // ── K1-c 999ms PDT 부분 겹침 ───────────────────────────────────────────

    @Test
    void 겹침_999ms_구간의_마크와_채팅_카드는_나중_조각으로_푼다() {
        시드_겹침();

        JumpCardSnapshot mark = webLive("S-k", at(103_500)).card();
        JumpCardSnapshot chat = 채팅_카드("S-k", 3_700);

        assertThat(mark.streamTimestampMs()).isEqualTo(3_500);
        assertThat(thumbnailOf("S-k", mark)).contains(new ThumbnailOrder(2, 499, OffsetOrigin.VIDEO_START));
        assertThat(mark.playerAtMs()).isEqualTo(abs(103_500));
        assertThat(thumbnailOf("S-k", chat)).contains(new ThumbnailOrder(2, 699, OffsetOrigin.VIDEO_START));
    }

    // ── 다른 방송 카드 · 상한 ─────────────────────────────────────────────

    /** 같은 eventId 가 같은 주인의 다른 방송 카드면 그 카드를 돌려준다 — 장면도 요청 방송이 아니라 그 카드 방송의 회차로 읽는다. */
    @Test
    void 같은_eventId가_내_다른_방송_카드면_장면도_그_카드_방송의_회차로_읽는다() {
        시드_d();
        시드_f();
        String eventId = UUID.randomUUID().toString();
        JumpCardSnapshot made = cards.recordHotkey("S-d", 주인, eventId, 20_500L).card();

        JumpCardSnapshot again = cards.recordHotkey("S-f", 주인, eventId, 205_000L).card();

        assertThat(again.streamId()).isEqualTo("S-d");
        assertThat(again.playerAtMs()).isEqualTo(abs(125_000)).isEqualTo(made.playerAtMs());
        assertThat(again.playerWindow()).isEqualTo(made.playerWindow());
    }

    /**
     * 지난 방송 마크도 저장 위치에 라이브 · OBS 와 같은 카드 ms 상한(방송 시작 뒤 48시간 · POK-562)을 건다 — 입력 위치만 보면
     * 상한이 입력 기준이 된다. 방송 시작 48시간 1초 뒤에 첫 조각이 선 회차라 저장 위치(그 조각 안 1초)가 상한을 2초 넘는다.
     * 종료 시각이 있어 입력 범위는 종료 기준이라 안 걸린다.
     */
    @Test
    void 지난_방송_마크의_저장_위치가_카드_ms_상한을_넘으면_400이다() {
        방송("S-m", "P31M", at(-MarkService.MAX_BROADCAST_MS));
        seed.session("S-m", "P31M", "S-m", null);
        seed.row("P31M", 1).pts(MarkService.MAX_BROADCAST_MS).wallAndPdt(1_000).session("S-m").insert();
        끝낸다("S-m");
        long origin = origins.originsOf(List.of(방송_찾기("S-m"))).get("S-m").toEpochMilli();

        assertThatThrownBy(() -> webPast("S-m", abs(2_000) - origin))
                .isInstanceOfSatisfying(InvalidHighlightException.class,
                        e -> assertThat(e.field()).isEqualTo("positionMs"));
    }

    /**
     * 상한 경계 안쪽 — 시작 = 기준점이라 카드 ms 상한이 꼭 48시간인 방송에서 저장 위치 48시간 − 1초는 201(새 카드)이다.
     * 상한을 1초 넘게 줄이는 변이를 잡는다.
     */
    @Test
    void 지난_방송_마크의_저장_위치가_카드_ms_상한_1초_안이면_201이다() {
        상한_시드("S-n", "P31N", CAP_MS - 2_000, CAP_MS - 2_000);
        long origin = origins.originsOf(List.of(방송_찾기("S-n"))).get("S-n").toEpochMilli();

        JumpCardService.RecordResult result = webPast("S-n", abs(1_000 + CAP_MS - 1_000) - origin);

        assertThat(result.created()).as("201 — 새 카드").isTrue();
        assertThat(result.card().streamTimestampMs()).isEqualTo(CAP_MS - 1_000);
    }

    /** 위와 같은 방송에서 저장 위치가 상한과 같은 48시간이면 아직 201이다 — 상한을 1ms 라도 줄이는 변이를 잡는다. */
    @Test
    void 지난_방송_마크의_저장_위치가_카드_ms_상한과_같으면_201이다() {
        상한_시드("S-n", "P31N", CAP_MS - 2_000, CAP_MS - 2_000);
        long origin = origins.originsOf(List.of(방송_찾기("S-n"))).get("S-n").toEpochMilli();

        JumpCardService.RecordResult result = webPast("S-n", abs(1_000 + CAP_MS) - origin);

        assertThat(result.created()).as("201 — 새 카드").isTrue();
        assertThat(result.card().streamTimestampMs()).isEqualTo(CAP_MS);
    }

    /** 위와 같은 방송에서 저장 위치가 48시간 + 1ms 면 400 {@code positionMs} 다 — 상한을 늘리는 변이를 잡는다. */
    @Test
    void 지난_방송_마크의_저장_위치가_카드_ms_상한을_1ms_넘으면_400이다() {
        상한_시드("S-n", "P31N", CAP_MS - 2_000, CAP_MS - 2_000);
        long origin = origins.originsOf(List.of(방송_찾기("S-n"))).get("S-n").toEpochMilli();

        assertThatThrownBy(() -> webPast("S-n", abs(1_000 + CAP_MS + 1) - origin))
                .isInstanceOfSatisfying(InvalidHighlightException.class,
                        e -> assertThat(e.field()).isEqualTo("positionMs"));
    }

    /**
     * 상한은 입력 위치가 아니라 저장 위치로 잰다. 행 2 의 위치 축이 목록 PDT 축보다 8초 앞서, 입력 48시간 − 4초(상한 안)가
     * 저장 위치 48시간 + 4초(상한 밖)로 옮겨진다 — 400 {@code positionMs}. 입력에 상한을 걸면 201 이 된다.
     */
    @Test
    void 지난_방송_마크는_입력_위치가_상한_안이어도_저장_위치가_넘으면_400이다() {
        상한_시드("S-x", "P31X", CAP_MS - 6_000, CAP_MS + 2_000);
        long origin = origins.originsOf(List.of(방송_찾기("S-x"))).get("S-x").toEpochMilli();

        assertThatThrownBy(() -> webPast("S-x", abs(1_000 + CAP_MS - 4_000) - origin))
                .isInstanceOfSatisfying(InvalidHighlightException.class,
                        e -> assertThat(e.field()).isEqualTo("positionMs"));
    }

    /**
     * 반대쪽 — 행 2 의 목록 PDT 축이 위치 축보다 8초 앞서(출시 이미지 식 판 NULL 행처럼), 입력 48시간 + 4초(상한 밖)가 저장
     * 위치 48시간 − 4초(상한 안)로 옮겨진다 — 201. 입력에 상한을 걸면 400 이 된다.
     */
    @Test
    void 지난_방송_마크는_입력_위치가_상한을_넘어도_저장_위치가_안이면_201이다() {
        상한_시드("S-y", "P31Y", CAP_MS + 2_000, CAP_MS - 6_000);
        long origin = origins.originsOf(List.of(방송_찾기("S-y"))).get("S-y").toEpochMilli();

        JumpCardService.RecordResult result = webPast("S-y", abs(1_000 + CAP_MS + 4_000) - origin);

        assertThat(result.created()).as("201 — 새 카드").isTrue();
        assertThat(result.card().streamTimestampMs()).isEqualTo(CAP_MS - 4_000);
    }

    /**
     * 종료 편지가 먼저 온 자리표시(시작 시각 없음)는 회차가 있으면 기준점이 서지만 「방송 시작 뒤 48시간」 을 잴 시작이 없다.
     * 그때 저장 위치 상한은 기준점 + 48시간이다(POK-196 병합 처분 · kty 11-11) — 기준점 + 48시간 안은 201.
     */
    @Test
    void 시작_시각이_없는_자리표시도_기준점_뒤_48시간_안의_지난_방송_마크는_201이다() {
        자리표시_시드();
        long origin = origins.originsOf(List.of(방송_찾기("S-p"))).get("S-p").toEpochMilli();

        JumpCardService.RecordResult result = webPast("S-p", abs(3_000) - origin);

        assertThat(result.created()).as("201 — 새 카드").isTrue();
        assertThat(result.card().streamTimestampMs()).isEqualTo(2_000);
    }

    /**
     * 위와 같은 자리표시에서 저장 위치가 기준점 + 48시간을 넘으면 400 {@code positionMs} 다. 종료 + 10분 입력 범위 안이라 입력
     * 검사는 통과하고 저장 위치 상한에서만 걸린다(그 상한을 생략하면 201 이 되고, 시작 시각으로 재면 NPE 다). 입력 위치는
     * 48시간 − 9초로 상한 안이고 저장 위치만 48시간 + 11초라, 상한을 입력에 걸어도 201 이 된다.
     */
    @Test
    void 시작_시각이_없는_자리표시는_저장_위치가_기준점_뒤_48시간을_넘으면_400이다() {
        자리표시_시드();
        long origin = origins.originsOf(List.of(방송_찾기("S-p"))).get("S-p").toEpochMilli();

        assertThatThrownBy(() -> webPast("S-p", abs(1_000 + MarkService.MAX_BROADCAST_MS - 9_000) - origin))
                .isInstanceOfSatisfying(InvalidHighlightException.class,
                        e -> assertThat(e.field()).isEqualTo("positionMs"));
    }

    /** 회차(머리 세션 행)도 시작 시각도 없는 자리표시는 회차가 없어 기준점이 없다 — 503(다시 보내기 간격 5초). */
    @Test
    void 회차도_시작_시각도_없는_자리표시의_지난_방송_마크는_503이다() {
        broadcasts.save(Broadcast.endedPlaceholder("S-q", 주인, "P31Q", 1L, at(60_000)));
        seed.row("P31Q", 1).pts(0).wallAndPdt(1_000).insert();

        assertThatThrownBy(() -> webPast("S-q", 2_000))
                .isInstanceOfSatisfying(TimelineNotReadyException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(MarkService.TIMELINE_RETRY_AFTER_SECONDS));
    }

    // ── 신호 ──────────────────────────────────────────────────────────────

    @Test
    void 머리_세션_행이_없는_옛_방송은_시간창으로_고르고_신호에_남긴다() {
        broadcasts.save(Broadcast.startedNow("S-old", 주인, "P31OLD", 1L, at(0), null));
        seed.row("P31OLD", 1).pts(0).wall(1_000).duration(600_000).insert();

        try (LogCaptor logs = new LogCaptor()) {
            JumpCardSnapshot card = obs("S-old", at(11_000)).card();

            assertThat(card.streamTimestampMs()).isEqualTo(10_000);
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("clip.mark.positioned streamId=S-old source=obs")
                    && m.contains("rounds=time_window") && m.contains("folded=false"));
        }
    }

    // ── 시드 ──────────────────────────────────────────────────────────────

    /**
     * 종료 선도착 자리표시 S-p(시작 시각 없음 · 회차 S-p). 행 1 은 기준점 자리(위치 0 · 1초), 행 2 는 위치 축으로 그보다
     * 48시간 10초 뒤에 서고 목록 PDT 축으로는 20초 이른 47시간 59분 50초 뒤에 선다(두 축이 갈린 판 NULL 행). 종료는 행 2
     * 목록 PDT 뒤 10초라 행 2 안 입력도 입력 범위(종료 + 10분) 안이다.
     */
    private void 자리표시_시드() {
        long far = MarkService.MAX_BROADCAST_MS + 10_000;
        long listed = far - 20_000;
        broadcasts.save(Broadcast.endedPlaceholder("S-p", 주인, "P31P", 1L, at(1_000 + listed + 10_000)));
        seed.session("S-p", "P31P", "S-p", null);
        seed.row("P31P", 1).pts(0).wallAndPdt(1_000).session("S-p").insert();
        seed.row("P31P", 2).pts(far).wallAndPdt(1_000 + listed).session("S-p").insert();
    }

    /**
     * 시작 시각 = 기준점(행 1 · 위치 0 · 1초)이라 카드 ms 상한이 꼭 {@link #CAP_MS}인 지난 방송. 행 2 는 목록 PDT 축으로
     * 기준점 뒤 {@code listedMs}, 위치 축으로 {@code storedMs} 에 선다(두 값이 다르면 그 차만큼 두 축이 갈린 판 NULL 행).
     * 종료는 행 2 목록 PDT 끝이라 행 2 안 입력은 입력 범위(종료 + 10분) 안이다.
     */
    private void 상한_시드(String streamId, String ingestKey, long listedMs, long storedMs) {
        방송(streamId, ingestKey, at(1_000));
        seed.session(streamId, ingestKey, streamId, null);
        seed.row(ingestKey, 1).pts(0).wallAndPdt(1_000).session(streamId).insert();
        seed.row(ingestKey, 2).pts(storedMs).wallAndPdt(1_000 + listedMs).session(streamId).insert();
        끝낸다(streamId, at(1_000 + listedMs + 4_000));
    }

    /** (a) 4초 조각 900개 · 파일명 간격 3,800 — 래칫으로 PDT · 위치가 행마다 200ms 씩 앞서 행 881 에서 176,000. */
    private void 시드_a() {
        방송("S-a", "P31A", at(-60_000));
        seed.session("S-a", "P31A", "S-a", null);
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT 'P31A', g + 1, g * 4000, ?::timestamptz + g * 3800 * interval '1 millisecond',
                       ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, 'k' || g, 'uploaded', 'S-a'
                  FROM generate_series(0, 899) g""", Timestamp.from(at(0)), Timestamp.from(at(0)));
    }

    /** (b) R1(앞 방송) 래칫 뒤 60초 재시작한 R2 — R2 PDT 가 벽시각보다 120,000 앞선 채 이어진다. */
    private void 시드_b() {
        방송("S-b1", "P31B", at(0));
        끝낸다("S-b1");
        방송("S-b2", "P31B", at(2_280_000));
        seed.session("S-b1", "P31B", "S-b1", null);
        seed.session("S-b2", "P31B", "S-b2", null);
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT 'P31B', g + 1, g * 4000, ?::timestamptz + g * 3700 * interval '1 millisecond',
                       ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, 'k' || g, 'uploaded', 'S-b1'
                  FROM generate_series(0, 599) g""", Timestamp.from(at(0)), Timestamp.from(at(0)));
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT 'P31B', 601 + j, 2400000 + j * 4000, ?::timestamptz + j * 4000 * interval '1 millisecond',
                       ?::timestamptz + j * 4000 * interval '1 millisecond', 4000, 'k2' || j, 'uploaded', 'S-b2'
                  FROM generate_series(0, 99) j""", Timestamp.from(at(2_280_000)), Timestamp.from(at(2_400_000)));
    }

    /** (c) 판 2 — 첫 행 뒤로 파일명 시각이 PDT 보다 700 늦다(장 잔차). 위치 보폭 = PDT 보폭. */
    private void 시드_c() {
        방송("S-c", "P31C", at(-60_000));
        seed.session("S-c", "P31C", "S-c", null);
        seed.row("P31C", 1).pts(0).wallAndPdt(0).session("S-c").v2().insert();
        for (int i = 1; i < 30; i++) {
            seed.row("P31C", i + 1).pts(4_000L * i).wall(4_000L * i + 700).pdt(4_000L * i).session("S-c").v2().insert();
        }
    }

    /**
     * (d) 출시 이미지 식({@code indexer.go} · {@code store.go}) — 길이 4,000 · 파일명 간격 4,900 다섯 번(drift 900 ≤ 1.5초라
     * 위치는 길이만 · PDT 는 파일명 시각) · 재접속(drift +10,000 — 위치에 더함) · 회차 안 비귀속 행 하나(위치만 더함).
     */
    private void 시드_d() {
        방송("S-d", "P31D", at(60_000));
        seed.session("S-d", "P31D", "S-d", null);
        seed.session("S-d2", "P31D", "S-d", "S-d");
        for (int i = 0; i < 6; i++) {
            seed.row("P31D", i + 1).pts(4_000L * i).wallAndPdt(100_000 + 4_900L * i).session("S-d").insert();
        }
        seed.row("P31D", 7).pts(34_000).wallAndPdt(138_500).session("S-d2").discontinuity().insert();
        seed.row("P31D", 8).pts(38_000).wall(142_500).insert();
        for (int k = 0; k < 12; k++) {
            seed.row("P31D", 9 + k).pts(42_000 + 4_000L * k).wallAndPdt(146_500 + 4_000L * k).session("S-d2").insert();
        }
    }

    /** (e) 귀속 q 뒤 하한 거부 꼴 비귀속 u — 위치 · PDT 가 같고 파일명 시각만 3초 이르다. */
    private void 시드_e() {
        방송("S-e", "P31E", at(60_000));
        seed.session("S-e", "P31E", "S-e", null);
        seed.row("P31E", 10).pts(200_000).wallAndPdt(100_000).session("S-e").insert();
        seed.row("P31E", 11).pts(200_000).wall(97_000).pdt(100_000).insert();
        for (int k = 0; k < 10; k++) {
            seed.row("P31E", 12 + k).pts(204_000 + 4_000L * k).wallAndPdt(104_000 + 4_000L * k).session("S-e").insert();
        }
    }

    /** (f) 귀속 A(길이 4000) · 재접속 뒤 귀속 B(10초 뒤 · is_discontinuity 참). */
    private void 시드_f() {
        방송("S-f", "P31F", at(100_000));
        seed.session("S-f", "P31F", "S-f", null);
        seed.session("S-f2", "P31F", "S-f", "S-f");
        seed.row("P31F", 10).pts(200_000).wallAndPdt(100_000).session("S-f").insert();
        seed.row("P31F", 11).pts(210_000).wallAndPdt(110_000).session("S-f2").discontinuity().insert();
        for (int k = 1; k < 10; k++) {
            seed.row("P31F", 11 + k).pts(210_000 + 4_000L * k).wallAndPdt(110_000 + 4_000L * k).session("S-f2").insert();
        }
    }

    /** (g) 귀속 j 뒤 비귀속 u(같은 녹화기 · 이어짐) 뒤 귀속 k′. */
    private void 시드_g() {
        방송("S-g", "P31G", at(240_000));
        seed.session("S-g", "P31G", "S-g", null);
        seed.row("P31G", 20).pts(300_000).wallAndPdt(300_000).session("S-g").insert();
        seed.row("P31G", 21).pts(304_000).wall(304_000).insert();
        for (int k = 0; k < 10; k++) {
            seed.row("P31G", 22 + k).pts(308_000 + 4_000L * k).wallAndPdt(308_000 + 4_000L * k).session("S-g").insert();
        }
    }

    /** K1-c — 판 2 두 조각의 PDT · 위치가 999ms 겹친다(뒤 조각 시작 3,001 < 앞 조각 끝 4,000). */
    private void 시드_겹침() {
        방송("S-k", "P31K", at(60_000));
        seed.session("S-k", "P31K", "S-k", null);
        seed.row("P31K", 1).pts(0).wallAndPdt(100_000).session("S-k").v2().insert();
        for (int k = 0; k < 15; k++) {
            seed.row("P31K", 2 + k).pts(3_001 + 4_000L * k).wallAndPdt(103_001 + 4_000L * k).session("S-k").v2().insert();
        }
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private static long abs(long ms) {
        return at(ms).toEpochMilli();
    }

    private JumpCardService.RecordResult obs(String streamId, Instant t) {
        MarkService service = new MarkService(keys, broadcasts, origins, positions, cards,
                Clock.fixed(t, ZoneOffset.UTC));
        String eventId = UUID.randomUUID().toString();
        String body = "{\"streamid\":\"" + STREAMID + "\",\"eventId\":\"" + eventId + "\",\"pressedAt\":" + SENT
                + ",\"sentAt\":" + SENT + "}";
        JumpCardService.RecordResult result = service.mark(BEARER, eventId, body, service.now());
        assertThat(result.card().streamId()).isEqualTo(streamId);
        return result;
    }

    private JumpCardService.RecordResult webLive(String streamId, Instant t) {
        WebMarkService service = new WebMarkService(guard, broadcasts, origins, positions, cards,
                Clock.fixed(t, ZoneOffset.UTC));
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"pressedAt\":" + SENT + ",\"sentAt\":" + SENT + "}";
        return service.mark(주인, streamId, body, service.now());
    }

    private JumpCardService.RecordResult webPast(String streamId, long positionMs) {
        WebMarkService service = new WebMarkService(guard, broadcasts, origins, positions, cards, Clock.systemUTC());
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"positionMs\":" + positionMs + "}";
        return service.mark(주인, streamId, body, service.now());
    }

    private JumpCardSnapshot 채팅_카드(String streamId, long position) {
        return cards.record(streamId, new HighlightRequest(UUID.randomUUID().toString(), "auto", position,
                new HighlightRequest.Window(Math.max(0, position - 20_000), position + 20_000), 90, null)).card();
    }

    private JumpCardSnapshot 목록_카드(String streamId) {
        List<JumpCardSnapshot> page = cards.listOf(주인, streamId, false, null, null).cards();
        assertThat(page).hasSize(1);
        return page.get(0);
    }

    private Optional<ThumbnailOrder> thumbnailOf(String streamId, JumpCardSnapshot card) {
        return sceneOrderOf(streamId, card.streamTimestampMs());
    }

    private Optional<ThumbnailOrder> sceneOrderOf(String streamId, long cardMs) {
        CardPositionReader.Scene scene = scenes.scenesOf(방송_찾기(streamId), List.of(cardMs)).get(cardMs);
        return scene == null ? Optional.empty() : scene.thumbnailOrder();
    }

    private void 방송(String streamId, String ingestKey, Instant startedAt) {
        broadcasts.save(Broadcast.startedNow(streamId, 주인, ingestKey, 1L, startedAt, null));
    }

    private Broadcast 방송_찾기(String streamId) {
        return broadcasts.findByStreamId(streamId).orElseThrow();
    }

    private void 끝낸다(String streamId) {
        끝낸다(streamId, at(10_000_000));
    }

    private void 끝낸다(String streamId, Instant endedAt) {
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = ?",
                Timestamp.from(endedAt), streamId);
    }
}
