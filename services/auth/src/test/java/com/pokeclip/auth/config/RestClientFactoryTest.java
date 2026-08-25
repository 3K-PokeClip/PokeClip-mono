package com.pokeclip.auth.config;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AWS SDK의 s3가 Apache5 HTTP 클라이언트를 runtime으로 딸려오고, httpclient5가 클래스패스에
 * 오르는 순간 Boot가 RestClient 구현을 JDK에서 Apache5로 <b>오류 없이</b> 바꾼다
 * (chat-collector 2026-08-16 실측 · auth에서도 이 카드에서 재현했다:
 * JdkClientHttpRequestFactory → HttpComponentsClientHttpRequestFactory).
 *
 * <p>auth의 connect 2s·read 5s는 구글 로그인·치지직 연동·유튜브 연동 호출 전부에 걸린 실측값이라,
 * 스택이 바뀌면 그 시한이 어느 층에서 끊는지가 달라진다. application.yml의
 * {@code spring.http.clients.imperative.factory=jdk}가 그것을 막고 이 시험이 그 줄을 지킨다.
 *
 * <p>chat-collector의 {@code CollectorConfigTest.RestClient는_JDK_스택을_쓴다_httpclient5가_있어도}와
 * 같은 방식으로 잰다 — 두 서버가 다른 방식으로 재면 한쪽이 낡는다. 주입만 생성자로 받는다
 * (auth는 필드 주입을 커밋 훅으로 막는다).
 */
class RestClientFactoryTest extends IntegrationTestSupport {

    /** 자동 설정된 빌더다. 구글·치지직·유튜브 클라이언트가 받는 것과 같은 것이어야 이 단언이 그들을 대변한다. */
    private final RestClient.Builder builder;

    RestClientFactoryTest(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Test
    void 요청_팩토리가_JDK_그대로다() throws Exception {
        RestClient client = builder.build();
        Field factory = client.getClass().getDeclaredField("clientRequestFactory");
        factory.setAccessible(true);

        assertThat(factory.get(client))
                .as("""
                        RestClient 구현이 JDK가 아니다 — 이 시험이 빨간불이면 \
                        구글·치지직·유튜브 호출의 시한(connect 2s·read 5s)이 재고 정한 층에서 안 끊는다. \
                        AWS SDK가 딸려온 Apache5가 클래스패스에 오르면 Boot가 구현을 조용히 바꾸는데, \
                        그 값들은 실측으로 정한 것이라 층이 바뀌면 어디서 끊는지를 다시 재야 한다. \
                        막는 것은 application.yml의 spring.http.clients.imperative.factory=jdk \
                        한 줄이다 — 그 줄을 지웠거나 새 SDK를 넣었는지 본다.""")
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }
}
