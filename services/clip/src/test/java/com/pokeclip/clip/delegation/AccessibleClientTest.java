package com.pokeclip.clip.delegation;

import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.support.FakeAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth의 {@code POST /internal/editor-delegations/accessible}을 부르는 쪽.
 * 방송 목록 화면이 「내가 볼 수 있는 스트리머 전부」를 여기서 받는다.
 *
 * <p><b>거절을 기대하는 갈래가 많아 「늘 unavailable」인 구현에 대부분 초록이다.</b>
 * 그것을 막는 양성 대조가 {@link #목록을_받아_번호와_관계로_읽는다()}와
 * {@link #빈_목록은_못_물은_것이_아니라_참인_답이다()}다 — 둘을 지우지 마라.
 *
 * <p>{@code resolve}를 재는 {@link DelegationResolveClientTest}와 같은 클래스를 태우지만
 * 갈래가 겹치지 않는다. 여기는 <b>목록</b>이라 「한 줄이 이상할 때 무엇을 하나」가 새로 생긴다.
 */
class AccessibleClientTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";

    @Test
    void 목록을_받아_번호와_관계로_읽는다() {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200,
                    "{\"streamers\":[{\"streamerUserId\":7,\"relation\":\"OWNER\"},"
                            + "{\"streamerUserId\":3,\"relation\":\"EDITOR\"}]}");

            AccessibleResult result = clientFor(auth).accessible(7L);

            assertThat(result.available()).isTrue();
            assertThat(result.streamers()).containsExactly(
                    new AccessibleResult.Entry(7L, ResolveResult.OWNER),
                    new AccessibleResult.Entry(3L, ResolveResult.EDITOR));
        }
    }

    /**
     * 🔴 <b>빈 목록과 못 물은 것을 가르는 자리다.</b> 빈 목록은 「볼 방송이 없다」는 참인 답이라
     * 화면이 「방송이 없습니다」를 띄워도 옳다. 이 갈래가 없으면 위 갈래 하나만으로는
     * 「목록이 비면 실패로 접는」 구현도 통과한다.
     */
    @Test
    void 빈_목록은_못_물은_것이_아니라_참인_답이다() {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200, "{\"streamers\":[]}");

            AccessibleResult result = clientFor(auth).accessible(7L);

            assertThat(result.available()).isTrue();
            assertThat(result.streamers()).isEmpty();
        }
    }

    /**
     * 🔴 <b>빈 목록이 아니다.</b> 빈 목록은 「볼 방송이 없다」는 참인 답이라 화면이 「방송이
     * 없다」고 단정한다 — 편집자는 auth가 살아난 뒤에도 다시 시도하지 않는다.
     */
    @Test
    void 못_물으면_available이_거짓이다() {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 500, "");

            assertThat(clientFor(auth).accessible(7L).available()).isFalse();
        }
    }

    /**
     * 그 줄만 버리지 않는다. auth가 관계 값을 늘리는 날 「모르는 관계」를 조용히 빼면
     * <b>볼 수 있는 방송이 목록에서 사라지는데 화면에는 그냥 없는 것으로 보인다.</b>
     * 통째로 거절하면 503이라 사람이 다시 누르고 로그가 남는다.
     *
     * <p>성한 줄을 <b>같이 실어</b> 보낸다 — 목록이 한 줄뿐이면 「거절」과 「그 줄만 버림」의
     * 결과가 둘 다 빈 목록이라 구분되지 않는다.
     */
    @Test
    void 모르는_관계값이_섞이면_그_줄만_버리지_않고_통째로_거절한다() {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200,
                    "{\"streamers\":[{\"streamerUserId\":7,\"relation\":\"OWNER\"},"
                            + "{\"streamerUserId\":3,\"relation\":\"ADMIN\"}]}");

            AccessibleResult result = clientFor(auth).accessible(7L);

            assertThat(result.available()).isFalse();
            assertThat(result.streamers())
                    .as("성한 줄만 남기면 「볼 수 있는데 목록에 없는」 방송이 조용히 생긴다")
                    .isEmpty();
        }
    }

    /**
     * 🔴 <b>번호도 관계 값과 같은 규칙으로 엄격하게 읽는다.</b> Jackson의 {@code asLong()}은
     * 칸이 없거나 숫자가 아니면 <b>조용히 0</b>을 준다. 그 0으로 방송을 찾으면 0건이므로
     * <b>틀린 목록이 「참인 답」으로 나간다</b> — 화면은 「방송이 없다」고 단정하고 아무 로그도 없다.
     *
     * <p>정수가 아닌 수({@code 7.5})도 거절한다. {@code isNumber()}만 보면 통과하고
     * {@code 7}로 잘려 <b>남의 방송 목록</b>이 나갈 수 있다 — 회원 번호는 정수다.
     * 계획 검증(m7)의 처방보다 한 칸 엄격한 쪽인데, 두 방향 다 문을 안 열고
     * <b>좁은 것을 넓히는 편이 반대보다 안전하다.</b>
     */
    @ParameterizedTest(name = "줄={0}")
    @ValueSource(strings = {
            "{\"streamerUserId\":\"7\",\"relation\":\"OWNER\"}",
            "{\"relation\":\"OWNER\"}",
            "{\"streamerUserId\":null,\"relation\":\"OWNER\"}",
            "{\"streamerUserId\":7.5,\"relation\":\"OWNER\"}"})
    void 스트리머_번호가_정수가_아니면_통째로_거절한다(String line) {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200, "{\"streamers\":[" + line + "]}");

            assertThat(clientFor(auth).accessible(7L).available()).isFalse();
        }
    }

    /**
     * 🔴 <b>칸 이름 하나만 믿고 있었다</b>(감사 1라운드가 잡았다). {@code relation}과
     * {@code streamerUserId}는 안 믿으면서 그것들을 담은 <b>그릇</b>은 믿었다 —
     * 칸이 없거나 배열이 아니면 순회가 통째로 비어 {@code available=true}에 빈 목록,
     * 즉 <b>「볼 방송이 없다」는 참인 답</b>이 나갔다. {@code unavailableList}를 안 타므로
     * WARN 한 줄도 안 남아 <b>발견 수단이 아예 없다.</b>
     *
     * <p>여섯 모양을 다 넣는다 — 실측으로 <b>여섯 다</b> 같은 증상이었다. 오타난 칸
     * ({@code streamer})을 넣은 것은 이것이 「auth가 값을 늘리는 날」만이 아니라
     * <b>우리가 칸 이름을 잘못 적는 날</b>의 그물이기도 하기 때문이다.
     *
     * <p>오늘 auth 실물은 항상 {@code {"streamers":[...]}}를 주므로 도달 경로가 없다.
     * 그래도 막는 것은 이 클라이언트의 설계 원칙이 <b>「auth가 값을 늘리는 날」을 안 믿는 것</b>이고,
     * {@code contracts/}에 정본이 아직 없기 때문이다.
     */
    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {
            "{}",
            "{\"streamers\":null}",
            "{\"streamers\":\"x\"}",
            "{\"streamers\":{}}",
            "{\"streamers\":7}",
            "{\"streamer\":[]}"})
    void streamers가_배열이_아니면_빈_목록이_아니라_거절이다(String body) {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200, body);

            AccessibleResult result = clientFor(auth).accessible(7L);

            assertThat(result.available())
                    .as("빈 목록으로 접으면 화면이 「방송이 없다」고 단정하고 아무도 모른다")
                    .isFalse();
        }
    }

    /**
     * 위 갈래들은 <b>auth를 아예 안 부르고 상수를 돌려주는 구현에도 초록</b>이다.
     * 요청이 실제로 나갔는지, 계약대로 생겼는지는 상대 쪽에서 센다.
     *
     * <p>헤더를 빠뜨리면 운영에서 전부 401이 되고, 401은 「못 물음」으로 접히므로
     * <b>방송 목록이 통째로 503</b>이 된다 — 서버는 UP이고 원인은 로그 한 줄뿐이다.
     */
    @Test
    void 내부_토큰과_경로가_계약대로다() {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200, "{\"streamers\":[]}");

            clientFor(auth).accessible(7L);

            assertThat(auth.callCount())
                    .as("요청이 안 나갔으면 헤더를 잰 것이 아니다 — 2 이상이면 재시도다")
                    .isEqualTo(1);
            assertThat(auth.lastToken()).isEqualTo(INTERNAL_TOKEN);
            assertThat(auth.lastPath()).isEqualTo(ACCESSIBLE);
        }
    }

    /**
     * 🔴 칸은 {@code userId} 하나다. {@code streamerUserId} 같은 옛 이름을 같이 실어 보내면
     * auth가 <b>400이 아니라 200</b>을 준다 — 모르는 필드를 조용히 버린다(README auth 절).
     * 오타를 아무도 안 알려주므로 이 갈래가 유일한 그물이다.
     */
    @Test
    void 요청_본문에_userId만_싣는다() {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(ACCESSIBLE, 200, "{\"streamers\":[]}");

            clientFor(auth).accessible(7L);

            assertThat(auth.lastBody()).isEqualTo("{\"userId\":7}");
        }
    }

    /**
     * 요청 팩토리를 JDK로 못박는 이유는 {@link DelegationResolveClientTest#clientFor}와 같다 —
     * 아무것도 안 고르면 Apache HC5가 잡히고 그 스택의 wire 로거가 헤더를 통째로 찍는다.
     * 운영 스택에서 토큰이 안 새는지는 {@code AuthClientTokenLeakTest}가 따로 잰다.
     */
    private DelegationResolveClient clientFor(FakeAuth auth) {
        return new DelegationResolveClient(
                RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()),
                new AuthClientProperties(auth.baseUrl()),
                new InternalApiProperties(INTERNAL_TOKEN));
    }
}
