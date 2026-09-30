package com.pokeclip.chat.detector.config;

import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환경변수 없이 뜬 판별기가 튄 창을 <b>20초</b> 간격으로 묶는지 잰다(POK-260).
 *
 * <p>10초였을 때 채팅이 잠깐 끊겼다 다시 터지면 한 장면이 카드 두 장으로 쪼개졌다. 로컬 실기동은
 * 실행 스크립트가 {@code DETECTION_EPISODE_GAP=20s}로 덮어써 괜찮았고, 덮어쓰지 않는 dev·운영만
 * 10초로 돌았다. 그 어긋남을 다시 만들지 않으려고 yml 기본값 자체를 잰다.
 *
 * <p>컨텍스트가 뜬다는 것 자체가 부팅 검증 ⑤·⑥(되돌아보기 폭 · 앞당김)을 새 기본값으로 통과했다는 뜻이다.
 */
@SpringBootTest
class EpisodeGapDefaultTest extends IntegrationTestSupport {

    private final DetectionProperties props;

    EpisodeGapDefaultTest(DetectionProperties props) {
        this.props = props;
    }

    @Test
    void 환경변수가_없으면_튄_창을_20초_간격으로_묶는다() {
        assertThat(props.episodeGap()).isEqualTo(Duration.ofSeconds(20));
    }
}
