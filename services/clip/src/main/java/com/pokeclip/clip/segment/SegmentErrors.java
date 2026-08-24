package com.pokeclip.clip.segment;

/**
 * 세그먼트 창 조회가 거절하는 네 가지. 한 파일에 모으는 이유는 {@code JumpCardErrors}와 같다 —
 * 어떤 상태 코드가 나가는지 보려고 파일 넷을 열지 않게. 매핑은 {@code SegmentExceptionHandler}
 * 하나가 한다.
 *
 * <p>넷 다 unchecked다. 이 서비스의 소비자가 둘이고(편집기 미리보기 · 렌더 잡 POK-125)
 * <b>둘이 서로 다르게 반응해야 하는데</b>, 검사 예외로 만들면 그 갈림이 호출부의 catch 모양에
 * 묶여 새 소비자가 붙을 때마다 시그니처가 흔들린다.
 */
public final class SegmentErrors {

    /**
     * 404. <b>「방송이 없다」와 「이 사람과 아무 관계가 없다」를 한 타입이 덮는다.</b>
     * 둘을 갈라 던지면 응답도 갈리고, 그러면 남의 방송 번호를 넣어 보는 것만으로 그 방송이
     * 실재하는지 알 수 있다(PRD 결정 표 「거절 응답」).
     *
     * <p>{@code reason}은 <b>로그 전용</b>이다 — {@code broadcast_not_found} ·
     * {@code relation_none} · {@code streamer_id_not_numeric} · {@code subject_not_numeric}.
     *
     * <p><b>그래서 reason을 예외 메시지에 넣지 않는다.</b> 여기 넣으면 이 예외가 핸들러를
     * 못 만나고 스프링 기본 {@code /error}로 떨어지는 날 그 사유가 응답 본문에 실릴 수 있다
     * ({@code server.error.include-message}는 지금 기본값 {@code never}지만, 그 한 줄이
     * 켜지는 순간 위에서 합쳐 놓은 404가 도로 갈린다). 메시지를 고정 문구로 두면 그 창이 없다.
     */
    public static class NotViewableException extends RuntimeException {
        private final String reason;

        public NotViewableException(String reason) {
            super("볼 수 없는 방송이다");
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * 410. 보관 기한(ADR-004 — 60일)이 지났다.
     *
     * <p>이 응답은 <b>자격이 확인된 사람에게만</b> 도달한다. 410은 「있었는데 없어졌다」는
     * 뜻이라 그 자체로 방송의 실재를 말하기 때문이다 — 그래서 판정 순서에서 자격 뒤에 온다.
     */
    public static class VodExpiredException extends RuntimeException {

        public VodExpiredException() {
            super("보관 기한이 지난 방송이다");
        }
    }

    /**
     * 503. auth에 자격을 못 물었다. <b>판정 불가는 거절이다</b>(PRD 결정) — 통과로 접으면
     * auth가 죽은 동안 남남이 남의 방송을 본다.
     */
    public static class AuthUnavailableException extends RuntimeException {

        public AuthUnavailableException() {
            super("자격을 확인하지 못했다");
        }
    }

    /**
     * 400. 요청 구간이 형식부터 틀렸다. {@code field}는 응답 본문에 실린다 — 어느 값을
     * 고쳐야 하는지 말해 주는 것이고, 여기엔 감출 것이 없다(사유를 감추는 것은 404 쪽이다).
     */
    public static class InvalidRangeException extends RuntimeException {
        private final String field;

        public InvalidRangeException(String field) {
            super("요청 구간이 잘못됐다: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private SegmentErrors() {
    }
}
