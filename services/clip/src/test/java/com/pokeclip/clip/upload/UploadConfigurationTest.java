package com.pokeclip.clip.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드를 켰는데 완성 영상 창고 이름이 비면 부팅을 거부한다(PR #198 codex P2). 창고 이름은 렌더 설정에 있고 렌더를 끄면 그쪽은
 * 검사를 안 한다 — 그대로 두면 주문은 201인데 주문서의 창고가 빈 문자열이라 일꾼이 영상을 못 받는다.
 */
class UploadConfigurationTest {

    @Test
    void 창고_이름이_비면_거부한다() {
        for (String bucket : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> UploadConfiguration.requireSourceBucket(bucket))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CLIPS_BUCKET");
        }
        assertThatCode(() -> UploadConfiguration.requireSourceBucket("pokeclip-clips-2557")).doesNotThrowAnyException();
    }
}
