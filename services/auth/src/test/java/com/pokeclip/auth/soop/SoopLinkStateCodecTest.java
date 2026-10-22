package com.pokeclip.auth.soop;

import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code ChzzkLinkStateCodecTest}의 쌍둥이 + 「치지직 state는 SOOP에서 거절」(계획 검증 M7). */
class SoopLinkStateCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final SecretKey KEY = key("test-only-secret-key-at-least-32-bytes-long!!");

    private final SoopLinkStateCodec codec = new SoopLinkStateCodec(KEY, Duration.ofMinutes(10));

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256");
    }

    @Test
    void 발급한_state는_같은_사용자로_검증된다() {
        assertThat(codec.matches(codec.issue(7L, NOW), 7L, NOW.plusSeconds(60))).isTrue();
    }

    /** CSRF: 공격자가 자기 state가 든 링크로 피해자 계정에 자기 방송국을 붙이는 경로. */
    @Test
    void 다른_사용자의_state는_거부된다() {
        assertThat(codec.matches(codec.issue(7L, NOW), 8L, NOW)).isFalse();
    }

    @Test
    void 만료된_state는_거부된다() {
        assertThat(codec.matches(codec.issue(7L, NOW), 7L, NOW.plus(Duration.ofMinutes(11)))).isFalse();
    }

    /** base64url 첫 글자를 뒤집는다(마지막 글자는 유효 비트가 4개라 16분의 1로 헛통과, auth/CLAUDE.md). */
    @Test
    void 서명이_한_글자라도_다르면_거부된다() {
        String state = codec.issue(7L, NOW);
        int dot = state.lastIndexOf('.');
        char first = state.charAt(dot + 1);
        String tampered = state.substring(0, dot + 1) + (first == 'A' ? 'B' : 'A') + state.substring(dot + 2);
        assertThat(codec.matches(tampered, 7L, NOW)).isFalse();
    }

    @Test
    void 다른_키로_만든_state는_거부된다() {
        SoopLinkStateCodec other = new SoopLinkStateCodec(
                key("another-secret-key-at-least-32-bytes-long!!"), Duration.ofMinutes(10));
        assertThat(codec.matches(other.issue(7L, NOW), 7L, NOW)).isFalse();
    }

    @Test
    void 형식이_깨진_문자열은_예외_없이_거부된다() {
        assertThat(codec.matches("garbage", 7L, NOW)).isFalse();
        assertThat(codec.matches("", 7L, NOW)).isFalse();
        assertThat(codec.matches(null, 7L, NOW)).isFalse();
    }

    /**
     * 같은 키(JWT_SECRET)로 서명하므로 서명 대상 접두가 같으면 치지직 동의에서 받은 state가 SOOP 완료에 통한다.
     * 같은 회원 것만 통해 피해는 작지만, 접두를 복제한 채 두면 나머지 갈래가 전부 초록이라 여기서만 잡힌다.
     */
    @Test
    void 치지직_코덱이_발급한_state는_거부된다() {
        ChzzkLinkStateCodec chzzk = new ChzzkLinkStateCodec(KEY, new ChzzkProperties(
                new ChzzkProperties.App("cid", "cs", "http://x"), "https://a", "https://b",
                Duration.ofMinutes(10), Duration.ofHours(6), Duration.ofHours(12),
                new ChzzkProperties.Refresh(false, Duration.ofMinutes(10))));
        assertThat(codec.matches(chzzk.issue(7L, NOW), 7L, NOW)).isFalse();
    }

    @Test
    void 같은_사용자라도_매번_다른_state가_나온다() {
        assertThat(codec.issue(7L, NOW)).isNotEqualTo(codec.issue(7L, NOW));
    }
}
