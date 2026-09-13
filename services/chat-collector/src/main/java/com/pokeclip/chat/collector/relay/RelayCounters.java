package com.pokeclip.chat.collector.relay;

/**
 * 중계 셈 넷. health 상세와 판정 줄이 이 묶음으로 받는다. 꺼져 있으면 {@link #NONE}.
 *
 * <p>셋은 <b>서로 다른 곳에서 잃은 것</b>이라 섞지 않는다 — clip이 죽었는지 바구니가 작은지를 가른다(문항 5).
 */
public interface RelayCounters {

    /**
     * 바구니가 번호를 붙여 담은 건수(감사 L6). 닫힌 뒤
     * {@code relayOffered = relayed + relayDropped + bufferDropped}가 닫힌다 — 판정 줄로 검산한다.
     */
    long relayOffered();

    /**
     * 켜져 있나. health의 {@code relay=disabled}를 가른다 — 「꺼져서 0」과 「켜졌는데 0」이 같으면
     * 운영자가 스위치를 켰는지부터 다시 확인해야 한다.
     */
    default boolean enabled() {
        return true;
    }

    /** clip이 2xx로 받은 건수. */
    long relayed();

    /** clip 실패·모르는 방송·종료 기한 초과로 못 보내고 버린 건수. 재시도는 없다. */
    long relayDropped();

    /** 수신 → 중계 바구니 상한 초과로 버린 건수. */
    long bufferDropped();

    RelayCounters NONE = new RelayCounters() {
        @Override public boolean enabled() { return false; }
        @Override public long relayOffered() { return 0; }
        @Override public long relayed() { return 0; }
        @Override public long relayDropped() { return 0; }
        @Override public long bufferDropped() { return 0; }
    };
}
