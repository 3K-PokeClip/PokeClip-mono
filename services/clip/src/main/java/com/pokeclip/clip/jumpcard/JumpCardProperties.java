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
     * <b>점유 시한의 상한. 기술 한계로 잡되 그 숫자를 그대로 쓰지 않는다.</b>
     *
     * <p>왜 상한이 필요한가 — 하한만 두면 <b>아주 큰 값이 통과하고 부팅도 멀쩡히 되는데</b>
     * ({@code PT2562047788015H}로 실기동: {@code Started ClipApplication in 2.267 seconds})
     * 그 뒤 <b>모든 claim 요청이 500이 된다</b>(2026-08-24 재현, PR #114 봇 지적 ②):
     * {@code ERROR: interval out of range} · SQLState <b>22008</b> →
     * {@code DataIntegrityViolationException}. 설정 오류가 「데이터 무결성 위반」으로 나온다.
     *
     * <p><b>잘린 값을 받는 하류가 셋이고 한계가 서로 다르다</b>(전부 2026-08-24 실측·이분탐색).
     * 다음 사람이 다시 재지 않게 셋 다 남긴다:
     * <table border="1">
     *   <caption>claimTtl 하류의 한계</caption>
     *   <tr><th>하류</th><th>통과 최대(초)</th><th>터질 때</th></tr>
     *   <tr><td>{@code now() - make_interval(secs => X)} (점유 SQL)</td>
     *       <td><b>212,654,337,917</b> (약 6738.6년)</td><td>{@code ERROR: timestamp out of range}</td></tr>
     *   <tr><td>{@code make_interval(secs => X)} 자체</td>
     *       <td>9,223,372,036,854</td><td>{@code ERROR: interval out of range}</td></tr>
     *   <tr><td>{@code claimedAt.plus(ttl)} ({@link JumpCardSnapshot#of})</td>
     *       <td>31,556,888,076,868,416</td>
     *       <td>{@code DateTimeException: Instant exceeds minimum or maximum instant}</td></tr>
     * </table>
     *
     * <p>🔴 <b>셋이 겹쳐 있어 claim 경로에서는 <u>언제나 DB가 먼저</u> 터진다.</b> 봇은
     * 「DB 오버플로 <b>또는</b> {@code DateTimeException}」이라고 적었는데, 봇이 든 값
     * ({@code PT2562047788015H} = 9,223,372,036,854,000초)에서 {@code Instant.plus}는
     * <b>안 던진다</b> — 서기 292,279,051년짜리 {@code Instant}를 멀쩡히 돌려준다(실측).
     * 그리고 <b>OR 단락 평가가 구제하지 않는다</b> — {@code claimed_by IS NULL}인 행에서도 터진다.
     *
     * <p>🔴 <b>{@code Instant.plus}는 claim이 아니라 <u>읽기</u>에서 터진다.</b> TTL이 그 한계를
     * 넘으면 이미 집힌 카드가 있는 방송의 <b>SSE 연결이 통째로 500</b>이 된다(실측:
     * {@code at JumpCardSnapshot.of(JumpCardSnapshot.java:39)}). 아무도 claim을 안 눌러도 그렇다.
     *
     * <p><b>그래서 100년이다.</b> 가장 좁은 한계(약 6738년)를 그대로 쓰지 않는 이유는 그것이
     * <b>PostgreSQL 구현 세부</b>라 버전이 바뀌면 움직일 수 있어서다. 100년은 그보다 <b>67배</b>
     * 아래라 DB 쪽이 흔들려도 안 걸리고, <b>읽는 사람이 왜 그 값인지 안다</b> — 편집자가 카드
     * 하나를 100년 붙들 일은 없다. 「기술 한계의 몇 %」 같은 값은 다음 사람이 근거를 못 찾는다.
     */
    private static final Duration MAX_CLAIM_TTL = Duration.ofDays(36_500);

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
        } else if (claimTtl.compareTo(MAX_CLAIM_TTL) > 0) {
            // compareTo로 잰다 — toSeconds()로 재도 되지만(Duration은 내부가 이미 초라 안 던진다)
            // 비교 대상이 Duration이므로 단위를 오가지 않는 쪽이 읽기 쉽다.
            throw new IllegalStateException(
                    "pokeclip.jump-card.claim-ttl은 " + MAX_CLAIM_TTL + "(100년) 이하여야 한다"
                    + "(그 위는 점유 SQL이 DB에서 터져 claim이 전부 500이 된다). 지금 값: " + claimTtl);
        }
    }
}
