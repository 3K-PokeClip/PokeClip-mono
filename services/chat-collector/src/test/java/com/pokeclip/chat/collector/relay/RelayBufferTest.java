package com.pokeclip.chat.collector.relay;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중계 바구니. <b>번호는 여기서만 붙는다</b> — 규칙(null 방송 번호 · 닫힘 · 번호 · 상한)이
 * 호출부가 아니라 이 한 곳에 있어야 부르는 곳이 늘어도(후원·방송 정보) 같은 규칙을 탄다
 * (계획 검증 F7·F9).
 */
class RelayBufferTest {

    @Test
    void 방송마다_1부터_번호를_붙인다() {
        RelayBuffer buffer = new RelayBuffer(100);

        buffer.offer("s-a", chat("a1"));
        buffer.offer("s-b", chat("b1"));
        buffer.offer("s-a", chat("a2"));

        // 문항 2: 전역 번호 하나로 되돌리면 1,2,3 이다. 방송 번호와 짝으로 본다.
        assertThat(buffer.drain(10))
                .extracting(RelayEvent::streamId, RelayEvent::seq)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("s-a", 1L),
                        org.assertj.core.groups.Tuple.tuple("s-b", 1L),
                        org.assertj.core.groups.Tuple.tuple("s-a", 2L));
    }

    /**
     * 🔴 <b>버린 것도 번호를 받은 뒤에 버린다</b> — 그래야 화면이 빈틈으로 알아챈다
     * (relay-loss-coverage 기준 C). 번호를 매기기 전에 버리면 건너뜀이 안 생겨 영영 모른다.
     */
    @Test
    void 넘치면_오래된_것부터_버리고_센다() {
        RelayBuffer buffer = new RelayBuffer(2);

        buffer.offer("s", chat("1"));
        buffer.offer("s", chat("2"));
        buffer.offer("s", chat("3"));

        assertThat(buffer.droppedCount()).isEqualTo(1);
        assertThat(buffer.drain(10)).extracting(RelayEvent::seq).containsExactly(2L, 3L);
    }

    @Test
    void drain은_최대치만큼만_앞에서부터_꺼낸다() {
        RelayBuffer buffer = new RelayBuffer(100);
        for (int i = 0; i < 5; i++) {
            buffer.offer("s", chat("m" + i));
        }

        assertThat(buffer.drain(3)).extracting(RelayEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.drain(10)).extracting(RelayEvent::seq).containsExactly(4L, 5L);
    }

    /**
     * 종료의 마무리가 시작된 뒤에는 담지 않는다 — 담으면 비우기가 끝을 못 본다.
     * 게이트가 {@code StreamSession}이 아니라 여기 있는 이유는 F5다: 옛 경로 시험틀은
     * 중계가 {@code NONE}이라 거기서 재면 자동으로 참이다.
     */
    @Test
    void 닫힌_뒤에는_담지_않는다() {
        RelayBuffer buffer = new RelayBuffer(100);
        buffer.offer("s", chat("before"));
        // 양성 대조 — 닫기 전 것은 담긴다. 이게 없으면 「아예 안 담는 바구니」도 아래가 초록이다.
        assertThat(buffer.size()).isEqualTo(1);

        buffer.close();
        buffer.offer("s", chat("after"));

        assertThat(buffer.drain(10))
                .extracting(e -> ((RelayPayload.Chat) e.payload()).text())
                .containsExactly("before");
        assertThat(buffer.droppedCount())
                .as("닫힌 뒤 거절은 상한 초과가 아니다 — 섞어 세면 bufferDropped가 거짓이 된다")
                .isZero();
    }

    /**
     * 옛 경로(CHZZK_ENABLED)는 방송 번호가 없다. 번호를 붙일 열쇠가 없고 clip 문 경로에도
     * 넣을 수 없다. {@code ConcurrentHashMap}이었다면 null 열쇠로 NPE가 나 수신 콜백이 죽는다.
     */
    @Test
    void 번호_없는_방송은_버린다() {
        RelayBuffer buffer = new RelayBuffer(100);

        buffer.offer(null, chat("legacy"));
        buffer.offer("s", chat("real"));

        assertThat(buffer.drain(10)).extracting(RelayEvent::streamId).containsExactly("s");
        assertThat(buffer.droppedCount()).isZero();
    }

    @Test
    void 잊은_방송은_다음에_1부터_다시_센다() {
        RelayBuffer buffer = new RelayBuffer(100);
        buffer.offer("s", chat("1"));
        buffer.offer("s", chat("2"));
        buffer.offer("other", chat("o"));

        buffer.forget("s");
        buffer.offer("s", chat("3"));

        assertThat(buffer.drain(10))
                .extracting(RelayEvent::streamId, RelayEvent::seq)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("s", 1L),
                        org.assertj.core.groups.Tuple.tuple("s", 2L),
                        org.assertj.core.groups.Tuple.tuple("other", 1L),
                        org.assertj.core.groups.Tuple.tuple("s", 1L));
        assertThat(buffer.trackedStreamCount()).as("잊은 뒤 다시 센 s와 other 둘").isEqualTo(2);
    }

    /**
     * 🔴 <b>번호 붙이기와 담기가 한 자물쇠 안이어야 한다.</b> 같은 방송에 소켓 둘이 겹쳐
     * 넣을 때(갈아끼움 직후 등) 둘이 갈리면 <b>줄 순서와 번호 순서가 어긋나</b> 화면이
     * 뒤로 가는 번호를 「수집기 재시작」으로 읽고 기준을 버린다(F3 계약 문장 둘째).
     */
    @Test
    void 겹쳐_넣어도_줄_순서가_번호_순서다() throws Exception {
        int threads = 8;
        int perThread = 20_000;
        RelayBuffer buffer = new RelayBuffer(threads * perThread);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    buffer.offer("s", chat("x"));
                }
            });
            worker.start();
            workers.add(worker);
        }
        go.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        List<RelayEvent> drained = buffer.drain(threads * perThread);
        assertThat(drained).hasSize(threads * perThread);
        long previous = 0;
        for (RelayEvent event : drained) {
            assertThat(event.seq()).as("줄에서 번호가 뒤로 갔다").isEqualTo(previous + 1);
            previous = event.seq();
        }
    }

    @Test
    void 채팅의_문자열_칸에서_NUL을_지운다() {
        RelayPayload.Chat chat = new RelayPayload.Chat(Instant.EPOCH,
                "닉\0네임", "sender\0id", "ro\0le", "본\0문");

        assertThat(chat.nickname()).isEqualTo("닉네임");
        assertThat(chat.senderChannelId()).isEqualTo("senderid");
        assertThat(chat.role()).isEqualTo("role");
        assertThat(chat.text()).isEqualTo("본문");
        assertThat(chat.kind()).isEqualTo("chat");
        assertThat(chat.timeBasis()).isEqualTo("message");
    }

    /**
     * 후원은 표({@code PersistableDonation})와 <b>같은 값</b>으로 정규화한다 — 종류·문구·
     * 보낸 사람이 null이면 표는 빈 문자열로 접는다. 여기서 null로 두면 겹침 열쇠
     * ({@code kind, time, senderChannelId, text})가 창구와 SSE 사이에서 갈려 같은 후원이
     * 화면에 두 번 뜬다(F3).
     */
    @Test
    void 후원의_문자열_칸에서_NUL을_지우고_표와_같게_null을_접는다() {
        RelayPayload.Donation withNul = new RelayPayload.Donation(Instant.EPOCH,
                "닉\0", "don\0ator", 1000L, "CH\0AT", "문\0구");
        RelayPayload.Donation withNulls = new RelayPayload.Donation(Instant.EPOCH,
                null, null, null, null, null);

        assertThat(withNul.nickname()).isEqualTo("닉");
        assertThat(withNul.senderChannelId()).isEqualTo("donator");
        assertThat(withNul.donationType()).isEqualTo("CHAT");
        assertThat(withNul.text()).isEqualTo("문구");
        assertThat(withNul.kind()).isEqualTo("donation");
        assertThat(withNul.timeBasis()).isEqualTo("received");

        assertThat(withNulls.nickname()).as("닉네임은 표도 null을 허용한다").isNull();
        assertThat(withNulls.senderChannelId()).isEmpty();
        assertThat(withNulls.donationType()).isEmpty();
        assertThat(withNulls.text()).isEmpty();
        assertThat(withNulls.amount()).isNull();
    }

    private static RelayPayload.Chat chat(String text) {
        return new RelayPayload.Chat(Instant.EPOCH, "nick", "sender", null, text);
    }
}
