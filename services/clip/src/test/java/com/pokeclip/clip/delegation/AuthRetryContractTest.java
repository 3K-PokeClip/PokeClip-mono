package com.pokeclip.clip.delegation;

import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.support.FakeAuth;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>auth에 요청이 몇 번 나가나.</b> 답은 언제나 <b>한 번</b>이다.
 *
 * <p>{@code DelegationResolveClient} 주석은 처음부터 「재시도하지 않는다」고 적었지만
 * <b>거짓이었다</b> — 우리 코드는 안 걸어도 <b>HTTP 계층이 걸었다.</b> Apache HC5의
 * {@code DefaultHttpRequestRetryStrategy}가 <b>429·503만</b> 한 번 되건다(5xx 전체가 아니다).
 * 감사 1라운드가 잡았고, 지금은 그 되걸기를 <b>명시적으로 껐다.</b>
 *
 * <p>왜 아팠나 — 이 호출은 <b>사람이 기다리는 요청 안</b>에서 돈다. 되걸면 최악 대기가
 * {@code connect 2s + read 5s} = 7초가 아니라 <b>14초</b>가 되고 톰캣 스레드를 그만큼 더 쥔다.
 * 그리고 이 카드가 {@code accessible()}을 얹으면서 표적이 넓어졌다 — 그것은 목록 화면이
 * <b>뜰 때마다</b> 부르는 호출이고, 설계 전제가 동시 100명이다. auth가 503을 뱉는 동안
 * 요청이 auth에 <b>2배로</b> 쌓인다.
 *
 * <p><b>이 시험이 운영과 같은 스택에서 도는 것이 전부다.</b> 다른 스택에서 돌면
 * 「되걸기가 원래 없는 환경」에서 1회를 재고 초록이 된다 — <b>아무것도 안 재는 시험</b>이
 * 하나 더 느는 것이다. 그래서 ① 스프링이 주입한 빌더를 쓰고(운영과 같은 경로)
 * ② {@link #운영_스택은_Apache_HC5다()}가 그 스택이 무엇인지 못박는다.
 * {@code AccessibleClientTest}·{@code DelegationResolveClientTest}는 JDK 팩토리를 못박으므로
 * <b>이 사실을 잴 수 없다</b> — 그래서 클래스가 따로 있다.
 */
class AuthRetryContractTest extends IntegrationTestSupport {

    private final RestClient.Builder 운영_빌더;

    AuthRetryContractTest(RestClient.Builder restClientBuilder) {
        this.운영_빌더 = restClientBuilder;
    }

    /**
     * 이 클래스가 무엇을 재고 있는지 못박는다. 클래스패스에 httpclient5가 있어
     * (awssdk:sqs가 끌어온다) JDK가 아니라 HC5가 뽑히고, <b>되걸기는 그 스택의 기본값</b>이다.
     * 스택이 바뀌는 날 이 줄이 먼저 빨간불이 되고, 그때 아래 갈래들이 아직 무언가를 재는지
     * 같이 본다 — JDK로 바뀌면 되걸기가 원래 없어 아래가 <b>자동으로 참</b>이 된다.
     *
     * <p>{@code AuthClientTokenLeakTest}가 같은 자리에서 같은 모양으로 스택을 못박는다.
     */
    @Test
    void 운영_스택은_Apache_HC5다() {
        assertThat(ClientHttpRequestFactoryBuilder.detect().build())
                .as("스택이 바뀌었다 — 아래 갈래들이 아직 되걸기를 재는지 다시 봐야 한다")
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    /**
     * <b>429·503이 표적이다.</b> 나머지는 대조군 — 되걸기를 끄는 처방이 <b>그 둘에만</b>
     * 듣는 것이 아니라 <b>어디도 안 망가뜨렸다</b>는 것을 같이 재야 「전부 1회」가 의미를 갖는다.
     */
    @ParameterizedTest(name = "auth 상태={0}")
    @ValueSource(ints = {401, 429, 500, 502, 503, 504})
    void resolve는_상태와_무관하게_한_번만_나간다(int status) {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(status, "");

            clientFor(auth).resolve(42L, 7L);

            assertThat(auth.callCount()).isEqualTo(1);
        }
    }

    /**
     * <b>{@code resolve}만 고치면 절반이다.</b> 되걸기는 클라이언트가 아니라 HTTP 스택에
     * 걸려 있어 두 메서드에 똑같이 작용한다 — 그래서 처방도 두 곳에 똑같이 들어야 한다.
     * 그리고 아픈 쪽은 오히려 이쪽이다(목록 화면이 뜰 때마다 부른다).
     */
    @ParameterizedTest(name = "auth 상태={0}")
    @ValueSource(ints = {401, 429, 500, 502, 503, 504})
    void accessible도_상태와_무관하게_한_번만_나간다(int status) {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(status, "");

            clientFor(auth).accessible(42L);

            assertThat(auth.callCount()).isEqualTo(1);
        }
    }

    /**
     * 🔴 <b>리다이렉트를 따라가지 않는다.</b> 되걸기와 <b>같은 서랍의 옆 칸</b>이다 —
     * 둘 다 「우리가 안 고른 HC5 기본값」이고, 하나를 껐다고 옆 칸이 닫히지 않는다.
     *
     * <p><b>따라가면 두 가지가 한꺼번에 무너진다.</b>
     * <ul>
     *   <li><b>열쇠가 샌다</b> — {@code 307}·{@code 308}은 원 요청을 그대로 다시 보내므로
     *       {@code X-Internal-Token}과 본문이 <b>리다이렉트가 가리키는 아무 출처에나</b> 간다.
     *       그 토큰은 저장소 CLAUDE.md가 「한 번 새면 세 서버가 같이 열린다」고 적은 그 열쇠다
     *   <li>🔴 <b>판정이 위조된다</b> — 도착지의 응답을 우리가 <b>자격 판정으로 읽는다</b>.
     *       즉 리다이렉트를 거는 쪽이 {@code OWNER}를 만들어 낼 수 있다. 고치기 전 실측:
     *       <b>다섯 상태 전부에서 {@code OWNER}를 받았다</b>({@code accessible}은 남의 목록을
     *       우리 목록으로 읽었다)
     * </ul>
     *
     * <p>🔴 <b>열쇠가 새는 것은 {@code 307}·{@code 308}뿐이지만 판정 위조는 다섯 다다.</b>
     * {@code 301}·{@code 302}·{@code 303}은 POST를 GET으로 바꾼 <b>새 요청</b>이라 토큰도 본문도
     * 안 따라간다(실측: {@code 받은 토큰=null 받은 본문=}). 그래서 「본문이 없으니 auth가 4xx를
     * 주고 우리는 {@code UNAVAILABLE}로 접는다 — 거절 쪽이라 안전하다」로 볼 수 있는데,
     * <b>그 안전함은 「도착지가 POST만 받는다」는 가정에 기대고 있다.</b> 리다이렉트 공격에서
     * 우리가 통제하지 못하는 것이 정확히 그 가정이다 — 도착지가 GET에도 {@code 200}을 주면
     * 그 답이 그대로 판정이 된다. 이 시험의 도착지가 그렇게 답하고, <b>다섯 다 뚫렸다.</b>
     *
     * <p><b>한계</b> — 그래서 이 갈래는 「진짜 auth가 GET에 무엇을 주나」를 재지 <b>않는다</b>.
     * 재는 것은 <b>「도착지가 답하면 우리가 그것을 판정으로 읽나」</b>이고, 답은 「읽는다」였다.
     *
     * <p><b>도달성은 실증하지 않았다.</b> 오늘 auth가 3xx를 줄 이유는 없다. 이 갈래가 재는 것은
     * 「지금 샌다」가 아니라 <b>「리다이렉트가 가리키는 곳에 우리 열쇠를 넘겨준다」는 성질</b>이고,
     * 그 방아쇠는 auth 코드 안에 갇혀 있지 않다 — 인그레스·리버스 프록시·서비스 메시·
     * HTTP→HTTPS 강제 어디서든 나온다.
     *
     * <p>🔴 <b>한계</b> — 같은 호스트의 <b>다른 포트</b>로 보내 쟀다. <b>다른 호스트까지 잰 것이
     * 아니다.</b> 이 줄을 지우지 마라 — 없으면 다음 사람이 「다 쟀다」로 읽는다.
     *
     * <p><b>{@code 출발지.callCount()}를 먼저 단언하는 이유</b> — {@code 도착지.callCount()==0}은
     * 「안 따라갔다」와 <b>「요청이 아예 안 나갔다」가 같은 값</b>이다. 빌더를 망가뜨려 요청이
     * 한 번도 안 나가도 그 0은 초록이다. 위 되걸기 갈래가 {@code isEqualTo(1)}을 재는 것과 같은 이유다.
     *
     * <p><b>{@code UNAVAILABLE}까지 단언하는 이유</b> — 막기만 재면 「3xx를 200처럼 읽어 파싱에
     * 성공하는」 회귀를 못 잡는다. 막은 뒤 3xx는 {@code retrieve()}의 기본 오류 처리가
     * <b>4xx·5xx만 던지므로</b> 「성공」으로 통과하고, 본문이 비어 파싱에서 터져
     * {@code UNAVAILABLE}로 접힌다 — <b>거절 쪽이라 안전하다</b>.
     * 🔴 다만 그 경로의 로그 사유가 <b>Jackson 예외 이름</b>으로 찍혀 「리다이렉트를 만났다」가
     * 안 보인다. 운영에서 이 갈래를 만나면 원인 추적이 한 단계 길어진다 — <b>알고 넣었다</b>.
     */
    @ParameterizedTest(name = "auth 상태={0}")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void resolve는_리다이렉트를_따라가지_않는다(int status) {
        try (FakeAuth 도착지 = FakeAuth.start(); FakeAuth 출발지 = FakeAuth.start()) {
            도착지.respondWith(200, "{\"relation\":\"OWNER\"}");
            출발지.redirectTo(status, 도착지.baseUrl() + "/internal/editor-delegations/resolve");

            ResolveResult 판정 = clientFor(출발지).resolve(42L, 7L);

            assertThat(출발지.callCount())
                    .as("요청이 안 나갔다 — 아래 0은 「안 따라갔다」가 아니다").isEqualTo(1);
            assertThat(도착지.callCount())
                    .as("리다이렉트를 따라갔다 — 받은 토큰=%s 받은 본문=%s",
                            도착지.lastToken(), 도착지.lastBody())
                    .isZero();
            assertThat(판정)
                    .as("리다이렉트가 가리킨 곳의 답을 자격 판정으로 읽었다")
                    .isEqualTo(ResolveResult.UNAVAILABLE);
        }
    }

    /** <b>{@code resolve}만 고치면 절반이다</b> — 되걸기와 같은 이유로 스택에 걸려 있다. */
    @ParameterizedTest(name = "auth 상태={0}")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void accessible도_리다이렉트를_따라가지_않는다(int status) {
        try (FakeAuth 도착지 = FakeAuth.start(); FakeAuth 출발지 = FakeAuth.start()) {
            도착지.respondWith(200, "{\"streamers\":[{\"streamerUserId\":7,\"relation\":\"OWNER\"}]}");
            출발지.redirectTo(status, 도착지.baseUrl() + "/internal/editor-delegations/accessible");

            AccessibleResult 답 = clientFor(출발지).accessible(42L);

            assertThat(출발지.callCount())
                    .as("요청이 안 나갔다 — 아래 0은 「안 따라갔다」가 아니다").isEqualTo(1);
            assertThat(도착지.callCount())
                    .as("리다이렉트를 따라갔다 — 받은 토큰=%s 받은 본문=%s",
                            도착지.lastToken(), 도착지.lastBody())
                    .isZero();
            assertThat(답.available())
                    .as("리다이렉트가 가리킨 곳의 목록을 우리 목록으로 읽었다").isFalse();
        }
    }

    /**
     * 🔴 <b>주입받은 빌더를 그대로 쓴다 — 여기서 팩토리를 갈아 끼우면 아무것도 안 잰다.</b>
     * {@code RestClient.builder()}로 새로 만들면 자동설정을 우회해 되걸기도 시한도 안 걸리고,
     * 그러면 이 클래스는 「끄지 않아도 초록」이 된다.
     */
    private DelegationResolveClient clientFor(FakeAuth auth) {
        return new DelegationResolveClient(운영_빌더,
                new AuthClientProperties(auth.baseUrl()),
                new InternalApiProperties("internal-token-for-test"));
    }
}
