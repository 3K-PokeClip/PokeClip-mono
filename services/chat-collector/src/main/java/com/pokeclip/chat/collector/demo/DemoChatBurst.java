package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 발표 데모의 「채팅 이벤트」를 흘려보낸다. 임시 채팅을 <b>실제 채팅이 들어가는 바로 그 자리</b>
 * (저장 바구니 {@link ChatBuffer} · 중계 {@link RelaySink})에 넣는다. 그 뒤 저장 · 판별 · 화면 표시는
 * 실제 채팅과 같은 길이다.
 *
 * <p>누가 부를 수 있나는 여기서 안 막는다. 이 서버의 {@code /internal/**}는 내부 토큰으로만 열리고,
 * 「데모 계정만」은 사람 문을 가진 clip이 판정한다({@code DemoChatEventController}).
 *
 * <p>원본 보관(S3 아카이브)에는 안 넣는다. 그곳은 플랫폼이 보낸 원문 프레임을 그대로 남기는 자리라
 * 우리가 지어낸 채팅을 원문처럼 섞지 않는다.
 */
@Component
public class DemoChatBurst {

    private static final Logger log = LoggerFactory.getLogger(DemoChatBurst.class);

    /** 틱 {@value DemoChatScript#BURST_TICKS}번 × 250ms = 10초 동안 흐른다. 5초 창 두 개에 걸친다 */
    private static final Duration TICK = Duration.ofMillis(250);

    private final ChatBuffer buffer;
    private final RelaySink relay;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clockMillis;
    private final Duration tick;

    /** 흐르고 있거나 카드를 기다리는 방송({@link DemoChatScript#LOCK_TICKS}). 또 눌러 급증이 이어 붙으면 카드가 밀린다 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public DemoChatBurst(ChatBuffer buffer, RelaySink relay) {
        this(buffer, relay, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "demo-chat-burst");
            thread.setDaemon(true);
            return thread;
        }), System::currentTimeMillis, TICK);
    }

    DemoChatBurst(ChatBuffer buffer, RelaySink relay, ScheduledExecutorService scheduler,
                  LongSupplier clockMillis, Duration tick) {
        this.buffer = buffer;
        this.relay = relay;
        this.scheduler = scheduler;
        this.clockMillis = clockMillis;
        this.tick = tick;
    }

    /**
     * 지난 2분치 평소 채팅을 저장하고, 급증을 10초 동안 흘린다.
     *
     * @return 시작했으면 {@code true}. 이 방송에 이미 흐르고 있거나 카드를 기다리는 중이면 아무것도 안 하고 {@code false}
     */
    public boolean start(String streamId) {
        if (!running.add(streamId)) {
            return false;
        }
        // 평소 채팅은 지난 시각이라 채팅창(중계)에는 안 보낸다. 보내면 26건이 한꺼번에 쏟아진다
        for (DemoChat chat : DemoChatScript.calm(clockMillis.getAsLong())) {
            buffer.offer(persistable(streamId, chat));
        }
        AtomicInteger next = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> handle = new AtomicReference<>();
        handle.set(scheduler.scheduleAtFixedRate(() -> {
            int index = next.getAndIncrement();
            try {
                if (index < DemoChatScript.BURST_TICKS) {
                    offerTick(streamId, index);
                    return;
                }
                if (index < DemoChatScript.LOCK_TICKS) {
                    // 다 흘렸지만 카드가 나오기 전이다 — 또 누르면 급증이 이어 붙어 카드가 밀린다(LOCK_TICKS)
                    return;
                }
            } catch (RuntimeException e) {
                // 던진 채로 두면 실행기가 이 작업을 조용히 멈추고 running에서도 안 빠져 다시 못 누른다
                log.warn("demo.chat_burst.failed stream={} tick={} cause={}", streamId, index, e.getClass().getSimpleName());
            }
            // 여기 오는 것은 잠금 틱이 다 지난 뒤(또는 실패)라 handle.set은 이미 끝나 있다
            handle.get().cancel(false);
            running.remove(streamId);
        }, 0, tick.toMillis(), TimeUnit.MILLISECONDS));
        log.info("demo.chat_burst.started stream={}", streamId);
        return true;
    }

    private void offerTick(String streamId, int index) {
        for (DemoChat chat : DemoChatScript.tick(index, clockMillis.getAsLong())) {
            buffer.offer(persistable(streamId, chat));
            relay.offer(streamId, new RelayPayload.Chat(chat.platform(), Instant.ofEpochMilli(chat.messageTimeMillis()),
                    chat.nickname(), chat.senderId(), null, chat.text()));
        }
    }

    /** 받은 시각은 지금이다. 판별기는 받은 시각으로 방송이 살아 있는지 보고, 찍힌 시각으로 창을 센다 */
    private PersistableChat persistable(String streamId, DemoChat chat) {
        return new PersistableChat(chat.platform(), streamId, channelId(chat.platform(), streamId), chat.senderId(),
                chat.text(), chat.messageTimeMillis(), clockMillis.getAsLong(), chat.nickname(), null);
    }

    /**
     * 실제 채널 번호를 안 쓴다. 채팅 지문 UNIQUE가 (채널, 보낸 사람, 시각, 본문)이라 방송마다 채널을 갈라야
     * 두 데모 방송이 같은 ms에 눌려도 한쪽이 버려지지 않고, 실제 채널 채팅과도 안 섞인다
     */
    private static String channelId(Platform platform, String streamId) {
        return "demo-" + platform.name().toLowerCase(Locale.ROOT) + "-" + streamId;
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
