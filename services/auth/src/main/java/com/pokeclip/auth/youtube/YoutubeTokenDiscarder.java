package com.pokeclip.auth.youtube;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 토큰을 구글에 버린다(revoke). 버리기만 한다 — 저장은 모른다.
 *
 * <p>🔴 <b>부르는 자리는 둘이다</b>:
 * <ul>
 *   <li>{@link YoutubeTokenRefresher}의 갱신 거부 정리(BROKEN). 그 토큰은 이미 {@code invalid_grant}로 죽어 있어 살아 있는
 *       grant에 닿지 않는다.</li>
 *   <li>{@link YoutubeLinkWriter}의 <b>해제 정리</b>(사용자 {@code DELETE} · 탈퇴, 2026-10-24 사용자 재결정 · ADR-116, 유튜브
 *       정책 III.D.2.c). 구글 revoke는 계정 단위라 그 구글 계정의 다른 grant까지 죽는다는 대가를 받아들였다. 그 사이 같은
 *       회원이 다시 연동했으면 부르지 않는다(그쪽 javadoc).</li>
 * </ul>
 * 재연동 · 연동 실패 정리는 여전히 안 부른다(새 토큰이 죽는다 · 판별자가 없다).
 *
 * <p>best-effort다. 실패는 삼킨다 — 다시 보내려면 토큰 원문을 남겨야 하는데 처리방침은 해제하면 바로 지운다. 남은 grant는
 * 사용자가 구글 계정 설정에서 철회할 수 있다. 전용 정리 스레드에서 부른다.
 *
 * <p>판정: 4xx(429·408·invalid_client·403 할당량 제외, 그것들은 Unavailable)는 구글이 이 토큰을 모르거나
 * 이미 무효 = 무효화 목적 달성 → INFO. 5xx·타임아웃·형식 오류만 「구글에 살아있을 수 있다」 → WARN.
 * 4xx까지 WARN이면 갱신 거부마다 거짓 경보가 남아 진짜 고아를 못 가린다.
 */
@Component
@RequiredArgsConstructor
public class YoutubeTokenDiscarder {

    private static final Logger log = LoggerFactory.getLogger(YoutubeTokenDiscarder.class);

    private final YoutubeOAuthClient oauthClient;

    /**
     * <b>한 번만</b> 부른다 — 구글 revoke는 그 계정의 동의 전부를 죽이므로 둘째 호출은 이미 죽은 것을
     * 다시 부르는 것이라 무의미하고 로그만 시끄럽다(치지직은 쌍 단위라 둘 다 불렀다 —
     * {@code ChzzkTokenDiscarder}와 나란히 놓고 「빠뜨렸네」로 되돌리지 마라).
     *
     * <p>refresh 하나만 받는다. 갱신 거부 경로에는 access 원문이 없고(그 자리에서 읽지 않는다) 있어도 쓸모없다 —
     * 한 번이면 그 계정의 grant가 통째로 정리된다.
     */
    public void discard(Long userId, String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        try {
            oauthClient.revoke(refreshToken);
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

}
