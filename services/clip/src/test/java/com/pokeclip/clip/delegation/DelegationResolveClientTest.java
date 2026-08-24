package com.pokeclip.clip.delegation;

import com.pokeclip.clip.config.InternalApiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth의 {@code POST /internal/editor-delegations/resolve}를 부르는 쪽.
 * 계약 정본은 auth의 {@code DelegationResolveController}다 — <b>판정은 항상 HTTP 200</b>이고
 * {@code NONE}도 200이다. 거절할지 말지는 이쪽(clip)이 정한다.
 *
 * <p><b>UNAVAILABLE을 기대하는 갈래가 다섯이라 「늘 UNAVAILABLE」인 구현에 전부 초록이다.</b>
 * 그것을 막는 양성 대조가 {@link #OWNER를_그대로_돌려준다()}와
 * {@link #EDITOR와_NONE도_그대로다(String, ResolveResult)}다 — 둘을 지우지 마라.
 */
class DelegationResolveClientTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    /** 아무도 안 듣는 포트. 연결 자체가 거부된다 — 5xx와 다른 갈래다. */
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    /**
     * 가짜 auth가 붙들고 있는 시간. 아래 {@link #READ_TIMEOUT}보다 훨씬 길다.
     * 임계가 {@code READ_TIMEOUT×3}이라 둘을 같이 벌려 둔다 — 지연이 임계보다 짧으면
     * 시한을 안 거는 구현도 통과해 <b>판별력이 통째로 사라진다</b>(POK-127에서 밟은 자리).
     */
    private static final Duration AUTH_DELAY = Duration.ofSeconds(20);

    /**
     * 이 값이 실제로 걸리는지가 시한 검사의 표적이다. 운영값은 {@code application.yml}의
     * {@code spring.http.clients.read-timeout}(5s)이고, 그 값이 걸리는 것은 계획 검증이
     * 실측했다(5,031ms에 SocketTimeoutException).
     *
     * <p>1초가 아니라 3초인 이유는 chat-collector의 같은 검사가 간헐 실패로 데인 자리이기
     * 때문이다 — 전수 실행에서 다른 컨텍스트가 같이 뜰 때 <b>요청이 서버에 닿기도 전에</b>
     * 1초가 지나갔다.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private FakeAuth auth;

    @BeforeEach
    void startFakeAuth() {
        auth = FakeAuth.start();
    }

    @AfterEach
    void stopFakeAuth() {
        auth.close();
    }

    @Test
    void OWNER를_그대로_돌려준다() {
        auth.respondWith(200, "{\"relation\":\"OWNER\"}");

        assertThat(client().resolve(42L, 7L)).isEqualTo(ResolveResult.OWNER);
    }

    /**
     * 위 검사는 <b>auth를 아예 안 부르고 상수를 돌려주는 구현에도 초록</b>이다.
     * 요청이 실제로 나갔는지, 계약대로 생겼는지는 상대 쪽에서 센다.
     *
     * <p>헤더를 빠뜨리면 운영에서 <b>전부 401</b>이 되고, 401은 UNAVAILABLE로 접히므로
     * <b>모든 미리보기가 503</b>이 된다 — 서버는 UP이고 원인은 로그 한 줄뿐이다.
     *
     * <p>칸 이름은 {@code streamerId}가 아니라 <b>{@code streamerUserId}</b>다. 틀리면
     * auth의 {@code @Valid}가 400을 주고, 400도 UNAVAILABLE로 접혀 같은 증상이 된다.
     */
    @Test
    void 요청에_내부_토큰과_두_회원_번호가_실린다() {
        auth.respondWith(200, "{\"relation\":\"OWNER\"}");

        client().resolve(42L, 7L);

        // 값을 실어 둔다 — 0(요청이 서버에 닿기 전에 끝났다)과 2 이상(재시도)은 처방이 다르다.
        assertThat(auth.callCount())
                .as("요청이 실제로 나갔는가 — 0이면 서버에 닿기 전에 끝난 것, 2 이상이면 재시도")
                .isEqualTo(1);
        assertThat(auth.lastPath()).isEqualTo("/internal/editor-delegations/resolve");
        assertThat(auth.lastToken()).isEqualTo(INTERNAL_TOKEN);
        assertThat(auth.lastBody())
                .contains("\"userId\":42")
                .contains("\"streamerUserId\":7");
    }

    /**
     * <b>주소 끝의 슬래시가 자격 판정을 통째로 죽이지 않는가.</b>
     * {@code AUTH_BASE_URL=http://auth:8082/}는 주소 값에 흔한 취향이고 compose 기본값을 손으로
     * 바꾸는 자리다. {@code //internal/…}이 나가면 auth의 {@code /internal/**} 체인이 자기 것으로
     * 안 잡고, 4xx는 전부 UNAVAILABLE로 접히므로 <b>모든 미리보기가 503</b>이 된다 — 남는 로그는
     * {@code status=…} 한 줄뿐이라 읽는 사람은 주소가 아니라 토큰을 의심한다.
     *
     * <p><b>🔴 이 갈래가 지키는 것은 우리 코드의 정규화가 아니다 — 그런 코드는 없다.</b>
     * {@code .uri(String)}이 {@code DefaultUriBuilderFactory}를 타고, 그 층이 이중 슬래시를
     * 접어 준다(2026-08-24 실측: 자동설정 빌더 = 운영과 같은 Apache HC5로 끝 슬래시 0·1·2·3개를
     * 태워 도착 경로가 넷 다 {@code /internal/editor-delegations/resolve}였다).
     *
     * <p>그러면 무엇을 재는가 — <b>그 층을 우회하는 날</b>이다. {@code RestClient}에는
     * {@code uri(URI)} 오버로드가 있고 그것은 빌더 팩토리를 안 탄다. {@code java.net.URI}는
     * 경로를 정규화하지 않으므로({@code //internal/…}이 그대로 남는다, 같은 날 실측) 그 오버로드로
     * 바꾸는 순간 이중 슬래시가 실제로 나간다. <b>주입으로 확인했다</b> — {@code .uri(baseUrl + PATH)}를
     * {@code .uri(URI.create(baseUrl + PATH))}로 바꾸면 이 갈래 둘만 빨간불이 되고 나머지 16건은 초록이다.
     *
     * <p>경로를 <b>가짜 auth가 받은 값</b>으로 잰다 — 이쪽에서 만든 문자열을 다시 보면 아무것도
     * 확인하지 못한다. {@link FakeAuth}는 {@code "/"} 컨텍스트라 어떤 경로든 200을 주므로,
     * 판별하는 단언은 {@code lastPath()} 하나다(결과 단언은 양성 대조일 뿐이다).
     */
    @ParameterizedTest(name = "주소 끝 = \"{0}\"")
    @ValueSource(strings = {"/", "//"})
    void 주소_끝에_슬래시가_있어도_경로는_하나다(String trailing) {
        auth.respondWith(200, "{\"relation\":\"OWNER\"}");

        ResolveResult result = clientFor(auth.baseUrl() + trailing, INTERNAL_TOKEN).resolve(42L, 7L);

        assertThat(auth.callCount())
                .as("요청이 안 나갔으면 경로를 잰 것이 아니다")
                .isEqualTo(1);
        assertThat(auth.lastPath()).isEqualTo("/internal/editor-delegations/resolve");
        assertThat(result).isEqualTo(ResolveResult.OWNER);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"EDITOR, EDITOR", "NONE, NONE"})
    void EDITOR와_NONE도_그대로다(String relation, ResolveResult expected) {
        auth.respondWith(200, "{\"relation\":\"" + relation + "\"}");

        assertThat(client().resolve(42L, 7L)).isEqualTo(expected);
    }

    /**
     * fail-closed다. auth가 아플 때 통과시키면 <b>남의 방송 세그먼트가 열린다.</b>
     * 401은 토큰이 갈렸을 때, 500은 auth가 아플 때다 — 우리 쪽 처방이 다르지만
     * 판정은 같다(모르면 안 연다).
     */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {400, 401, 500, 503})
    void 오류_응답이면_UNAVAILABLE이다(int status) {
        auth.respondWith(status, "{\"relation\":\"OWNER\"}");

        assertThat(client().resolve(42L, 7L)).isEqualTo(ResolveResult.UNAVAILABLE);
    }

    @Test
    void 못_닿으면_UNAVAILABLE이다() {
        assertThat(clientFor(UNREACHABLE, INTERNAL_TOKEN).resolve(42L, 7L))
                .isEqualTo(ResolveResult.UNAVAILABLE);
    }

    /**
     * auth가 값을 늘리는 날 <b>통과가 아니라 거절이 기본</b>이다. 모르는 이름을 「권한 없음」이
     * 아니라 UNAVAILABLE로 접는 것은, 그 사이 진짜로 권한이 생긴 사람을 「없다」로 단정하지
     * 않기 위해서다 — 둘 다 문을 안 열지만 응답 코드와 로그가 다르다.
     *
     * <p>소문자와 빈 값도 같은 갈래다. {@code relation} 칸이 아예 없는 경우까지 넣는다 —
     * 그때 파서가 빈 문자열을 주면 「모르는 값」으로 접혀야 한다.
     */
    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {
            "{\"relation\":\"SUPER\"}",
            "{\"relation\":\"owner\"}",
            "{\"relation\":\"\"}",
            "{\"relation\":null}",
            "{}"})
    void 모르는_relation이면_UNAVAILABLE이다(String body) {
        auth.respondWith(200, body);

        assertThat(client().resolve(42L, 7L)).isEqualTo(ResolveResult.UNAVAILABLE);
    }

    /** auth 앞에 프록시가 HTML 오류 페이지를 끼워 넣는 날이 이 갈래다. */
    @Test
    void 응답이_JSON이_아니면_UNAVAILABLE이다() {
        auth.respondWith(200, "<html>Bad Gateway</html>");

        assertThat(client().resolve(42L, 7L)).isEqualTo(ResolveResult.UNAVAILABLE);
    }

    /**
     * 「주입받은 {@code RestClient.Builder}를 쓴다」를 값이 아니라 <b>행동</b>으로 잰다.
     * {@code RestClient.create()}로 되돌리면 시한이 어디에도 안 걸려 20초를 다 기다린다.
     *
     * <p>이 저장소가 이미 한 번 데인 자리다(chat-collector {@code CLAUDE.md} — 설정 파일은
     * 완벽한데 타임아웃이 어디에도 안 걸렸고 검토 일곱 바퀴가 못 잡았다). 여기서는 미리보기
     * 요청이 <b>톰캣 스레드를 무기한 쥔다</b> — auth 하나가 아프면 clip의 사람 문이 통째로 막힌다.
     *
     * <p>시간은 <b>가짜 서버가 요청을 받은 시각</b>부터 잰다 — 클라이언트 조립 시간이 섞이면
     * 무엇을 쟀는지 흐려진다(POK-84 선례: 6.375초 → 0.696초).
     */
    @Test
    void 시한이_실제로_걸려_있다() {
        auth.respondWith(200, "{\"relation\":\"OWNER\"}");
        auth.holdFor(AUTH_DELAY);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);

        ResolveResult result = new DelegationResolveClient(
                RestClient.builder().requestFactory(factory),
                new AuthClientProperties(auth.baseUrl()),
                new InternalApiProperties(INTERNAL_TOKEN)).resolve(42L, 7L);

        Duration heldFor = auth.sinceFirstRequest();
        // 양성 대조. 요청이 아예 안 나갔다면 위 시간은 아무것도 안 잰 것이다.
        assertThat(auth.callCount())
                .as("요청을 안 보냈으면 시한을 잰 것이 아니다")
                .isEqualTo(1);
        assertThat(heldFor)
                .as("가짜 auth가 붙들고 있는 " + AUTH_DELAY.toSeconds()
                        + "초를 다 기다렸다면 주입받은 빌더를 안 쓴 것이다")
                .isLessThan(READ_TIMEOUT.multipliedBy(3));
        assertThat(result).isEqualTo(ResolveResult.UNAVAILABLE);
    }

    private DelegationResolveClient client() {
        return clientFor(auth.baseUrl(), INTERNAL_TOKEN);
    }

    /**
     * <b>요청 팩토리를 JDK로 못박는다.</b> 아무것도 안 고르면 Apache HC5가 잡힌다 —
     * AWS SDK가 httpclient5를 클래스패스에 올려 두기 때문이다. 그 스택의 wire·headers 로거는
     * DEBUG에서 <b>나가는 헤더를 통째로</b> 찍는다(계획 검증 F4 실측 2건).
     *
     * <p>여기서 JDK로 고정하는 것은 <b>이 검사의 표적이 우리 코드</b>이기 때문이다.
     * 운영 스택(Apache HC5)에서 토큰이 안 새는지는 {@code AuthClientTokenLeakTest}가
     * 진짜 컨텍스트의 빌더로 따로 잰다 — 한쪽을 고치면 다른 쪽도 본다.
     */
    private DelegationResolveClient clientFor(String baseUrl, String internalToken) {
        return new DelegationResolveClient(
                RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()),
                new AuthClientProperties(baseUrl),
                new InternalApiProperties(internalToken));
    }
}
