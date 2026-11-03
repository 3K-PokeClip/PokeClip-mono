package com.pokeclip.clip.render;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.render.RenderErrors.InvalidSegmentRangeException;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 소리 주소 API 의 축 표지(POK-196 · 설계 r7 9.6 · 16.2 P22). 표지 {@code axis=fileHead} 가 있으면 두 판 모두 파일명 시각 축으로
 * 후보 · 범위 끝 · 조각 시작 · {@code gapEvidence} 를 내고 뿌리에 메아리를 싣는다. 표지가 없으면 develop 과 같다 — 조각마다
 * {@code startWallUtcMs} 칸만 더한다.
 *
 * <p>응답은 문(MVC)과 같은 JSON 사상으로 문자열을 떠 잰다(컨텍스트의 {@code ObjectMapper}).
 */
class SegmentAudioAxisTest extends IntegrationTestSupport {

    private static final String 요청자 = "4182";
    private static final String 방송 = "S-aud";
    private static final String 키 = "AUDKEY";

    /** develop 응답 바이트(그때 코드로 뜬 것 · 시각에 따라 바뀌는 두 값만 자리표시). */
    private static final String DEVELOP = "/render/audio-access-develop.json";

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final StreamSegmentReader segments;
    private final RoundRows rounds;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DataSource dataSource;
    private final LedgerSeed seed;

    SegmentAudioAxisTest(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                         RoundRows rounds, JdbcTemplate jdbc, ObjectMapper mapper, DataSource dataSource) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.segments = segments;
        this.rounds = rounds;
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

    // ── 표지 없음 = develop 그대로(P22 ①) ──────────────────────────

    /**
     * 판 NULL 래칫 시드(PDT 가 파일명 시각보다 6초 앞섬) — 표지 없는 응답에서 {@code startWallUtcMs} 를 빼면 develop 응답과 바이트가
     * 같다(행 집합 · 순서 · 다른 칸). 표지와 무관하게 축을 바꾸면(rev3 꼴) 고르는 행이 달라져 여기서 빨간불이다.
     */
    @Test
    void 표지_없는_응답은_새_칸을_뺀_나머지가_develop_과_바이트_같다() throws Exception {
        래칫_시드();

        String body = json(issue(10_000, 22_000, null));

        String expected = new String(getClass().getResourceAsStream(DEVELOP).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(정규화(body).replaceAll(",\"startWallUtcMs\":\\d+", "")).isEqualTo(expected);
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("segments").get(0).get("startWallUtcMs").asLong())
                .as("새 칸 — start_wall_utc 의 ms 내림").isEqualTo(at(4_000).toEpochMilli());
    }

    // ── 표지 있음(P22 ②) ──────────────────────────────────────────

    /**
     * 표지가 있으면 파일명 시각 축으로 고른다(래칫 시드에서 develop 과 다른 행). 메아리 · 조각 시작 · 이웃 표시 · 확보 조각까지.
     * 아직 안 올라간 4 는 빠지고, 그 뒤 5 의 이웃 표시는 거짓이다(응답의 바로 앞 조각이 장부 바로 앞 행이 아님).
     */
    @Test
    void 표지가_있으면_파일명_시각_축으로_고르고_메아리를_싣는다() throws Exception {
        래칫_시드();

        JsonNode root = mapper.readTree(json(issue(10_000, 22_000, "fileHead")));

        assertThat(root.get("axis").asString()).isEqualTo("fileHead");
        assertThat(seqs(root)).containsExactly(3L, 5L, 6L, 7L);
        JsonNode first = root.get("segments").get(0);
        assertThat(first.get("startWallUtcMs").asLong()).isEqualTo(at(8_000).toEpochMilli());
        assertThat(first.get("startAtMs").asLong()).as("옛 값 그대로(재생 축)").isEqualTo(at(14_000).toEpochMilli());
        assertThat(roles(root)).containsExactly("segment", "segment", "segment", "audio_capture");
        assertThat(follows(root)).containsExactly(false, false, true, true);
        assertThat(root.get("segments").get(1).get("isDiscontinuity").asBoolean()).isFalse();
        assertThat(root.get("audioCapture").asString()).isEqualTo("attached");
        assertThat(root.get("gapEvidence").size()).isZero();
    }

    /** 판 2 회차도 파일명 시각 축이다 — PDT 를 120초 어긋낸 시드에서 PDT 로 고르면 다른 행이다. */
    @Test
    void 판_2_회차도_표지가_있으면_파일명_시각으로_고른다() throws Exception {
        방송(true);
        for (int i = 0; i < 20; i++) {
            seed.row(키, i + 1).pts(4_000L * i).wall(4_000L * i).pdt(4_000L * i + 120_000).session(방송).v2().insert();
        }

        JsonNode root = mapper.readTree(json(issue(40_000, 45_500, "fileHead")));

        assertThat(seqs(root)).containsExactly(11L, 12L);
        assertThat(root.get("audioCapture").asString())
                .as("판 2 거리 조건 — 끝이 조각 끝에서 1초 넘게 안쪽이라 확보 없음").isEqualTo("skipped");
    }

    /** 회차 귀속 행 사이에 비귀속 행이 끼면 그 쌍이 확정 공백이다 — 이웃 표시 거짓 + {@code gapEvidence} 쌍. */
    @Test
    void 비귀속_행이_낀_회차_이웃은_gapEvidence_쌍이다() throws Exception {
        방송(true);
        seed.row(키, 1).pts(0).wall(0).session(방송).insert();
        seed.row(키, 2).pts(4_000).wall(4_000).duration(300).insert();
        seed.row(키, 3).pts(4_300).wall(4_300).session(방송).insert();

        JsonNode root = mapper.readTree(json(issue(1_000, 7_000, "fileHead")));

        assertThat(seqs(root)).containsExactly(1L, 3L);
        assertThat(follows(root)).containsExactly(false, false);
        assertThat(root.get("gapEvidence").toString()).isEqualTo("[{\"beforeSeq\":1,\"afterSeq\":3}]");
    }

    // ── 끝 소리 확보(계약 6-6-8 · AudioCaptureRule) ────────────────

    @Test
    void 확보_후보가_아직이면_not_ready_이고_새_녹화면_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).pts(0).wall(0).session(방송).insert();
        seed.row(키, 2).pts(4_000).wall(4_000).pending().insert();

        JsonNode pending = mapper.readTree(json(issue(1_000, 3_500, "fileHead")));
        assertThat(pending.get("audioCapture").asString()).isEqualTo("not_ready");
        assertThat(seqs(pending)).containsExactly(1L);

        jdbc.update("UPDATE public.stream_segments SET upload_state = 'uploaded', is_discontinuity = true WHERE seq = 2");
        assertThat(mapper.readTree(json(issue(1_000, 3_500, "fileHead"))).get("audioCapture").asString())
                .isEqualTo("skipped");
    }

    /** 다음 행이 없으면 방송이 끝났거나 회차가 닫혔을 때만 없이 진행한다(ended ∨ 회차 완결). 둘 다 아니면 기다린다. */
    @Test
    void 다음_행이_없으면_방송_종료나_회차_완결이어야_skipped_다() throws Exception {
        방송(true);
        seed.row(키, 1).pts(0).wall(0).session(방송).insert();

        assertThat(capture(issue(1_000, 3_500, "fileHead"))).as("방송 중 · 회차 열림").isEqualTo("not_ready");

        seed.endSession(방송, true);
        assertThat(capture(issue(1_000, 3_500, "fileHead"))).as("회차 완결").isEqualTo("skipped");

        jdbc.update("UPDATE public.stream_sessions SET round_closed_at = NULL");
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = ?",
                Timestamp.from(at(60_000)), 방송);
        assertThat(capture(issue(1_000, 3_500, "fileHead"))).as("방송 종료").isEqualTo("skipped");
    }

    // ── 갈래 · 신호 ──────────────────────────────────────────────

    /** 회차를 못 고르는 방송(머리 세션 행도 시작 시각도 없음)은 그 축으로 처리하지 않았으니 메아리 없이 develop 그대로다. */
    @Test
    void 회차를_못_고르면_표지가_있어도_메아리_없이_옛_갈래다() throws Exception {
        broadcasts.save(Broadcast.endedPlaceholder(방송, TestIds.STREAMER, 키, 1L, at(60_000)));
        seed.row(키, 1).pts(0).wall(0).pdt(0).insert();

        JsonNode root = mapper.readTree(json(issue(1_000, 3_500, "fileHead")));

        assertThat(root.has("axis")).isFalse();
        assertThat(root.has("audioCapture")).isFalse();
        assertThat(root.get("segments").get(0).has("role")).isFalse();
    }

    @Test
    void 모르는_표지는_400_axis_다() {
        방송(true);

        assertThatThrownBy(() -> issue(1_000, 3_500, "pdt"))
                .isInstanceOfSatisfying(InvalidSegmentRangeException.class, e -> assertThat(e.field()).isEqualTo("axis"));
    }

    @Test
    void 발급_신호에_갈래가_남는다() {
        래칫_시드();

        try (LogCaptor logs = new LogCaptor()) {
            issue(10_000, 22_000, null);
            issue(10_000, 22_000, "fileHead");

            assertThat(logs.messages()).anyMatch(m -> m.startsWith("clip.segment_audio_access.issued streamId=S-aud")
                    && m.contains("axis=legacy"));
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("clip.segment_audio_access.issued streamId=S-aud")
                    && m.contains("axis=fileHead"));
        }
    }

    /**
     * 장부 왕복 — 회차 고르기 1 · 창 안 회차 행 1 · 확보 후보 1 · 회차 완결 1(방송 중일 때만). 창 안 행은 조각 수와 무관하게 한 번이다.
     */
    @Test
    void 표지_갈래의_장부_왕복은_넷이고_끝난_방송은_셋이다() throws Exception {
        래칫_시드();
        AtomicInteger statements = new AtomicInteger();
        JdbcTemplate counted = new JdbcTemplate(counting(dataSource, statements));
        SegmentAudioAccessService service = service(new StreamSegmentReader(counted), new RoundRows(counted));

        service.issue(요청자, 방송, at(10_000).toEpochMilli(), at(22_000).toEpochMilli(), "fileHead");
        assertThat(statements).hasValue(4);

        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = ?",
                Timestamp.from(at(60_000)), 방송);
        statements.set(0);
        service.issue(요청자, 방송, at(10_000).toEpochMilli(), at(22_000).toEpochMilli(), "fileHead");
        assertThat(statements).hasValue(3);
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

    private void 방송(boolean withSession) {
        broadcasts.save(Broadcast.startedNow(방송, TestIds.STREAMER, 키, 1L, at(0), null));
        if (withSession) {
            seed.session(방송, 키, 방송, null);
        }
    }

    private SegmentAudioAccess issue(long from, long to, String axis) {
        return service(segments, rounds).issue(요청자, 방송, at(from).toEpochMilli(), at(to).toEpochMilli(), axis);
    }

    private SegmentAudioAccessService service(StreamSegmentReader reader, RoundRows roundRows) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("signer", new SegmentAudioSigner(S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build(), "segments-test", Duration.ofMinutes(5)));
        return new SegmentAudioAccessService(guard, broadcasts, reader, roundRows,
                factory.getBeanProvider(SegmentAudioSigner.class), Clock.fixed(at(100_000), ZoneOffset.UTC));
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

    private static List<Long> seqs(JsonNode root) {
        List<Long> out = new ArrayList<>();
        root.get("segments").forEach(s -> out.add(s.get("seq").asLong()));
        return out;
    }

    private static List<String> roles(JsonNode root) {
        List<String> out = new ArrayList<>();
        root.get("segments").forEach(s -> out.add(s.get("role").asString()));
        return out;
    }

    private static List<Boolean> follows(JsonNode root) {
        List<Boolean> out = new ArrayList<>();
        root.get("segments").forEach(s -> out.add(s.get("followsLedgerPrevious").asBoolean()));
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
