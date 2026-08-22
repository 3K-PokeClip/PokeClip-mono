package com.pokeclip.clip.jumpcard;

/**
 * 카드 관련 예외를 한 파일에 모은다. 흩어 두면 어떤 상태 코드가 나가는지 보려고
 * 파일 넷을 열어야 한다. 매핑은 {@code JumpCardExceptionHandler} 하나가 한다.
 */
public final class JumpCardErrors {

    /** 404. FK 위반(500)으로 두지 않는 이유 — 판별기가 재시도 상한을 세려면 404를 받아야 한다. */
    public static class BroadcastNotFoundException extends RuntimeException {
        private final String streamId;

        public BroadcastNotFoundException(String streamId) {
            super("없는 방송이다: " + streamId);
            this.streamId = streamId;
        }

        public String streamId() {
            return streamId;
        }
    }

    /**
     * 400. <b>{@code IllegalArgumentException}을 통째로 400으로 잡지 않으려고</b> 좁혀 던지는 타입이다 —
     * 내부 버그로 나온 같은 예외가 「요청이 잘못됐다」로 둔갑하면 판별기가 재시도를 멈춘다.
     */
    public static class InvalidHighlightException extends RuntimeException {
        private final String field;

        public InvalidHighlightException(String field) {
            super("요청이 잘못됐다: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** 404. 카드 자체가 없다. */
    public static class JumpCardNotFoundException extends RuntimeException {
        private final long id;

        public JumpCardNotFoundException(long id) {
            super("없는 카드다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    /**
     * 409. 남이 잡고 있다. <b>현재 카드를 함께 싣는다</b> — 웹이 "누가 잡고 있는지"를
     * 바로 보여줘야 편집자가 새로고침 없이 상황을 안다.
     */
    public static class ClaimedByOtherException extends RuntimeException {
        private final transient JumpCardSnapshot current;

        public ClaimedByOtherException(JumpCardSnapshot current) {
            super("남이 잡고 있는 카드다: " + current.id());
            this.current = current;
        }

        public JumpCardSnapshot current() {
            return current;
        }
    }

    /** 403. 남이 잡은 카드를 놓으려 했다. 아무도 안 잡은 카드를 놓는 것은 성공이다(멱등). */
    public static class NotClaimOwnerException extends RuntimeException {
        private final long id;

        public NotClaimOwnerException(long id) {
            super("내가 잡은 카드가 아니다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    private JumpCardErrors() {
    }
}
