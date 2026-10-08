package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.purge.ChatPurgeStore.Due;
import com.pokeclip.chat.collector.purge.ChatPurgeStore.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 정리기 둘(POK-256). 탈퇴 채널을 지우는 쪽과 60일 지난 줄을 지우는 쪽이다. 둘 다 예외를 삼켜 스케줄러 스레드를 살린다.
 */
public class ChatPurgeSweepers {

    private static final Logger log = LoggerFactory.getLogger(ChatPurgeSweepers.class);

    /** 한 순회에 맡는 채널 수. 남으면 다음 순회가 잇는다. */
    static final int CHANNEL_BATCH = 20;

    private final ChatPurgeStore store;
    private final ChannelPurger purger;
    private final ChatPurgeProperties properties;
    private final Supplier<Instant> clock;

    public ChatPurgeSweepers(ChatPurgeStore store, ChannelPurger purger, ChatPurgeProperties properties,
                             Supplier<Instant> clock) {
        this.store = store;
        this.purger = purger;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pokeclip.chat-purge.interval}", initialDelayString = "${pokeclip.chat-purge.interval}")
    public void purgeChannels() {
        try {
            for (Due due : store.due(CHANNEL_BATCH)) {
                try {
                    purger.purge(due);
                } catch (Throwable t) {
                    // 한 채널의 실패가 다른 채널을 막지 않는다.
                    log.warn("chat.purge.failed causeType={}", t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            log.warn("chat.purge.sweep_failed causeType={}", t.getClass().getSimpleName());
        }
    }

    /**
     * 받은 지 {@code retain}(기본 60일)이 지난 채팅·후원·방송 정보를 지운다. 영상 보관 기한(ADR-004)과 같다:
     * 영상이 사라진 방송의 채팅 차트는 볼 자리가 없다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.chat-purge.retention-interval}",
            initialDelayString = "${pokeclip.chat-purge.retention-interval}")
    public void expire() {
        try {
            Instant cutoff = clock.get().minus(properties.retain());
            int deleted = 0;
            for (Table table : Table.values()) {
                int batch;
                do {
                    batch = store.deleteExpiredBatch(table, cutoff, properties.batch());
                    deleted += batch;
                } while (batch == properties.batch());
            }
            deleted += store.deleteExpiredIngestKeys(cutoff);
            if (deleted > 0) {
                log.info("chat.retention.expired count={}", deleted);
            }
        } catch (Throwable t) {
            log.warn("chat.retention.failed causeType={}", t.getClass().getSimpleName());
        }
    }
}
