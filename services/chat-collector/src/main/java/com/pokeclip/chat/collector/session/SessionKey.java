package com.pokeclip.chat.collector.session;

/**
 * 세션 하나가 누구의 것인지 가리키는 열쇠.
 *
 * <p><b>태스크 8은 이 값을 들고만 있는다.</b> 읽는 쪽이 아직 없다 — 등록부가
 * {@code streamId}로 세션을 찾는 것은 태스크 9이고, 채팅 행에 {@code streamId}를
 * 싣는 것은 태스크 12다. 지금 만들어 두는 이유는 세션 생성자의 모양이 그 두 태스크에서
 * 다시 바뀌지 않게 하기 위해서다.
 *
 * @param streamId   1번의 방송 번호. <b>옛 경로에서는 null이다</b> — 편지 없이
 *                   {@code CHZZK_ENABLED}로 붙으면 방송 번호를 알 방법이 없다
 * @param streamerId 우리 회원 번호. 옛 경로에서는 0이다
 * @param channelId  치지직 채널. <b>옛 경로에서는 null이다</b> — 구독 API에 채널
 *                   파라미터가 없어 "누구 채팅을 받을지"는 토큰이 이미 정해 놨고,
 *                   우리는 채팅이 와야 그 값을 안다
 */
public record SessionKey(String streamId, long streamerId, String channelId) {

    /**
     * 편지 없이 설정으로 붙는 옛 경로의 열쇠. 셋 다 모르는 상태다.
     *
     * <p>비어 있는 것을 예외로 만들지 않는다 — 태스크 12가 "방송 번호를 몰라도
     * 저장은 된다"를 요구한다. 여기서 죽으면 옛 경로의 수집 자체가 죽는다.
     */
    public static SessionKey legacy() {
        return new SessionKey(null, 0L, null);
    }
}
