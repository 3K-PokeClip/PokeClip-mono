package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * SOOP 채팅 세션들(POK-397 PR4). 「붙어 있어야 하는 방송」 표를 들고, 브라우저가 알리는 채팅을 기존 세 길(적재 바구니 · 원본 아카이브 ·
 * 화면 중계)에 넣는다. 끊기면 그 방송이 아직 표에 있을 때 1초부터 두 배씩(상한 60초) 기다렸다 다시 붙는다.
 *
 * <p><b>시각은 받은 시각이다.</b> SOOP은 메시지에 시각 · ID를 안 준다(공식 문서). 그래서 {@code message_time = received_at}이고 시차 보정은
 * 아직 없다(실방송에서 잰 뒤 정한다, Q3). <b>멱등 전제</b>: 지문(채널 · 보낸 사람 · 본문 · 시각)이 시각을 품어, 다시 붙은 직후 SDK가 지난 줄을
 * 다시 준다면 막지 못한다. 공식 문서에 지난 줄을 다시 준다는 말은 없다(실방송에서 확인할 것).
 *
 * <p>넣기 · 빼기({@link #ensure} · {@link #close})는 지켜보기 틱 스레드가, 알림({@link #onMessage} 등)은 브라우저 스레드가 부른다.
 * 둘 다 막지 않는다. 표는 동시 맵이고, 한 방송의 상태 바꾸기는 그 줄을 잠근다.
 *
 * <p>🔴 토큰 · SOOP 아이디 · 본문은 로그에 안 싣는다. 방송 번호 · 사유 · 건수만.
 */
public class SoopChatSessions implements SoopChat, SoopChatBrowser.Listener {

    private static final Logger log = LoggerFactory.getLogger(SoopChatSessions.class);

    /** 브라우저에 명령을 보내는 쪽. 운영은 {@link SoopChatBrowser}, 시험은 가짜. */
    public interface Tabs {
        void open(String streamId, String token);

        void close(String streamId);
    }

    private final SoopChatProperties properties;
    private final ChatBuffer buffer;
    private final ChatArchive archive;
    private final RelaySink relay;
    private final LongSupplier clockMillis;
    private final ScheduledExecutorService retries;
    private volatile Tabs tabs;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 한 방송의 채팅 세션. 칸은 이 객체를 잠그고 바꾼다. */
    private static final class Session {
        final String streamId;
        final String channelId;
        String token;
        int failures;
        long received;
        /** 다시 붙기를 예약했는가. 예약이 겹치지 않게 한다. */
        boolean retryScheduled;

        Session(String streamId, String channelId, String token) {
            this.streamId = streamId;
            this.channelId = channelId;
            this.token = token;
        }
    }

    public SoopChatSessions(SoopChatProperties properties, ChatBuffer buffer, ChatArchive archive, RelaySink relay,
                            LongSupplier clockMillis) {
        this.properties = properties;
        this.buffer = buffer;
        this.archive = archive;
        this.relay = relay;
        this.clockMillis = clockMillis;
        this.retries = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "soop-chat-retry");
            t.setDaemon(true);
            return t;
        });
    }

    /** 브라우저를 단다. 브라우저가 이 객체를 듣기 때문에 생성 뒤에 잇는다(구성이 한다). */
    public void attach(Tabs tabs) {
        this.tabs = tabs;
    }

    @Override
    public void ensure(String streamId, String channelId, String token) {
        if (streamId == null || channelId == null || token == null) {
            return;
        }
        Session current = sessions.get(streamId);
        if (current != null && !current.channelId.equals(channelId)) {
            // 방송 중에 다른 SOOP 계정으로 다시 연동했다. 토큰만 바꾸면 새 계정으로 받은 채팅이 옛 채널로 저장된다. 떼고 새로 붙인다
            log.info("soop.chat.channel_changed stream={}", streamId);
            close(streamId);
            current = null;
        }
        if (current != null) {
            synchronized (current) {
                current.token = token;
            }
            return;
        }
        if (sessions.size() >= properties.maxSessions()) {
            log.warn("soop.chat.full stream={} max={}", streamId, properties.maxSessions());
            return;
        }
        Session session = new Session(streamId, channelId, token);
        if (sessions.putIfAbsent(streamId, session) != null) {
            return;
        }
        log.info("soop.chat.opening stream={}", streamId);
        tabs.open(streamId, token);
    }

    @Override
    public void close(String streamId) {
        Session removed = sessions.remove(streamId);
        if (removed == null) {
            return;
        }
        tabs.close(streamId);
        long received;
        synchronized (removed) {
            received = removed.received;
        }
        log.info("soop.chat.closed stream={} received={}", streamId, received);
    }

    @Override
    public void onMessage(String streamId, SoopChatMessage message) {
        Session session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        long receivedAt = clockMillis.getAsLong();
        synchronized (session) {
            session.received++;
            session.failures = 0;
        }
        String role = "MANAGER_MESSAGE".equals(message.action()) ? "manager" : null;
        buffer.offer(new PersistableChat(Platform.SOOP, streamId, session.channelId, message.userId(), message.text(),
                receivedAt, receivedAt, message.nickname(), role));
        archive.offer(new ArchivableChat(Platform.SOOP, session.channelId, receivedAt, message.raw()));
        relay.offer(streamId, new RelayPayload.Chat(Instant.ofEpochMilli(receivedAt), message.nickname(), message.userId(),
                role, message.text()));
    }

    @Override
    public void onClosed(String streamId) {
        scheduleRetry(streamId, "closed");
    }

    @Override
    public void onError(String streamId, String code) {
        log.warn("soop.chat.error stream={} code={}", streamId, safeCode(code));
        scheduleRetry(streamId, "error");
    }

    private void scheduleRetry(String streamId, String reason) {
        Session session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        Duration delay;
        synchronized (session) {
            if (session.retryScheduled) {
                return;
            }
            session.retryScheduled = true;
            delay = backoff(session.failures);
            session.failures++;
        }
        log.info("soop.chat.retrying stream={} reason={} delayMs={}", streamId, reason, delay.toMillis());
        retries.schedule(() -> reopen(session), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void reopen(Session session) {
        // 그 사이 떼였거나 같은 방송으로 새 세션이 섰으면 이 예약은 버린다
        if (sessions.get(session.streamId) != session) {
            return;
        }
        String token;
        synchronized (session) {
            session.retryScheduled = false;
            token = session.token;
        }
        tabs.open(session.streamId, token);
    }

    Duration backoff(int failures) {
        Duration delay = properties.reconnectMin();
        for (int i = 0; i < failures && delay.compareTo(properties.reconnectMax()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(properties.reconnectMax()) > 0 ? properties.reconnectMax() : delay;
    }

    /** 오류 코드는 SDK가 주는 글이라 길이 · 글자를 줄여 싣는다(로그 줄 위조 · 무엇이 섞일지 모름). */
    private static String safeCode(String code) {
        if (code == null) {
            return "";
        }
        String cleaned = code.replaceAll("[^A-Za-z0-9_\\-]", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** 지금 붙어 있어야 하는 방송 수(시험 · 진단). */
    public int size() {
        return sessions.size();
    }

    /** 끌 때 첫걸음: 다시 붙기 예약을 멈추고 세션을 다 뗀다(뒤로 오는 채팅 · 닫힘 알림은 버려진다). 탭은 다음 걸음에 브라우저가 닫는다. */
    public void shutdown() {
        retries.shutdownNow();
        sessions.clear();
    }
}
