package com.pokeclip.upload.auth;

import com.pokeclip.upload.work.InternalHttp;
import com.pokeclip.upload.work.InternalHttp.Reply;

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

        public boolean valid() {
            return accessToken != null;
        }

        /** 잠시 뒤 다시 물으면 될 거절. 나머지는 사람이 다시 연동해야 풀린다. */
        public boolean transientRefusal() {
            return "REFRESH_UNAVAILABLE".equals(reason);
        }

        @Override
        public String toString() {
            return "Token[valid=" + valid() + ", reason=" + reason + "]";
        }
    }

    /** @throws com.pokeclip.upload.work.Unavailable auth가 답을 안 준다 */
    public Token resolve(long userId) {
        Reply reply = http.post(baseUrl + "/internal/youtube-link/resolve", Map.of("userId", userId));
        if (reply.status() != 200 || reply.body() == null) {
            throw new IllegalStateException("auth resolve가 " + reply.status() + "을 줬다");
        }
        if (reply.body().path("valid").asBoolean()) {
            return new Token(reply.body().path("accessToken").asString(), null);
        }
        return new Token(null, reply.body().path("reason").asString("UNKNOWN"));
    }
}
