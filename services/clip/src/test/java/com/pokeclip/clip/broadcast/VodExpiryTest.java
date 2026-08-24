package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방송 보관 기한(ADR-004 — VOD 60일)이 <b>언제 채워지는가</b>를 잰다.
 *
 * <p>뒤 태스크의 만료 판정이 이 칸 하나에 얹히므로, 채우는 경로 둘
 * (종료 편지 반영 · 종료 선도착 placeholder)과 <b>안 채우는 경로</b>(방송 중),
 * 그리고 이미 끝난 방송을 소급하는 V203 백필문까지 넷을 닫는다.
 */
class VodExpiryTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant ENDED_AT = Instant.parse("2026-08-18T01:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-18T00:00:00Z");
    private static final Duration 육십일 = Duration.ofDays(60);

    private static final String V203 = "db/migration/V203__add_vod_expires_at.sql";

    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    VodExpiryTest(BroadcastEventProcessor processor, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 종료_편지를_반영하면_보관기한이_종료시각_더하기_60일이다() {
        processor.process(started("s-vod-1", 1));
        processor.process(ended("s-vod-1", 2, ENDED_AT));

        Broadcast b = broadcasts.findByStreamId("s-vod-1").orElseThrow();
        assertThat(b.getVodExpiresAt()).isEqualTo(ENDED_AT.plus(육십일));
    }

    /**
     * 종료가 먼저 온 placeholder는 private 생성자를 지나가지 않고 팩토리에서
     * 만들어진다 — {@code applyEnded}만 고치면 이 경로가 비어 있는 채로 남는다.
     */
    @Test
    void 종료가_먼저_온_placeholder에도_보관기한이_찬다() {
        processor.process(ended("s-vod-2", 1, ENDED_AT));

        Broadcast b = broadcasts.findByStreamId("s-vod-2").orElseThrow();
        assertThat(b.getVodExpiresAt()).isEqualTo(ENDED_AT.plus(육십일));
    }

    /** NULL이 「아직 안 끝나 기한이 없다」는 뜻이다. 뒤 태스크의 만료 판정이 이 약속에 얹힌다. */
    @Test
    void 방송중이면_보관기한이_NULL이다() {
        processor.process(started("s-vod-3", 1));

        Broadcast b = broadcasts.findByStreamId("s-vod-3").orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BroadcastStatus.LIVE);
        assertThat(b.getVodExpiresAt()).isNull();
    }

    /**
     * 계획이 「{@code applyStarted}는 안 건드린다」고 못 박은 자리를 재는 유일한 갈래다.
     *
     * <p><b>「방송중이면 NULL」로는 이것이 안 잡힌다</b> — 그 갈래는 행이 없을 때
     * 도는 {@code startedNow}만 지나가고 {@code applyStarted}는 한 번도 안 탄다.
     * 실제로 {@code applyStarted}에 기한을 채우는 결함을 주입했더니 넷이 모두
     * 초록이었다(주입 6). 이미 끝난 방송의 기한을 뒤늦은 시작 편지가 60일 미루면
     * 만료된 VOD가 되살아난다.
     */
    @Test
    void 시작이_늦게_와도_이미_찬_보관기한을_되돌리지_않는다() {
        processor.process(ended("s-vod-4", 1, ENDED_AT));
        processor.process(started("s-vod-4", 2));

        Broadcast b = broadcasts.findByStreamId("s-vod-4").orElseThrow();
        // 이 단언이 먼저다. 뒤늦은 시작이 낡은 편지로 걸려 반영조차 안 됐다면
        // 아래 기한 단언은 자동으로 참이 되고 아무것도 안 재게 된다.
        assertThat(b.getStartedAt()).as("뒤늦은 시작이 반영되지 않았다 — 아래 단언이 헛돈다")
                .isEqualTo(STARTED_AT);
        assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
        assertThat(b.getVodExpiresAt()).isEqualTo(ENDED_AT.plus(육십일));
    }

    /**
     * 백필문을 <b>파일에서 읽어</b> 돌린다 — 여기에 SQL을 베껴 두면 파일과 시험이
     * 따로 놀아, 마이그레이션을 고쳐도 시험은 옛 문장을 계속 통과시킨다.
     */
    @Test
    void V203_백필문은_끝난_방송만_채우고_라이브는_안_건드린다() {
        Instant 옛날에_끝난_시각 = Instant.parse("2026-01-01T00:00:00Z");
        기한없는_방송을_넣는다("s-backfill-ended", BroadcastStatus.ENDED, STARTED_AT, 옛날에_끝난_시각);
        기한없는_방송을_넣는다("s-backfill-live", BroadcastStatus.LIVE, STARTED_AT, null);

        List<String> 백필문 = V203의_UPDATE문();
        // 🔴 이 단언이 먼저다. 추출이 0건이면 아래 루프가 조용히 지나가 아무것도 안
        //    재고 초록이 된다(계획 검증 m1 실측). 「돌렸다」가 아니라 「돌릴 것을
        //    찾았다」를 못 박아야 그 구멍이 닫힌다.
        assertThat(백필문).as("V203에서 UPDATE 문을 못 찾았다 — 이 시험은 아무것도 안 재고 있다").hasSize(1);
        백필문.forEach(jdbc::update);

        assertThat(보관기한("s-backfill-ended")).isEqualTo(옛날에_끝난_시각.plus(육십일));
        assertThat(보관기한("s-backfill-live")).as("라이브는 소급 대상이 아니다").isNull();
    }

    /**
     * 마이그레이션 파일에서 UPDATE 문만 뽑는다.
     *
     * <p>주석을 <b>먼저 걷어낸다.</b> 안 걷으면 {@code split(";")} 조각의 앞머리가
     * {@code -- 이미 끝난 방송에…} 주석이라 {@code startsWith("UPDATE")}가 거짓이 되고,
     * 추출이 0건이 된다(계획 검증 m1).
     *
     * <p>줄에서 {@code --}를 통째로 자르는 것은 <b>이 파일에 한해</b> 안전하다 —
     * 문자열 리터럴 안에 {@code --}가 없는 것을 눈으로 확인했다. 리터럴에 그것이
     * 들어가는 날 이 도우미부터 고쳐야 한다.
     */
    private static List<String> V203의_UPDATE문() {
        String 주석_걷은_SQL = 파일을_읽는다().lines()
                .map(line -> {
                    int 주석 = line.indexOf("--");
                    return 주석 < 0 ? line : line.substring(0, 주석);
                })
                .collect(Collectors.joining("\n"));

        return Arrays.stream(주석_걷은_SQL.split(";"))
                .map(String::trim)
                .filter(문장 -> 문장.toUpperCase(Locale.ROOT).startsWith("UPDATE"))
                .toList();
    }

    private static String 파일을_읽는다() {
        try {
            return new ClassPathResource(V203).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(V203 + "을 읽지 못했다", e);
        }
    }

    /** 마이그레이션 <b>이전</b>의 줄을 재현한다 — 기한 칸이 비어 있는 상태. */
    private void 기한없는_방송을_넣는다(String streamId, BroadcastStatus status,
                                Instant startedAt, Instant endedAt) {
        jdbc.update("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, ended_at, last_sequence, vod_expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL)""",
                streamId, "streamer-1", status.dbValue(),
                startedAt == null ? null : OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
                endedAt == null ? null : OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC),
                1L);
    }

    private Instant 보관기한(String streamId) {
        return jdbc.queryForObject(
                "SELECT vod_expires_at FROM broadcasts WHERE stream_id = ?",
                (rs, rowNum) -> {
                    OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                },
                streamId);
    }

    private static LifecycleEnvelope started(String streamId, long sequence) {
        return new LifecycleEnvelope(1, streamId + "-" + sequence, "broadcast.started",
                STARTED_AT, streamId, "streamer-1", sequence, "trace-vod", MAPPER.createObjectNode());
    }

    private static LifecycleEnvelope ended(String streamId, long sequence, Instant at) {
        return new LifecycleEnvelope(1, streamId + "-" + sequence, "broadcast.ended",
                at, streamId, "streamer-1", sequence, "trace-vod", MAPPER.createObjectNode());
    }
}
