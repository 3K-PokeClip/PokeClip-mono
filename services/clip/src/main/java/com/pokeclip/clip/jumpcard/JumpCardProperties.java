package com.pokeclip.clip.jumpcard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code @EnableConfigurationProperties}를 붙이지 않는다 — {@code ClipApplication}에
 * {@code @ConfigurationPropertiesScan}이 이미 있어 스캔만으로 붙는다.
 *
 * <p><b>이 서버는 설정 오류를 두 방식으로 다룬다</b> — 덮기({@link
 * com.pokeclip.clip.jumpcard.stream.StreamProperties})와 던지기({@code IntakeProperties}·여기).
 * 불일치가 아니라 <b>클래스마다 이미 서 있던 정책을 따른 결과</b>다. 통일은 별도 카드다.
 */
@ConfigurationProperties(prefix = "pokeclip.jump-card")
public record JumpCardProperties(Duration claimTtl) {

    private static final Duration DEFAULT_CLAIM_TTL = Duration.ofMinutes(30);

    /**
     * 설정이 없어도 부팅한다. 비밀이 아니라 숫자 설정이라 기본값을 두는 것이 옳다.
     * 다만 <b>적어 놓고 틀린 것</b>은 거부한다 — 안 적은 것과 잘못 적은 것은 다르다.
     *
     * <p>🔴 <b>초 단위로 떨어질 것까지 요구하는 이유.</b> {@link JumpCardService}가
     * {@code claimTtl().toSeconds()}로 <b>잘라서</b> SQL에 넘긴다. 자르기 <b>전</b> 값만 보면
     * {@code PT0.5S}가 「양수」로 통과한 뒤 SQL에서 0이 되어 {@code PT0S}와 완전히 같아진다 —
     * {@code claimed_at < now() - make_interval(secs => 0)}은 방금 집은 점유도 만료로 읽는다.
     * 2026-08-23 재현: u-A가 집은 그 자리에서 u-B가 그대로 가져갔다(PR #111 봇 지적 ②).
     * <b>양수 검사만으로는 못 막는다.</b> 자를 것이 없게 만들어 자리를 아예 없앤다.
     *
     * <p>덤으로 <b>응답과 SQL이 다른 값을 쓰는 것</b>도 사라진다. {@link JumpCardSnapshot}은
     * 원본 {@code Duration}으로 {@code claimExpiresAt}을 만들고 SQL은 자른 값으로 판정한다 —
     * {@code PT0.5S}에서 응답은 「0.5초 뒤까지 내 것」이라 말하는데 SQL은 이미 만료로 봤고,
     * {@code PT-60S}에서는 <b>집자마자 이미 지난 시각</b>을 만료로 줬다(같은 재현).
     *
     * <p>{@code getNano()}로 보는 이유: {@code toSeconds()}가 버리는 것이 정확히 그 자리다.
     * {@code PT90.5S}처럼 1초를 넘겨도 초 아래가 남으면 같은 자리이므로 함께 막는다.
     */
    public JumpCardProperties {
        if (claimTtl == null) {
            claimTtl = DEFAULT_CLAIM_TTL;
        } else if (claimTtl.isZero() || claimTtl.isNegative()) {
            throw new IllegalStateException(
                    "pokeclip.jump-card.claim-ttl은 0보다 커야 한다. 지금 값: " + claimTtl);
        } else if (claimTtl.getNano() != 0) {
            throw new IllegalStateException(
                    "pokeclip.jump-card.claim-ttl은 초 단위로 떨어져야 한다"
                    + "(JumpCardService가 toSeconds()로 잘라 SQL에 넘긴다). 지금 값: " + claimTtl);
        }
    }
}
