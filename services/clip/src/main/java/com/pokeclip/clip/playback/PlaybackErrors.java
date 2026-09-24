package com.pokeclip.clip.playback;

/** 출입증 문의 거절 — 자격 관련은 {@code AccessErrors}를 그대로 쓰고, 여기는 서명 쪽 하나뿐이다. */
public final class PlaybackErrors {

    /**
     * 503. 서명 재료가 없거나(로컬·설정 누락) 방송 번호가 정책에 넣을 수 없는 모양이다.
     * <b>404로 접지 않는다</b> — 화면이 「없는 방송」으로 단정하면 설정을 채운 뒤에도 다시 안 누른다.
     */
    public static class SigningUnavailableException extends RuntimeException {

        public SigningUnavailableException(String reason) {
            super("출입증을 만들 수 없다: " + reason);
        }
    }

    private PlaybackErrors() {
    }
}
