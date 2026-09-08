package com.pokeclip.clip.collector;

import com.pokeclip.clip.config.CollectorHttpConfig;
import com.pokeclip.clip.config.HttpClientRetryConfig;
import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.support.FakeCollector;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 🔴 <b>수집기에 요청이 몇 번 나가고, 리다이렉트를 따라가나.</b> 답은 <b>한 번</b>이고 <b>안 따라간다</b>.
 *
 * <p>{@code AuthRetryContractTest}의 쌍둥이다 — 되걸기·리다이렉트를 끄는 것은 클라이언트가 아니라
 * <b>HTTP 스택</b>에 걸려 있고({@link HttpClientRetryConfig}), 그 빈을 받아 쓰는 클라이언트가
 * 이제 둘이다. 하나를 재고 다른 하나를 안 재면 <b>둘째가 그 빈을 실제로 받는지</b> 아무도 모른다.
 *
 * <p>🔴 <b>이 클래스가 없으면 어떤 실수가 통과하나</b> — {@link CollectorHttpConfig}가 주입받은
 * 팩토리 빌더 대신 {@code ClientHttpRequestFactoryBuilder.detect()}를 새로 부르는 것이다.
 * 그 javadoc이 「이러면 되걸기와 리다이렉트 따라가기가 조용히 되살아난다」고 경고해 두었는데,
 * 감사 라운드 4가 <b>실제로 그 실수를 심어 보니 clip 515건이 전부 초록</b>이었다.
 *
 * <p>🔴 <b>노출이 auth보다 넓다.</b> auth 호출은 POST라 {@code 301}·{@code 302}·{@code 303}이
 * GET으로 격하된 <b>새 요청</b>이 되어 헤더·본문이 안 따라가고 {@code 307}·{@code 308}만
 * 열쇠를 흘린다. <b>수집기 호출은 원래 GET</b>이라 다섯 상태 <b>전부</b>가 원 요청 그대로
 * 재전송되고 {@code X-Internal-Token}이 리다이렉트가 가리키는 아무 출처에나 도착한다.
 * 그 토큰은 저장소 CLAUDE.md가 「한 번 새면 세 서버가 같이 열린다」고 적은 그 열쇠다.
 *
 * <p>{@code CollectorClientTest}는 이 사실을 <b>잴 수 없다</b> — 거기서는 팩토리 빌더를
 * {@code detect()}로 손수 넘긴다(그 클래스의 표적은 시한이라 컨텍스트를 안 띄운다).
 * 그래서 클래스가 따로 있다.
 *
 * <p><b>도달성은 실증하지 않았다.</b> 오늘 수집기가 3xx를 줄 이유는 없다. 재는 것은
 * 「지금 샌다」가 아니라 <b>「리다이렉트가 가리키는 곳에 우리 열쇠를 넘겨준다」는 성질</b>이고,
 * 그 방아쇠는 수집기 코드 안에 갇혀 있지 않다 — 인그레스·리버스 프록시·서비스 메시·
 * HTTP→HTTPS 강제 어디서든 나온다.
 *
 * <p>🔴 <b>한계</b> — 같은 호스트의 <b>다른 포트</b>로 보내 쟀다. <b>다른 호스트까지 잰 것이
 * 아니다.</b> 이 줄을 지우지 마라 — 없으면 다음 사람이 「다 쟀다」로 읽는다.
 */
class CollectorRetryContractTest extends IntegrationTestSupport {

    private static final String 문 = "/internal/streams/s-1/chat-messages";

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    private final RestClient.Builder 운영_빌더;

    private final ClientHttpRequestFactoryBuilder<?> 운영_팩토리_빌더;

    CollectorRetryContractTest(RestClient.Builder restClientBuilder,
                               ClientHttpRequestFactoryBuilder<?> factoryBuilder) {
        this.운영_빌더 = restClientBuilder;
        this.운영_팩토리_빌더 = factoryBuilder;
    }

    /**
     * 이 클래스가 무엇을 재고 있는지 못박는다. 클래스패스에 httpclient5가 있어(awssdk:sqs가
     * 끌어온다) JDK가 아니라 HC5가 뽑히고, <b>되걸기·리다이렉트 추종은 그 스택의 기본값</b>이다.
     * 스택이 바뀌는 날 이 줄이 먼저 빨간불이 되고, 그때 아래 갈래들이 아직 무언가를 재는지
     * 같이 본다 — JDK로 바뀌면 되걸기가 원래 없어 아래가 <b>자동으로 참</b>이 된다.
     */
    @Test
    void 운영_스택은_Apache_HC5다() {
        assertThat(ClientHttpRequestFactoryBuilder.detect().build())
                .as("스택이 바뀌었다 — 아래 갈래들이 아직 되걸기를 재는지 다시 봐야 한다")
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    /**
     * <b>429·503이 표적이다</b> — HC5 기본 전략이 되거는 것이 그 둘뿐이다. 나머지는 대조군이라
     * 「전부 1회」가 뜻을 갖는다.
     *
     * <p>이 호출은 <b>사람이 기다리는 요청 안</b>에서 돈다. 되걸면 최악 대기가
     * {@code connect 2s + read 3s}가 아니라 두 배가 되고, 자격 판정(auth 왕복)이 그 앞에 이미 있다.
     *
     * <p>던지는지는 안 본다 — 그것은 {@code CollectorClientTest}가 재고, 여기 표적은 <b>횟수</b>다.
     */
    @ParameterizedTest(name = "수집기 상태={0}")
    @ValueSource(ints = {401, 429, 500, 502, 503, 504})
    void 수집기_호출은_상태와_무관하게_한_번만_나간다(int status) {
        try (FakeCollector 수집기 = FakeCollector.start()) {
            수집기.respondWith(status, "");

            catchThrowable(() -> clientFor(수집기).get(문, Map.of()));

            assertThat(수집기.callCount())
                    .as("0이면 요청이 안 나갔고, 2 이상이면 되걸린 것이다 — 둘 다 처방이 다르다")
                    .isEqualTo(1);
        }
    }

    /**
     * 🔴 <b>되걸기와 같은 서랍의 옆 칸이다</b> — 둘 다 「우리가 안 고른 HC5 기본값」이고, 하나를
     * 껐다고 옆 칸이 닫히지 않는다.
     *
     * <p><b>따라가면 두 가지가 한꺼번에 무너진다.</b> 열쇠({@code X-Internal-Token})가
     * 도착지로 가고, 도착지가 만든 본문이 <b>그대로 브라우저에 채팅으로</b> 그려진다 —
     * 이 문은 본문을 해석하지 않고 그대로 넘기기 때문이다.
     *
     * <p><b>{@code 출발지.callCount()}를 먼저 단언하는 이유</b> — {@code 도착지.callCount()==0}은
     * 「안 따라갔다」와 <b>「요청이 아예 안 나갔다」가 같은 값</b>이다. 빌더를 망가뜨려 요청이
     * 한 번도 안 나가도 그 0은 초록이다.
     *
     * <p><b>3xx는 접지 않는다 — 알고 그대로 둔다.</b> {@code retrieve()}의 기본 오류 처리가
     * 4xx·5xx만 던지므로 리다이렉트 응답은 {@code CollectorResponse(3xx, 빈 본문)}으로 돌아가
     * 브라우저까지 간다. <b>지금 수집기가 3xx를 낼 자리가 없어</b> 이 카드에서 접는 갈래를
     * 만들지 않았다(400·404 갈래와 달리 실재하지 않는다). 아래 단언이 그 사실을 <b>글이 아니라
     * 값으로</b> 남긴다 — 접는 처방이 생기는 날 이 줄이 먼저 빨간불이 된다.
     */
    @ParameterizedTest(name = "수집기 상태={0}")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void 리다이렉트를_따라가지_않는다(int status) {
        try (FakeCollector 도착지 = FakeCollector.start(); FakeCollector 출발지 = FakeCollector.start()) {
            도착지.respondWith(200, "{\"items\":[{\"text\":\"남의 채팅\"}]}");
            출발지.redirectTo(status, 도착지.baseUrl() + 문);

            CollectorResponse 답 = clientFor(출발지).get(문, Map.of());

            assertThat(출발지.callCount())
                    .as("요청이 안 나갔다 — 아래 0은 「안 따라갔다」가 아니다").isEqualTo(1);
            assertThat(도착지.callCount())
                    .as("리다이렉트를 따라갔다 — 받은 토큰=%s", 도착지.lastToken())
                    .isZero();
            assertThat(답.status())
                    .as("3xx를 접는 처방이 생겼다 — 이 클래스 javadoc의 「알고 그대로 둔다」를 고쳐라")
                    .isEqualTo(status);
            assertThat(답.body() == null ? "" : 답.body())
                    .as("도착지가 만든 본문이 화면에 채팅으로 그려진다").doesNotContain("남의 채팅");
        }
    }

    /**
     * 🔴 <b>운영 배선을 그대로 태운다.</b> {@link CollectorHttpConfig}의 빈 메서드를 직접 부르되
     * <b>주입받은 빌더 둘</b>을 넘긴다 — 여기서 {@code ClientHttpRequestFactoryBuilder.detect()}로
     * 손수 만들면 이 클래스가 <b>끄지 않아도 초록</b>이 된다(그것이 정확히 이 클래스가 잡으려는 실수다).
     *
     * <p>주소만 가짜 수집기로 갈아 끼운다. 컨텍스트에 실린 {@code 수집기_전용_RestClient} 빈은
     * {@link IntegrationTestSupport#COLLECTOR} 하나에 못박혀 있어 출발지·도착지 둘을 못 만든다.
     */
    private CollectorClient clientFor(FakeCollector 수집기) {
        CollectorClientProperties properties = new CollectorClientProperties(
                수집기.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(3));
        RestClient restClient = new CollectorHttpConfig()
                .수집기_전용_RestClient(운영_빌더, 운영_팩토리_빌더, properties);
        return new CollectorClient(restClient, properties, new InternalApiProperties(INTERNAL_TOKEN));
    }
}
