package com.pokeclip.clip.render;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.segment.CutDecision;
import com.pokeclip.clip.segment.LedgerCutPlanner;
import com.pokeclip.clip.segment.RoundRow;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.segment.StreamSegmentReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 소리 주소 API 의 끝 소리 확보 판정(POK-196 · 설계 r7 9.6 · 계획 델타 7절 P38 · 착수 체크리스트 C-B2 · C-B5). 표지 갈래를 거둔
 * 뒤에는 응답이 하나다 — 조각 목록 {@code segments} 는 PR-B1 이전 develop 뜻(물리 키 목록 PDT 겹침 · 올라간 조각)이고, 뿌리에
 * {@code audioCapture} 를 늘 싣고 {@code attached} 일 때만 확보 조각 {@code audioCaptureSegment} 를 싣는다(kty K-2 (나)).
 *
 * <p><b>판정 입력은 응답 목록과 분리한다.</b> k(영상 범위의 마지막 조각) · 컷 끝 좌표 · 원점은 렌더 주문과 같은 회차 귀속 F끝
 * ({@code locateEnd(toMs)} → {@code endOf})에서 얻는다. 응답 목록의 마지막은 비귀속 · 아직 안 올라간 행일 수 있어서다(r1 cx
 * 반례 A · U · B).
 *
 * <p>응답은 문(MVC)과 같은 JSON 사상으로 문자열을 떠 잰다(컨텍스트의 {@code ObjectMapper}).
 */
class SegmentAudioCaptureTest extends IntegrationTestSupport {

    private static final String 요청자 = "4182";
    private static final String 방송 = "S-aud";
    private static final String 키 = "AUDKEY";

    /**
     * PR-B1 이전 develop 응답 바이트 — develop(8e7b329c)의 조회식 · 응답 칸 순서로 아래 {@link #래칫_시드} 를 돌려 뜬 것이다. 시각에
     * 따라 바뀌는 두 값(만료 시각 · 서명 주소)만 자리표시다. 다시 뜰 때는 develop 을 체크아웃해 같은 시드로 응답을 받는다.
     */
    private static final String DEVELOP = "/render/audio-access-develop.json";

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final StreamSegmentReader segments;
    private final RoundRows rounds;
    private final LedgerCutPlanner cuts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DataSource dataSource;
    private final LedgerSeed seed;

    SegmentAudioCaptureTest(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                            RoundRows rounds, LedgerCutPlanner cuts, JdbcTemplate jdbc, ObjectMapper mapper,
                            DataSource dataSource) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.segments = segments;
        this.rounds = rounds;
        this.cuts = cuts;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        AUTH.respondWith("/internal/editor-delegations/resolve", 200, "{\"relation\":\"OWNER\"}");
    }

    // ── 응답 꼴(D3 · K-2) ─────────────────────────────────────────

    /**
     * {@code audioCapture ≠ attached} 이면 조각 목록이 PR-B1 이전 develop 응답과 바이트 같고 뿌리에 {@code audioCapture} 만
     * 더해진다. 래칫 시드는 판 NULL · PDT 가 파일명 시각보다 6초 앞서므로, 파일명 시각 축으로 고르면 다른 행이 실린다.
     */
    @Test
    void attached_가_아니면_조각_목록이_develop_과_바이트_같고_뿌리에_audioCapture_만_더한다() throws Exception {
        래칫_시드();

        String body = 정규화(json(issue(10_000, 22_000)));

        String develop = new String(getClass().getResourceAsStream(DEVELOP).readAllBytes(), StandardCharsets.UTF_8)
                .strip();
        assertThat(body).isEqualTo(develop.substring(0, develop.length() - 1) + ",\"audioCapture\":\"not_ready\"}");
    }

    /**
     * {@code attached} 이면 확보 조각을 뿌리 칸 {@code audioCaptureSegment} 에 따로 싣고 {@code segments} 는 develop 뜻 그대로다
     * (창과 겹치는 올라간 조각만 — 확보 조각은 창 밖이라 없다). 판 NULL 은 거리 조건을 보지 않는다(끝이 조각 머리 1초여도 싣는다).
     * 거둔 칸(메아리 · 공백 쌍 · 조각 시작 파일명 시각 · 역할 · 이웃 표시)은 어디에도 없다.
     */
    @Test
    void attached_면_확보_조각을_뿌리_칸에_따로_싣고_거둔_칸은_없다() throws Exception {
        래칫_시드();

        JsonNode root = mapper.readTree(json(issue(10_000, 11_000)));

        assertThat(fieldNames(root)).containsExactly("expiresAt", "segments", "audioCapture", "audioCaptureSegment");
        assertThat(root.get("audioCapture").asString()).isEqualTo("attached");
        assertThat(seqs(root)).as("창 [10000, 11000) 과 겹치는 올라간 조각").containsExactly(2L);
        JsonNode capture = root.get("audioCaptureSegment");
        assertThat(fieldNames(capture)).containsExactly("seq", "startAtMs", "durationMs", "url");
        assertThat(capture.get("seq").asLong()).isEqualTo(3L);
        assertThat(capture.get("startAtMs").asLong()).as("목록 PDT 축").isEqualTo(at(14_000).toEpochMilli());
        assertThat(capture.get("durationMs").asInt()).isEqualTo(4_000);
        assertThat(capture.get("url").asString()).contains("seg/AUDKEY/3").contains("X-Amz-Signature=");
        for (JsonNode s : root.get("segments")) {
            assertThat(fieldNames(s)).containsExactly("seq", "startAtMs", "durationMs", "url");
        }
    }

    /** {@code attached} 가 아니면 확보 조각 칸이 없다(빈 칸은 싣지 않는다). */
    @Test
    void attached_가_아니면_확보_조각_칸이_없다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();

        JsonNode root = mapper.readTree(json(issue(1_000, 3_500)));

        assertThat(root.get("audioCapture").asString()).isEqualTo("not_ready");
        assertThat(fieldNames(root)).containsExactly("expiresAt", "segments", "audioCapture");
    }

    // ── 판 2 거리 조건 · 원점(P38) ────────────────────────────────

    /**
     * 판 2 는 거리 조건 {@code x_out > dur − H}(H 1초)를 영상 첫 표본 원점(PDT)에서 잰다. PDT 가 파일명 시각보다 0.8초 뒤인 시드 —
     * k = 3(PDT 8800)에서 끝 12500 은 3700 이라 참(싣는다), 11500 은 2700 이라 거짓(없이 진행)이다. 원점을 파일 머리(파일명 시각
     * 8000)로 재면 11500 이 3500 이 되어 싣는 쪽으로 갈린다.
     */
    @Test
    void 판_2_거리_조건은_영상_첫_표본_원점으로_잰다() throws Exception {
        판_2_시드(800);

        assertThat(capture(issue(9_000, 12_500))).as("3700 > 3000").isEqualTo("attached");
        assertThat(capture(issue(9_000, 11_500))).as("2700 ≤ 3000").isEqualTo("skipped");
    }

    // ── 후보 없음 · 끝남 · failed(P38) ─────────────────────────────

    /** 다음 행이 없으면 방송이 끝났거나 회차가 닫혔을 때만 없이 진행한다(ended ∨ 회차 완결 — 6-6-8 5항). 둘 다 아니면 기다린다. */
    @Test
    void 후보가_없으면_방송_중엔_not_ready_이고_회차_완결이나_방송_종료면_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();

        assertThat(capture(issue(1_000, 3_500))).as("방송 중 · 회차 열림").isEqualTo("not_ready");

        seed.endSession(방송, true);
        assertThat(capture(issue(1_000, 3_500))).as("회차 완결").isEqualTo("skipped");

        jdbc.update("UPDATE public.stream_sessions SET round_closed_at = NULL");
        끝낸다();
        assertThat(capture(issue(1_000, 3_500))).as("방송 종료 · 회차 열림").isEqualTo("skipped");
    }

    /** 확보 후보가 {@code failed} 면 15분까지는 아직이고, 넘으면 없이 진행한다(영구 대기가 아니다). */
    @Test
    void 확보_후보가_failed_면_15분까지_not_ready_이고_넘으면_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        seed.row(키, 2).wallAndPdt(4_000).session(방송).insert();
        jdbc.update("UPDATE public.stream_segments SET upload_state = 'failed' WHERE seq = 2");

        assertThat(capture(issueAt(1_000, 3_500, at(4_000).plus(Duration.ofMinutes(14))))).isEqualTo("not_ready");
        assertThat(capture(issueAt(1_000, 3_500, at(4_000).plus(Duration.ofMinutes(16))))).isEqualTo("skipped");
    }

    /** 다음 행이 새 녹화면({@code is_discontinuity} 참) 다른 회차 · 방송으로 넘어가지 않는다 — 없이 진행. */
    @Test
    void 후보가_새_녹화면_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        seed.row(키, 2).wallAndPdt(4_000).discontinuity().insert();

        assertThat(capture(issue(1_000, 3_500))).isEqualTo("skipped");
    }

    /**
     * 다음 방송의 머리 세션에 귀속된 다음 행도 새 녹화({@code is_discontinuity} 참)면 확보로 싣지 않는다(c5 보안 L2 — 지운
     * {@code 다음_회차_첫_행이_새_녹화면_확보로_싣지_않는다} 를 잇는다). 확보 후보는 회차로 자르지 않으므로(계약 6-6-5) 다른 방송으로
     * 넘어가는 것을 막는 것은 이 표시뿐이다. 렌더 주문과 같은 판정이라 회귀가 나면 양쪽이 같이 깨진다.
     */
    @Test
    void 다음_방송_머리_세션의_새_녹화_행은_확보로_싣지_않는다() throws Exception {
        방송(true);
        broadcasts.save(Broadcast.startedNow("S-aud-next", TestIds.STREAMER, 키, 2L, at(30_000), null));
        seed.session("S-aud-next", 키, "S-aud-next", null);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        seed.row(키, 2).wallAndPdt(4_000).session("S-aud-next").discontinuity().insert();

        JsonNode root = mapper.readTree(json(issue(1_000, 4_000)));

        assertThat(root.get("audioCapture").asString()).isEqualTo("skipped");
        assertThat(root.has("audioCaptureSegment")).isFalse();
        assertThat(seqs(root)).containsExactly(1L);
    }

    // ── 세션 없는 옛 방송 · 판정 실패 · 방송 삭제 경합(c5 지적 4 · 5 · kty 2026-11-12 A17) ──────

    /**
     * 머리 세션 행이 없는 옛 방송(시간창 회차 — 회차 완결 술어가 늘 거짓)은 끝난 방송이어도 창 끝이 마지막 행 뒤면 {@code not_ready}
     * 다(렌더도 409 로 기다린다). 목록은 develop 뜻 그대로 — 물리 키 조각 가운데 창과 목록 PDT 축으로 겹치는 올라간 조각이다.
     */
    @Test
    void 세션_없는_옛_방송은_라이브_머리_창이면_끝났어도_not_ready_이고_목록은_그대로다() throws Exception {
        방송(false);
        seed.row(키, 1).wallAndPdt(0).insert();
        seed.row(키, 2).wallAndPdt(4_000).insert();
        seed.row(키, 3).wallAndPdt(8_000).insert();
        끝낸다();

        JsonNode root = mapper.readTree(json(issue(6_000, 14_000)));

        assertThat(root.get("audioCapture").asString()).isEqualTo("not_ready");
        assertThat(root.has("audioCaptureSegment")).isFalse();
        assertThat(seqs(root)).as("창 [6000, 14000) 과 겹치는 올라간 조각").containsExactly(2L, 3L);
        for (JsonNode s : root.get("segments")) {
            assertThat(fieldNames(s)).containsExactly("seq", "startAtMs", "durationMs", "url");
        }
    }

    /**
     * 끝 소리 확보 판정이 실패하면(여기선 회차 안 판 섞임 — 장부 불변식 위반 · 생산자 DDL 없음도 같은 길) 확보 칸만 빼고 목록은
     * develop 뜻 그대로 낸다 — 카드 장면 실패({@code jumpcard.scene_unavailable})와 같은 처분이다(kty 2026-11-12 A17). WARN 에는
     * 예외 클래스 이름만 싣는다.
     */
    @Test
    void 확보_판정이_실패하면_확보_칸만_빼고_목록은_그대로_낸다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).v2().insert();
        seed.row(키, 2).wallAndPdt(4_000).session(방송).insert();

        try (LogCaptor logs = new LogCaptor()) {
            JsonNode root = mapper.readTree(json(issue(1_000, 6_000)));

            assertThat(fieldNames(root)).containsExactly("expiresAt", "segments");
            assertThat(seqs(root)).containsExactly(1L, 2L);
            assertThat(logs.messages()).contains(
                    "clip.segment_audio_access.capture_unavailable streamId=" + 방송 + " reason=IllegalStateException");
        }
    }

    /** 자격 검사 뒤 방송 줄이 지워지면(삭제 경합) 500 이 아니라 404 {@code broadcast_not_found} 다 — 자격 검사와 같은 답. */
    @Test
    void 자격_검사_뒤_방송이_지워지면_404_다() {
        방송(true);
        BroadcastRepository vanished = mock(BroadcastRepository.class);
        given(vanished.isVodExpired(방송)).willReturn(false);
        given(vanished.findByStreamId(방송)).willReturn(Optional.empty());
        SegmentAudioAccessService service = service(segments, cuts, at(100_000), vanished);

        assertThatThrownBy(() -> service.issue(요청자, 방송, at(1_000).toEpochMilli(), at(2_000).toEpochMilli()))
                .isInstanceOfSatisfying(AccessErrors.NotViewableException.class,
                        e -> assertThat(e.reason()).isEqualTo("broadcast_not_found"));
    }

    /**
     * R28(kty 결정 (나)) — 회차 행을 읽은 직후 생산자가 마지막 행과 다음 행을 넣고 회차를 닫으면, 옛 행 목록으로는 창 끝이 마지막
     * 행 뒤(AfterLast)인데 완결이 참이라 {@code skipped} 가 나왔다(맞는 답은 새 행 위 {@code attached}). 완결이 참이면 세션 목록 ·
     * 회차 행을 다시 읽어 판정한다 — 닫힌 회차는 더 바뀌지 않는다. 렌더 주문의 같은 처분(B2)과 짝이다.
     */
    @Test
    void 행을_읽은_뒤_마지막_행과_완결이_들어오면_다시_읽어_확보한다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        StreamSegmentReader racing = new StreamSegmentReader(jdbc) {
            private boolean raced;

            @Override
            public List<RoundRow> findRoundRows(RoundRows.Round round) {
                List<RoundRow> read = super.findRoundRows(round);
                if (!raced) {
                    raced = true;
                    seed.row(키, 2).wallAndPdt(4_000).session(방송).insert();
                    seed.row(키, 3).wallAndPdt(8_000).insert();
                    seed.endSession(방송, true);
                }
                return read;
            }
        };

        JsonNode root = mapper.readTree(json(service(racing, new LedgerCutPlanner(rounds, racing), at(100_000))
                .issue(요청자, 방송, at(1_000).toEpochMilli(), at(6_000).toEpochMilli())));

        assertThat(root.get("audioCapture").asString()).isEqualTo("attached");
        assertThat(root.get("audioCaptureSegment").get("seq").asLong()).isEqualTo(3L);
    }

    // ── 판정 전에 끝나는 갈래(C-B2 대응표) ─────────────────────────

    /**
     * cx 반례 — 판 2 · A 10 귀속 · U 11 비귀속 {@code pending} · B 12 귀속 · 창 {@code [100000, 107000)}. 렌더는 끝이 A · B 사이
     * 끊긴 틈이라 A 끝까지(toSegmentEnd)이고 후보 U 가 아직이라 409({@code capture_pending}) → {@code not_ready}. 응답 목록의 마지막
     * (U — 비귀속 · 아직)을 k 로 고르면 U 끝 거리 3000 이 거짓이라 {@code skipped} 로 갈린다. U 가 올라가면 렌더는 U 를 확보로
     * 싣는다 — 올라간 목록의 마지막(U)을 k 로 골라도 같은 까닭으로 {@code skipped} 로 갈린다.
     */
    @Test
    void 응답_목록_마지막이_비귀속이어도_k_는_회차_귀속_F끝이다() throws Exception {
        방송(true);
        seed.row(키, 10).wallAndPdt(100_000).session(방송).v2().insert();
        seed.row(키, 11).wallAndPdt(104_000).v2().pending().insert();
        seed.row(키, 12).wallAndPdt(108_000).session(방송).v2().insert();

        JsonNode pending = mapper.readTree(json(issue(100_000, 107_000)));
        assertThat(pending.get("audioCapture").asString()).isEqualTo("not_ready");
        assertThat(seqs(pending)).as("U 는 아직이라 목록에 없다 · B 는 창 밖").containsExactly(10L);

        jdbc.update("UPDATE public.stream_segments SET upload_state = 'uploaded' WHERE seq = 11");
        JsonNode uploaded = mapper.readTree(json(issue(100_000, 107_000)));
        assertThat(uploaded.get("audioCapture").asString()).isEqualTo("attached");
        assertThat(uploaded.get("audioCaptureSegment").get("seq").asLong()).isEqualTo(11L);
        assertThat(seqs(uploaded)).as("U 는 창과 겹쳐 목록에도 있다 — 목록 뜻은 그대로").containsExactly(10L, 11L);
    }

    /** 창이 회차 귀속 행 사이 끊긴 틈 안에만 있으면 영상 조각이 없다(렌더 422 {@code no_footage}) → 확보할 것이 없다. */
    @Test
    void 창에_영상_조각이_없으면_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        seed.row(키, 2).wallAndPdt(10_000).session(방송).discontinuity().insert();

        assertThat(capture(issue(5_000, 8_000))).isEqualTo("skipped");
    }

    /**
     * 창 시작이 회차 첫 행 앞(렌더 422 {@code outside_recording}) → 렌더가 받지 않는 컷이라 확보도 없다. 끝(k = 1 · 다음 행 없음
     * · 방송 중)만 보면 기다리는 쪽이다 — 끝만 보는 판정과 갈린다.
     */
    @Test
    void 창_시작이_첫_행_앞이면_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(10_000).session(방송).insert();

        assertThat(capture(issue(8_000, 12_000))).isEqualTo("skipped");
    }

    /** 창 끝이 마지막 행 뒤(라이브 머리) — 회차가 안 닫혔으면 렌더 409 라 {@code not_ready}, 닫혔으면 렌더 422 라 {@code skipped}. */
    @Test
    void 창_끝이_라이브_머리면_회차가_닫힐_때까지_not_ready_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();

        assertThat(capture(issue(3_000, 6_000))).as("회차 열림").isEqualTo("not_ready");

        seed.endSession(방송, true);
        assertThat(capture(issue(3_000, 6_000))).as("회차 완결").isEqualTo("skipped");
    }

    /**
     * 창 안 회차 행이 아직 안 올라갔으면(렌더 409 {@code media_pending}) {@code not_ready} 다 — k = 3 의 다음 행 4 가 올라가 있어
     * 끝만 보면 싣는 쪽이지만, 렌더는 거기까지 가지 않는다. 목록은 올라간 조각만이다.
     */
    @Test
    void 창_안_회차_행이_아직이면_not_ready_다() throws Exception {
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        seed.row(키, 2).wallAndPdt(4_000).session(방송).pending().insert();
        seed.row(키, 3).wallAndPdt(8_000).session(방송).insert();
        seed.row(키, 4).wallAndPdt(12_000).session(방송).insert();

        JsonNode root = mapper.readTree(json(issue(1_000, 9_000)));

        assertThat(root.get("audioCapture").asString()).isEqualTo("not_ready");
        assertThat(seqs(root)).containsExactly(1L, 3L);
    }

    /** 회차 행이 아직 없는 방송(렌더 409 {@code not_yet_recorded}) — 회차가 닫혔으면 렌더 422 라 {@code skipped}. */
    @Test
    void 회차_행이_없으면_회차가_닫힐_때까지_not_ready_다() throws Exception {
        방송(true);

        assertThat(capture(issue(1_000, 3_500))).as("회차 열림").isEqualTo("not_ready");

        seed.endSession(방송, true);
        assertThat(capture(issue(1_000, 3_500))).as("회차 완결").isEqualTo("skipped");
    }

    // ── 렌더 주문과 같은 판정(6-6-8 8항 · Ready 이후 갈래) ──────────

    /**
     * 같은 시드 · 같은 창을 렌더 주문 컷으로 받았을 때의 확보 결과와 소리 응답 {@code audioCapture} 가 같다. 렌더가 확보 판정까지
     * 가는 갈래만 맞댄다(판정 전에 끝나는 갈래는 위 대응표 시험이 잰다).
     */
    @Test
    void 렌더_주문_확보_결과와_소리_응답_audioCapture_가_같다() throws Exception {
        판_2_시드(800);
        assertSameAsRender(9_000, 12_500, "attached");
        assertSameAsRender(9_000, 11_500, "skipped");

        방송과_카드를_비운다(jdbc);
        래칫_시드();
        assertSameAsRender(10_000, 11_000, "attached");

        방송과_카드를_비운다(jdbc);
        방송(true);
        seed.row(키, 10).wallAndPdt(100_000).session(방송).v2().insert();
        seed.row(키, 11).wallAndPdt(104_000).v2().pending().insert();
        seed.row(키, 12).wallAndPdt(108_000).session(방송).v2().insert();
        assertSameAsRender(100_000, 107_000, "not_ready");
        jdbc.update("UPDATE public.stream_segments SET upload_state = 'uploaded' WHERE seq = 11");
        assertSameAsRender(100_000, 107_000, "attached");

        방송과_카드를_비운다(jdbc);
        방송(true);
        seed.row(키, 1).wallAndPdt(0).session(방송).insert();
        끝낸다();
        assertSameAsRender(1_000, 3_500, "skipped");
    }

    // ── 신호 · 왕복 ───────────────────────────────────────────────

    /** 발급 신호에 확보 판정이 남고, 거둔 칸(처리한 축 · 요청 표지)은 없다. */
    @Test
    void 발급_신호에_audioCapture_가_남고_거둔_칸은_없다() {
        래칫_시드();

        try (LogCaptor logs = new LogCaptor()) {
            issue(10_000, 22_000);

            assertThat(logs.messages()).anyMatch(m -> m.startsWith("clip.segment_audio_access.issued streamId=S-aud")
                    && m.contains(" segments=2 ") && m.endsWith(" audioCapture=not_ready"));
            assertThat(logs.messages()).noneMatch(m -> m.contains("axis=") || m.contains("requested="));
        }
    }

    /**
     * 장부 왕복(C-B5 · R24) — 회차 고르기 1 · 회차 귀속 행 1 · 응답 목록 1 · 확보 후보 0–1(판 2 는 거리 조건이 참일 때 · 판 NULL 은
     * 늘) · 회차 완결 0–1(확보 후보가 장부에 없고 방송 중일 때만 — 지연 평가). 방송 행은 JPA 라 이 측정기에 안 잡힌다.
     */
    @Test
    void 장부_왕복은_후보가_있으면_넷이고_방송_중_후보가_없을_때만_다섯이다() {
        래칫_시드();
        AtomicInteger statements = new AtomicInteger();
        SegmentAudioAccessService service = countedService(statements);

        service.issue(요청자, 방송, at(10_000).toEpochMilli(), at(11_000).toEpochMilli());
        assertThat(statements).as("방송 중 · 확보 후보 있음").hasValue(4);

        statements.set(0);
        service.issue(요청자, 방송, at(42_000).toEpochMilli(), at(45_000).toEpochMilli());
        assertThat(statements).as("방송 중 · 마지막 행이라 후보 없음 → 회차 완결을 묻는다").hasValue(5);

        끝낸다();
        statements.set(0);
        service.issue(요청자, 방송, at(42_000).toEpochMilli(), at(45_000).toEpochMilli());
        assertThat(statements).as("끝난 방송 — 회차 완결을 안 묻는다").hasValue(4);
    }

    /**
     * R28 다시 판정의 왕복 — 방송 중 · 후보 없음(다섯) 갈래에서 회차 완결이 참이면 회차 고르기 · 회차 행을 한 번씩 더 읽고, 다시
     * 읽은 행으로도 컷 끝이 같은 조각이라 확보 후보도 한 번 더 묻는다 — 여덟. 완결이 거짓이면 그대로 다섯이다(위 시험).
     */
    @Test
    void 장부_왕복은_방송_중_회차_완결이_참이면_다시_읽어_여덟이다() {
        래칫_시드();
        seed.endSession(방송, true);
        AtomicInteger statements = new AtomicInteger();

        countedService(statements).issue(요청자, 방송, at(42_000).toEpochMilli(), at(45_000).toEpochMilli());

        assertThat(statements).as("방송 중 · 회차 완결 참 → 회차 고르기 · 회차 행 · 확보 후보를 다시 읽는다").hasValue(8);
    }

    /** 장부 문장을 세는 소리 주소 서비스. */
    private SegmentAudioAccessService countedService(AtomicInteger statements) {
        JdbcTemplate counted = new JdbcTemplate(counting(dataSource, statements));
        StreamSegmentReader reader = new StreamSegmentReader(counted);
        return service(reader, new LedgerCutPlanner(new RoundRows(counted), reader), at(100_000));
    }

    // ── 시드 · 도우미 ──────────────────────────────────────────────

    /** 판 NULL · PDT 가 파일명 시각보다 6초 앞섬 · 4 는 아직(develop 기대값 파일과 같은 시드). */
    private void 래칫_시드() {
        방송(true);
        for (int i = 0; i < 10; i++) {
            LedgerSeed.Row row = seed.row(키, i + 1).pts(4_000L * i).wall(4_000L * i).pdt(4_000L * i + 6_000)
                    .session(방송);
            (i == 3 ? row.pending() : row).insert();
        }
    }

    /** 판 2 · 조각 열 개 · PDT 가 파일명 시각보다 {@code pdtLeadMs} 뒤(영상 첫 표본이 파일 머리보다 늦다). */
    private void 판_2_시드(long pdtLeadMs) {
        방송(true);
        for (int i = 0; i < 10; i++) {
            seed.row(키, i + 1).pts(4_000L * i).wall(4_000L * i).pdt(4_000L * i + pdtLeadMs).session(방송).v2()
                    .insert();
        }
    }

    private void 방송(boolean withSession) {
        broadcasts.save(Broadcast.startedNow(방송, TestIds.STREAMER, 키, 1L, at(0), null));
        if (withSession) {
            seed.session(방송, 키, 방송, null);
        }
    }

    private void 끝낸다() {
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = ?",
                Timestamp.from(at(60_000)), 방송);
    }

    /** 같은 창을 렌더 주문 컷으로 판정한 확보 결과(확보 판정까지 가는 갈래만)와 소리 응답이 같은지, 그리고 그 값이 기대값인지. */
    private void assertSameAsRender(long from, long to, String expected) throws Exception {
        Broadcast broadcast = broadcasts.findByStreamId(방송).orElseThrow();
        CutDecision decision = cuts.plan(broadcast, 1L, at(from).toEpochMilli(), at(to).toEpochMilli(), List.of(),
                at(100_000));
        String render = switch (decision) {
            case CutDecision.Ready ready -> ready.capture().isPresent() ? "attached" : "skipped";
            case CutDecision.NotReady notReady when "capture_pending".equals(notReady.reason()) -> "not_ready";
            default -> throw new AssertionError("렌더가 확보 판정 전에 끝났다: " + decision);
        };
        assertThat(capture(issue(from, to))).as("소리 응답 = 렌더 확보").isEqualTo(render).isEqualTo(expected);
    }

    private SegmentAudioAccess issue(long from, long to) {
        return issueAt(from, to, at(100_000));
    }

    private SegmentAudioAccess issueAt(long from, long to, Instant now) {
        return service(segments, cuts, now).issue(요청자, 방송, at(from).toEpochMilli(), at(to).toEpochMilli());
    }

    private SegmentAudioAccessService service(StreamSegmentReader reader, LedgerCutPlanner planner, Instant now) {
        return service(reader, planner, now, broadcasts);
    }

    private SegmentAudioAccessService service(StreamSegmentReader reader, LedgerCutPlanner planner, Instant now,
                                              BroadcastRepository broadcastRows) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("signer", new SegmentAudioSigner(S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build(), "segments-test", Duration.ofMinutes(5)));
        return new SegmentAudioAccessService(guard, broadcastRows, reader, planner,
                factory.getBeanProvider(SegmentAudioSigner.class), Clock.fixed(now, ZoneOffset.UTC));
    }

    private String json(SegmentAudioAccess access) {
        return mapper.writeValueAsString(access);
    }

    private String capture(SegmentAudioAccess access) throws Exception {
        return mapper.readTree(json(access)).get("audioCapture").asString();
    }

    /** 시각에 따라 바뀌는 두 값(만료 시각 · 서명 주소)만 자리표시로. */
    private static String 정규화(String body) {
        return body.replaceAll("\"expiresAt\":\"[^\"]*\"", "\"expiresAt\":\"<EXPIRES_AT>\"")
                .replaceAll("\"url\":\"[^\"]*\"", "\"url\":\"<URL>\"");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static List<Long> seqs(JsonNode root) {
        List<Long> out = new ArrayList<>();
        root.get("segments").forEach(s -> out.add(s.get("seq").asLong()));
        return out;
    }

    private static final Set<String> STATEMENT_MAKERS = Set.of("prepareStatement", "createStatement", "prepareCall");

    /** 연결이 문장을 만들 때마다 센다(CardPositionRoundTripTest 와 같은 측정기). */
    private static DataSource counting(DataSource real, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (result instanceof Connection connection) {
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[] {Connection.class}, (p, m, a) -> {
                                    if (STATEMENT_MAKERS.contains(m.getName())) {
                                        counter.incrementAndGet();
                                    }
                                    return invoke(connection, m, a);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
