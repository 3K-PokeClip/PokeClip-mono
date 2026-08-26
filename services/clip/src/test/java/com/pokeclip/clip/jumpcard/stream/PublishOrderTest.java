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
 * <b>놓은 카드가 집힌 것으로 남았다.</b>
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
        broadcasts.save(Broadcast.startedNow("s-ord", TestIds.STREAMER, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    @Test
    void 대기_순서가_뒤집혀도_화면에는_최신만_남는다() throws Exception {
        long cardId = service.record("s-ord", auto("evt-ord", 3_000_000L)).card().id();
        long markerId = service.record("s-ord", auto("evt-marker", 5_000_000L)).card().id();

        int staleDelivered = 0;
        int staleWon = 0;        // 마지막에 적용된 것이 낡은 쪽이었던 회차
        int missing = 0;

        try (SseReader reader = new SseReader("http://localhost:" + port + "/api/clip/broadcasts/s-ord/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("1601")))) {
            // 연결이 명부에 올랐는지는 주석으로 본다 — 통로는 지난 카드를 안 보낸다(POK-174).
            assertThat(reader.await(1, Duration.ofSeconds(3))).as("주석조차 안 왔다").isTrue();

            for (int i = 0; i < TRIALS; i++) {
                long stale = 1_000L + i * 2L;          // 집힘 — 낡은 쪽
                long fresh = stale + 1;                // 놓임 — 최신
                JumpCardSnapshot claimed = snapshot(cardId, stale, "1602");
                JumpCardSnapshot released = snapshot(cardId, fresh, null);

                Thread first = new Thread(() -> registry.publish(claimed), "publish-N");
                Thread second = new Thread(() -> registry.publish(released), "publish-N+1");
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
                long marker = 900_000L + i;
                registry.publish(snapshot(markerId, marker, null));
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
                if (ofCard.contains(stale)) {
                    staleDelivered++;
                }
            }

            assertThat(missing).as("%d/%d 회차에서 카드 이벤트가 하나도 안 왔다", missing, TRIALS).isZero();
            assertThat(staleWon)
                    .as("%d/%d 회차에서 마지막에 적용된 것이 낡은 쪽이었다 — 화면이 뒤로 간다",
                            staleWon, TRIALS)
                    .isZero();

            assertThat(마지막_카드_상태(reader, cardId).get("claimedBy").isNull())
                    .as("놓았는데 집힌 채로 남으면 아무도 그 카드를 못 집는다").isTrue();
        }

        // 낡은 것은 애초에 안 나가야 한다. 「순서만 맞으면 된다」로 두면 화면이 잠깐 뒤로 갔다 온다.
        assertThat(staleDelivered).as("낡은 순번이 %d/%d 회차에서 전송됐다", staleDelivered, TRIALS)
                .isZero();
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

    private JumpCardSnapshot snapshot(long id, long eventSeq, String claimedBy) {
        Instant now = Instant.now();
        return new JumpCardSnapshot(id, "s-ord", JumpCardSource.AUTO, 3_023_000L,
                new JumpCardSnapshot.Window(3_000_000L, 3_042_000L), 97, null,
                claimedBy, claimedBy == null ? null : now,
                claimedBy == null ? null : now.plusSeconds(120), false, null, eventSeq, now);
    }

    private HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }
}
