package com.pokeclip.auth.retention;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 보관 기한 청소(POK-89). 표 셋의 보관 기간과 청소 틱의 주기·상한.
 *
 * <p>값은 application.yml 기본값이고 환경변수로 빼지 않았다(PRD 결정) — {@code ${VAR:}} 모양이면
 * 「없으면 부팅 실패」가 되고 compose·.env.dev.example까지 셋을 같이 고쳐야 한다.
 * 켜기/끄기만 기존 스케줄러 둘과 같은 모양이다(프로퍼티 없으면 켜짐).
 *
 * <p>🔴 <b>{@code refreshTokensKeepFor}가 곧 도난 토큰 재사용 봉쇄의 창이다.</b> 회수된 행이 표에 남아 있어야
 * {@code TokenService.rotate}가 「이미 쓴 토큰이 또 왔다」를 잡는다. 하한은 {@code pokeclip.jwt.refresh-token-ttl}이고
 * {@link RetentionKeepForCheck}가 부팅에서 본다(짧으면 만료 전 옛 토큰의 재사용이 봉쇄 밖으로 빠진다).
 * 그 검사는 {@code enabled=false}면 함께 꺼진다 — 청소가 안 돌면 회수 행을 지우는 코드 자체가 없다. 이 record는
 * {@code JwtProperties}를 모르므로 그 검사는 여기 없다. 값의 근거·잃는 것은 README 「운영 전 잔불 정리」 절.
 *
 * <p>양수 검증을 컴팩트 생성자에서 하는 이유는 {@code Duration}에 걸 표준 제약이 없어서다.
 * 0이나 음수가 조용히 바인딩되면 — 주기 0은 틱이 쉼 없이 돌고, 보관 0은 방금 회수한 토큰을 바로 지운다.
 * <b>그 밖의 하한</b>(재회전 유예·교환 한도 창)은 안 본다 — 양수 검증뿐이다(PRD 결정). refresh는 10초
 * ({@code TokenService.REUSE_GRACE}), 시도 기록은 1분(교환 한도 창) 아래로 내리면 그 판정을 청소가 먹는데,
 * 그 둘은 운영자가 지킨다. 코드 보관은 내려도 판정이 안 바뀐다 — 살아있는 코드는 만료 전이라
 * 청소 대상이 아니다(감사 2회차 실측). 숫자와 증상은 application.yml의 {@code retention} 주석에 있다.
 */
@ConfigurationProperties(prefix = "pokeclip.retention")
@Validated
public record RetentionProperties(
        boolean enabled,
        @NotNull Duration interval,
        @Min(1) int batchLimit,
        @NotNull Duration refreshTokensKeepFor,
        @NotNull Duration pairingAttemptsKeepFor,
        @NotNull Duration pairingCodesKeepFor) {

    public RetentionProperties {
        requirePositive(interval, "interval");
        requirePositive(refreshTokensKeepFor, "refresh-tokens-keep-for");
        requirePositive(pairingAttemptsKeepFor, "pairing-attempts-keep-for");
        requirePositive(pairingCodesKeepFor, "pairing-codes-keep-for");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalStateException("pokeclip.retention." + name + "은 0보다 커야 한다");
        }
    }
}
