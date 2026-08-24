package com.pokeclip.auth.youtube;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 우리가 더는 안 쓸 토큰을 구글에 버린다. 버리기만 한다 — 저장은 모른다.
 *
 * <p>best-effort다. 실패는 삼킨다 — 안 버려지면 구글에 살아있는 채 방치되는데 그것이 요청 실패(409·400)를
 * 막을 이유는 못 된다. 트랜잭션 밖 또는 전용 정리 스레드에서 부른다.
 *
 * <p><b>치지직({@code ChzzkTokenDiscarder})과 모양이 다르다 — 나란히 놓고 「빠뜨렸네」로 되돌리지 마라.</b>
 *
 * <p><b>왜 한 번만 부르나</b> — 구글 revoke는 「그 토큰 쌍」이 아니라 <b>그 사용자가 이 프로젝트(우리 앱)에
 * 준 동의 전부</b>를 무효화한다(공식 문서: 발급된 모든 access·refresh가 죽는다). 치지직은 쌍 단위라
 * access·refresh를 둘 다 불렀지만, 구글에서 둘째 호출은 이미 죽은 것을 다시 부르는 것이라 무의미하고
 * 로그만 시끄럽다. refresh가 있으면 그것을, 없으면 access를 <b>한 번</b> 부른다.
 *
 * <p>판정은 치지직과 같다 — 4xx(429·408·invalid_client·403 할당량 제외, 그 넷은 Unavailable)는 구글이 이 토큰을
 * 모르거나 이미 무효 = 무효화 목적 달성 → INFO. 5xx·타임아웃·형식 오류만 「구글에 살아있을 수 있다」 → WARN.
 * 4xx까지 WARN이면 해제·갱신 거부마다 거짓 경보가 남아 진짜 고아를 못 가린다.
 */
@Component
@RequiredArgsConstructor
public class YoutubeTokenDiscarder {

    private static final Logger log = LoggerFactory.getLogger(YoutubeTokenDiscarder.class);

    private final YoutubeOAuthClient oauthClient;
    private final YoutubeDiscardGuard guard;

    /**
     * 한 번만 부른다(위 javadoc). <b>부르는 자리는 둘뿐이다</b> — 사용자 해제(DELETE)와 갱신 거부 정리(BROKEN).
     * 재연동과 연동 실패 정리는 여기로 오지 않거나({@link #discardIfNoLiveLink}) 아예 안 부른다.
     */
    public void discard(Long userId, String accessToken, String refreshToken) {
        String token = refreshToken != null ? refreshToken : accessToken;
        if (token == null) {
            return;
        }
        try {
            oauthClient.revoke(token);
        } catch (YoutubeRejectedException e) {
            log.info("auth.youtube.link.token_already_dead userId={} status={}", userId, e.status());
        } catch (YoutubeUnavailableException e) {
            // 원인(Http503·타임아웃 타입)을 남긴다 — 예외 타입 이름보다 관측에 쓸모 있다. 토큰 값은 안 남긴다.
            log.warn("auth.youtube.link.orphan_token userId={} causeType={}", userId, e.causeType());
        } catch (RuntimeException e) {
            // 원인은 타입 이름만 — 메시지에 응답 본문이 붙을 수 있다.
            log.warn("auth.youtube.link.orphan_token userId={} causeType={}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * 실패·정리 진입점 — 교환은 성공했는데 그 뒤(scope 대조·채널 0개·409·5xx)가 실패한 자리와
     * 사용자 해제의 커밋 뒤 정리에서 부른다.
     *
     * <p><b>왜 조건부인가</b> — revoke가 그 <b>구글 계정</b>의 동의 <b>전체</b>를 죽이므로, 실패한 시도의
     * 토큰을 버리는 순간 <b>멀쩡한 연동까지 죽는다</b>(표는 ACTIVE인데 토큰만 죽어 다음 resolve가 BROKEN).
     * 버려진 access는 <b>1시간이면 스스로 죽는다</b> — 살아있는 연동을 죽이는 대가가 훨씬 크다.
     *
     * <p>판정은 {@link YoutubeDiscardGuard}가 락 안에서 한다(진행 중인 재연동과 남이 쓰는 채널까지 본다).
     * <b>revoke는 그 트랜잭션 밖에서</b> 돈다 — 커넥션을 쥔 채 외부 HTTP를 기다리지 않는다.
     *
     * @param channelId 이 토큰이 가리키는 채널. 모르면 null(교환 실패·scope 부족 — 채널을 아직 못 읽었다)
     */
    public void discardIfNoLiveLink(Long userId, String channelId, String accessToken, String refreshToken) {
        if (guard.blocksDiscard(userId, channelId)) {
            log.info("auth.youtube.link.discard_skipped userId={} reason=LIVE_LINK", userId);
            return;
        }
        discard(userId, accessToken, refreshToken);
    }
}
