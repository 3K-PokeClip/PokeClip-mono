package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.jumpcard.JumpCardErrors.StreamLimitExceededException;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.JumpCardSource;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연결 보관소의 규칙을 잰다. 스프링을 안 띄우고 실물 {@code CardStreamExecutor}(스트라이프 1)를 쓴다 —
 * 가짜 executor를 쓰면 "전송이 정말 다른 스레드에서 도는가"를 못 재고 순서 시험이 의미를 잃는다.
 *
 * <p><b>{@code complete()}·타임아웃 콜백은 여기서 못 잰다.</b> 서블릿 밖 {@code SseEmitter}는
 * {@code handler == null}이라 콜백을 안 부른다(plan-critic 소스+실측). 그래서
 * 「연결이 닫히면 자리가 돌아온다」는 태스크 9의 실 HTTP 시험이 잰다.
 * 여기서는 <b>등록 경로</b>(onCompletion에 remove를 걸었는가)만 손으로 콜백을 불러 확인한다.
 */
class CardStreamRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CardStreamExecutor executor;
    private CardStreamRegistry registry;

    @AfterEach
    void 정리() {
        if (registry != null) {
            registry.stop();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    private CardStreamRegistry registry(StreamProperties properties) {
        // 🔴 스트라이프를 운영과 같은 4로 둔다. 1개면 Conn.stripe()가 무엇을 돌려주든 전부 0번
        // 줄로 가서, `같은_연결의_이벤트는_순서대로_온다`가 <b>고정이 깨진 것을 못 본다</b>
        // (감사가 stripe()를 난수로 바꿔도 3/3 초록임을 재현했다).
        executor = new CardStreamExecutor(4, 1000);
        registry = new CardStreamRegistry(executor, properties, mapper, d -> new RecordingEmitter());
        return registry;
    }

    private static StreamProperties props(int maxPerUser, int maxPerStream, int maxTotal) {
        // heartbeat를 길게 둔다 — 시험 중 스케줄이 끼어들면 받은 이벤트에 ping이 섞인다.
        return new StreamProperties(Duration.ofHours(1), Duration.ofHours(1), 4, 1000,
                maxPerUser, maxPerStream, maxTotal);
    }

    @Test
    void 사용자당_상한을_넘기면_거절한다() {
        CardStreamRegistry registry = registry(props(2, 50, 500));
        registry.open("s-1", "u-1", Duration.ofMinutes(1));
        registry.open("s-1", "u-1", Duration.ofMinutes(1));

        assertThatThrownBy(() -> registry.open("s-2", "u-1", Duration.ofMinutes(1)))
                .as("방송이 달라도 사람 기준으로 센다")
                .isInstanceOf(StreamLimitExceededException.class)
                .satisfies(e -> assertThat(((StreamLimitExceededException) e).scope()).isEqualTo("user"));
    }

    @Test
    void 방송당_상한과_전체_상한도_센다() {
        CardStreamRegistry perStream = registry(props(500, 2, 500));
        perStream.open("s-1", "u-1", Duration.ofMinutes(1));
        perStream.open("s-1", "u-2", Duration.ofMinutes(1));
        assertThatThrownBy(() -> perStream.open("s-1", "u-3", Duration.ofMinutes(1)))
                .isInstanceOf(StreamLimitExceededException.class)
                .satisfies(e -> assertThat(((StreamLimitExceededException) e).scope()).isEqualTo("stream"));
        perStream.stop();
        executor.shutdown();

        CardStreamRegistry total = registry(props(500, 500, 2));
        total.open("s-1", "u-1", Duration.ofMinutes(1));
        total.open("s-2", "u-2", Duration.ofMinutes(1));
        assertThatThrownBy(() -> total.open("s-3", "u-3", Duration.ofMinutes(1)))
                .isInstanceOf(StreamLimitExceededException.class)
                .satisfies(e -> assertThat(((StreamLimitExceededException) e).scope()).isEqualTo("total"));
    }

    /**
     * 컨테이너가 부를 콜백을 손으로 흉내낸다 — <b>등록 경로만</b> 재는 시험이다.
     * 진짜 「끊기면 반납된다」는 태스크 9가 실 HTTP로 잰다.
     */
    @Test
    void 콜백이_불리면_자리가_돌아온다() {
        CardStreamRegistry registry = registry(props(4, 50, 500));
        RecordingEmitter emitter = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        assertThat(registry.connectionCount()).isEqualTo(1);

        emitter.fireCompletion();

        assertThat(registry.connectionCount()).isZero();
    }

    @Test
    void publish는_그_방송의_연결에만_간다() {
        CardStreamRegistry registry = registry(props(4, 50, 500));
        RecordingEmitter a = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        RecordingEmitter b = (RecordingEmitter) registry.open("s-1", "u-2", Duration.ofMinutes(1));
        RecordingEmitter other = (RecordingEmitter) registry.open("s-2", "u-3", Duration.ofMinutes(1));

        registry.publish(snapshot("s-1", 41L));

        awaitUntil(() -> a.events().size() == 1 && b.events().size() == 1);
        assertThat(a.events().get(0).name()).isEqualTo("card");
        assertThat(a.events().get(0).id()).as("SSE id는 그 카드의 eventSeq다").isEqualTo("41");
        assertThat(a.events().get(0).data()).contains("\"source\":\"auto\"");
        assertThat(other.events()).as("다른 방송에 새면 편집자가 남의 방송 카드를 본다").isEmpty();
    }

    @Test
    void sendInitial은_카드_전부를_보내고_끝난_방송이면_ended_뒤에_닫는다() {
        CardStreamRegistry registry = registry(props(4, 50, 500));
        RecordingEmitter live = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        registry.sendInitial(live, List.of(snapshot("s-1", 1L), snapshot("s-1", 2L)), false);
        awaitUntil(() -> live.named().size() == 2);
        // 이름 있는 것만 센다 — sendInitial은 헤더를 즉시 커밋시키려고 주석("ok")을 먼저 보낸다.
        assertThat(live.named()).extracting(RecordingEmitter.Event::name).containsExactly("card", "card");
        assertThat(live.events().get(0).comment()).as("첫 쓰기가 주석이어야 헤더가 바로 나간다").isEqualTo("ok");
        assertThat(live.completed()).as("진행 중인 방송이면 열어 둔다").isFalse();

        RecordingEmitter ended = (RecordingEmitter) registry.open("s-2", "u-2", Duration.ofMinutes(1));
        registry.sendInitial(ended, List.of(snapshot("s-2", 3L)), true);
        awaitUntil(() -> ended.named().size() == 2);
        assertThat(ended.named()).extracting(RecordingEmitter.Event::name).containsExactly("card", "ended");
        awaitUntil(ended::completed);
    }

    /**
     * <b>초기 스냅샷은 큐를 한 칸만 쓴다.</b>
     *
     * <p>카드 하나당 태스크 하나로 제출하면 큐를 <b>카드 수만큼</b> 먹는다. 상한(운영 1000)을
     * 넘으면 거부 처리기가 조용히 버리고, <b>재연결해도 같은 스냅샷이라 같은 자리에서 또 잘린다</b>
     * (PR #109 봇 지적 ③, 2026-08-23 재현 — 1200장에서 201건 유실이 재연결 2회차에도 그대로).
     * 처음 잘리는 것이 {@code ended}라 <b>연결이 안 닫히기까지</b> 한다.
     *
     * <p>여기서는 큐 상한을 <b>2</b>로 조여 카드 200장을 민다. 쪼개 제출하면 201건 중 199건이
     * 거부되고, 한 태스크로 묶으면 한 칸이면 충분하다.
     */
    @Test
    void 초기_스냅샷은_카드가_많아도_큐를_한_칸만_쓴다() {
        executor = new CardStreamExecutor(1, 2);
        registry = new CardStreamRegistry(executor, props(4, 50, 500), mapper, d -> new RecordingEmitter());
        RecordingEmitter emitter = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        List<JumpCardSnapshot> cards =
                IntStream.rangeClosed(1, 200).mapToObj(i -> snapshot("s-1", i)).toList();

        try (LogCaptor captor = new LogCaptor()) {
            registry.sendInitial(emitter, cards, true);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (emitter.named().size() < 201 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            assertThat(captor.messages().stream().filter(m -> m.startsWith("jumpcard.stream.rejected")))
                    .as("초기 스냅샷이 거부되면 그 카드는 재연결해도 같은 자리에서 또 잘린다")
                    .isEmpty();
        }

        assertThat(emitter.named()).as("카드 200장 + ended").hasSize(201);
        assertThat(emitter.named().get(200).name())
                .as("ended가 거부되면 연결이 안 닫힌 채 불완전하게 남는다").isEqualTo("ended");
        assertThat(emitter.events().get(0).comment())
                .as("한 태스크로 묶여도 헤더를 틔우는 주석이 여전히 첫 쓰기여야 한다").isEqualTo("ok");
        awaitUntil(emitter::completed);
    }

    @Test
    void broadcastEnded는_그_방송_연결에_ended를_보내고_닫는다() {
        CardStreamRegistry registry = registry(props(4, 50, 500));
        RecordingEmitter mine = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        RecordingEmitter other = (RecordingEmitter) registry.open("s-2", "u-2", Duration.ofMinutes(1));

        registry.broadcastEnded("s-1");

        awaitUntil(() -> mine.events().size() == 1);
        assertThat(mine.events().get(0).name()).isEqualTo("ended");
        awaitUntil(mine::completed);
        assertThat(other.events()).isEmpty();
        assertThat(other.completed()).isFalse();
    }

    /**
     * <b>{@code broadcastEnded}가 연결을 여는 도중에 끼어도 순서가 지켜진다.</b>
     *
     * <p><b>창이 둘이다</b>(2026-08-23 재현). 봇은 앞의 하나만 지적했다.
     * <ul>
     *   <li><b>(가) 등록 직후 ~ 첫 제출 전</b> — {@code ended}가 스냅샷 <b>전체</b>보다 먼저 간다.
     *       클라이언트가 받는 것은 {@code ended} <b>하나뿐</b>이고, 뒤따르는 카드는 이미 닫힌
     *       emitter에 쓰다 {@code IllegalStateException}으로 삼켜진다(카드 5장 중 도착 0장)</li>
     *   <li><b>(나) 스냅샷을 제출하는 도중</b> — 앞 카드는 가고 <b>뒤 카드가 거부</b>된다.
     *       이 창은 카드 수에 비례한다({@code openWithSnapshot} 실측 0.011~1.830ms,
     *       카드 0~1000장)</li>
     * </ul>
     *
     * <p>같은 자물쇠가 둘 다 닫는다 — {@code broadcastEnded}에 {@code synchronized}가 붙으면
     * 종료 알림은 {@code openWithSnapshot} <b>앞이나 뒤로만</b> 갈라진다.
     *
     * <p>훅에서 <b>200ms를 잔다</b>. 자물쇠가 없으면 그 사이에 {@code broadcastEnded}가 통째로
     * 끝나고(재현됨), 있으면 락에서 기다리다 이 블록이 끝난 뒤에 돈다. 「안 기다려서 통과」의
     * 반대 방향이라 느린 기계에서 더 안전하다.
     */
    @Test
    void broadcastEnded가_연결을_여는_도중에_끼어도_스냅샷이_먼저_간다() throws Exception {
        executor = new CardStreamExecutor(4, 1000);
        Thread[] ender = new Thread[1];
        RecordingEmitter[] made = new RecordingEmitter[1];

        registry = new CardStreamRegistry(executor, props(4, 50, 500), mapper, d -> {
            RecordingEmitter emitter = new RecordingEmitter(() -> {
                CountDownLatch aboutToEnd = new CountDownLatch(1);
                ender[0] = new Thread(() -> {
                    aboutToEnd.countDown();
                    registry.broadcastEnded("s-1");
                }, "ender");
                ender[0].start();
                try {
                    aboutToEnd.await(5, TimeUnit.SECONDS);
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            made[0] = emitter;
            return emitter;
        });

        List<JumpCardSnapshot> cards =
                IntStream.rangeClosed(1, 5).mapToObj(i -> snapshot("s-1", i)).toList();
        registry.openWithSnapshot("s-1", "u-1", () -> Duration.ofMinutes(1),
                () -> new CardStreamRegistry.InitialSnapshot(cards, false));
        ender[0].join(5_000);

        RecordingEmitter emitter = made[0];
        // awaitUntil을 안 쓴다 — 못 모으면 거기서 터져 <b>무엇이 왔는지</b>를 못 본다.
        // 여기서는 실패의 이유가 「몇 개가 아니라 어떤 순서인가」라 목록이 보여야 한다.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (emitter.named().size() < 6 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(emitter.named()).extracting(RecordingEmitter.Event::name)
                .as("ended가 먼저 나가면 클라이언트는 카드 0장에 ended만 받는다")
                .containsExactly("card", "card", "card", "card", "card", "ended");
        assertThat(emitter.rejectedCount())
                .as("닫힌 뒤에 쓴 것이 있으면 그만큼 카드가 조용히 사라진 것이다").isZero();
        awaitUntil(emitter::completed);
    }

    @Test
    void ping은_모든_연결에_주석을_보낸다() {
        CardStreamRegistry registry = registry(props(4, 50, 500));
        RecordingEmitter a = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        RecordingEmitter b = (RecordingEmitter) registry.open("s-2", "u-2", Duration.ofMinutes(1));

        registry.ping();

        awaitUntil(() -> a.events().size() == 1 && b.events().size() == 1);
        assertThat(a.events().get(0).comment()).isEqualTo("ping");
    }

    /**
     * <b>이 시험이 재는 것은 「{@code ping()}이 예외를 밖으로 내보내지 않는다」다</b> —
     * 「스케줄러가 안 죽는다」를 직접 재는 것이 <b>아니다.</b>
     *
     * <p>주기 작업이 예외를 던지면 {@code scheduleAtFixedRate}가 이후 실행을 취소하고 재개하지
     * 않는 것은 <b>JDK의 규약</b>이라 우리가 시험으로 뒤집을 대상이 아니다. 우리가 통제할 수 있는
     * 것은 <b>예외를 새지 않게 하는 것</b> 하나뿐이고, 그것이 방어의 본질이라 그대로 잰다.
     *
     * <p>한 번 새면 모든 연결의 {@code :ping}이 영구히 멈추고 앞단 프록시가 조용해진 연결을
     * 끊기 시작한다 — <b>하트비트의 존재 이유가 바로 그것을 막는 것</b>인데 로그도 안 남는다.
     */
    @Test
    void ping이_예외를_만나도_밖으로_안_샌다() {
        executor = new CardStreamExecutor(1, 10) {
            @Override
            public boolean submit(int stripe, SseEmitter emitter, SendAction action) {
                throw new IllegalStateException("전송 제출이 터졌다");
            }
        };
        registry = new CardStreamRegistry(executor, props(4, 50, 500), mapper, d -> new RecordingEmitter());
        registry.open("s-1", "u-1", Duration.ofMinutes(1));

        assertThatCode(registry::ping)
                .as("예외가 새면 scheduleAtFixedRate가 이 작업을 취소하고 다시는 안 돈다")
                .doesNotThrowAnyException();
    }

    /** 순서가 뒤바뀌면 화면이 「집음 → 놓음」을 거꾸로 받는다. */
    @Test
    void 같은_연결의_이벤트는_순서대로_온다() {
        CardStreamRegistry registry = registry(props(4, 50, 500));
        RecordingEmitter emitter = (RecordingEmitter) registry.open("s-1", "u-1", Duration.ofMinutes(1));

        for (int i = 1; i <= 50; i++) {
            registry.publish(snapshot("s-1", i));
        }

        awaitUntil(() -> emitter.events().size() == 50);
        assertThat(emitter.events()).extracting(RecordingEmitter.Event::id)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 50).mapToObj(Integer::toString).toList());
    }

    private JumpCardSnapshot snapshot(String streamId, long eventSeq) {
        return new JumpCardSnapshot(eventSeq, streamId, JumpCardSource.AUTO, 1_500L,
                new JumpCardSnapshot.Window(1_000L, 2_000L), 97, null, null, null, null,
                false, null, eventSeq, Instant.parse("2026-08-23T00:00:00Z"));
    }

    private void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("3초 안에 조건이 참이 되지 않았다");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /**
     * 보낸 것을 적는 가짜 emitter. 서블릿 응답 없이 {@code SseEmitter.send}를 부르면 터지므로 덮는다.
     * 콜백은 컨테이너가 부르는 것이라 여기서 손으로 부른다.
     */
    private static final class RecordingEmitter extends SseEmitter {

        record Event(String name, String id, String data, String comment) {
        }

        private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        private final List<Runnable> completionCallbacks = Collections.synchronizedList(new ArrayList<>());
        private final List<SseEventBuilder> rejected = Collections.synchronizedList(new ArrayList<>());
        /** {@code open()}이 명부에 자리를 잡은 <b>직후</b> 돌 훅. 임계구역을 벌리는 데 쓴다. */
        private final Runnable afterRegister;
        private volatile boolean completed;

        RecordingEmitter() {
            this(null);
        }

        RecordingEmitter(Runnable afterRegister) {
            this.afterRegister = afterRegister;
        }

        /**
         * {@code open()}이 <b>마지막으로</b> 부르는 자리다 — {@code conns.put}은 끝났고
         * {@code sendInitial}은 아직이다. 그 사이를 벌리려고 여기에 훅을 건다.
         */
        @Override
        public void onError(java.util.function.Consumer<Throwable> callback) {
            if (afterRegister != null) {
                afterRegister.run();
            }
        }

        /**
         * 조각을 <b>먼저 이어 붙인 뒤</b> 줄 단위로 읽는다. {@code SseEventBuilder}는
         * {@code "id:41\nevent:card\ndata:"}를 한 조각으로 내보내고 본문을 다음 조각에 담는다 —
         * 조각마다 {@code startsWith}로 보면 {@code event}·{@code id}가 통째로 안 잡힌다(실측).
         */
        /**
         * 실물과 같은 규칙이다 — <b>{@code complete()} 뒤의 send는
         * {@code IllegalStateException}</b>(spring-webmvc 7.0.8
         * {@code ResponseBodyEmitter.send(Set)}의
         * {@code Assert.state(!this.complete, "ResponseBodyEmitter has already completed")}).
         * 서블릿 밖의 진짜 {@code SseEmitter}는 {@code handler == null}이라 이 갈래를 안 타므로
         * 여기서 흉내낸다. 이것이 없으면 「닫힌 뒤에도 카드가 도착한 것처럼」 보여
         * 순서가 깨진 것을 못 잡는다.
         */
        @Override
        public void send(SseEventBuilder builder) {
            if (completed) {
                rejected.add(builder);
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
            StringBuilder raw = new StringBuilder();
            for (DataWithMediaType chunk : builder.build()) {
                raw.append(String.valueOf(chunk.getData()));
            }

            String name = null;
            String id = null;
            String comment = null;
            StringBuilder data = new StringBuilder();
            for (String line : raw.toString().split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("id:")) {
                    id = line.substring("id:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                } else if (line.startsWith(":")) {
                    comment = line.substring(1).trim();
                }
            }
            events.add(new Event(name, id, data.toString(), comment));
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallbacks.add(callback);
        }

        @Override
        public void complete() {
            completed = true;
        }

        void fireCompletion() {
            List.copyOf(completionCallbacks).forEach(Runnable::run);
        }

        /** 이름 있는 것만. 주석(ok·ping)을 뺀다. */
        List<Event> named() {
            return events().stream().filter(e -> e.name() != null).toList();
        }

        List<Event> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        boolean completed() {
            return completed;
        }

        int rejectedCount() {
            return rejected.size();
        }
    }
}
