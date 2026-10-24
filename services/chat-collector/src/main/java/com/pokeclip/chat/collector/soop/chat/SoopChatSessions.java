package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.PersistableDonation;
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
import java.util.concurrent.atomic.AtomicLong;
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
 * <p><b>세대 번호</b>(POK-431, PR4 봇 codex P2): 탭을 열 때마다(붙이기 · 다시 붙기 둘 다) 새 세대를 받고, 브라우저는 이벤트마다 그것을 낸
 * 탭의 세대를 싣는다. 세션은 지금 연 탭의 세대와 다른 이벤트를 버린다. 탭 명령은 줄에 들어가기만 해서 브라우저가 옛 탭을 실제로 닫기까지
 * 펌프 한 번(최대 0.1초)이 걸리는데, 그 사이 옛 탭의 채팅이 새 채널 아이디로 저장되거나 옛 탭의 늦은 닫힘 · 오류가 막 연 새 탭을 다시
 * 열게 했다. 다시 붙기도 새 세대인 이유: 같은 세션 안의 재연결이라 세션당 세대 하나로는 옛 탭의 늦은 알림을 못 거른다(계획 검증 중대 1).
 *
 * <p>🔴 토큰 · SOOP 아이디 · 본문은 로그에 안 싣는다. 방송 번호 · 사유 · 건수만.
 */
public class SoopChatSessions implements SoopChat, SoopChatBrowser.Listener {

    private static final Logger log = LoggerFactory.getLogger(SoopChatSessions.class);

    /** 브라우저에 명령을 보내는 쪽. 운영은 {@link SoopChatBrowser}, 시험은 가짜. */
    public interface Tabs {
        /** {@code generation}은 이 탭이 낼 이벤트에 실릴 번호다. */
        void open(String streamId, String token, long generation);

        void close(String streamId);
    }

    private final SoopChatProperties properties;
    private final ChatBuffer buffer;
    private final DonationBuffer donations;
    private final ChatArchive archive;
    private final RelaySink relay;
    private final LongSupplier clockMillis;
    private final ScheduledExecutorService retries;
    private volatile Tabs tabs;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 탭 세대. 프로세스 하나에서 한 줄로 늘어 방송 · 세션이 달라도 겹치지 않는다. 자물쇠 안에서 {@code tabs.open}과 같이 받는다. */
    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong staleEvents = new AtomicLong();
    /**
     * 🔴 SOOP 후원 받은 순번. <b>음수(-1, -2, …)로, 프로세스 하나에서</b> 센다(POK-431 계획 검증 중대 2). 후원 중복 열쇠
     * {@code uq_chat_donations_received (stream_id, received_at, received_seq)}에 플랫폼이 없고, 같은 방송을 치지직과 SOOP에 같이 내보내면
     * 두 세션이 같은 방송 번호로 후원을 넣는다. 치지직 순번은 세션마다 1부터라 SOOP도 그렇게 세면 같은 밀리초의 n번째 후원끼리 부딪혀 뒤의
     * 것이 오류 없이 사라진다(후원은 원본이 없어 되찾을 길도 없다). 음수면 양수인 치지직과 안 겹치고, 세션이 바뀌어도 이어져 같은 방송의 옛
     * SOOP 세션과도 안 겹친다. 재시작 뒤는 받은 시각이 가른다(치지직과 같은 이유). 제약에 플랫폼을 넣는 길은 색인 교체라 이 카드 밖이다.
     */
    private final AtomicLong donationSeq = new AtomicLong();

    /** 한 방송의 채팅 세션. 칸은 이 객체를 잠그고 바꾼다. */
    private static final class Session {
        final String streamId;
        final String channelId;
        String token;
        int failures;
        long received;
        long donations;
        /** 중계방에서 와 버린 후원 수. 닫을 때 남긴다. */
        long relayDonationsDropped;
        /** 다시보기 · 방송국에서 와 버린 후원 수. 닫을 때 남긴다. */
        long vodStationDonationsDropped;
        /** 다시 붙기를 예약했는가. 예약이 겹치지 않게 한다. */
        boolean retryScheduled;
        /** 지금 연 탭의 세대. 이와 다른 세대의 이벤트는 지난 탭에서 온 것이다. */
        long generation;
        /** 지난 탭 이벤트를 INFO로 한 번 남겼는가(그 뒤는 DEBUG). */
        boolean staleLogged;

        Session(String streamId, String channelId, String token) {
            this.streamId = streamId;
            this.channelId = channelId;
            this.token = token;
        }
    }

    public SoopChatSessions(SoopChatProperties properties, ChatBuffer buffer, DonationBuffer donations, ChatArchive archive,
                            RelaySink relay, LongSupplier clockMillis) {
        this.properties = properties;
        this.buffer = buffer;
        this.donations = donations;
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

    /**
     * 붙이기 · 떼기 · 다시 붙기는 이 객체의 자물쇠 하나로 줄 세운다. 🔴 다시 붙기 예약이 「아직 그 세션인가」를 본 직후 떼기가 세션을 빼고
     * 탭 닫기를 넣으면, 예약이 뒤이어 탭 열기를 넣어 명부에 없는 방송의 탭이 남는다(다음 떼기도 명부에 없어 못 닫는다). 탭 명령은 줄에
     * 넣기만 하므로 자물쇠를 오래 쥐지 않는다.
     */
    @Override
    public synchronized void ensure(String streamId, String channelId, String token) {
        if (streamId == null || channelId == null || token == null || shutDown) {
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
        openTab(session);
    }

    /**
     * 새 세대로 탭을 연다. 붙이기 · 다시 붙기가 이 객체의 자물쇠 안에서 부른다. 🔴 예약 표시 끄기와 세대 바꾸기를 세션 자물쇠 한 번에 한다:
     * 따로 잡으면 그 사이에 온 옛 탭의 닫힘이 「세대는 아직 옛것, 예약은 꺼짐」을 보고 다시 붙기를 한 번 더 건다(감사 1회차 경미 1).
     */
    private void openTab(Session session) {
        long generation = generations.incrementAndGet();
        String token;
        synchronized (session) {
            session.retryScheduled = false;
            session.generation = generation;
            token = session.token;
        }
        tabs.open(session.streamId, token, generation);
    }

    /** 이 세대가 지금 연 탭의 것인가. 아니면(지난 탭) 세고 버린다. 세션 자물쇠를 쥔 채 부른다. */
    private boolean isCurrent(Session session, long generation) {
        if (session.generation == generation) {
            return true;
        }
        staleEvents.incrementAndGet();
        if (!session.staleLogged) {
            session.staleLogged = true;
            log.info("soop.chat.stale_event stream={}", session.streamId);
        } else {
            log.debug("soop.chat.stale_event stream={}", session.streamId);
        }
        return false;
    }

    @Override
    public synchronized void close(String streamId) {
        Session removed = sessions.remove(streamId);
        if (removed == null) {
            return;
        }
        tabs.close(streamId);
        long received;
        long donationCount;
        long relayDropped;
        long vodStationDropped;
        synchronized (removed) {
            received = removed.received;
            donationCount = removed.donations;
            relayDropped = removed.relayDonationsDropped;
            vodStationDropped = removed.vodStationDonationsDropped;
        }
        log.info("soop.chat.closed stream={} received={} donations={} relayDonationsDropped={} vodStationDonationsDropped={}",
                streamId, received, donationCount, relayDropped, vodStationDropped);
    }

    @Override
    public void onMessage(String streamId, long generation, SoopChatMessage message) {
        Session session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        long receivedAt = clockMillis.getAsLong();
        synchronized (session) {
            if (!isCurrent(session, generation)) {
                return;
            }
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
    public void onDonation(String streamId, long generation, SoopChatDonation donation) {
        Session session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        long receivedAt = clockMillis.getAsLong();
        synchronized (session) {
            if (!isCurrent(session, generation)) {
                return;
            }
            if (donation.fromVod() || donation.fromStation()) {
                // 다시보기 · 방송국에서 보낸 후원은 지금 이 방송의 반응이 아니다(결정 1). 칸 뜻을 잘못 읽었으면 후원이 전부 여기로 오므로 센다
                session.vodStationDonationsDropped++;
                return;
            }
            if (donation.relaysBroad()) {
                // 중계방에서 온 후원은 이 방송국 시청자의 반응이 아니다(결정 3). 얼마나 오는지 보려고 센다
                if (session.relayDonationsDropped++ == 0) {
                    log.info("soop.chat.donation_relay_dropped stream={}", streamId);
                }
                return;
            }
            session.donations++;
            session.failures = 0;
        }
        // 칸 짝: 금액 칸에 개수, 종류 칸에 action 이름. 단위(치지직 = 원, SOOP = 개수)는 종류로 가른다(결정 2). SOOP 후원에는 문구가 없다
        donations.offer(new PersistableDonation(Platform.SOOP, streamId, session.channelId, donation.userId(),
                donation.nickname(), donation.action(), donation.count(), "", receivedAt, donationSeq.decrementAndGet()));
        relay.offer(streamId, new RelayPayload.Donation(Instant.ofEpochMilli(receivedAt), donation.nickname(),
                donation.userId(), donation.count(), donation.action(), ""));
        // 원본 아카이브에는 안 넣는다: 치지직 후원과 같다(StreamSession 주석, 검산 등식 received = archived + 버림)
    }

    @Override
    public void onClosed(String streamId, long generation) {
        scheduleRetry(streamId, generation, "closed");
    }

    @Override
    public void onError(String streamId, long generation, String code) {
        log.warn("soop.chat.error stream={} code={}", streamId, safeCode(code));
        scheduleRetry(streamId, generation, "error");
    }

    private void scheduleRetry(String streamId, long generation, String reason) {
        Session session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        Duration delay;
        synchronized (session) {
            if (!isCurrent(session, generation) || session.retryScheduled) {
                return;
            }
            session.retryScheduled = true;
            delay = backoff(session.failures);
            session.failures++;
        }
        log.info("soop.chat.retrying stream={} reason={} delayMs={}", streamId, reason, delay.toMillis());
        try {
            retries.schedule(() -> reopen(session), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 끄는 중이다(예약기가 멈췄다). 브라우저 스레드로 번지지 않게 삼킨다
        }
    }

    private synchronized void reopen(Session session) {
        if (shutDown) {
            return;
        }
        // 그 사이 떼였거나 같은 방송으로 새 세션이 섰으면 이 예약은 버린다
        if (sessions.get(session.streamId) != session) {
            return;
        }
        openTab(session);
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

    /** 지난 탭에서 와 버린 이벤트 수(시험 · 진단). */
    long staleEvents() {
        return staleEvents.get();
    }

    /** 지금 붙어 있어야 하는 방송 수(시험 · 진단). */
    public int size() {
        return sessions.size();
    }

    /** 끌 때 첫걸음: 다시 붙기 예약을 멈추고 세션을 다 뗀다(뒤로 오는 채팅 · 닫힘 알림은 버려진다). 탭은 다음 걸음에 브라우저가 닫는다. */
    public synchronized void shutdown() {
        shutDown = true;
        sessions.clear();
        retries.shutdownNow();
    }

    /** 끈 뒤에는 지켜보기 틱이 붙이기를 불러도 새 세션을 안 만든다(닫히는 브라우저에 탭 열기가 가지 않게). 자물쇠 안에서만 바뀐다. */
    private boolean shutDown;
}
