package com.pokeclip.auth.config;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AWS SDK의 s3가 Apache5 HTTP 클라이언트를 runtime으로 딸려오고, httpclient5가 클래스패스에
 * 오르는 순간 Boot가 HTTP 구현을 JDK에서 Apache5로 <b>오류 없이</b> 바꾼다
 * (chat-collector 2026-08-16 실측 · auth에서도 이 카드에서 재현했다:
 * JdkClientHttpRequestFactory → HttpComponentsClientHttpRequestFactory).
 *
 * <p>auth의 connect 2s·read 5s는 구글 로그인·치지직 연동·유튜브 연동 호출 전부에 걸린 실측값이라,
 * 스택이 바뀌면 그 시한이 어느 층에서 끊는지가 달라진다. <b>그리고 시한만 달라지는 것이 아니다</b> —
 * httpclient5 5.6.1은 429·503에 재시도 1회(간격 1초)를 기본으로 붙이고, 상태코드 기반이라 POST도 탄다.
 * 치지직 갱신은 users 행 락을 쥔 채 HTTP를 기다리므로(「알려진 구멍」 10번) 그 한 번이 그대로
 * 잠금 시간이 된다. AWS SDK 자기 경로는 무관하다 — apache5-client가 disableAutomaticRetries를
 * 부른다. <b>이 증폭은 스프링 쪽 핀을 잃었을 때만 생긴다.</b>
 *
 * <p>막는 것은 application.yml의 {@code spring.http.clients.imperative.factory=jdk} 한 줄이고
 * 이 시험이 그 줄을 지킨다. <b>두 갈래인 이유</b>는 auth가 두 종류의 클라이언트를 쓰기 때문이다 —
 * 구글·치지직·유튜브는 {@code RestClient.Builder}, 구글 ID 토큰 검증({@code NimbusJwtDecoder})은
 * {@code RestTemplateBuilder}를 탄다. 한쪽만 재면 다른 쪽이 조용히 바뀌어도 초록이다.
 *
 * <p>chat-collector의 {@code CollectorConfigTest.RestClient는_JDK_스택을_쓴다_httpclient5가_있어도}와
 * 같은 방식으로 잰다 — 두 서버가 다른 방식으로 재면 한쪽이 낡는다. 주입만 생성자로 받는다
 * (auth는 필드 주입을 커밋 훅으로 막는다).
 */
class RestClientFactoryTest extends IntegrationTestSupport {

    /** 자동 설정된 빌더다. 구글·치지직·유튜브 클라이언트가 받는 것과 같은 것이어야 이 단언이 그들을 대변한다. */
    private final RestClient.Builder builder;

    /** 같은 이유로 자동 설정된 것을 받는다. GoogleAuthConfig가 NimbusJwtDecoder에 넘기는 그 빌더다. */
    private final RestTemplateBuilder restTemplateBuilder;

    RestClientFactoryTest(RestClient.Builder builder, RestTemplateBuilder restTemplateBuilder) {
        this.builder = builder;
        this.restTemplateBuilder = restTemplateBuilder;
    }

    @Test
    void 요청_팩토리가_JDK_그대로다() throws Exception {
        RestClient client = builder.build();
        Field factory = client.getClass().getDeclaredField("clientRequestFactory");
        factory.setAccessible(true);

        assertThat(factory.get(client))
                .as(WHY)
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    /**
     * 구글 ID 토큰 검증은 {@code RestClient}가 아니라 {@code RestTemplate}을 탄다
     * ({@code GoogleAuthConfig} → {@code NimbusJwtDecoder}). 시한이 걸린다는 것만으로는
     * 스택을 못 가른다 — <b>Apache5여도 같은 시한이 걸린다.</b> 그래서 구현체를 직접 본다.
     */
    @Test
    void RestTemplate_쪽도_JDK_그대로다() {
        RestTemplate template = restTemplateBuilder.build();

        assertThat(template.getRequestFactory())
                .as(WHY)
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    private static final String WHY = """
            HTTP 구현이 JDK가 아니다 — 이 시험이 빨간불이면 구글·치지직·유튜브 호출의 \
            시한(connect 2s·read 5s)이 재고 정한 층에서 안 끊고, 429·503에 요청이 한 번 더 나간다. \
            AWS SDK가 딸려온 Apache5가 클래스패스에 오르면 Boot가 구현을 조용히 바꾸는데, \
            그 값들은 실측으로 정한 것이라 층이 바뀌면 어디서 끊는지를 다시 재야 한다. \
            막는 것은 application.yml의 spring.http.clients.imperative.factory=jdk \
            한 줄이다 — 그 줄을 지웠거나 새 SDK를 넣었는지 본다.""";
}
