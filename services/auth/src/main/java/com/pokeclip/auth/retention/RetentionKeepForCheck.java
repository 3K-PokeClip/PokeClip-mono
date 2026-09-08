package com.pokeclip.auth.retention;

import com.pokeclip.auth.token.JwtProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 부팅 검증 하나: 회수 행 보관({@code pokeclip.retention.refresh-tokens-keep-for})이 갱신 토큰 수명
 * ({@code pokeclip.jwt.refresh-token-ttl})보다 짧으면 부팅을 거부한다(사용자 결정 2026-09-03).
 *
 * <p>왜: 회수 행 보관은 재사용 봉쇄의 창이다. 수명보다 짧으면 「아직 살아 있어야 할 옛 토큰」의 행이 먼저 지워져
 * 그 재사용이 봉쇄(REUSED, 그 회원 세션 전부 끊음) 대신 UNKNOWN(401뿐)으로 빠진다. 값 둘이 서로 다른 record에
 * 있어 한쪽만 올리기 쉽다(ttl만 30일로 올리면 15~30일째 재사용이 그 자리다).
 *
 * <p>{@code RetentionProperties} 안에 넣지 않는다: 그 record는 {@code JwtProperties}를 모르고, 그래야
 * {@code RetentionPropertiesTest}의 ContextRunner가 그대로 산다. Phase 1의 「하한은 양수만」은 10초·1분 코드 상수
 * 이야기였고 yml 값 둘 사이 관계는 별개다. 값 둘은 시크릿이 아니라 메시지에 싣는다.
 *
 * <p><b>끄기 스위치가 이 검사도 끈다</b>(스케줄러와 같은 조건 애노테이션 — 쌍둥이를 한 모양으로 둔다).
 * {@code enabled=false}면 회수 행을 지우는 코드가 아예 안 돌아 이 검사가 막으려는 것(「행이 먼저 지워진다」)이
 * 일어날 수 없다. 그런데 검사만 남으면 README가 안내하는 끄기를 따라도 부팅이 거부된다 — 청소가 장애를 낼 때
 * 끄고 재기동하는 길이 이 검사 하나에 막힌다(리뷰 라운드 2 재현 R4).
 * {@code matchIfMissing = true}도 스케줄러와 같다: 프로퍼티를 빠뜨려도 검사가 조용히 사라지지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "pokeclip.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionKeepForCheck {

    private final RetentionProperties retention;
    private final JwtProperties jwt;

    public RetentionKeepForCheck(RetentionProperties retention, JwtProperties jwt) {
        this.retention = retention;
        this.jwt = jwt;
    }

    @PostConstruct
    void check() {
        Duration keepFor = retention.refreshTokensKeepFor();
        Duration ttl = jwt.refreshTokenTtl();
        if (keepFor.compareTo(ttl) < 0) {
            throw new IllegalStateException("pokeclip.retention.refresh-tokens-keep-for(" + describe(keepFor)
                    + ")가 pokeclip.jwt.refresh-token-ttl(" + describe(ttl) + ")보다 짧다: 만료 전 옛 토큰의 재사용이 봉쇄 밖으로 빠진다");
        }
    }

    /**
     * {@code Duration.toString()}만 쓰면 yml에 {@code P14D}라고 적힌 값이 {@code PT336H}로 찍혀 <b>운영자가
     * 파일에서 그 값을 못 찾는다</b>(리뷰 라운드 2). 그래서 일수를 함께 싣는다. {@code toDays()}는 내림이라
     * 하루 미만은 {@code 0일}로 보이는데, 그런 값은 애초에 이 거부에 걸리는 쪽이라 오해가 아니라 신호다.
     */
    private static String describe(Duration value) {
        return value + "=" + value.toDays() + "일";
    }
}
