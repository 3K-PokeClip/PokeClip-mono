package com.pokeclip.clip.purge;

import com.pokeclip.clip.purge.StreamerPurgeStore.Detached;
import com.pokeclip.clip.purge.StreamerPurgeStore.SegmentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 탈퇴한 스트리머 한 명의 clip 기록을 지운다(POK-256). 세 단계이고 어디서 끊겨도 다시 부르면 이어진다.
 *
 * <ol>
 *   <li><b>표</b>: 방송과 딸린 줄 전부. 지울 파일 주소는 같은 트랜잭션에서 명부로 옮긴다.</li>
 *   <li><b>완성 영상 창고</b>: 명부에 옮긴 접두사를 하나씩 지우고 하나씩 명부에서 뺀다.</li>
 *   <li><b>녹화 조각</b>: 1번 장부에서 500줄씩 읽어 파일을 지우고 그 줄을 지운다. 파일이 먼저다: 줄이 먼저 사라지면
 *       파일 키를 다시 알 길이 없다.</li>
 * </ol>
 *
 * <p>표를 창고보다 먼저 지우는 이유: 반대면 창고를 지운 뒤 표를 지우기 전에 렌더가 끝나 새 파일이 생기고, 그 영상 줄이
 * 지워지면 파일만 남는다. 표가 먼저면 늦게 끝난 렌더는 「주문 없음」을 받고 일꾼이 자기 파일을 지운다.
 *
 * <p>유튜브에 이미 올라간 영상은 건드리지 않는다(사용자 결정 2026-10-08). 스트리머 채널의 소유물이다.
 */
@Component
public class StreamerPurger {

    private static final Logger log = LoggerFactory.getLogger(StreamerPurger.class);

    /** 녹화 조각 한 묶음. 재생용 사본까지 키가 두 배라 S3 묶음 지우기 상한 1,000에 맞는다. */
    static final int SEGMENT_BATCH = 500;

    private final StreamerPurgeStore store;
    private final TransactionTemplate tx;
    private final ObjectProvider<PurgeStorage> storage;
    private final Clock clock;

    StreamerPurger(StreamerPurgeStore store, TransactionTemplate tx, ObjectProvider<PurgeStorage> storage) {
        this.store = store;
        this.tx = tx;
        this.storage = storage;
        this.clock = Clock.systemUTC();
    }

    /** 실패는 예외로 올린다. 정리기가 잡아 다음 순회에 다시 부른다. */
    public void purge(String streamerId) {
        Detached detached = tx.execute(status -> store.detach(streamerId));
        log.info("clip.purge.rows_deleted streamerId={} broadcasts={} clips={} cards={}",
                streamerId, detached.broadcasts(), detached.clips(), detached.cards());

        PurgeStorage files = storage.getIfAvailable();
        if (files == null) {
            // 렌더도 업로드도 꺼진 배포는 창고 이름을 모른다(그 배포에서는 이 서버가 창고에 새 파일을 만들지 않는다).
            // 녹화 조각 줄도 안 지운다: 줄을 지우면 media가 올린 파일의 키를 다시 알 길이 없다.
            store.clearPrefixes(streamerId);
            store.clearSegmentKeys(streamerId);
            store.complete(streamerId, clock.instant());
            log.warn("clip.purge.storage_unavailable streamerId={}", streamerId);
            return;
        }

        List<String> prefixes = store.pendingPrefixes(streamerId);
        for (String prefix : prefixes) {
            files.deleteOutputPrefix(prefix);
            store.prefixDone(streamerId, prefix);
        }
        int segments = 0;
        if (files.deletesSegments()) {
            segments = purgeSegments(streamerId, files);
        } else {
            // 렌더를 끄고 업로드만 켠 배포: 조각 창고 이름을 모른다. 줄을 남기면 파일 키를 잃지 않는다(위 갈래와 같은 이유).
            store.clearSegmentKeys(streamerId);
            log.warn("clip.purge.segments_skipped streamerId={}", streamerId);
        }
        store.complete(streamerId, clock.instant());
        log.info("clip.purge.completed streamerId={} prefixes={} segments={}", streamerId, prefixes.size(), segments);
    }

    private int purgeSegments(String streamerId, PurgeStorage files) {
        List<String> keys = store.segmentKeys(streamerId);
        if (keys.isEmpty() || !store.tableExists("stream_segments")) {
            return 0;
        }
        if (store.tableExists("stream_sessions")) {
            List<String> initKeys = store.initKeys(keys);
            if (!initKeys.isEmpty()) {
                files.deleteSegmentObjects(initKeys);
            }
        }
        int total = 0;
        while (true) {
            List<SegmentRow> batch = store.segmentBatch(keys, SEGMENT_BATCH);
            if (batch.isEmpty()) {
                return total;
            }
            List<String> objectKeys = new ArrayList<>(batch.size() * 2);
            for (SegmentRow row : batch) {
                objectKeys.add(row.s3Key());
                if (row.playbackS3Key() != null) {
                    objectKeys.add(row.playbackS3Key());
                }
            }
            files.deleteSegmentObjects(objectKeys);
            total += store.deleteSegments(batch);
        }
    }
}
