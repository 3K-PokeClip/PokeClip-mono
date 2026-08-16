package com.pokeclip.auth.chzzk;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ChzzkLinkStateCodecTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private final ChzzkLinkStateCodec codec = new ChzzkLinkStateCodec(
            key("test-only-secret-key-at-least-32-bytes-long!!"), Duration.ofMinutes(10));

    /** 운영에서는 JwtConfig의 SecretKey 빈(32B 하한 검증 포함)이 들어온다. 여기선 그 검증을 안 거치는 대신 32B 넘는 값을 쓴다. */
    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256");
    }

    @Test
    void 발급한_state는_같은_사용자로_검증된다() {
        String state = codec.issue(7L, NOW);
        assertThat(codec.matches(state, 7L, NOW.plusSeconds(60))).isTrue();
    }

    /** CSRF — 공격자가 자기 state가 든 링크로 피해자 계정에 자기 채널을 붙이는 경로. */
    @Test
    void 다른_사용자의_state는_거부된다() {
        assertThat(codec.matches(codec.issue(7L, NOW), 8L, NOW)).isFalse();
    }

    @Test
    void 만료된_state는_거부된다() {
        assertThat(codec.matches(codec.issue(7L, NOW), 7L, NOW.plus(Duration.ofMinutes(11)))).isFalse();
    }

    @Test
    void 서명이_한_글자라도_다르면_거부된다() {
        String state = codec.issue(7L, NOW);
        int dot = state.lastIndexOf('.');
        // base64url 첫 글자는 유효 비트 6개를 다 쓴다. 마지막 글자를 뒤집으면 16분의 1로
        // 같은 바이트열이 된다(auth/CLAUDE.md 「그 외」).
        char first = state.charAt(dot + 1);
        String tampered = state.substring(0, dot + 1) + (first == 'A' ? 'B' : 'A') + state.substring(dot + 2);
        assertThat(codec.matches(tampered, 7L, NOW)).isFalse();
    }

    @Test
    void 다른_키로_만든_state는_거부된다() {
        ChzzkLinkStateCodec other = new ChzzkLinkStateCodec(
                key("another-secret-key-at-least-32-bytes-long!!"), Duration.ofMinutes(10));
        assertThat(codec.matches(other.issue(7L, NOW), 7L, NOW)).isFalse();
    }

    @Test
    void 형식이_깨진_문자열은_예외_없이_거부된다() {
        assertThat(codec.matches("garbage", 7L, NOW)).isFalse();
        assertThat(codec.matches("", 7L, NOW)).isFalse();
        assertThat(codec.matches(null, 7L, NOW)).isFalse();
    }

    @Test
    void 같은_사용자라도_매번_다른_state가_나온다() {
        assertThat(codec.issue(7L, NOW)).isNotEqualTo(codec.issue(7L, NOW));
    }
}
