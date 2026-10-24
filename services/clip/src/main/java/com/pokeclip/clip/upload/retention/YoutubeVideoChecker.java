package com.pokeclip.clip.upload.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 올린 영상이 유튜브에 아직 있는지 30일마다 확인한다(POK-423, 처리방침 13절 · 유튜브 API 정책 III.E.4). 없으면 번호를 지운다.
 *
 * <p>판정(물어본 토큰의 채널 = 지금 연동 채널):
 * <ul>
 *   <li>있다 → 확인 시각. 채널을 모르는 줄이면 지금 채널을 채운다(그 토큰으로 보였다).</li>
 *   <li>없다 → 지금 채널 줄 · 채널 모름 줄이면 번호를 지운다. 🔴 <b>다른 채널 줄은 안 지운다</b>: 비공개 영상은 그 채널 토큰으로만
 *       보여서 「안 보임」이 「지워짐」의 근거가 아니다. 그 줄은 auth가 해제 기한에 지우라고 알린다.</li>
 *   <li>연동이 없다 → 안 지우고 확인 시각만 미룬다(지우는 것은 auth의 해제 기한이 한다. 매 순회 같은 사람을 묻지 않게).</li>
 *   <li>못 물었다 → 아무것도 안 바꾼다. 다음 순회가 다시 묻는다.</li>
 * </ul>
 *
 * <p>쿼터: 한 번에 50개(videos.list 한 번의 상한, 1유닛). 회원 N명 · 영상 V개면 30일에 대략 V/50번이다.
 */
@Component
public class YoutubeVideoChecker {

    private static final Logger log = LoggerFactory.getLogger(YoutubeVideoChecker.class);

    /** videos.list 한 번이 받는 번호 수 상한(유튜브 문서). */
    static final int BATCH = 50;
    /** 한 순회에 맡는 주인 수. 남으면 다음 순회가 잇는다. */
    static final int OWNERS_PER_SWEEP = 20;
    /** 한 주인에게 한 순회에 묻는 횟수 상한(영상 500개). 한 사람이 순회를 오래 쥐지 않게. */
    static final int CALLS_PER_OWNER = 10;

    private final YoutubeVideoStore store;
    private final YoutubeVideoCheckClient client;
    private final YoutubeVideoProperties properties;

    YoutubeVideoChecker(YoutubeVideoStore store, YoutubeVideoCheckClient client, YoutubeVideoProperties properties) {
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pokeclip.youtube-video.check-interval}",
            initialDelayString = "${pokeclip.youtube-video.check-interval}")
    void sweep() {
        try {
            checkDue(Instant.now());
        } catch (Throwable t) {
            // 스케줄러 스레드가 예외로 죽지 않게 한다. 다음 순회가 다시 돈다.
            log.warn("clip.youtube_video.sweep_failed causeType={}", t.getClass().getSimpleName());
        }
    }

    /** 확인할 때가 된 것을 한 순회만큼 확인한다. */
    public void checkDue(Instant now) {
        Instant before = now.minus(properties.checkAfter());
        for (String owner : store.dueOwners(before, OWNERS_PER_SWEEP)) {
            try {
                checkOwner(owner, before, now);
            } catch (RuntimeException e) {
                log.warn("clip.youtube_video.check_failed owner={} causeType={}", owner, e.getClass().getSimpleName());
            }
        }
    }

    private void checkOwner(String owner, Instant before, Instant now) {
        long userId = Long.parseLong(owner);
        for (int call = 0; call < CALLS_PER_OWNER; call++) {
            List<YoutubeVideoStore.Due> due = store.due(owner, before, BATCH);
            if (due.isEmpty()) {
                return;
            }
            YoutubeVideoCheckClient.Answer answer = client.check(userId, due.stream().map(YoutubeVideoStore.Due::videoId).toList());
            if (answer == null) {
                // 못 물었다. 이 사람은 다음 순회로 넘긴다(그대로 다시 물으면 같은 줄을 끝없이 고른다).
                return;
            }
            List<Long> keep = new ArrayList<>();
            List<Long> gone = new ArrayList<>();
            for (YoutubeVideoStore.Due d : due) {
                boolean ours = d.channelId() == null || d.channelId().equals(answer.channelId());
                if (answer.linked() && ours && !answer.existing().contains(d.videoId())) {
                    gone.add(d.id());
                } else {
                    keep.add(d.id());
                }
            }
            store.checked(keep, answer.linked() ? answer.channelId() : null, now);
            int forgotten = store.forgetIds(gone, now);
            log.info("clip.youtube_video.checked owner={} linked={} checked={} forgotten={}",
                    owner, answer.linked(), keep.size(), forgotten);
        }
    }
}
