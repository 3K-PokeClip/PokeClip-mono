package com.pokeclip.upload.auth;

import com.pokeclip.upload.work.InternalHttp;
import com.pokeclip.upload.work.InternalHttp.Reply;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * auth {@code POST /internal/youtube-link/resolve {userId}}. 늘 200이고 {@code valid}로 가른다(services/README 「resolve 계약」).
 * 🔴 토큰은 여기서 받아 바로 쓰고 버린다. 어디에도 적거나 찍지 않는다.
 */
public class YoutubeTokenClient {

    private final InternalHttp http;
    private final String baseUrl;

    public YoutubeTokenClient(InternalHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    /**
     * @param reason 거절 사유({@code NOT_LINKED}·{@code UNLINKED}·{@code BROKEN}·{@code REFRESH_UNAVAILABLE}). 받았으면 null
     */
    public record Token(String accessToken, String reason) {

        /** 강제 갱신을 청했는데 401을 받은 그 토큰이 다시 왔다(지문 칸을 모르는 옛 auth). 잠시 뒤 다시 = 지금 동작. */
        public static final String NOT_REFRESHED = "NOT_REFRESHED";

        public boolean valid() {
            return accessToken != null;
        }

        /** 잠시 뒤 다시 물으면 될 거절. 나머지는 사람이 다시 연동해야 풀린다. */
        public boolean transientRefusal() {
            return "REFRESH_UNAVAILABLE".equals(reason) || NOT_REFRESHED.equals(reason);
        }

        @Override
        public String toString() {
            return "Token[valid=" + valid() + ", reason=" + reason + "]";
        }
    }

    /** @throws com.pokeclip.upload.work.Unavailable auth가 답을 안 준다 */
    public Token resolve(long userId) {
        return ask(Map.of("userId", userId));
    }

    /**
     * 유튜브가 401을 준 토큰 대신 새 토큰(POK-250). 그 토큰의 SHA-256(소문자 16진)을 실어 보내면 auth가 지금 토큰이 그것일 때만 수명과
     * 무관하게 갱신한다(이미 누가 갱신했으면 지금 토큰을 준다). 원문은 보내지 않는다. 받은 토큰이 거절된 그 토큰이면(칸을 모르는 옛 auth)
     * {@link Token#NOT_REFRESHED}로 돌려준다.
     *
     * @throws com.pokeclip.upload.work.Unavailable auth가 답을 안 준다
     */
    public Token resolveAfterRejection(long userId, String rejectedAccessToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("rejectedTokenSha256", sha256(rejectedAccessToken));
        Token token = ask(body);
        return rejectedAccessToken.equals(token.accessToken()) ? new Token(null, Token.NOT_REFRESHED) : token;
    }

    private Token ask(Map<String, Object> request) {
        Reply reply = http.post(baseUrl + "/internal/youtube-link/resolve", request);
        if (reply.status() != 200 || reply.body() == null) {
            throw new IllegalStateException("auth resolve가 " + reply.status() + "을 줬다");
        }
        if (reply.body().path("valid").asBoolean()) {
            return new Token(reply.body().path("accessToken").asString(), null);
        }
        return new Token(null, reply.body().path("reason").asString("UNKNOWN"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }
}
