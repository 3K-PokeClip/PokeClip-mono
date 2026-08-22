package com.pokeclip.chat.collector.status;

import java.time.Instant;

/**
 * 창구의 답 한 장. 필드 이름은 2번(web)·clip과의 약속이다 — 바꾸면 README·clip 배선도 같이 바꾼다.
 *
 * @param state       {@link CollectionState#wireName()} — 소문자 여섯 중 하나
 * @param since       문제가 시작된 시각. 정상(establishing·collecting)이면 null. <b>stopped인데도 null인
 *                    구간이 있다</b> — 포기 메모가 남기 전에는 그 시각을 아무도 안 들고 있다(지어내지 않는다)
 * @param attempt     reconnecting일 때만. 아니면 null
 * @param needsRelink stopped일 때만 뜻이 있다. 아니면 false
 * @param observedAt  이 답을 만든 시각
 */
public record ChatCollectionStatus(String streamId, String state, Instant since, Integer attempt,
                                   boolean needsRelink, Instant observedAt) {
}
