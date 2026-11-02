package com.pokeclip.clip.config;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 🔴 <b>나가는 HTTP 요청을 자동으로 되걸지 않는다.</b>
 *
 * <p><b>이 빈이 없으면 되걸린다 — 지우지 마라.</b> Apache HC5의 기본 재시도 전략
 * ({@code DefaultHttpRequestRetryStrategy})이 <b>429·503만</b> 한 번 되건다(5xx 전체가 아니다).
 * 그래서 「우리 코드가 재시도를 안 한다」와 「요청이 한 번만 나간다」가 <b>다른 말</b>이었고,
 * {@code DelegationResolveClient} 주석이 「재시도하지 않는다」고 적어 둔 채로 <b>거짓이었다</b>
 * (감사 1라운드 실측: {@code 500·502·401·504 → 1회}, {@code 429·503 → 2회}).
 *
 * <p><b>왜 끄나</b> — auth 자격 판정은 <b>사람이 기다리는 요청 안</b>에서 돈다. 되걸면 최악
 * 대기가 {@code connect 2s + read 5s} = 7초가 아니라 <b>14초</b>가 되고 톰캣 스레드를 그만큼
 * 더 쥔다. 게다가 되걸리는 상태가 하필 <b>503(과부하)·429(속도 제한)</b>이라, auth가 힘들 때
 * 정확히 <b>요청을 두 배로</b> 보낸다. 설계 전제가 동시 100명이고 방송 목록은 화면이 뜰 때마다
 * auth를 부르므로, 그 두 배는 auth가 회복할 틈을 뺏는 쪽으로 작용한다.
 *
 * <p><b>범위가 clip의 모든 RestClient다</b>(자동설정 빌더를 바꾸므로). 지금 나가는 HTTP는
 * auth 호출뿐이라 표적과 범위가 사실상 같다. 새 바깥 호출이 생기는 날 <b>되걸기가 필요한
 * 상대</b>가 있으면 그 클라이언트에만 전략을 다시 얹는다 — 전역을 되돌리지 않는다.
 *
 * <p><b>{@code detect()}로 시작하는 이유</b> — 스프링이 고르는 것과 같은 것을 고르고 거기서
 * 되걸기만 뺀다. 스택이 HC5가 아니게 되는 날에도 불변식은 유지된다(JDK 클라이언트는 원래
 * 되걸지 않는다). {@code AuthRetryContractTest}가 스택이 무엇인지와 요청이 한 번인지를
 * <b>둘 다</b> 못박는다.
 */
@Configuration
public class HttpClientRetryConfig {

    @Bean
    ClientHttpRequestFactoryBuilder<?> 되걸지_않는_요청_팩토리_빌더() {
        ClientHttpRequestFactoryBuilder<?> detected = ClientHttpRequestFactoryBuilder.detect();
        if (detected instanceof HttpComponentsClientHttpRequestFactoryBuilder httpComponents) {
            return httpComponents.withHttpClientCustomizer(builder -> builder
                    .disableAutomaticRetries()
                    // 🔴 되걸기와 같은 서랍의 옆 칸이다. 307·308은 원 요청을 그대로 다시 보내
                    // X-Internal-Token과 본문이 리다이렉트가 가리키는 아무 출처에나 도착하고,
                    // 다섯 상태 전부에서 도착지의 답이 그대로 자격 판정이 된다(고치기 전 실측:
                    // 전부 OWNER). 방아쇠는 auth 코드 안에 갇혀 있지 않다 — 인그레스·리버스
                    // 프록시·서비스 메시·HTTP→HTTPS 강제 어디서든 나온다.
                    // 도달성은 실증 안 했다(오늘 auth가 3xx를 줄 이유는 없다). 막는 것은
                    // 「지금 샌다」가 아니라 그 성질이다. AuthRetryContractTest가 지킨다.
                    .disableRedirectHandling());
        }
        return detected;
    }
}
