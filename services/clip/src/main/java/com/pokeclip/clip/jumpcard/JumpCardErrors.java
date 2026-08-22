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

    private JumpCardErrors() {
    }
}
