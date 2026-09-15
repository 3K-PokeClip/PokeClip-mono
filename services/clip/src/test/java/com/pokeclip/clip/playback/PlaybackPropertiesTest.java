package com.pokeclip.clip.playback;

import com.pokeclip.clip.support.TestPlaybackKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 부팅 검증. 잘못된 설정은 <b>첫 발급의 500</b>이 아니라 <b>부팅</b>에서 드러나야 한다. */
class PlaybackPropertiesTest {

    private static final String PEM = TestPlaybackKeys.privateKeyPem();

    @Test
    void 셋_다_비면_꺼짐이고_부팅은_산다() {
        PlaybackProperties p = new PlaybackProperties("", "", "", "", Duration.ofMinutes(60));
        p.validate();
        assertThat(p.enabled()).isFalse();
    }

    @Test
    void 셋_다_있으면_켜진다() {
        PlaybackProperties p = new PlaybackProperties("K1", PEM, "https://media.test", null, Duration.ofMinutes(60));
        p.validate();
        assertThat(p.enabled()).isTrue();
        assertThat(p.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(p.cookieDomain()).isNull();
    }

    /** 「일부러 안 켬」과 「깜빡함」이 같아 보이면 안 된다 — 일부만 있으면 실수다. */
    @Test
    void 일부만_채우면_부팅을_거부하고_어느_칸이_비었는지_말한다() {
        PlaybackProperties p = new PlaybackProperties("K1", "", "https://media.test", null, Duration.ofMinutes(60));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("privateKeyPem=false").hasMessageContaining("keyPairId=true");
    }

    /** 환경변수 한 줄로 넣으면 개행이 리터럴 {@code \n}으로 온다. 그것도 읽는다. */
    @Test
    void 리터럴_개행의_PEM도_읽는다() {
        PlaybackProperties p = new PlaybackProperties("K1", PEM.replace("\n", "\\n"), "https://media.test", null, Duration.ofMinutes(60));
        p.validate();
        assertThat(p.enabled()).isTrue();
    }

    /** JDK가 못 읽는 모양은 변환 명령까지 적어 거부한다. 메시지에 <b>키 본문은 없다</b>. */
    @Test
    void PKCS1이면_변환_명령을_알려주고_키_본문은_안_싣는다() {
        String pkcs1 = "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIEowIBAAKCAQEAsecretbody\n-----END RSA " + "PRIVATE KEY-----";
        PlaybackProperties p = new PlaybackProperties("K1", pkcs1, "https://media.test", null, Duration.ofMinutes(60));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8 -topk8")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secretbody"));
    }

    @Test
    void PEM이_아니면_거부한다() {
        PlaybackProperties p = new PlaybackProperties("K1", "not a key at all", "https://media.test", null, Duration.ofMinutes(60));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("PEM");
    }

    @Test
    void 수명이_0이거나_음수면_거부한다() {
        assertThatThrownBy(() -> new PlaybackProperties("", "", "", "", Duration.ZERO).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ttl");
        assertThatThrownBy(() -> new PlaybackProperties("", "", "", "", Duration.ofSeconds(-1)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ttl");
    }

    /**
     * 켜졌는데 {@code cookie-domain}만 비면 부팅은 살되 WARN 한 줄(봇 리뷰 1판). 거부하지 않는 이유는
     * 로컬(같은 호스트)이 그 모양이라서다 — 운영에서는 이 줄이 유일한 신호다.
     */
    @Test
    void 켜졌는데_쿠키_도메인이_비면_경고한다() {
        PlaybackProperties 켜짐_도메인_없음 = new PlaybackProperties("K1", PEM, "https://media.test", null, Duration.ofMinutes(60));
        켜짐_도메인_없음.validate();
        PlaybackProperties 켜짐_도메인_있음 = new PlaybackProperties("K1", PEM, "https://media.test", ".pokeclip.com", Duration.ofMinutes(60));
        켜짐_도메인_있음.validate();

        try (com.pokeclip.web.support.LogCaptor logs = new com.pokeclip.web.support.LogCaptor()) {
            new PlaybackAccessSigner(켜짐_도메인_없음);
            assertThat(logs.messages()).anyMatch(m -> m.contains("clip.playback.cookie_domain_empty"));
        }
        try (com.pokeclip.web.support.LogCaptor logs = new com.pokeclip.web.support.LogCaptor()) {
            new PlaybackAccessSigner(켜짐_도메인_있음);
            assertThat(logs.messages()).as("도메인이 있으면 경고가 없어야 한다 — 없으면 위 단언이 아무것도 안 잰다")
                    .noneMatch(m -> m.contains("clip.playback.cookie_domain_empty"));
        }
    }

    /** 끝 슬래시가 있으면 정책이 {@code //*}가 되어 CloudFront가 아무것도 안 맞춘다. */
    @Test
    void 자원_주소_끝_슬래시는_거부한다() {
        PlaybackProperties p = new PlaybackProperties("K1", PEM, "https://media.test/", null, Duration.ofMinutes(60));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("/");
    }
}
