package com.pokeclip.auth.profile;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사진 표는 <b>계정을 여는 열쇠가 아니라 그림 한 장을 여는 표</b>다(PRD). 그 성질을 여기서 굳힌다 —
 * 서명키가 로그인 토큰과 다르다는 것은 PhotoKeySeparationTest(태스크 6)가 잰다.
 */
class PhotoTokenTest {

    private static final String SECRET = "photo-token-secret-for-test-32bytes!!";
    private static final Instant T = Instant.parse("2026-08-25T10:03:00Z");

    @Test
    void 같은_십분_창_안에서는_글자까지_같다() {
        String a = PhotoToken.issue(SECRET, 7L, 1000L, T);
        String b = PhotoToken.issue(SECRET, 7L, 1000L, T.plusSeconds(299));
        assertThat(a).as("주소가 안 바뀌어야 브라우저 캐시가 먹는다").isEqualTo(b);
    }

    @Test
    void 십분_경계를_넘으면_달라진다() {
        String a = PhotoToken.issue(SECRET, 7L, 1000L, Instant.parse("2026-08-25T10:09:59Z"));
        String b = PhotoToken.issue(SECRET, 7L, 1000L, Instant.parse("2026-08-25T10:10:00Z"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 사진이_바뀌면_즉시_달라진다() {
        String before = PhotoToken.issue(SECRET, 7L, 1000L, T);
        String after = PhotoToken.issue(SECRET, 7L, 2000L, T);
        assertThat(after).as("바꾼 직후 새 그림이 보이는 근거").isNotEqualTo(before);
    }

    @Test
    void 발급된_표는_최소_십분_최대_이십분_산다() {
        String t = PhotoToken.issue(SECRET, 7L, 1000L, T);
        assertThat(PhotoToken.verify(SECRET, t, 7L, T.plusSeconds(599))).isTrue();
        assertThat(PhotoToken.verify(SECRET, t, 7L, T.plusSeconds(1200))).isFalse();
    }

    @Test
    void 남의_번호로는_안_열린다() {
        String t = PhotoToken.issue(SECRET, 7L, 1000L, T);
        assertThat(PhotoToken.verify(SECRET, t, 8L, T)).isFalse();
    }

    @Test
    void 서명을_고치면_안_열린다() {
        String t = PhotoToken.issue(SECRET, 7L, 1000L, T);
        String tailFlipped = t.substring(0, t.length() - 1) + (t.endsWith("A") ? "B" : "A");
        // 끝 글자만으로 재면 그물이 구현에 기댄다 — base64url 43글자의 마지막 글자는 유효 비트가
        // 4개뿐(43×6=258>256)이라, 서명을 디코딩해서 비교하는 구현으로 바뀌는 순간 약 16분의 1로
        // 헛통과한다(auth의 SecretLeakTest.tamperSignature가 실제로 그랬다). 첫 글자 갈래를 같이 둔다.
        int sig = t.lastIndexOf('.') + 1;
        String headFlipped = t.substring(0, sig) + (t.charAt(sig) == 'A' ? 'B' : 'A') + t.substring(sig + 1);
        assertThat(PhotoToken.verify(SECRET, tailFlipped, 7L, T)).isFalse();
        assertThat(PhotoToken.verify(SECRET, headFlipped, 7L, T)).isFalse();
    }

    @Test
    void 만료를_늘려_적으면_서명이_안_맞는다() {
        String t = PhotoToken.issue(SECRET, 7L, 1000L, T);
        String[] parts = t.split("\\.");
        String forged = parts[0] + "." + (Long.parseLong(parts[1]) + 100_000) + "." + parts[2] + "." + parts[3];
        assertThat(PhotoToken.verify(SECRET, forged, 7L, T)).isFalse();
    }

    @Test
    void 다른_키로_만든_표는_안_열린다() {
        String t = PhotoToken.issue("another-secret-entirely-different!!", 7L, 1000L, T);
        assertThat(PhotoToken.verify(SECRET, t, 7L, T)).isFalse();
    }

    @Test
    void 모양이_아닌_글자는_조용히_거부한다() {
        for (String junk : new String[]{"", ".", "a.b.c.d", "7.1.2", "7.x.1.sig", "eyJhbGciOiJIUzI1NiJ9.x.y"}) {
            assertThat(PhotoToken.verify(SECRET, junk, 7L, T)).as("입력 %s", junk).isFalse();
        }
    }
}
