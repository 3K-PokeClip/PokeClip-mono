package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.purge.ChatPurgeStore.Due;
import com.pokeclip.chat.collector.purge.ChatPurgeStore.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 탈퇴한 스트리머 채널 하나의 채팅·후원·방송 정보와 채팅 원본 파일을 지운다(POK-256).
 *
 * <p>지우는 범위는 「요청 시각 + 늦은 창」 전에 받은 줄이다. 창이 닫히기 전에는 줄을 닫지 않는다: 탈퇴 직전에 받아
 * 바구니에 남아 있던 채팅이 지운 뒤에 적재될 수 있다. 창이 닫힌 뒤 같은 채널에 쌓이는 것은 새 연동의 것이라 건드리지 않는다.
 */
public class ChannelPurger {

    private static final Logger log = LoggerFactory.getLogger(ChannelPurger.class);

    private final ChatPurgeStore store;
    private final ArchivePurge archive;
    private final BiConsumer<String, Instant> stopCollecting;
    private final ChatPurgeProperties properties;
    private final Supplier<Instant> clock;

    /**
     * @param stopCollecting 그 채널을 지금 걷고 있는 세션 중 탈퇴 시각 전에 시작한 것을 닫는다. 탈퇴가 치지직 토큰을 폐기해도 붙어 있는 소켓이 끊긴다는
     *                       보장이 없다(ADR-085 한계). 닫지 않으면 창이 닫힌 뒤 쌓인 채팅은 60일까지 남는다(PR #220 codex P1).
     */
    public ChannelPurger(ChatPurgeStore store, ArchivePurge archive, BiConsumer<String, Instant> stopCollecting,
                         ChatPurgeProperties properties, Supplier<Instant> clock) {
        this.store = store;
        this.archive = archive;
        this.stopCollecting = stopCollecting;
        this.properties = properties;
        this.clock = clock;
    }

    /** 실패는 예외로 올린다. 정리기가 잡아 다음 순회에 다시 부른다. */
    public void purge(Due due) {
        Instant before = due.requestedAt().plus(properties.lateWindow());
        stopCollecting.accept(due.channelId(), due.requestedAt());
        int deleted = store.deleteIngestKeysOfChannel(due.channelId(), before);
        for (Table table : Table.values()) {
            int batch;
            do {
                batch = store.deleteChannelBatch(table, due.channelId(), before, properties.batch());
                deleted += batch;
            } while (batch == properties.batch());
        }
        archive.deleteChannel(due.channelId(), before);
        // 채널 번호는 안 찍는다(auth 규칙과 같다. 치지직 채널을 사람과 잇는 값이다).
        if (deleted > 0) {
            log.info("chat.purge.rows_deleted count={}", deleted);
        }
        Instant now = clock.get();
        if (!now.isBefore(before)) {
            store.complete(due.channelId(), now);
            log.info("chat.purge.completed");
        }
    }
}
