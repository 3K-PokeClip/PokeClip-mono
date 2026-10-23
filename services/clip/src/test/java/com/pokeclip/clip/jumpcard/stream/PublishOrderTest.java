package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.JumpCardSource;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>낡은 스냅샷이 새 상태를 덮으면 안 된다.</b>
 *
 * <p>{@code publish}는 {@code synchronized}이고 <b>자바 모니터는 공정하지 않다</b> —
 * 먼저 대기한 스레드가 먼저 얻는다는 보장이 없다. 두 수정이 가까이 커밋되면 각자의
 * {@code afterCommit}이 서로 다른 요청 스레드에서 돌아 {@code eventSeq} N+1이 N보다 먼저
 * 제출될 수 있고, 스트라이프는 그 순서를 그대로 지키므로 <b>화면에 낡은 값이 남는다.</b>
 *
 * <p>2026-08-23 재현(PR #112 봇 지적 ③): 대기 순서를 N → N+1로 <b>강제</b>했더니
 * <b>100회 시도 100회</b> 뒤집혔다. 도착 {@code id}가
 * {@code [1001, 1000, 1003, 1002, …]}였고, 마지막에 적용되는 것이 낡은 쪽이라
 * <b>놓은 카드가 집힌 것으로 남았다.</b> 집기는 POK-393에서 없앴고, 지금은 같은 성질을 점수 칸으로 잰다
 * (카드를 바꾸는 쓰기가 무엇이든 순번 비교는 같다).
 *
 * <p><b>처방은 순서를 바로잡는 것이 아니라 낡은 것을 안 보내는 것이다.</b> 공정 락은
 * 「자물쇠에 줄 선 순서」만 보존하는데, 커밋 순서와 {@code afterCommit} 도달 순서는 애초에
 * 별개라 그것만으로는 부족하다. 카드별 마지막 발행 순번을 비교해 <b>더 낮거나 같은 것을 버린다.</b>
 *
 * <p>🔴 <b>순번 표는 카드별이어야 한다.</b> {@code jump_card_event_seq}는 방송별이 아니라
 * <b>전역 시퀀스</b>다(V202). 연결별로 워터마크 하나만 두면 <b>다른 카드</b>의 변경이
 * 워터마크를 올려서, 늦게 도착한 카드의 갱신을 「이미 더 큰 번호가 갔다」는 이유로 영영 버린다.
 *
 * <p>🔴 <b>이 시험은 {@code synchronized (registry)}로 {@code publish}와 같은 모니터를
 * 밖에서 잡는다.</b> 자물쇠를 {@code ReentrantLock}으로 바꾸면 여기가 <b>다른 것을 잡게 되어
 * 대기 순서를 못 만들고 조용히 헛통과한다</b> — 반드시 같이 고친다.
 * ({@code TokenExpiryBoundaryTest}도 같은 의존이 있다.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublishOrderTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int 낡은_점수 = 10;

    private static final int 최신_점수 = 20;

    /** 재현과 같은 횟수. 한 번만 하면 「우연히 안 뒤집힘」과 구별되지 않는다. */
    private static final int TRIALS = 100;

    private final int port;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    PublishOrderTest(@LocalServerPort int port, JumpCardService service, BroadcastRepository broadcasts,
                     CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        broadcasts.save(Broadcast.startedNow("s-ord", TestIds.STREAMER, null, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    @Test
    void 대기_순서가_뒤집혀도_화면에는_최신만_남는다() throws Exception {
        long cardId = service.record("s-ord", auto("evt-ord", 3_000_000L)).card().id();
        JumpCardSnapshot markerCard = service.record("s-ord", auto("evt-marker", 5_000_000L)).card();
        long markerId = markerCard.id();
        long base = markerCard.eventSeq();   // 전역 시퀀스 — 고정 숫자면 앞선 시험이 올린 순번 아래로 깔린다

        int wentBackward = 0;    // 최신 뒤에 낡은 것이 도착한 회차 — 화면이 뒤로 간다
        int staleWon = 0;        // 마지막에 적용된 것이 낡은 쪽이었던 회차
        int missing = 0;
        try (SseReader reader = new SseReader("http://localhost:" + port + "/api/clip/broadcasts/s-ord/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("1601")))) {
            // 연결이 명부에 올랐는지는 주석으로 본다 — 통로는 지난 카드를 안 보낸다(POK-174).
            assertThat(reader.await(1, Duration.ofSeconds(3))).as("주석조차 안 왔다").isTrue();

            for (int i = 0; i < TRIALS; i++) {
                long stale = base + 1_000L + i * 2L;   // 낡은 쪽
                long fresh = stale + 1;                // 최신
                JumpCardSnapshot older = snapshot(cardId, stale, 낡은_점수);
                JumpCardSnapshot newer = snapshot(cardId, fresh, 최신_점수);

                Thread first = new Thread(() -> registry.publish(older), "publish-N");
                Thread second = new Thread(() -> registry.publish(newer), "publish-N+1");
                // 대기 순서를 강제한다 — 먼저 N이 자물쇠에 줄을 서고, 그 뒤에 N+1이 선다.
                synchronized (registry) {
                    first.start();
                    awaitBlocked(first);
                    second.start();
                    awaitBlocked(second);
                }
                first.join();
                second.join();

                // 마커는 <b>다른 카드</b>라 순번 비교에 안 걸린다. 같은 연결 = 같은 스트라이프라
                // FIFO가 보장되므로, 마커가 도착했으면 위 둘의 처리는 이미 끝났다.
                // sleep으로 기다리면 「덜 와서 통과」하는 시험이 된다.
                long marker = base + 900_000L + i;
                registry.publish(snapshot(markerId, marker, 최신_점수));
                assertThat(awaitId(reader, marker)).as("마커가 안 왔다 — 회차 %d", i).isTrue();

                // 🔴 회차마다 단언하지 않고 <b>세기만 한다.</b> 첫 회차에서 멈추면
                // 「몇 번 중 몇 번」을 알 수 없고, 그 숫자가 이 결함의 크기다
                // (재현에서 100/100이 나온 것이 판정의 근거였다).
                List<Long> ofCard = 도착한_순번(reader, cardId);
                if (ofCard.isEmpty()) {
                    missing++;
                } else if (ofCard.get(ofCard.size() - 1) != fresh) {
                    staleWon++;
                }
                // 🔴 낡은 N이 <b>먼저</b> 도착한 것은 결함이 아니다. 모니터가 공정하지 않다는 것은
                // 「뒤집힌다」가 아니라 「순서 보장이 없다」는 뜻이라, 가끔 N이 먼저 자물쇠를 얻는다.
                // 그때 N은 보내는 순간 가장 새 값이었고 화면은 N → N+1로 앞으로만 간다.
                // 이 회차를 결함으로 세던 옛 단언이 실행 20번에 1번꼴, 그 실행의 100회차 중 1회차에서 깨졌다(2026-10-23).
                List<Long> thisRound = ofCard.stream().filter(seq -> seq == stale || seq == fresh).toList();
                int staleAt = thisRound.indexOf(stale);
                int freshAt = thisRound.indexOf(fresh);
                if (staleAt >= 0 && freshAt >= 0 && staleAt > freshAt) {
                    wentBackward++;
                }
            }

            assertThat(missing).as("%d/%d 회차에서 카드 이벤트가 하나도 안 왔다", missing, TRIALS).isZero();
            assertThat(staleWon)
                    .as("%d/%d 회차에서 마지막에 적용된 것이 낡은 쪽이었다 — 화면이 뒤로 간다",
                            staleWon, TRIALS)
                    .isZero();

            assertThat(마지막_카드_상태(reader, cardId).get("score").asInt())
                    .as("화면에 남은 카드가 낡은 값이다").isEqualTo(최신_점수);
        }

        // 낡은 것은 최신 뒤에 나가면 안 된다. 「마지막 값만 맞으면 된다」로 두면 화면이 잠깐 뒤로 갔다 온다.
        assertThat(wentBackward).as("최신 뒤에 낡은 순번이 %d/%d 회차에서 전송됐다", wentBackward, TRIALS)
                .isZero();
        // 뒤집힌 회차 수는 단언하지 않는다. 모니터는 뒤집힘도 보장하지 않아서 0이어도 결함이 아니다.
        // 그래서 이 시험은 걸러 내기를 확률로만 지나고, 걸러 내기 자체는 아래 시험이 매번 잰다.
    }

    @Test
    void 최신_뒤에_온_낡은_순번은_보내지_않는다() throws Exception {
        long cardId = service.record("s-ord", auto("evt-ord", 3_000_000L)).card().id();
        JumpCardSnapshot markerCard = service.record("s-ord", auto("evt-marker", 5_000_000L)).card();
        long markerId = markerCard.id();
        // 🔴 순번은 전역 시퀀스다. record가 afterCommit에서 실제 순번으로 이미 발행했으므로
        // 고정 숫자를 쓰면 앞선 시험들이 시퀀스를 그 위로 올린 날 전부 낡은 것으로 버려진다.
        long newer = markerCard.eventSeq() + 1_001L;
        long older = newer - 1;
        long marker = newer + 900_000L;

        try (SseReader reader = new SseReader("http://localhost:" + port + "/api/clip/broadcasts/s-ord/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("1601")))) {
            assertThat(reader.await(1, Duration.ofSeconds(3))).as("주석조차 안 왔다").isTrue();

            // 모니터 순서에 기대지 않고 「최신 → 낡은 것」 도착을 직접 만든다.
            // 경합 시험은 이 갈래를 확률로만 지나서, 걸러 내기를 지워도 운이 나쁘면 초록이 된다.
            registry.publish(snapshot(cardId, newer, 최신_점수));
            registry.publish(snapshot(cardId, older, 낡은_점수));
            registry.publish(snapshot(cardId, newer, 낡은_점수));    // 같은 순번도 버린다
            registry.publish(snapshot(markerId, marker, 최신_점수));
            assertThat(awaitId(reader, marker)).as("마커가 안 왔다").isTrue();

            assertThat(도착한_순번(reader, cardId)).containsExactly(newer);
            assertThat(마지막_카드_상태(reader, cardId).get("score").asInt()).isEqualTo(최신_점수);
        }
    }

    private List<Long> 도착한_순번(SseReader reader, long cardId) {
        return reader.named().stream()
                .filter(e -> "card".equals(e.name()))
                .filter(e -> MAPPER.readTree(e.data()).get("id").asLong() == cardId)
                .map(e -> Long.parseLong(e.id()))
                .toList();
    }

    private tools.jackson.databind.JsonNode 마지막_카드_상태(SseReader reader, long cardId) {
        List<SseReader.Event> events = reader.named().stream()
                .filter(e -> "card".equals(e.name()))
                .filter(e -> MAPPER.readTree(e.data()).get("id").asLong() == cardId)
                .toList();
        return MAPPER.readTree(events.get(events.size() - 1).data());
    }

    private boolean awaitId(SseReader reader, long eventSeq) {
        String wanted = Long.toString(eventSeq);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (reader.named().stream().anyMatch(e -> wanted.equals(e.id()))) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void awaitBlocked(Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (t.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(1);
        }
        throw new IllegalStateException("자물쇠 대기에 안 들어갔다: " + t.getState());
    }

    private JumpCardSnapshot snapshot(long id, long eventSeq, int score) {
        Instant now = Instant.now();
        return new JumpCardSnapshot(id, "s-ord", JumpCardSource.AUTO, 3_023_000L,
                new JumpCardSnapshot.Window(3_000_000L, 3_042_000L), score, null, eventSeq, now, null);
    }

    private HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }
}
