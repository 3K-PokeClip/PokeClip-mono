package com.pokeclip.chat.collector.archive;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * S3 키 규칙 한 곳. {@code chat/{channelId}/{yyyy-MM-dd}/{HH}/{HHmm}-{runId}.jsonl}
 *
 * <p><b>UTC · 창 시작 시각(우리가 받은 시각 기준)</b>. 1번 media의 세그먼트 키
 * {@code streams/{streamId}/{date}/{HH}/seg_NNNNNN.m4s}와 같은 시계·같은 층위다 —
 * 나중에 영상과 채팅을 나란히 놓는다. 방송 식별자는 아직 채널이다({@code stream_id}는
 * POK-82 이후) — 방송 시간표가 생기면 채널 + 시각 범위로 찾으므로 키를 안 바꾼다.
 *
 * <p>{@code runId}는 프로세스 시작 시 만든 짧은 무작위 표식이다. 10:23:10에 껐다가
 * 10:23:40에 다시 켜면 두 프로세스가 같은 "1023" 파일을 쓰려다 나중 것이 앞 것을
 * 덮어쓴다 — 표식이 그것을 막는다. 읽는 쪽은 그 분의 파일을 전부 읽는다.
 */
public final class ArchiveKey {

    private static final long MINUTE_MS = 60_000L;
    /** 날짜 폴더 · 시 폴더 · 파일 이름의 분을 한 번에 — {@code yyyy-MM-dd/HH/HHmm}. */
    private static final DateTimeFormatter PATH = DateTimeFormatter.ofPattern("yyyy-MM-dd/HH/HHmm").withZone(ZoneOffset.UTC);

    private ArchiveKey() { }

    /** 받은 시각이 속한 분의 시작(epoch ms). 창의 열쇠다. */
    public static long windowStartOf(long receivedAtMillis) {
        return Math.floorDiv(receivedAtMillis, MINUTE_MS) * MINUTE_MS;
    }

    /**
     * {@code seq}는 같은 (채널, 분) 창이 <b>다시 열렸을 때</b>의 순번이다 — 1이면 접미 없음, 2부터 {@code -2}·{@code -3}.
     * 창이 유예로 닫힌 뒤 그 분의 채팅이 뒤늦게 오면(바구니가 2초 넘게 밀리거나 시계가 역행할 때) 같은 열쇠의 창이
     * 다시 열리는데, 그때 같은 키로 PUT하면 S3가 앞 파일을 <b>조용히 덮어쓴다</b>(리뷰 1회차 재현). runId가 재시작
     * 덮어쓰기를 막는 것과 같은 방식으로 순번이 재열림 덮어쓰기를 막는다. 읽는 쪽 규칙은 그대로 — 그 분의 파일을 전부 읽는다.
     */
    public static String of(String channelId, long windowStartMillis, String runId, int seq) {
        String suffix = seq <= 1 ? "" : "-" + seq;
        return "chat/" + sanitize(channelId) + "/" + PATH.format(Instant.ofEpochMilli(windowStartMillis))
                + "-" + runId + suffix + ".jsonl";
    }

    /** 치지직 채널 ID는 32자 hex라 평소엔 무해하지만, 경로 문자가 섞이면 폴더 깊이가 바뀌어 검색 규칙이 깨진다. */
    private static String sanitize(String channelId) {
        return channelId.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
