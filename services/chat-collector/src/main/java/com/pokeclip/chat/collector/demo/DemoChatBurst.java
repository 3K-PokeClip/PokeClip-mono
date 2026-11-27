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
import java.util.List;
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
 * (저장 바구니 {@link ChatBuffer} · 중계 {@link RelaySink})에 넣고, {@link DemoChatScript#CARD_AT_CHAT}번째 채팅에서
 * 자동 하이라이트 카드를 낸다({@link DemoHighlight}). 저장 · 반응 그래프 · 채팅창 표시는 실제 채팅과 같은 길이다.
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

    /** 틱 {@value DemoChatScript#BURST_TICKS}번 × 250ms = 10초 동안 흐른다 */
    static final Duration TICK = Duration.ofMillis(250);

    private final ChatBuffer buffer;
    private final RelaySink relay;
    private final DemoHighlight highlight;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clockMillis;
    private final Duration tick;

    /** 흐르고 있는 방송. 흐르는 동안 또 누르면 급증이 겹친다 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public DemoChatBurst(ChatBuffer buffer, RelaySink relay, DemoHighlight highlight) {
        this(buffer, relay, highlight, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "demo-chat-burst");
            thread.setDaemon(true);
            return thread;
        }), System::currentTimeMillis, TICK);
    }

    DemoChatBurst(ChatBuffer buffer, RelaySink relay, DemoHighlight highlight, ScheduledExecutorService scheduler,
                  LongSupplier clockMillis, Duration tick) {
        this.buffer = buffer;
        this.relay = relay;
        this.highlight = highlight;
        this.scheduler = scheduler;
        this.clockMillis = clockMillis;
        this.tick = tick;
    }

    /**
     * 급증을 10초 동안 흘리고, {@link DemoChatScript#CARD_AT_CHAT}번째 채팅에서 카드를 낸다.
     *
     * @return 시작했으면 {@code true}. 이 방송에 이미 흐르고 있으면 아무것도 안 하고 {@code false}
     */
    public boolean start(String streamId) {
        if (!running.add(streamId)) {
            return false;
        }
        DemoChatScript.Audience audience = DemoChatScript.audienceOf(streamId);
        long pressedAt = clockMillis.getAsLong();
        AtomicInteger next = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> handle = new AtomicReference<>();
        handle.set(scheduler.scheduleAtFixedRate(() -> {
            int index = next.getAndIncrement();
            try {
                offerTick(streamId, index, audience, pressedAt);
                if (index < DemoChatScript.BURST_TICKS - 1) {
                    return;
                }
            } catch (RuntimeException e) {
                // 던진 채로 두면 실행기가 이 작업을 조용히 멈추고 running에서도 안 빠져 다시 못 누른다
                log.warn("demo.chat_burst.failed stream={} tick={} cause={}", streamId, index, e.getClass().getSimpleName());
            }
            // 여기 오는 것은 마지막 급증 틱(또는 실패)이라 handle.set은 이미 끝나 있다
            running.remove(streamId);
            handle.get().cancel(false);
        }, 0, tick.toMillis(), TimeUnit.MILLISECONDS));
        log.info("demo.chat_burst.started stream={}", streamId);
        return true;
    }

    private void offerTick(String streamId, int index, DemoChatScript.Audience audience, long pressedAt) {
        List<DemoChat> chats = DemoChatScript.tick(index, clockMillis.getAsLong(), audience);
        for (int j = 0; j < chats.size(); j++) {
            DemoChat chat = chats.get(j);
            buffer.offer(persistable(streamId, chat));
            relay.offer(streamId, new RelayPayload.Chat(chat.platform(), Instant.ofEpochMilli(chat.messageTimeMillis()),
                    chat.nickname(), chat.senderId(), null, chat.text()));
            // 넣은 채팅 수가 정확히 CARD_AT_CHAT이 되는 순간이다. 틱은 한 번씩만 도므로 한 번뿐이다
            if (index * DemoChatScript.PER_TICK + j + 1 == DemoChatScript.CARD_AT_CHAT) {
                highlight.publish(streamId, pressedAt);
            }
        }
    }

    /** 받은 시각은 지금이다. 찍힌 시각과 같다 — 데모 채팅에는 전달 지연이 없다 */
    private PersistableChat persistable(String streamId, DemoChat chat) {
        return new PersistableChat(chat.platform(), streamId, channelId(chat.platform(), streamId), chat.senderId(),
                chat.text(), chat.messageTimeMillis(), clockMillis.getAsLong(), chat.nickname(), null);
    }

    /**
     * 실제 채널 번호를 안 쓴다. 채팅 지문 UNIQUE가 (채널, 보낸 사람, 시각, 본문)이라 방송마다 채널을 갈라야
     * 두 데모 방송이 같은 ms에 눌려도 한쪽이 버려지지 않고, 실제 채널 채팅과도 안 섞인다.
     *
     * <p>🔴 판별기(chat-detector {@code ChatWindowReader})가 이 접두어({@code demo-})로 데모 채팅을 빼고 센다 — 세면 같은 급증에
     * {@link DemoHighlight} 카드 말고 판별기 카드가 한 장 더 선다. 접두어를 바꾸면 그쪽도 바꾼다
     */
    static String channelId(Platform platform, String streamId) {
        return "demo-" + platform.name().toLowerCase(Locale.ROOT) + "-" + streamId;
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
