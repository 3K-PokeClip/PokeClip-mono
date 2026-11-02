package com.pokeclip.clip.purge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 명부를 주기마다 훑어 남은 지우기를 한다(POK-256). 내부 문은 명부에 적기만 하고 바로 답한다:
 * 녹화 조각이 수십만 개면 지우기가 auth의 응답 시한을 넘는다.
 *
 * <p>스트리머마다 따로 잡는다. 한 사람의 실패(창고 일시 장애)가 다른 사람의 지우기를 막지 않게 한다.
 */
@Component
class StreamerPurgeSweeper {

    private static final Logger log = LoggerFactory.getLogger(StreamerPurgeSweeper.class);

    /** 한 순회에 맡는 사람 수. 남으면 다음 순회가 잇는다. */
    static final int BATCH = 20;

    private final StreamerPurgeStore store;
    private final StreamerPurger purger;

    StreamerPurgeSweeper(StreamerPurgeStore store, StreamerPurger purger) {
        this.store = store;
        this.purger = purger;
    }

    @Scheduled(fixedDelayString = "${pokeclip.purge.interval}", initialDelayString = "${pokeclip.purge.interval}")
    void sweep() {
        try {
            for (String streamerId : store.due(BATCH)) {
                try {
                    purger.purge(streamerId);
                } catch (Throwable t) {
                    log.warn("clip.purge.failed streamerId={} causeType={}", streamerId, t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            // 스케줄러 스레드가 예외로 죽지 않게 한다. 다음 순회가 다시 돈다.
            log.warn("clip.purge.sweep_failed causeType={}", t.getClass().getSimpleName());
        }
    }
}
