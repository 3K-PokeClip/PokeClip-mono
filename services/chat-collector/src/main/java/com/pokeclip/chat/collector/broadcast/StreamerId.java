package com.pokeclip.chat.collector.broadcast;

/**
 * 편지의 {@code streamerId}를 우리 회원 번호로 읽는다.
 *
 * <p><b>1번 발행 코드가 없어 이 가정은 아직 대조되지 않았다.</b> 틀리면 모든 방송의
 * 토큰 조회가 실패하므로, 실패를 예외가 아니라 값으로 돌려 세는 쪽이 드러낼 수 있게 한다.
 */
public record StreamerId(boolean valid, long value) {

    private static final StreamerId INVALID = new StreamerId(false, 0L);

    public static StreamerId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INVALID;
        }
        try {
            return new StreamerId(true, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return INVALID;   // 범위 초과도 여기로 온다
        }
    }
}
