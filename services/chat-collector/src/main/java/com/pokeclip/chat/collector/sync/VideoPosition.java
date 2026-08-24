package com.pokeclip.chat.collector.sync;

/**
 * 채팅 시각 하나를 영상 위치로 바꾼 결과.
 *
 * <p><b>{@code positionMs}·{@code segmentSeq}는 {@link State#CONVERTED}일 때만 값이 있다.</b>
 * 나머지 둘에서 0을 실으면 「0초 지점」이라는 <b>그럴듯하게 틀린 답</b>이 되고, 부르는 쪽은
 * 그것을 변환 성공과 구분할 수 없다. 그래서 상자 타입({@code Long})으로 두고 팩터리 셋으로만
 * 만든다 — 생성자를 직접 부르지 못하게 막지는 않지만, <b>위치 없는 판정에 위치를 실을 방법이
 * 팩터리에는 없다</b>.
 *
 * @param positionMs      영상 시작을 0으로 한 위치(ms)
 * @param segmentSeq      그 위치가 들어 있는 조각 번호
 * @param appliedOffsetMs 이 답을 낼 때 실제로 뺀 보정값. <b>판정과 무관하게 늘 싣는다</b> —
 *                        「왜 이 위치가 나왔나」를 밖에서 재현하려면 이 값이 필요하다
 */
public record VideoPosition(State state, Long positionMs, Long segmentSeq, long appliedOffsetMs) {

    /**
     * 판정 셋. <b>가르는 축은 「다시 물으면 답이 바뀔 수 있는가」다.</b>
     *
     * <p>{@link #NOT_YET_INDEXED}는 조각이 아직 장부에 안 들어온 것이라 <b>다시 물으면 된다</b>.
     * {@link #NO_FOOTAGE}는 그 시각의 영상이 <b>영영 없다</b> — 첫 조각 이전이거나, 조각과 조각
     * 사이의 진짜 공백이거나, 벽시계가 역행해 위치를 믿을 수 없는 구간이다. 둘을 뭉치면
     * 부르는 쪽이 영영 안 올 것을 영원히 다시 묻는다.
     */
    public enum State { CONVERTED, NOT_YET_INDEXED, NO_FOOTAGE }

    public static VideoPosition converted(long positionMs, long segmentSeq, long appliedOffsetMs) {
        return new VideoPosition(State.CONVERTED, positionMs, segmentSeq, appliedOffsetMs);
    }

    public static VideoPosition notYetIndexed(long appliedOffsetMs) {
        return new VideoPosition(State.NOT_YET_INDEXED, null, null, appliedOffsetMs);
    }

    public static VideoPosition noFootage(long appliedOffsetMs) {
        return new VideoPosition(State.NO_FOOTAGE, null, null, appliedOffsetMs);
    }
}
