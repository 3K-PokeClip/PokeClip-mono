package com.pokeclip.chat.collector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 배선을 실제로 밟는다. <b>이 파일이 없으면 러너가 한 번도 안 도는 상태가
 * 전 테스트 초록으로 통과한다.</b>
 *
 * <p>원인은 DISABLED가 두 가지를 뜻한다는 것이다 — "러너가 돌아서 껐다"와
 * "러너가 한 번도 안 돌아 초기값 그대로다"가 같은 값이다. 그래서 @Component
 * 누락·run()이 start()를 안 부름·러너와 health가 다른 인스턴스를 봄, 셋 중
 * 무엇이 깨져도 기존 단언은 전부 참이었다.
 *
 * <p>운영에서의 모습이 이 카드가 막으려는 그 실패다. enabled=true로 띄웠는데
 * 배선이 깨져 있으면 수집은 안 도는데 health는 UP + status:disabled를 응답한다.
 * 배포도 헬스체크도 통과한다.
 *
 * <p>죽은 포트로 향하게 해서 가짜 서버 없이 부팅 경로 전체를 밟는다. 연결 거부는
 * SESSION_AUTH_FAILED로 감싸이고 러너가 잡으므로 부팅 자체는 살아남는다 —
 * <b>그 "실패해도 부팅은 산다"까지가 이 테스트가 확인하는 것이다.</b>
 */
@SpringBootTest(properties = {
        "pokeclip.chzzk.enabled=true",
        "pokeclip.chzzk.base-url=http://localhost:1"
})
@ActiveProfiles("test")
class CollectorBootWiringTest {

    @Autowired CollectionStatus status;   // 러너가 쓰는 그 싱글턴이어야 한다
    @Autowired CollectorHealth health;

    @Test
    void 부팅하면_러너가_실제로_돌고_health가_같은_상태를_읽는다() {
        // STOPPED는 러너가 돌지 않고서는 나올 수 없는 값이다. 초기값은 DISABLED다.
        assertThat(status.state())
                .as("DISABLED면 러너가 한 번도 안 돈 것이다 — 초기값과 구분되지 않는다")
                .isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_FAILED);

        // health가 러너와 다른 인스턴스를 보면 여기서 갈린다.
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("reason", "SESSION_AUTH_FAILED");
    }
}
