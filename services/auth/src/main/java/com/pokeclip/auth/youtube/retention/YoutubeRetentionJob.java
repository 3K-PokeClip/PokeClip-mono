package com.pokeclip.auth.youtube.retention;

import com.pokeclip.auth.youtube.YoutubeChannel;
import com.pokeclip.auth.youtube.YoutubeLinkService;
import com.pokeclip.auth.youtube.YoutubeOAuthClient;
import com.pokeclip.auth.youtube.YoutubeResolveResult;
import com.pokeclip.auth.youtube.retention.YoutubeRetentionStore.ForgetJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 유튜브 데이터를 처리방침대로 지우고 갱신한다(POK-423, 처리방침 13절 · 유튜브 API 정책 III.D.2 · III.E.4). 한 바퀴에 셋:
 * <ol>
 *   <li><b>기한 지난 닫힌 줄</b>: 해제 7일 · 철회 · 갱신 불가 30일 뒤 채널 번호 · 이름을 지운다. 그 회원이 지금 그 채널에 연동돼 있지
 *       않으면 clip에 그 채널의 영상 번호를 지우라고 장부에 적는다({@link YoutubeRetentionStore#closeOut}).</li>
 *   <li><b>장부 보내기</b>: 받을 때까지 다시 보낸다(15초부터 두 배씩, 상한 1시간). 받으면 줄째 지운다.</li>
 *   <li><b>채널 정보 갱신</b>: 연동 중 30일이 지나면 channels.list로 이름을 다시 받는다(1유닛).</li>
 * </ol>
 * 영상 번호의 30일 확인은 clip이 영상 번호를 들고 {@code /internal/youtube-videos/check}로 묻는다(번호가 clip에 있다).
 *
 * <p>🔴 <b>구글에 권한 취소(revoke)를 보내지 않는다</b>(ADR-052 유지, 2026-10-24 사용자 결정). revoke는 계정 단위라 같은 구글 계정의
 * 다른 동의까지 죽는다. 사용자는 구글 계정 설정에서 직접 철회한다.
 *
 * <p>🔴 <b>자기 스레드 하나에서 돈다</b>(탈퇴 장부 발송기와 같은 이유): auth의 {@code @Scheduled}는 스레드 하나를 치지직 갱신 ·
 * 유튜브 점검이 나눠 쓴다. 여기 HTTP(clip · 구글)가 그 스레드에 오르면 상대가 느린 동안 치지직 갱신이 밀린다. 종료 때 기다리지 않는다:
 * 장부와 표에 남아 다음 기동이 잇는다(종료 유예 산수에 안 걸린다).
 */
@Component
public class YoutubeRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(YoutubeRetentionJob.class);

    /** 한 바퀴에 지우는 닫힌 줄 수. 표 쓰기뿐이라 넉넉하다. */
    static final int CLOSE_OUT_BATCH = 100;
    /** 한 바퀴에 보내는 장부 줄 수. 줄마다 최대 7초라 한 바퀴가 70초를 안 넘는다. */
    static final int SEND_BATCH = 10;
    /** 한 바퀴에 채널 정보를 다시 받는 회원 수. 회원마다 resolve(갱신) + channels.list 두 번의 구글 호출이다. */
    static final int REFRESH_BATCH = 25;
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(15);
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final YoutubeRetentionStore store;
    private final YoutubeVideoForgetNotifier notifier;
    private final YoutubeLinkService links;
    private final YoutubeOAuthClient youtube;
    private final YoutubeRetentionProperties properties;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    YoutubeRetentionJob(YoutubeRetentionStore store, YoutubeVideoForgetNotifier notifier, YoutubeLinkService links,
                        YoutubeOAuthClient youtube, YoutubeRetentionProperties properties) {
        this.store = store;
        this.notifier = notifier;
        this.links = links;
        this.youtube = youtube;
        this.properties = properties;
        this.clock = Clock.systemUTC();
        if (properties.run()) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "youtube-retention");
                thread.setDaemon(true);
                return thread;
            });
            long millis = properties.interval().toMillis();
            executor.scheduleWithFixedDelay(this::runSafely, millis, millis, TimeUnit.MILLISECONDS);
        } else {
            this.executor = null;
        }
    }

    private void runSafely() {
        try {
            runOnce(clock.instant());
        } catch (Throwable t) {
            // 스레드가 예외로 죽으면 다음 주기가 안 돈다(scheduleWithFixedDelay 규약).
            log.warn("auth.youtube.retention.run_failed causeType={}", t.getClass().getSimpleName());
        }
    }

    /** 한 바퀴. 시험이 시각을 넣어 직접 부른다. */
    public void runOnce(Instant now) {
        closeOutDue(now);
        sendForgetJobs(now);
        refreshChannels(now);
    }

    void closeOutDue(Instant now) {
        List<Long> due = store.dueClosed(now.minus(properties.unlinkedGrace()), now.minus(properties.brokenGrace()),
                CLOSE_OUT_BATCH);
        int forget = 0;
        for (Long linkId : due) {
            try {
                if (store.closeOut(linkId, now)) {
                    forget++;
                }
            } catch (RuntimeException e) {
                log.warn("auth.youtube.retention.close_out_failed linkId={} causeType={}", linkId, e.getClass().getSimpleName());
            }
        }
        if (!due.isEmpty()) {
            log.info("auth.youtube.retention.closed_out links={} forgetJobs={}", due.size(), forget);
        }
    }

    void sendForgetJobs(Instant now) {
        for (ForgetJob job : store.claim(now, LEASE, SEND_BATCH)) {
            try {
                notifier.send(job);
                store.sent(job, clock.instant());
                log.info("auth.youtube.retention.forget_sent userId={}", job.userId());
            } catch (Exception e) {
                store.retryLater(job.id(), clock.instant().plus(backoff(job.attempts())));
                log.warn("auth.youtube.retention.forget_retry userId={} attempts={} causeType={}",
                        job.userId(), job.attempts() + 1, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 연동 중 30일마다 채널 이름을 다시 받는다. 토큰은 resolve가 준다(필요하면 갱신). resolve가 거절이면(끊김 · 일시 실패) 건너뛴다:
     * 끊긴 연동은 닫힌 줄이 되어 위 기한이 지우고, 일시 실패는 다음 바퀴가 다시 묻는다.
     */
    void refreshChannels(Instant now) {
        for (Long userId : store.dueChannelRefresh(now.minus(properties.channelRefreshAfter()), REFRESH_BATCH)) {
            try {
                YoutubeResolveResult token = links.resolve(userId);
                if (!token.valid()) {
                    log.info("auth.youtube.retention.channel_refresh_skipped userId={} reason={}", userId, token.reason());
                    continue;
                }
                String name = youtube.listChannels(token.accessToken()).stream()
                        .filter(c -> c.channelId().equals(token.channelId()))
                        .map(YoutubeChannel::channelName).findFirst().orElse(null);
                if (name == null) {
                    // 그 토큰의 채널 목록에 연동한 채널이 없다(채널을 지웠거나 넘겼다). 이름은 그대로 두고 다음 달에 다시 본다.
                    log.warn("auth.youtube.retention.channel_missing userId={}", userId);
                }
                store.channelRefreshed(userId, token.channelId(), name, now);
            } catch (RuntimeException e) {
                log.warn("auth.youtube.retention.channel_refresh_failed userId={} causeType={}",
                        userId, e.getClass().getSimpleName());
            }
        }
    }

    static Duration backoff(int attempts) {
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
