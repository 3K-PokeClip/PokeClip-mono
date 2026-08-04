package com.pokeclip.auth.streamkey;

import com.pokeclip.auth.support.CrockfordBase32;

import java.util.Optional;

/**
 * SRT Access Control 문법의 streamid (ADR-019).
 * {@code #!::r=<26자 Crockford>,m=publish}
 *
 * <p>형식의 주인이 auth다. Media는 SRT에서 읽은 원문을 그대로 보내고 파싱은
 * 여기서 한다 — 그러지 않으면 같은 규칙이 Go와 Java 두 곳에 생긴다.
 *
 * <p>ADR-019: streamid는 비밀이 아니라 공개 식별자다. 평문으로 전송된다.
 */
public record StreamId(String token) {

    private static final String PREFIX = "#!::";
    private static final int TOKEN_LENGTH = 26;

    public static Optional<StreamId> parse(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) {
            return Optional.empty();
        }

        String resource = null;
        boolean publishMode = false;

        for (String part : raw.substring(PREFIX.length()).split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                return Optional.empty();
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);

            if ("r".equals(key)) {
                resource = value;
            } else if ("m".equals(key)) {
                publishMode = "publish".equals(value);
            }
        }

        if (resource == null || !publishMode || resource.length() != TOKEN_LENGTH) {
            return Optional.empty();
        }
        try {
            // 우리가 발급한 형태 그대로인지까지 본다. Media가 보내는 값은 사람이
            // 친 것이 아니므로 정규화로 고쳐서 받아주지 않는다.
            if (!CrockfordBase32.normalize(resource).equals(resource)) {
                return Optional.empty();
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new StreamId(resource));
    }

    public String toSrtFormat() {
        return PREFIX + "r=" + token + ",m=publish";
    }
}
