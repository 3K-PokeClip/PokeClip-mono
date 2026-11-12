package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.CardPositionReader;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.segment.RoundRows.Round;
import com.pokeclip.clip.segment.RoundRows.Row;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static com.pokeclip.clip.support.StatementCounting.counting;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 썸네일 주문 경로(POK-196 S-c4 · 설계 r7 8.2 「카드 → 장면(S-c4)」 · 계획 8.2 P31 ② ⑪ ⑫ 의 썸네일 부분). 실제로 줄에 실린
 * 주문서의 조각 · 자리 · 원점이 카드 장면 함수({@link CardPositionReader})의 장면과 같은지 잰다. 옛 카드 SQL(덮는 행 · seq 최소 ·
 * 방송 시간창)이면 틈 · 비귀속 자리에는 주문이 안 나가거나 다른 조각이 나가고, 겹침 자리는 앞 조각이 나간다.
 *
 * <p>시각은 {@link LedgerSeed#BASE}에서 잰 ms다. 순회기는 손으로 만들어 한 번씩 돌린다(시험 설정은 썸네일이 꺼져 일정이 안 돈다).
 */
class CardThumbnailSceneTest extends IntegrationTestSupport {

    private static final String 조각_창고 = "segments-test";
    private static final String 사진_창고 = "clips-test";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ThumbnailTargets targets;
    private final ThumbnailRepository thumbnails;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final CardPositionReader scenes;
    private final ObjectMapper mapper;
    private final LedgerSeed seed;

    private final List<JsonNode> sent = new ArrayList<>();
    private final Instant now = at(50_000_000);

    CardThumbnailSceneTest(JdbcTemplate jdbc, DataSource dataSource, ThumbnailTargets targets,
                           ThumbnailRepository thumbnails, BroadcastRepository broadcasts, TimelineOriginReader origins,
                           CardPositionReader scenes, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.targets = targets;
        this.thumbnails = thumbnails;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.scenes = scenes;
        this.mapper = mapper;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        sent.clear();
    }

    // ── P31 ⑪ ⑫ — 틈 · 비귀속 자리는 뒤 귀속 행 첫 화면 ────────────────────────────

    /**
     * 단언 ⑪(썸네일) — 재접속 틈 안(A 끝으로 접힌 204,000) 카드의 사진은 B 첫 화면이다(kty 11:27). 「Gap = (j, dur(j))」
     * (r6 S-c4)면 A 의 4,000 자리가 나간다. 옛 SQL 은 덮는 행이 없어 주문을 안 냈다.
     */
    @Test
    void f_재접속_틈_안_카드의_사진은_B_첫_화면이다() {
        시드_f();
        카드("S-f", 204_000);

        sweeper(targets).sweepCards();

        assertThat(sent).hasSize(1);
        assertOrder(sent.get(0), "seg/P31F/11", 0, "FILE_HEAD");
    }

    /** 단언 ⑫(썸네일) — 비귀속 u 안 채팅 카드의 사진은 k′ 첫 화면이다. 옛 SQL 은 u(덮는 행)의 1,500 자리를 냈다. */
    @Test
    void g_비귀속_옆_채팅_카드의_사진은_k_프라임_첫_화면이다() {
        시드_g();
        카드("S-g", 305_500);

        sweeper(targets).sweepCards();

        assertThat(sent).hasSize(1);
        assertOrder(sent.get(0), "seg/P31G/22", 0, "FILE_HEAD");
    }

    /**
     * 단언 ②(썸네일) — 실제 주문 조각 · 자리 · 원점 = 카드 장면 함수의 장면. 판 2 두 조각이 999ms 겹친 자리는 나중 조각이고
     * (F · B8) 원점은 행 판({@code VIDEO_START})이다. 옛 SQL(seq 최소)이면 앞 조각 3,500 자리다.
     */
    @Test
    void 겹침_자리_카드의_사진은_나중_조각이고_원점은_행_판이다() {
        시드_겹침();
        카드("S-k", 3_500);

        sweeper(targets).sweepCards();

        CardPositionReader.Scene scene = scenes.scenesOf(방송_찾기("S-k"), List.of(3_500L)).get(3_500L);
        assertThat(sent).hasSize(1);
        assertOrder(sent.get(0), scene.row().s3Key(), scene.offsetMs(), scene.thumbnailOrder().orElseThrow()
                .offsetOrigin().name());
        assertOrder(sent.get(0), "seg/P31K/2", 499, "VIDEO_START");
    }

    /**
     * 시간창 판정을 거뒀다 — 회차 귀속 행이 방송 시작 편지보다 2분 넘게 앞서도(재시작 편지가 {@code started_at}을 덮은 꼴) 그
     * 회차의 조각을 찍는다. 옛 SQL 은 시간창 밖이라 주문을 안 냈다.
     */
    @Test
    void 방송_시작_편지보다_앞선_회차_행도_찍는다() {
        broadcasts.save(Broadcast.startedNow("S-late", TestIds.STREAMER, "LATE", 1L, at(1_000_000), null));
        seed.session("S-late", "LATE", "S-late", null);
        seed.row("LATE", 1).pts(0).wallAndPdt(100_000).session("S-late").insert();
        seed.row("LATE", 2).pts(4_000).wallAndPdt(104_000).session("S-late").insert();
        카드("S-late", 1_000);

        sweeper(targets).sweepCards();

        assertThat(sent).hasSize(1);
        assertOrder(sent.get(0), "seg/LATE/1", 1_000, "FILE_HEAD");
    }

    /** 장면 조각이 아직 안 올라왔으면 주문도 횟수도 없다. 올라온 뒤 순회가 낸다. */
    @Test
    void 장면_조각이_아직이면_기다린다() {
        broadcasts.save(Broadcast.startedNow("S-wait", TestIds.STREAMER, "WAIT", 1L, at(0), null));
        seed.session("S-wait", "WAIT", "S-wait", null);
        seed.row("WAIT", 1).pts(0).wallAndPdt(0).session("S-wait").pending().insert();
        seed.row("WAIT", 2).pts(4_000).wallAndPdt(4_000).session("S-wait").insert();
        카드("S-wait", 1_000);

        sweeper(targets).sweepCards();
        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();

        jdbc.update("UPDATE public.stream_segments SET upload_state = 'uploaded'");
        sweeper(targets).sweepCards();
        assertThat(sent).hasSize(1);
        assertOrder(sent.get(0), "seg/WAIT/1", 1_000, "FILE_HEAD");
    }

    /** 라이브 머리(마지막 귀속 행 뒤) 카드는 장면이 없어 주문하지 않는다. */
    @Test
    void 라이브_머리_카드는_주문하지_않는다() {
        broadcasts.save(Broadcast.startedNow("S-head", TestIds.STREAMER, "HEAD", 1L, at(0), null));
        seed.session("S-head", "HEAD", "S-head", null);
        seed.row("HEAD", 1).pts(0).wallAndPdt(0).session("S-head").insert();
        카드("S-head", 4_500);

        sweeper(targets).sweepCards();

        assertThat(sent).isEmpty();
    }

    // ── 여러 방송 묶음(cc rev9 부수 ⑦ · 착수 체크리스트 C4) ─────────────────────────

    /**
     * 순회 한 묶음이 여러 방송에 걸치면 방송마다 그 방송의 회차로 장면을 고른다. 머리 세션 행이 없는 옛 방송(시간창)도 같은
     * 묶음에 든다. 첫 방송 회차로 다 풀면 다른 방송 카드는 장면이 없거나 다른 조각이다.
     */
    @Test
    void 묶음이_여러_방송에_걸치면_방송마다_회차를_고른다() {
        세_방송_시드();

        sweeper(targets).sweepCards();

        assertThat(sent).extracting(job -> job.path("source").path("s3Key").asString())
                .containsExactlyInAnyOrder("seg/MA/2", "seg/MB/1", "seg/MC/1");
    }

    /**
     * 장면 몫 장부 문장은 묶음이 몇 방송에 걸치든 두 번이다(회차 고르기 한 번 + 회차 행 한 번 · C4 · C5). 방송마다 묻는 모양이면
     * 세 방송 묶음이 여섯이 된다.
     */
    @Test
    void 장면_몫_장부_문장은_방송_수와_무관하게_두_번이다() {
        AtomicInteger ledger = new AtomicInteger();
        RoundRows counted = new RoundRows(new JdbcTemplate(counting(dataSource, ledger)));
        ThumbnailTargets measured = new ThumbnailTargets(jdbc, broadcasts, counted, new CardPositionReader(counted));
        세_방송_시드();

        sweeper(measured).sweepCards();

        assertThat(sent).as("양성 대조 — 세 방송 모두 주문했다").hasSize(3);
        assertThat(ledger.get()).isEqualTo(2);
    }

    /**
     * 내려놓을지 따질 카드(장면 없음)가 방송 중인 방송 여럿에 걸쳐도 회차 완결은 묶음 한 번에 묻는다(c2 확인 패스 #9 — 2초 빠른
     * 순회 경로라 방송마다 물으면 묶음당 최대 50 문장). 완결인 방송이 없으면 장면 몫 둘 + 완결 하나 = 셋이고, 완결인 방송이 있으면
     * 그 방송들만 장면을 한 번 더 읽어(회차 고르기 + 회차 행 = 둘 · 종결 r1) 다섯이다. 완결인 방송 카드만 내려놓는다.
     */
    @Test
    void 방송_중_장면_없는_카드의_회차_완결은_묶음당_한_번_묻는다() {
        AtomicInteger ledger = new AtomicInteger();
        RoundRows counted = new RoundRows(new JdbcTemplate(counting(dataSource, ledger)));
        ThumbnailTargets measured = new ThumbnailTargets(jdbc, broadcasts, counted, new CardPositionReader(counted));
        라이브_머리_카드("S-la", "LA", false);
        라이브_머리_카드("S-lb", "LB", false);

        sweeper(measured).sweepCards();
        assertThat(ledger.get()).as("완결 없음").isEqualTo(3);

        long closed = 라이브_머리_카드("S-lc", "LC", true);
        ledger.set(0);
        sweeper(measured).sweepCards();

        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForList("SELECT target_id FROM thumbnails WHERE kind = 'card'", String.class))
                .as("완결 회차 카드만 내려놓는다").containsExactly(String.valueOf(closed));
        assertThat(ledger.get()).as("완결 하나 — 그 방송만 다시 읽는다").isEqualTo(5);
    }

    /**
     * 종결 r1 cc 2 · cx 1 — 회차 행을 읽은 뒤 생산자가 마지막 행을 넣고 회차를 닫으면, 완결은 참인데 읽은 행에는 그 행이 없다.
     * 옛 행만 보고 내려놓으면 이제 장면이 있는 카드를 영구히 포기한다(시도 3 으로 적혀 다시 안 찍는다). 완결이 참이면 그 방송의
     * 회차를 다시 읽어 판정한 뒤에만 내려놓는다 — 닫힌 회차는 더 바뀌지 않는다.
     */
    @Test
    void 행을_읽은_뒤_마지막_행과_완결이_들어오면_다시_읽어_장면을_주문한다() {
        broadcasts.save(Broadcast.startedNow("S-race", TestIds.STREAMER, "RACE", 1L, at(0), null));
        seed.session("S-race", "RACE", "S-race", null);
        seed.row("RACE", 1).pts(0).wallAndPdt(0).session("S-race").insert();
        long card = 카드("S-race", 5_000);
        RoundRows racing = new RoundRows(jdbc) {
            private boolean raced;

            @Override
            public Map<String, List<Row>> rowsOf(Map<String, Round> rounds) {
                Map<String, List<Row>> read = super.rowsOf(rounds);
                if (!raced) {
                    raced = true;
                    seed.row("RACE", 2).pts(4_000).wallAndPdt(4_000).session("S-race").insert();
                    seed.endSession("S-race", true);
                }
                return read;
            }
        };

        sweeper(new ThumbnailTargets(jdbc, broadcasts, racing, new CardPositionReader(racing))).sweepCards();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("targetId").asString()).isEqualTo(String.valueOf(card));
        assertOrder(sent.get(0), "seg/RACE/2", 1_000, "FILE_HEAD");
        assertThat(jdbc.queryForObject("SELECT attempts FROM thumbnails WHERE kind = 'card' AND target_id = ?",
                Integer.class, String.valueOf(card))).as("주문 한 번 — 내려놓지 않았다").isEqualTo(1);
    }

    // ── 실패 격리 ─────────────────────────────────────────────────────────────

    /**
     * 한 방송의 장부 값이 장면 계산의 전제를 깨도(공통 모듈의 행 열 검사) 그 방송 카드만 빠지고 다른 방송 카드는 나간다. 묶음
     * 전체가 실패하면 그 카드가 묶음에 남는 동안 모든 카드 사진이 멈춘다. 빠진 방송은 경고로 남긴다.
     */
    @Test
    void 한_방송의_장면이_깨져도_다른_방송_카드는_나간다() {
        broadcasts.save(Broadcast.startedNow("S-bad", TestIds.STREAMER, "BAD", 1L, at(0), null));
        seed.session("S-bad", "BAD", "S-bad", null);
        seed.row("BAD", 1).pts(0).wallAndPdt(0).duration(-1).session("S-bad").insert();
        seed.row("BAD", 2).pts(4_000).wallAndPdt(4_000).session("S-bad").insert();
        카드("S-bad", 1_000);
        broadcasts.save(Broadcast.startedNow("S-good", TestIds.STREAMER, "GOOD", 1L, at(0), null));
        seed.session("S-good", "GOOD", "S-good", null);
        seed.row("GOOD", 1).pts(0).wallAndPdt(0).session("S-good").insert();
        seed.row("GOOD", 2).pts(4_000).wallAndPdt(4_000).session("S-good").insert();
        카드("S-good", 1_000);

        try (LogCaptor logs = new LogCaptor()) {
            sweeper(targets).sweepCards();

            assertThat(sent).extracting(job -> job.path("source").path("s3Key").asString())
                    .containsExactly("seg/GOOD/1");
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("jumpcard.scene_unavailable streamId=S-bad"));
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private void assertOrder(JsonNode job, String s3Key, long offsetMs, String origin) {
        assertThat(job.path("kind").asString()).isEqualTo("card");
        assertThat(job.path("source").path("bucket").asString()).isEqualTo(조각_창고);
        assertThat(job.path("source").path("s3Key").asString()).isEqualTo(s3Key);
        assertThat(job.path("source").path("offsetMs").isIntegralNumber()).as("정수 offset").isTrue();
        assertThat(job.path("source").path("offsetMs").asLong()).isEqualTo(offsetMs);
        assertThat(job.path("source").path("offsetOrigin").asString()).isEqualTo(origin);
    }

    private ThumbnailSweeper sweeper(ThumbnailTargets using) {
        ThumbnailQueueClient queue = new ThumbnailQueueClient(null, "unused") {
            @Override
            public void send(String body) {
                sent.add(mapper.readTree(body));
            }
        };
        return new ThumbnailSweeper(using, thumbnails, queue, broadcasts, origins, mapper, 조각_창고, 사진_창고,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** (f) 귀속 A(길이 4000) · 재접속 뒤 귀속 B(10초 뒤 · is_discontinuity 참) — MarkSceneP31Test 시드 그대로. */
    private void 시드_f() {
        broadcasts.save(Broadcast.startedNow("S-f", TestIds.STREAMER, "P31F", 1L, at(100_000), null));
        seed.session("S-f", "P31F", "S-f", null);
        seed.session("S-f2", "P31F", "S-f", "S-f");
        seed.row("P31F", 10).pts(200_000).wallAndPdt(100_000).session("S-f").insert();
        seed.row("P31F", 11).pts(210_000).wallAndPdt(110_000).session("S-f2").discontinuity().insert();
        seed.row("P31F", 12).pts(214_000).wallAndPdt(114_000).session("S-f2").insert();
    }

    /** (g) 귀속 j 뒤 비귀속 u(같은 녹화기 · 이어짐) 뒤 귀속 k′ — MarkSceneP31Test 시드 그대로. */
    private void 시드_g() {
        broadcasts.save(Broadcast.startedNow("S-g", TestIds.STREAMER, "P31G", 1L, at(240_000), null));
        seed.session("S-g", "P31G", "S-g", null);
        seed.row("P31G", 20).pts(300_000).wallAndPdt(300_000).session("S-g").insert();
        seed.row("P31G", 21).pts(304_000).wall(304_000).insert();
        seed.row("P31G", 22).pts(308_000).wallAndPdt(308_000).session("S-g").insert();
        seed.row("P31G", 23).pts(312_000).wallAndPdt(312_000).session("S-g").insert();
    }

    /** K1-c — 판 2 두 조각의 PDT · 위치가 999ms 겹친다(뒤 조각 시작 3,001 < 앞 조각 끝 4,000). */
    private void 시드_겹침() {
        broadcasts.save(Broadcast.startedNow("S-k", TestIds.STREAMER, "P31K", 1L, at(60_000), null));
        seed.session("S-k", "P31K", "S-k", null);
        seed.row("P31K", 1).pts(0).wallAndPdt(100_000).session("S-k").v2().insert();
        seed.row("P31K", 2).pts(3_001).wallAndPdt(103_001).session("S-k").v2().insert();
        seed.row("P31K", 3).pts(7_001).wallAndPdt(107_001).session("S-k").v2().insert();
    }

    /**
     * 회차 방송 둘(물리 키 MA · MB)과 머리 세션 행이 없는 옛 방송 하나(MC · 시간창). 세 방송의 카드 위치 축이 서로 겹쳐(모두 0
     * 부근) 첫 방송 회차로 풀면 다른 조각이 나온다.
     */
    private void 세_방송_시드() {
        broadcasts.save(Broadcast.startedNow("S-ma", TestIds.STREAMER, "MA", 1L, at(0), null));
        seed.session("S-ma", "MA", "S-ma", null);
        seed.row("MA", 1).pts(0).wallAndPdt(0).session("S-ma").insert();
        seed.row("MA", 2).pts(4_000).wallAndPdt(4_000).session("S-ma").insert();
        seed.row("MA", 3).pts(8_000).wallAndPdt(8_000).session("S-ma").insert();
        카드("S-ma", 5_000);
        broadcasts.save(Broadcast.startedNow("S-mb", TestIds.STREAMER, "MB", 1L, at(0), null));
        seed.session("S-mb", "MB", "S-mb", null);
        seed.row("MB", 1).pts(4_000).wallAndPdt(4_000).session("S-mb").insert();
        seed.row("MB", 2).pts(8_000).wallAndPdt(8_000).session("S-mb").insert();
        카드("S-mb", 5_000);
        broadcasts.save(Broadcast.startedNow("S-mc", TestIds.STREAMER, "MC", 1L, at(0), null));
        seed.row("MC", 1).pts(4_500).wall(4_500).insert();
        seed.row("MC", 2).pts(8_500).wall(8_500).insert();
        카드("S-mc", 5_000);
    }

    /**
     * 방송 중(편지 {@code live})인 회차 방송 하나와 그 마지막 귀속 행 뒤(라이브 머리) 카드. {@code roundClosed}면 회차 세션을
     * 끝내고 닫는다(완결 · 방송 편지 전).
     *
     * @return 카드 번호
     */
    private long 라이브_머리_카드(String streamId, String key, boolean roundClosed) {
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, key, 1L, at(0), null));
        seed.session(streamId, key, streamId, null);
        seed.row(key, 1).pts(0).wallAndPdt(0).session(streamId).insert();
        if (roundClosed) {
            seed.endSession(streamId, true);
        }
        return 카드(streamId, 9_000);
    }

    private Broadcast 방송_찾기(String streamId) {
        return broadcasts.findByStreamId(streamId).orElseThrow();
    }

    private long 카드(String streamId, long ts) {
        return jdbc.queryForObject("""
                        INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, score, event_seq)
                        VALUES (?, 'auto', ?, ?, ?, ?, 50, 0)
                        RETURNING id""",
                Long.class, streamId, "evt-" + streamId + "-" + ts, ts, ts - 500, ts + 500);
    }
}
