package com.pokeclip.clip.playback;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 출입증 한 장 = 경로 종류마다 쿠키 셋 하나. 종류 셋({@code live}·{@code dvr}·{@code vod})이라 쿠키 아홉 장이다.
 *
 * <p><b>왜 셋으로 나누나</b> — 정책 {@code Resource}에 앞 와일드카드({@code /*&#47;{streamId}/*})를 쓰면 CloudFront는
 * 경로 구분자를 안 보고 문자열로만 맞추므로 {@code /live/남의방송/x?q=/내방송/} 같은 URL이 통과한다(봇 리뷰 2판, claude).
 * 종류마다 {@code {base}/{kind}/{streamId}/*}로 고정하면 앞 와일드카드가 없다. 쿠키 이름 셋은 CloudFront가 정한 것이라
 * 세 정책을 같은 이름으로 나란히 두려면 <b>쿠키 {@code Path}로 갈라야</b> 하고, 브라우저는 요청 경로에 맞는 쿠키만 보낸다.
 *
 * @param scopes 종류 순서 그대로({@code live}·{@code dvr}·{@code vod})
 */
public record PlaybackAccess(String streamId, Instant expiresAt, List<Scope> scopes) {

    /**
     * @param cookiePath {@code Set-Cookie}의 {@code Path} — 이 경로 아래 요청에만 브라우저가 이 셋을 붙인다
     * @param resource   정책의 {@code Resource}. {@code cookiePath}와 같은 뿌리다 — 둘이 갈리면 쿠키는 가는데 정책이 안 맞는다
     */
    public record Scope(String kind, String cookiePath, String resource, Map<String, String> cookies) {
    }
}
