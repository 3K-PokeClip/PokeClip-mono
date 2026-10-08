package com.pokeclip.chat.collector.purge;

import java.time.Instant;

/**
 * 채팅 원본 파일(S3 {@code chat/{channelId}/…})을 지운다(POK-256). 원본 창고가 꺼진 배포에서는 {@link #NONE}이다.
 * 없는 파일을 지워도 성공이다. 실패는 예외로 던지고 정리기가 다음 순회에 다시 한다.
 */
public interface ArchivePurge {

    ArchivePurge NONE = (channelId, before) -> { };

    /** 이 채널 원본 중 {@code before} 전 분(分)의 파일만 지운다. 그 뒤 것은 같은 채널을 새로 연동한 사람의 것일 수 있다. */
    void deleteChannel(String channelId, Instant before);
}
