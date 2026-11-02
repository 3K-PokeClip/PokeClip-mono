package com.pokeclip.clip.delegation;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.HttpClientSettings;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>{@code spring.http.clients.*}가 실제로 바인딩됐는지 잰다.</b> 지시서에 없던 갈래인데,
 * 구현 중에 <b>그 두 줄을 지워도 이 모듈 전체가 초록</b>인 것을 주입으로 확인해서 더했다.
 *
 * <p>계획 검증이 이 자리에서 치명 둘을 잡았다 — 스타터가 없어 바인딩 대상 클래스 자체가
 * 클래스패스에 없었고, 그 상태에서 yml에 시한을 써 넣어도 <b>오류 없이 통째로 무시</b>됐다.
 * 이 저장소는 같은 모양(단수형 {@code spring.http.client.*})으로 두 번 데였다.
 *
 * <p><b>{@code DelegationResolveClientTest.시한이_실제로_걸려_있다}와 각도가 다르다.</b>
 * 그쪽은 검사가 직접 만든 팩토리로 「우리 코드가 주입받은 빌더를 쓰는가」를 행동으로 재고,
 * 여기는 「운영 설정이 실제로 값에 닿았는가」를 잰다. 어느 한쪽만으로는
 * <b>운영에서 시한이 안 걸리는 상태가 초록으로 지나간다.</b>
 *
 * <p>빈이 아예 없으면(스타터를 빼면) 이 검사는 컨텍스트 조립에서 죽는다 — 그것도 신호다.
 */
class AuthClientTimeoutBindingTest extends IntegrationTestSupport {

    private final HttpClientSettings settings;

    /**
     * {@code HttpClientSettings}는 {@code spring.http.clients}를 바인딩한 뒤
     * <b>팩토리에 실제로 먹이는 값</b>이다({@code HttpClientAutoConfiguration}).
     * 프로퍼티 클래스가 아니라 이쪽을 보는 이유는, 중간의 매핑이 끊겨도 잡히기 때문이다.
     */
    AuthClientTimeoutBindingTest(HttpClientSettings settings) {
        this.settings = settings;
    }

    /**
     * 값이 {@code null}이면 「기본값으로 잘 돌고 있다」가 아니라 <b>시한이 없다</b>는 뜻이다.
     * auth가 연결만 받고 답을 안 하면 미리보기 요청이 톰캣 스레드를 무기한 쥔다.
     */
    @Test
    void 발신_HTTP_시한이_설정에서_실제로_바인딩된다() {
        assertThat(settings.connectTimeout())
                .as("spring.http.clients.connect-timeout이 어디에도 안 걸렸다")
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.readTimeout())
                .as("spring.http.clients.read-timeout이 어디에도 안 걸렸다")
                .isEqualTo(Duration.ofSeconds(5));
    }
}
