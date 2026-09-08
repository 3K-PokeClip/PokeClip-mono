package com.pokeclip.clip.collector;

import com.pokeclip.clip.config.CollectorHttpConfig;
import com.pokeclip.clip.config.HttpClientRetryConfig;
import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.support.FakeCollector;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * clip이 수집기 창구를 부르는 쪽. <b>clip이 두 번째 서버를 HTTP로 부르는 첫 코드다</b>
 * (첫째는 auth 자격 판정).
 *
 * <p><b>4xx를 예외로 바꾸지 않는 것이 이 클래스의 축이다.</b> 수집기의 400 본문은
 * {@code {"error":"…"}}로 이미 정해져 있고 프론트가 그 낱말을 읽는다 — 여기서 503으로 접으면
 * 「내가 잘못 물었다」가 「서버가 아프다」로 둔갑해 화면이 다시 시도한다.
 *
 * <p><b>「늘 unavailable」인 구현에 초록인 갈래가 셋이다.</b> 양성 대조가
 * {@link #이백과_사백은_그대로_돌려준다()}이고, 지우면 나머지가 아무것도 안 잰다.
 *
 * <p>컨텍스트를 안 띄운다 — 표적이 우리 코드이고, 스프링 배선은
 * {@code BroadcastChatControllerTest}가 진짜 문을 태워 잰다.
 */
class CollectorClientTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    private static final String 문 = "/internal/streams/s-1/chat-messages";

    /** 아무도 안 듣는 포트. 연결 자체가 거부된다 — 5xx와 다른 갈래다. */
    private static final String 못_닿는_주소 = "http://127.0.0.1:1";

    /**
     * 가짜 수집기가 붙들고 있는 시간. 읽기 시한(3초)보다 훨씬 길다 — 지연이 시한보다 짧으면
     * <b>시한을 안 거는 구현도 통과해 판별력이 통째로 사라진다</b>(POK-127에서 밟은 자리).
     */
    private static final Duration 오래_붙든다 = Duration.ofSeconds(10);

    /**
     * 시한이 걸렸다고 볼 상한. 운영값 {@code connect 2s + read 3s}보다 넉넉하고, 위 지연보다는
     * 짧다 — 그 사이가 아니면 이 단언은 「지연이 아예 안 심겼다」와 구분되지 않는다.
     */
    private static final Duration 시한_상한 = Duration.ofSeconds(6);

    private FakeCollector collector;

    @BeforeEach
    void 가짜_수집기를_띄운다() {
        collector = FakeCollector.start();
    }

    @AfterEach
    void 가짜_수집기를_내린다() {
        collector.close();
    }

    @Test
    void 이백과_사백은_그대로_돌려준다() {
        collector.respondWith(문, 200, "{\"items\":[]}");
        assertThat(client(collector.baseUrl()).get(문, Map.of()))
                .isEqualTo(new CollectorResponse(200, "{\"items\":[]}"));

        collector.respondWith(문, 400, "{\"error\":\"inverted_window\"}");
        CollectorResponse 사백 = client(collector.baseUrl()).get(문, Map.of());
        assertThat(사백.status()).isEqualTo(400);
        assertThat(사백.body())
                .as("본문을 우리가 다시 쓰면 프론트가 읽는 사유 낱말이 사라진다")
                .isEqualTo("{\"error\":\"inverted_window\"}");
    }

    /**
     * 🔴 <b>통과하는 4xx는 400 하나다.</b> 나머지는 「수집기가 아프다」로 접는다 — 수집기가 실제로
     * 내는 4xx가 셋인데({@code QueryErrors}의 400 · {@code InternalTokenFilter}의 401 ·
     * <b>경로가 없을 때의 스프링 기본 404</b>) 뒤 둘은 clip의 계약에서 <b>다른 뜻</b>이다.
     *
     * <ul>
     *   <li><b>401</b> — clip에서는 「사용자 토큰 만료」다({@code JumpCardExceptionHandler}).
     *       수집기의 401은 <b>내부 토큰</b> 불일치·빈 토큰이라 사용자가 못 고치는데, 화면이
     *       재로그인을 시키고 다시 401을 받는다
     *   <li>🔴 <b>404</b> — clip에서는 「없는 방송·자격 없음」이다. 수집기의 404는
     *       <b>롤링 배포에서 반드시 지나간다</b>(clip이 먼저 뜨고 수집기가 아직 옛 이미지인 구간).
     *       그때 <b>실재하는 자기 방송</b>에 「없는 방송입니다」가 뜬다 — 유실보다 나쁘다,
     *       화면이 그럴듯해서 아무도 안 본다
     * </ul>
     *
     * <p>403·410·429는 <b>지금 수집기에 그 코드를 내는 자리가 없다</b>(전수: 403을 만드는 자리 0건,
     * 창구가 GET 하나뿐이라 405도 없다). 그래도 같이 재는 것은 규칙이 「400 <b>말고는</b>」이기
     * 때문이다 — 코드를 하나씩 열거하는 구현으로 좁혀지면 나중에 느는 코드가 조용히 통과한다.
     *
     * <p>본문에 {@code path}를 실어 두는 것은 스프링 기본 404 본문을 흉내 낸 것이다
     * ({@code server.error.include-path} 기본값이 {@code ALWAYS}이고 수집기 yml에 재정의가 없다).
     * 접는 처방이 <b>수집기 내부 경로가 브라우저로 나가는 것</b>도 같이 닫는다.
     *
     * <p>양성 대조는 {@link #이백과_사백은_그대로_돌려준다()}이다 — 없으면 「늘 unavailable」인
     * 구현에도 이 갈래가 초록이다.
     */
    @ParameterizedTest(name = "수집기 상태={0}")
    @ValueSource(ints = {401, 403, 404, 410, 429})
    void 사백_말고는_전부_unavailable이다(int 상태) {
        collector.respondWith(문, 상태,
                "{\"timestamp\":\"…\",\"path\":\"/internal/streams/s-1/chat-messages\"}");

        assertThatThrownBy(() -> client(collector.baseUrl()).get(문, Map.of()))
                .as("수집기의 %d가 상태·본문 그대로 프론트까지 간다", 상태)
                .isInstanceOf(CollectorErrors.CollectorUnavailableException.class);
    }

    @Test
    void 오백은_unavailable이다() {
        collector.respondWith(문, 500, "{\"whatever\":1}");

        assertThatThrownBy(() -> client(collector.baseUrl()).get(문, Map.of()))
                .isInstanceOf(CollectorErrors.CollectorUnavailableException.class);
        assertThat(collector.callCount())
                .as("0이면 요청이 안 나갔고, 2 이상이면 되걸린 것이다 — 둘 다 처방이 다르다")
                .isEqualTo(1);
    }

    @Test
    void 못_닿아도_unavailable이다() {
        assertThatThrownBy(() -> client(못_닿는_주소).get(문, Map.of()))
                .isInstanceOf(CollectorErrors.CollectorUnavailableException.class);
    }

    /**
     * <b>값이 아니라 행동으로 잰다.</b> 「설정에 2s·3s라고 적혀 있다」는 시한이 어디에도 안 걸린
     * 상태에서도 참이다 — 이 저장소가 이미 한 번 데인 자리다({@code chat-collector/CLAUDE.md}
     * 「{@code RestClient.create()}를 쓰면 설정이 통째로 무시된다」).
     */
    @Test
    void 시한이_실제로_걸려_있다() {
        collector.holdFor(오래_붙든다);

        long 시작 = System.nanoTime();
        assertThatThrownBy(() -> client(collector.baseUrl()).get(문, Map.of()))
                .isInstanceOf(CollectorErrors.CollectorUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - 시작))
                .as("시한이 어디에도 안 걸렸다 — 수집기가 답을 안 하면 톰캣 스레드를 무기한 쥔다")
                .isLessThan(시한_상한);
    }

    /**
     * 헤더를 빠뜨리면 수집기가 <b>전부 401</b>을 주고, 401은 4xx라 그대로 프론트에 나간다 —
     * 화면은 「로그인이 풀렸다」로 오진한다.
     */
    @Test
    void 내부_토큰과_쿼리를_실어_보낸다() {
        collector.respondWith(문, 200, "{}");

        client(collector.baseUrl()).get(문, Map.of("from", "2026-09-01T00:00:00Z", "limit", "50"));

        assertThat(collector.lastToken()).isEqualTo(INTERNAL_TOKEN);
        assertThat(collector.lastPath()).isEqualTo(문);
        assertThat(collector.lastQuery())
                .contains("limit=50")
                // 콜론은 쿼리에서 예약어가 아니라 날것으로 나가도 된다(RFC 3986) — 실측으로
                // 확인했다. 여기서 재는 것은 「시각이 온전히 갔는가」이지 인코딩 모양이 아니다.
                .contains("from=2026-09-01T00:00:00Z");
    }

    /**
     * 🔴 <b>값 안의 {@code &}가 날것으로 나가면 쿼리 하나가 둘로 쪼개진다.</b> 커서는 우리가
     * 만든 값이지만 <b>프론트가 되돌려 보내는 값</b>이라 손댈 수 있고, 쪼개지면 수집기가
     * 안 물어본 칸을 받는다 — 쪼개진 뒤쪽이 {@code channelId}면 허용 목록이 무의미해진다.
     */
    @Test
    void 값에_섞인_구분자가_쿼리를_쪼개지_않는다() {
        collector.respondWith(문, 200, "{}");

        client(collector.baseUrl()).get(문, Map.of("cursor", "h:1&channelId=남의채널"));

        assertThat(collector.lastQuery())
                .as("값 안의 &가 그대로 나가 칸이 둘이 됐다")
                .doesNotContain("&channelId=");
    }

    /**
     * 🔴 <b>주소가 비어도 부팅은 살아야 한다.</b> auth 주소는 비면 부팅 거부인데
     * ({@code AuthClientProperties.validate}) 여기는 조건부다 — 자격 판정이 없으면 사람 문 전부가
     * 무방비지만, 수집기가 없으면 <b>채팅 문 셋만</b> 못 열리고 카드 편집은 그대로 된다.
     */
    @Test
    void 주소가_비면_부팅_대신_unavailable이다() {
        CollectorClient 꺼진_것 = client("");

        assertThat(꺼진_것.enabled()).isFalse();
        assertThatThrownBy(() -> 꺼진_것.get(문, Map.of()))
                .isInstanceOf(CollectorErrors.CollectorUnavailableException.class);
    }

    /**
     * <b>운영 빈 메서드를 그대로 부른다</b> — 시한을 거는 배선이 그 안에 있으므로, 여기서 손으로
     * 다시 조립하면 이 클래스의 시한 검사가 <b>운영 코드를 안 재게</b> 된다.
     *
     * <p>팩토리 빌더만 {@code detect()}다. 운영에서는 {@link HttpClientRetryConfig}가 만든 것이
     * 주입되는데 그 빈 메서드는 패키지 밖에서 못 부른다 — 되걸기가 꺼져 있다는 것은
     * {@code AuthRetryContractTest}가 <b>진짜 컨텍스트로</b> 따로 잰다. 여기서 그것을 재려는 것이
     * 아니고, {@code detect()}가 고르는 스택은 같다(AWS SDK가 올린 Apache HC5).
     */
    private CollectorClient client(String baseUrl) {
        CollectorClientProperties properties =
                new CollectorClientProperties(baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(3));
        RestClient restClient = new CollectorHttpConfig().수집기_전용_RestClient(
                RestClient.builder(),
                ClientHttpRequestFactoryBuilder.detect(),
                properties);
        return new CollectorClient(restClient, properties, new InternalApiProperties(INTERNAL_TOKEN));
    }
}
