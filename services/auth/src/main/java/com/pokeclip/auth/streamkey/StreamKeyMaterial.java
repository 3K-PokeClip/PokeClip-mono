package com.pokeclip.auth.streamkey;

/**
 * SecretStore에 한 덩어리로 들어가는 값.
 *
 * <p>stream_keys에는 해시만 두므로 두 번째 호출 때 streamid 원문을 줄 수 없다.
 * 교환(POK-72)이 그 원문을 내려줘야 해서 토큰도 여기 담는다. POK-67의
 * "해시만 DB에 둔다"는 stream_keys 표에 대한 제약이고, SecretStore는 암호화
 * 보관소라 다른 곳이다.
 *
 * <p><b>이 record를 {}에 통째로 넣지 않는다.</b> 자동 toString()이 passphrase를
 * 찍는다. SecretLeakTest가 "StreamKeyMaterial[" 문자열을 금지해 못박는다.
 */
public record StreamKeyMaterial(String streamToken, String passphrase) {

    private static final String SEPARATOR = ":";

    String serialize() {
        return streamToken + SEPARATOR + passphrase;
    }

    static StreamKeyMaterial deserialize(String stored) {
        int at = stored.indexOf(SEPARATOR);
        if (at < 0) {
            throw new IllegalStateException("SecretStore에 저장된 형식이 아니다");
        }
        return new StreamKeyMaterial(stored.substring(0, at), stored.substring(at + 1));
    }

    public StreamId streamId() {
        return new StreamId(streamToken);
    }
}
