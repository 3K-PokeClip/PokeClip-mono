package com.pokeclip.clip.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastOrderingTest extends IntegrationTestSupport {

    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;
    private final JdbcTemplate jdbc;

    BroadcastOrderingTest(BroadcastEventProcessor processor, BroadcastRepository broadcasts,
                          BroadcastEventRepository events, JdbcTemplate jdbc) {
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.events = events;
        this.jdbc = jdbc;
    }

    /** 이 클래스의 테스트 넷이 e1·e2를 재사용한다. 정리가 없으면 서로를 막는다. */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        // jump_cards가 broadcasts의 자식이다. 카드를 남긴 클래스가 앞에 돌면
        // 아래 삭제가 FK로 죽는다(POK-118). 실행 순서에 기대지 않는다.
        jdbc.update("DELETE FROM jump_cards");
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
    }

    @Test
    void 정상_순서면_live_였다가_ended가_된다() {
        processor.process(Envelopes.started("e1", "s1", 1L));
        assertThat(broadcasts.findByStreamId("s1")).get()
                .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BroadcastStatus.LIVE));

        processor.process(Envelopes.ended("e2", "s1", 2L));
        assertThat(broadcasts.findByStreamId("s1")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getEndedAt()).isNotNull();
                    assertThat(b.getStartedAt()).isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(2L);
                });
    }

    @Test
    void 종료가_먼저_와도_죽지_않고_빈_줄이_생기며_경고가_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            processor.process(Envelopes.ended("e1", "s2", 5L));

            // 실물 API는 messages()·levelOf(String)이다 — forClass()·warnMessages()는 없다.
            // levelOf는 String이 아니라 logback Level을 준다.
            assertThat(captor.messages()).anyMatch(m -> m.contains("ended_before_started"));
            assertThat(captor.levelOf("broadcast.ended_before_started")).isEqualTo(Level.WARN);
        }

        assertThat(broadcasts.findByStreamId("s2")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt()).as("시작 시각을 모르는 placeholder다").isNull();
                    assertThat(b.getEndedAt()).isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(5L);
                });
    }

    /**
     * 종료가 먼저 와 생긴 자리표시에 낮은 번호의 시작이 늦게 오면(실패 편지함을 되돌린 경우) <b>시작 시각만</b> 채운다(POK-327).
     * 상태·순서 번호·보관 기한은 그대로다. 전에는 이 편지를 통째로 버려 시작 시각이 영영 비었고, 화면은 끝 12시간 창으로만 읽어
     * 같은 키로 한 앞 회차 녹화가 섞일 수 있었다.
     */
    @Test
    void 종료_뒤에_낮은_번호의_시작이_오면_시작_시각만_채우고_되돌아가지_않는다() {
        Instant 시작 = Instant.parse("2026-08-18T00:00:00Z");
        Instant 끝 = Instant.parse("2026-08-18T01:00:00Z");
        processor.process(Envelopes.ended("e1", "s3", null, 5L, 끝));
        Instant 기한 = broadcasts.findByStreamId("s3").orElseThrow().getVodExpiresAt();

        try (LogCaptor captor = new LogCaptor()) {
            assertThat(processor.process(Envelopes.started("e2", "s3", null, 3L, 시작)))
                    .as("빈 칸을 채웠으니 반영이다")
                    .isEqualTo(ProcessResult.PROCESSED);
            assertThat(captor.messages()).anyMatch(m -> m.contains("broadcast.placeholder.started_filled"));
            assertThat(captor.messages()).noneMatch(m -> m.contains("stale_ignored"));
        }

        assertThat(broadcasts.findByStreamId("s3")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).as("끝난 방송이 다시 LIVE가 되면 안 된다").isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt()).isEqualTo(시작);
                    assertThat(b.getEndedAt()).isEqualTo(끝);
                    assertThat(b.getVodExpiresAt()).as("보관 기한은 종료 시각에서만 나온다").isEqualTo(기한);
                    assertThat(b.getLastSequence()).as("낮은 번호로 덮이지 않는다").isEqualTo(5L);
                });
    }

    /**
     * 채우는 것은 <b>빈 칸</b>뿐이다. 순서대로 받은 시작 시각을 낡은 편지가 바꾸면 기준점·녹화 창이 그 편지 쪽으로 옮겨 간다.
     * 놓친 방송 치우개가 닫은 줄(시작 시각이 있다)에 뒤늦게 온 낮은 번호의 시작도 이 갈래다(POK-244).
     */
    @Test
    void 시작_시각이_이미_있으면_낮은_번호의_시작이_덮지_않는다() {
        Instant 시작 = Instant.parse("2026-08-18T00:00:00Z");
        processor.process(Envelopes.started("e1", "s8", null, 1L, 시작));
        processor.process(Envelopes.ended("e2", "s8", null, 2L, 시작.plusSeconds(3_600)));

        assertThat(processor.process(Envelopes.started("e3", "s8", null, 1L, 시작.plusSeconds(600))))
                .isEqualTo(ProcessResult.IGNORED_STALE);

        assertThat(broadcasts.findByStreamId("s8")).get()
                .satisfies(b -> assertThat(b.getStartedAt()).isEqualTo(시작));
    }

    /**
     * 끝보다 늦은 시작으로는 안 채운다. 받으면 화면이 [시작, 끝] 창을 거꾸로 잡아 녹화도 그래프도 비고, 시작을 모를 때(끝 12시간
     * 창)보다 나빠진다. 편지 시각이 보낸 때인 로컬 스크립트로 종료를 먼저, 시작을 나중에 보내면 이 모양이 된다.
     */
    @Test
    void 끝보다_늦은_시작으로는_시작_시각을_채우지_않는다() {
        Instant 끝 = Instant.parse("2026-08-18T01:00:00Z");
        processor.process(Envelopes.ended("e1", "s9", null, 5L, 끝));

        assertThat(processor.process(Envelopes.started("e2", "s9", null, 3L, 끝.plusSeconds(1))))
                .isEqualTo(ProcessResult.IGNORED_STALE);

        assertThat(broadcasts.findByStreamId("s9")).get()
                .satisfies(b -> assertThat(b.getStartedAt()).isNull());
    }

    /** 채우는 재료는 시작 편지의 시각뿐이다. 낡은 종료 편지의 시각은 그 방송이 시작한 때가 아니다. */
    @Test
    void 낮은_번호의_종료로는_시작_시각을_채우지_않는다() {
        Instant 끝 = Instant.parse("2026-08-18T01:00:00Z");
        processor.process(Envelopes.ended("e1", "s10", null, 5L, 끝));

        assertThat(processor.process(Envelopes.ended("e2", "s10", null, 3L, 끝.minusSeconds(1_800))))
                .isEqualTo(ProcessResult.IGNORED_STALE);

        assertThat(broadcasts.findByStreamId("s10")).get()
                .satisfies(b -> assertThat(b.getStartedAt()).isNull());
    }

    /**
     * 시작→종료로 받은 방송과 종료→시작으로 받은 방송의 마지막 줄이 같다. 같은 방송 안에서는 큐가 순서를 지키지만 실패 편지함을
     * 거치면 뒤집힌다. 소비 결과가 도착 순서에 기대지 않아야 재전송·되돌리기를 마음 놓고 쓴다.
     */
    @Test
    void 시작과_종료가_어느_순서로_와도_마지막_줄이_같다() {
        Instant 시작 = Instant.parse("2026-08-18T00:00:00Z");
        Instant 끝 = 시작.plusSeconds(3_600);
        processor.process(Envelopes.started("a1", "s-in-order", "key-a", 1L, 시작));
        processor.process(Envelopes.ended("a2", "s-in-order", "key-a", 2L, 끝));
        processor.process(Envelopes.ended("b2", "s-reversed", "key-a", 2L, 끝));
        processor.process(Envelopes.started("b1", "s-reversed", "key-a", 1L, 시작));

        Function<Broadcast, List<Object>> 마지막_줄 = b -> Arrays.asList(b.getStatus(), b.getStartedAt(),
                b.getEndedAt(), b.getVodExpiresAt(), b.getLastSequence(), b.ingestKey());
        assertThat(마지막_줄.apply(broadcasts.findByStreamId("s-reversed").orElseThrow()))
                .isEqualTo(마지막_줄.apply(broadcasts.findByStreamId("s-in-order").orElseThrow()));
    }

    /**
     * {@code applyStarted}의 ENDED 보호 분기를 실행하는 유일한 시험이다(감사 1차 지적 1).
     *
     * <p>낮은 번호로는 이 분기에 못 닿는다 — 앞의 {@code sequence <= lastSequence}에서
     * 걸러진다. <b>번호가 더 높은</b> 시작이 와야 반영 경로로 들어가고, 그때 시작 시각은
     * 채우되 상태는 ENDED로 남아야 한다. 끝난 방송이 다시 LIVE가 되면 그 뒤 파이프라인이
     * 살아 있는 방송으로 오해한다.
     */
    @Test
    void 종료된_방송에_더_높은_번호의_시작이_와도_상태가_되돌아가지_않는다() {
        processor.process(Envelopes.ended("e1", "s5", 5L));

        assertThat(processor.process(Envelopes.started("e2", "s5", 7L)))
                .as("번호가 더 높으니 무시가 아니라 반영이다")
                .isEqualTo(ProcessResult.PROCESSED);

        assertThat(broadcasts.findByStreamId("s5")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus())
                            .as("끝난 방송이 다시 LIVE가 되면 안 된다")
                            .isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt())
                            .as("뒤늦게 받은 시작 시각은 채운다")
                            .isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(7L);
                });
    }

    /**
     * 트랙 정보는 ADR-016이 "broadcast.started payload의 스냅샷"으로 정한 값이고
     * <b>한 번 지워지면 복구 경로가 없다.</b> 뒤에 온 시작에 그 값이 없다고 덮어쓰면,
     * sequence가 올라갔으니 낡은 편지 가드에도 안 걸려 조용히 사라진다.
     */
    @Test
    void 뒤에_온_시작에_트랙_정보가_없어도_기존_값을_지우지_않는다() {
        processor.process(Envelopes.startedWithManifest("e1", "s6", 1L));
        processor.process(Envelopes.started("e2", "s6", 2L));

        assertThat(broadcasts.findByStreamId("s6")).get()
                .satisfies(b -> {
                    assertThat(b.getTrackManifest())
                            .as("트랙 정보가 null로 덮였다 — 복구 경로가 없는 값이다")
                            .contains("manifestVersion");
                    assertThat(b.getLastSequence()).as("나머지는 정상 반영된다").isEqualTo(2L);
                });
    }

    /**
     * 위 가드가 지나치게 넓으면 이쪽이 깨진다. 종료 선도착으로 생긴 placeholder는
     * 트랙 정보가 없는 채로 시작하고, 뒤늦게 도착한 시작 편지가 그것을 채워야 한다.
     */
    @Test
    void placeholder에_뒤늦게_온_시작이_트랙_정보를_채운다() {
        processor.process(Envelopes.ended("e1", "s7", 1L));
        processor.process(Envelopes.startedWithManifest("e2", "s7", 2L));

        assertThat(broadcasts.findByStreamId("s7")).get()
                .satisfies(b -> assertThat(b.getTrackManifest())
                        .as("빈 placeholder에는 채워져야 한다")
                        .contains("manifestVersion"));
    }

    @Test
    void 낡은_편지여도_받은_기록에는_남는다() {
        processor.process(Envelopes.ended("e1", "s4", 5L));
        processor.process(Envelopes.started("e2", "s4", 3L));

        // 같은 낡은 편지가 또 와도 판정을 다시 하지 않는다.
        assertThat(processor.process(Envelopes.started("e2", "s4", 3L)))
                .isEqualTo(ProcessResult.DUPLICATE);
    }
}
