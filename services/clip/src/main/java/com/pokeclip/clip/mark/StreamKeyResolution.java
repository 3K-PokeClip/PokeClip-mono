package com.pokeclip.clip.mark;

/**
 * auth에 스트림키를 물은 결과. {@link Kind#VALID}일 때만 {@code userId}·{@code passphrase}가 찬다.
 *
 * <p>{@code userId}는 <b>문자열</b>이다: 방송 명부의 {@code streamer_id}가 문자열이라 여기서 바꿔 둔다.
 * auth는 숫자로 준다.
 *
 * <p>🔴 <b>passphrase는 비밀이다.</b> record 기본 {@code toString}은 모든 칸을 찍으므로 덮는다 :
 * 이 결과가 로그 한 줄이나 예외 메시지에 통째로 들어가는 날 그대로 새지 않게.
 */
public record StreamKeyResolution(Kind kind, String userId, String passphrase) {

    public enum Kind {
        /** 키가 있고 살아 있다. */
        VALID,
        /** auth가 streamid 모양을 못 읽었다(400 invalid_request). */
        MALFORMED,
        /** 없는 키이거나 폐기한 키다. 둘을 안 가른다: 가르면 키의 존재가 샌다(401). */
        REJECTED,
        /** auth를 못 물었거나 답을 못 믿는다. 키가 틀렸다는 뜻이 아니다(503, 플러그인이 다시 보낸다). */
        UNAVAILABLE
    }

    private static final StreamKeyResolution MALFORMED_RESULT = new StreamKeyResolution(Kind.MALFORMED, null, null);
    private static final StreamKeyResolution REJECTED_RESULT = new StreamKeyResolution(Kind.REJECTED, null, null);
    private static final StreamKeyResolution UNAVAILABLE_RESULT =
            new StreamKeyResolution(Kind.UNAVAILABLE, null, null);

    static StreamKeyResolution valid(String userId, String passphrase) {
        return new StreamKeyResolution(Kind.VALID, userId, passphrase);
    }

    static StreamKeyResolution malformed() {
        return MALFORMED_RESULT;
    }

    static StreamKeyResolution rejected() {
        return REJECTED_RESULT;
    }

    static StreamKeyResolution unavailable() {
        return UNAVAILABLE_RESULT;
    }

    @Override
    public String toString() {
        return "StreamKeyResolution[kind=" + kind + ", userId=" + userId + "]";
    }
}
