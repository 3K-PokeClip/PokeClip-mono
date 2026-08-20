package com.pokeclip.chat.collector.session;

import java.time.Instant;

/**
 * 이 세션이 <b>누구의 · 어느 방송 · 어느 채널</b>인가, 그리고 <b>그 방송이 언제 시작했나</b>.
 *
 * <p>{@code startedAt}은 편지의 {@code occurredAt}이다. <b>방송 사이의 앞뒤를 가르는 유일한
 * 값이라 여기 든다</b> — {@code sequence}는 방송 안에서만 뜻이 있어 다른 방송끼리 비교할 수
 * 없고, SQS FIFO의 그룹이 방송 번호라 <b>같은 스트리머의 두 방송은 순서 보장이 없다.</b>
 * 이것이 없으면 늦게 도착한 앞 방송의 시작 편지가 지금 걷는 방송을 자기 쪽으로 되돌린다
 * (codex P1, 재현함).
 */
public record SessionKey(String streamId, long streamerId, String channelId, Instant startedAt) {

    /**
     * 설정 토큰 하나로 붙는 옛 경로({@code CHZZK_ENABLED})의 자리표.
     *
     * <p>{@code startedAt}이 {@link Instant#EPOCH}인 이유는 <b>그 경로에 방송 경계가 아예
     * 없기 때문</b>이다 — 편지를 안 받으므로 갈아끼움도 없고, 따라서 이 값이 비교에 쓰이는
     * 길이 없다. null 대신 값을 두는 것은 비교하는 쪽이 널 검사를 하지 않아도 되게 하려는 것이다.
     */
    public static SessionKey legacy() {
        return new SessionKey(null, 0L, null, Instant.EPOCH);
    }
}
