package com.pokeclip.auth.youtube;

import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class YoutubeLinkStateCodecTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    private static final String SECRET = "test-only-secret-key-at-least-32-bytes-long!!";

    private final YoutubeLinkStateCodec codec = new YoutubeLinkStateCodec(key(SECRET), Duration.ofMinutes(10));

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
        YoutubeLinkStateCodec other = new YoutubeLinkStateCodec(
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

    /**
     * 도메인 접두어 분리의 증명 — 두 코덱이 <b>같은 JWT 키</b>를 쓰므로, 접두어가 없으면
     * 치지직 동의 링크로 받은 state를 유튜브 연동에 그대로 낼 수 있다.
     */
    @Test
    void 치지직_state를_유튜브에_내면_거부된다() {
        // 치지직 코덱의 짧은 생성자는 그 패키지 전용이라 공개 생성자(프로퍼티를 받는 쪽)로 만든다.
        ChzzkLinkStateCodec chzzk = new ChzzkLinkStateCodec(key(SECRET), new ChzzkProperties(
                new ChzzkProperties.App("cid", "csecret", "http://x"),
                "http://x", "http://x",
                Duration.ofMinutes(10), Duration.ofHours(6), Duration.ofHours(12),
                new ChzzkProperties.Refresh(false, Duration.ofMinutes(10))));
        String chzzkState = chzzk.issue(7L, NOW);

        assertThat(chzzk.matches(chzzkState, 7L, NOW)).as("치지직 코덱 자신은 받아들인다").isTrue();
        assertThat(codec.matches(chzzkState, 7L, NOW)).isFalse();
        assertThat(chzzk.matches(codec.issue(7L, NOW), 7L, NOW)).as("반대 방향도 막힌다").isFalse();
    }
}
