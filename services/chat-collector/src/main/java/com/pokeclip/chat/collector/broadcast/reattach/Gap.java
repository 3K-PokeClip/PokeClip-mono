package com.pokeclip.chat.collector.broadcast.reattach;

import java.time.Instant;

/**
 * 재부착이 붙기 직전까지 우리가 <b>못 받고 있던 구간</b>.
 *
 * <p><b>「유실」이라 단정하지 않는다.</b> 채팅이 한 건도 없던 방송은 원래 조용했을 수도 있다.
 * 그래서 숫자만 주지 않고 <b>무엇을 기준으로 쟀는지</b>({@link Basis})를 같이 준다 — 나중에
 * 로그 한 줄만 보고 그 숫자의 뜻을 알 수 있어야 한다.
 */
public record Gap(Basis basis, Instant since, long gapMs) {

    public enum Basis {
        /** 그 방송의 마지막 채팅 시각부터 쟀다. 가장 정확한 「확실히 받은 마지막 순간」이다. */
        LAST_CHAT,
        /** 채팅이 한 건도 없어 방송 시작부터 쟀다. 원래 조용한 방송일 수 있다. */
        BROADCAST_START,
        /**
         * 방송 시작 시각조차 모른다(clip이 {@code null}을 줘 EPOCH가 된 줄).
         * {@code since}는 {@code null}이고 {@code gapMs}는 <b>{@code -1}</b>이다 —
         * 1970년부터 재면 56년이 찍혀 로그가 거짓말을 한다.
         */
        UNKNOWN,
        /**
         * <b>재는 데 실패했다</b> — DB가 반개방이면 {@code socketTimeout}이 끊는다.
         * {@code since}는 {@code null}이고 {@code gapMs}는 {@code -1}이다.
         *
         * <p><b>{@link #UNKNOWN}과 뭉치지 않는다.</b> 그쪽은 「clip이 시작 시각을 안 줬다」이고
         * 이쪽은 「우리 DB를 못 읽었다」다 — 한 값으로 두면 로그를 세는 쪽이 두 원인을 못 가른다.
         *
         * <p><b>{@link GapMeasurer}가 아니라 {@code Reattacher}가 만든다.</b> 측정기는 던지는
         * 쪽이고, 그것을 「모르는 것도 답이다」로 바꾸는 판단은 <b>부르는 쪽</b>의 것이다 —
         * 판정에 쓰는 부름이 나중에 생기면 그쪽은 던지는 편이 안전하다.
         */
        MEASURE_FAILED
    }
}
