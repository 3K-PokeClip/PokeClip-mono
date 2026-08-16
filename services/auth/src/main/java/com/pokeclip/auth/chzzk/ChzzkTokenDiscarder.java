package com.pokeclip.auth.chzzk;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 우리가 더는 안 쓸 토큰을 치지직에 버린다(revoke 2회). 버리기만 한다 — 저장은 모른다.
 *
 * <p>best-effort다. 실패는 삼킨다 — 안 버려지면 치지직에 살아있는 채 방치되는데
 * 그것이 요청 실패(409·400)를 막을 이유는 못 된다. 트랜잭션 밖 또는 전용 정리 스레드에서 부른다.
 *
 * <p>실측(2026-08-17): 치지직은 access·refresh를 <b>한 세트로</b> 무효화한다 — 하나를 revoke하면 200이고
 * 그 즉시 다른 하나도 죽어(me 401), 둘째 revoke는 {@code 401 INVALID_TOKEN}을 받는다. 이미 죽은 토큰을 다시
 * revoke해도 401. 문서화되지 않은 동작이라 여기에 기대지 않고 <b>둘 다 부른다</b> — 그래서 둘째의 4xx는 정상이다.
 * 4xx(429·408 제외 — 그 둘은 Unavailable → WARN orphan_token causeType=Http429/408) = 치지직이 이 토큰을 모르거나
 * 이미 무효 = 무효화 목적 달성 → INFO. 5xx·타임아웃·형식 오류만 "치지직에 살아있을 수 있다" → WARN {@code orphan_token}. 4xx까지 WARN이면 해제·재연동·갱신 거부마다 거짓 경보가 남아
 * 진짜 고아를 못 가린다.
 */
@Component
@RequiredArgsConstructor
public class ChzzkTokenDiscarder {

    private static final Logger log = LoggerFactory.getLogger(ChzzkTokenDiscarder.class);

    private final ChzzkOAuthClient oauthClient;

    public void discard(Long userId, String accessToken, String refreshToken) {
        for (var t : List.of(Map.entry(accessToken, "access_token"), Map.entry(refreshToken, "refresh_token"))) {
            try {
                oauthClient.revoke(t.getKey(), t.getValue());
            } catch (ChzzkRejectedException e) {
                log.info("auth.chzzk.link.token_already_dead userId={} hint={} status={}",
                        userId, t.getValue(), e.status());
            } catch (ChzzkUnavailableException e) {
                // 원인(Http503·타임아웃 타입·MalformedResponse)을 남긴다 — 예외 타입 이름보다 관측에 쓸모 있다.
                log.warn("auth.chzzk.link.orphan_token userId={} hint={} causeType={}",
                        userId, t.getValue(), e.causeType());
            } catch (RuntimeException e) {
                // 원인은 타입 이름만 — 메시지에 응답 본문이 붙을 수 있다.
                log.warn("auth.chzzk.link.orphan_token userId={} hint={} causeType={}",
                        userId, t.getValue(), e.getClass().getSimpleName());
            }
        }
    }
}
