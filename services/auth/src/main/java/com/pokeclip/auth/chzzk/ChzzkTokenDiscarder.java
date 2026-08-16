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
 * <p>best-effort다. 실패는 삼키고 WARN 한 줄 — 안 버려지면 치지직에 살아있는 채 방치되는데
 * 그것이 요청 실패(409·400)를 막을 이유는 못 된다. 트랜잭션 밖 또는 afterCommit에서 부른다.
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
            } catch (RuntimeException e) {
                // 원인은 타입 이름만 — 메시지에 응답 본문이 붙을 수 있다.
                log.warn("auth.chzzk.link.orphan_token userId={} hint={} causeType={}",
                        userId, t.getValue(), e.getClass().getSimpleName());
            }
        }
    }
}
