package com.pokeclip.clip.config;

import com.pokeclip.clip.collector.CollectorClientProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 수집기 호출 전용 {@link RestClient}. <b>이 서버에서 시한이 갈리는 첫 클라이언트다.</b>
 *
 * <p>auth는 전역 {@code spring.http.clients.*}(연결 2s + 읽기 5s)를 그대로 쓴다. 채팅 문 셋은
 * 그 판정을 <b>먼저 태우고</b> 수집기를 부르므로, 같은 값을 얹으면 사람이 최악 14초를 기다린다.
 * 그래서 여기만 <b>2s + 3s</b>다.
 *
 * <p>🔴 <b>주입받은 팩토리 빌더를 그대로 쓴다.</b> {@link HttpClientRetryConfig}가 만든 그 빈이라
 * 되걸기·리다이렉트 따라가기가 꺼진 채로 유지된다 — {@code ClientHttpRequestFactoryBuilder.detect()}를
 * 여기서 새로 부르면 <b>둘 다 조용히 되살아난다</b>(HC5 기본 전략이 429·503을 한 번 되걸고,
 * 307·308은 {@code X-Internal-Token}을 그대로 들고 따라간다).
 *
 * <p>🔴 <b>주입되는 팩토리 빌더 빈은 하나다.</b> Boot 4.1의 {@code ImperativeHttpClientAutoConfiguration}이
 * {@code @ConditionalOnMissingBean}이라, {@link HttpClientRetryConfig}의 빈이 있으면 자동설정 빈은
 * 아예 안 만들어진다 — 「둘 중 어느 것이 왔나」를 걱정할 자리가 아니다(계획 검증 실물 확인).
 *
 * <p><b>{@code RestClient.create()}를 쓰지 않는다.</b> 그것은 자동 설정을 우회해 시한이 어디에도
 * 안 걸리고, 수집기가 연결만 받고 답을 안 하면 요청이 톰캣 스레드를 무기한 쥔다. 이 저장소가
 * 이미 한 번 데인 자리다({@code chat-collector/CLAUDE.md}).
 * {@code CollectorClientTest.시한이_실제로_걸려_있다}가 값이 아니라 행동으로 지킨다.
 */
@Configuration
public class CollectorHttpConfig {

    /**
     * <p>{@code public}인 것은 위 검사가 이 메서드를 <b>직접</b> 부르기 때문이다 — 시한을 거는
     * 배선이 이 안에 있어서, 검사가 손으로 다시 조립하면 운영 코드를 안 재게 된다.
     */
    @Bean
    public RestClient 수집기_전용_RestClient(RestClient.Builder builder,
                                       ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                       CollectorClientProperties properties) {
        RestClient.Builder configured = builder.requestFactory(factoryBuilder.build(
                HttpClientSettings.defaults()
                        .withTimeouts(properties.connectTimeout(), properties.readTimeout())));
        // 주소가 비어 있으면 안 건다. CollectorClient가 그 상태에서 요청을 아예 안 보내므로
        // 값이 쓰이지 않지만, 빈 문자열을 base로 심어 두면 나중에 누가 그것을 쓸 때
        // 「어디로 갔는지 모를 요청」이 된다.
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            configured = configured.baseUrl(properties.baseUrl());
        }
        return configured.build();
    }
}
