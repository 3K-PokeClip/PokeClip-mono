package com.pokeclip.clip.segment.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.pokeclip.clip.delegation.AuthClientTokenLeakTest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>사람의 액세스 토큰이 로그로 새는지 잰다.</b> 이 서버의 사람 문에는
 * {@code Authorization: Bearer <JWT>}가 실리고, 그 토큰 하나면 그 편집자가 되어 남의 방송을 연다.
 *
 * <p><b>표적은 우리 코드가 아니라 {@code application.yml}의 {@code logging.level} 두 줄이다</b>
 * ({@code AuthClientTokenLeakTest}가 「나가는」 쪽에서 재는 것과 같은 구조). 들어오는 쪽에는
 * <b>토큰을 통째로 찍는 자리가 둘</b>이고 갈래마다 스위치가 다르다:
 *
 * <table>
 *   <caption>들어오는 요청의 헤더를 찍는 자리 둘</caption>
 *   <tr><th>찍는 것</th><th>켜지는 조건</th><th>막는 줄</th><th>재는 갈래</th></tr>
 *   <tr><td>톰캣 {@code Http11InputBuffer}</td><td>{@code root=TRACE}</td>
 *       <td>{@code org.apache.coyote: info}</td><td>아래 첫 갈래</td></tr>
 *   <tr><td>{@code DispatcherServlet}</td><td>{@code root=TRACE} <b>+</b>
 *       {@code spring.mvc.log-request-details=true}</td>
 *       <td>{@code org.springframework.web: info}</td><td>아래 둘째 갈래</td></tr>
 * </table>
 *
 * <p><b>MockMvc로는 못 잰다.</b> 그쪽은 톰캣을 안 지나가서 첫 로거가 아예 안 돈다 —
 * {@code SegmentControllerTest}와 이 검사를 나눈 이유가 그것 하나다.
 *
 * <p><b>탐지기는 {@code AuthClientTokenLeakTest}의 것 하나를 쓴다</b> — 복사하면 둘이 갈리고
 * 느슨해진 쪽이 조용히 초록이 된다(POK-118의 {@code SseReader} 사고).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SegmentApiTokenLeakTest extends IntegrationTestSupport {

    private static final String COYOTE = "org.apache.coyote";

    private static final String SPRING_WEB = "org.springframework.web";

    /** 실제로 요청 바이트를 찍는 클래스. yml이 막는 것은 트리 전체이고 탐침은 그 잎 하나에 건다. */
    private static final String RECEIVED_LOGGER = "org.apache.coyote.http11.Http11InputBuffer";

    /**
     * 캡터 자기검사가 쓰는 <b>트리 밖</b> 로거. 이름이 {@code org.apache.coyote}로 <b>시작</b>하지만
     * 점 경계가 달라 자식이 아니다 — logback 계층은 점으로 끊는다. 일부러 이렇게 지었다:
     * 「이름이 비슷하면 같이 눌린다」고 잘못 알고 있으면 그 갈래가 빨간불이 되어 알려 준다.
     */
    private static final String OUTSIDE_LOGGER = "org.apache.coyoteXX.probe";

    /** 없는 방송이라 404다 — 자격 창구를 안 부르므로 가짜 빈이 필요 없다. */
    private static final String PROBE = "/api/clip/broadcasts/leak-probe/segments?startMs=0&endMs=1000";

    /**
     * 🔴 <b>JUL은 로거를 약한 참조로 들고 있다.</b> 우리가 만든 핸들러와, 레벨을 건 로거를
     * 여기 붙들어 두지 않으면 긴 실행 중에 회수돼 <b>기록이 통째로 안 만들어진다</b> —
     * 이 카드 구현 중에 세 번 밟은 간헐 실패가 정확히 그것이었다.
     */
    private static final List<Object> JUL_강참조 = Collections.synchronizedList(new ArrayList<>());

    private final int port;
    private final Environment environment;
    private final DispatcherServlet dispatcherServlet;

    SegmentApiTokenLeakTest(@LocalServerPort int port, Environment environment,
                            DispatcherServlet dispatcherServlet) {
        this.port = port;
        this.environment = environment;
        this.dispatcherServlet = dispatcherServlet;
    }

    /**
     * 🔴 <b>이 갈래는 두 가지를 <i>동시에</i> 못 박는다.</b>
     * ① 톰캣이 <b>지금도</b> 그 헤더를 통째로 찍는다 — 조용해지는 날 ②는 아무것도 안 본 채
     * 초록이 되는데, 그 상태를 여기서 잡는다. ② 그런데 logback까지는 안 온다 —
     * {@code org.apache.coyote: info} 한 줄이 실제로 막는다.
     *
     * <p><b>표적이 잎({@code Http11InputBuffer})인 것이 핵심이다.</b> 부모({@code org.apache.coyote})에
     * 레벨·핸들러를 걸면 <b>전체 실행에서만 간헐로</b> 기록이 0건이 된다 — JUL이 로거를 약한 참조로
     * 들고 있어 아무도 안 붙드는 부모 노드가 회수되기 때문이다(이 카드 구현 중 세 번 실측).
     * 잎은 톰캣이 {@code static final Log}로 강하게 붙들고 있어 그 함정이 없고, JUL은 자기 레벨이
     * 있으면 부모 사슬을 안 걷는다. 감사 2회차가 이 표적으로 단독·전체 실행에서 같은 값을 냈다
     * ({@code julRecords=4 julWithAuthorizationHeader=1 logbackLeak=0}).
     *
     * <p>레벨이 {@code FINER}인 것은 <b>톰캣의 TRACE = JUL FINER</b>여서다. 브릿지는 그것을
     * logback DEBUG로 번역하므로 {@code root=DEBUG}에서는 안 보인다 — 조건은 {@code root=TRACE} 하나다.
     *
     * <p>{@code chat-collector}의
     * {@code ChatCollectionEndpointTest.창구를_쳐도_내부_토큰이_root_TRACE에서_로그에_안_남는다}가
     * 같은 자리를 지키는데(POK-128), <b>그쪽 양성 대조는 부모 로거를 미는 방식이라 같은 간헐
     * 실패가 잠재한다.</b> 처방도 같다 — 표적을 잎으로 바꾸면 된다. 남의 카드 범위라 여기서는
     * 고치지 않고 적어만 둔다.
     */
    @Test
    void 미리보기를_쳐도_액세스_토큰이_root_TRACE에서_로그에_안_남는다() throws Exception {
        박혀_있나(COYOTE);

        String 토큰 = TestTokens.access("42");
        String 대조_바늘 = "LEAK-control-" + UUID.randomUUID();

        java.util.logging.Logger 잎 = java.util.logging.Logger.getLogger(RECEIVED_LOGGER);
        List<LogRecord> 톰캣_기록 = Collections.synchronizedList(new ArrayList<>());
        Handler 탐침 = 모으는_핸들러(톰캣_기록);
        java.util.logging.Level 잎_원래_레벨 = 잎.getLevel();
        JUL_강참조.add(잎);
        JUL_강참조.add(탐침);
        잎.addHandler(탐침);
        잎.setLevel(java.util.logging.Level.FINER);
        try (LogCaptor captor = new LogCaptor()) {
            Level rootBefore = AuthClientTokenLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
            AuthClientTokenLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
            try {
                // 진짜 요청. 운영자가 LOGGING_LEVEL_ROOT=trace를 켠 상황 그대로다.
                assertThat(get(토큰).statusCode())
                        .as("요청이 톰캣을 안 지나갔다면 아래 부정 단언은 아무것도 안 본 것이다")
                        .isEqualTo(404);

                // 캡터 자기검사 — 캡터가 죽어 있거나 root가 안 내려갔으면 ②는 공짜로 통과한다.
                LoggerFactory.getLogger(OUTSIDE_LOGGER)
                        .debug("Received [GET {} HTTP/1.1 Authorization: Bearer {}]", PROBE, 대조_바늘);
            } finally {
                AuthClientTokenLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
            }

            // ① 톰캣이 지금도 찍는다. 이 단언이 빨간불이면 톰캣이 조용해진 것이고,
            //    그때는 yml의 그 줄이 아직 필요한지 다시 본다 — 지우라는 신호가 아니다.
            assertThat(톰캣_기록.stream().map(SegmentApiTokenLeakTest::본문).toList())
                    .as("톰캣이 " + RECEIVED_LOGGER + "로 요청 헤더를 안 찍는다 — ②가 재는 대상이 사라졌다")
                    .anyMatch(m -> m.contains("Authorization") && m.contains(토큰));

            assertThat(captor.events())
                    .as("트리 밖 DEBUG가 안 잡혔다 — 캡터나 root 레벨이 죽었고 ②는 아무것도 안 잰 것이다")
                    .anyMatch(e -> AuthClientTokenLeakTest.renderFully(e).contains(대조_바늘));

            // ② 그런데 logback까지는 안 온다. yml에서 COYOTE 줄을 지우면 여기가 빨간불이다.
            AuthClientTokenLeakTest.assertNoSecretsIn(captor, List.of(토큰));
        } finally {
            잎.removeHandler(탐침);
            잎.setLevel(잎_원래_레벨);
            JUL_강참조.remove(탐침);
        }
    }

    /**
     * 🔴 <b>{@code org.springframework.web: info}는 「방어 깊이」가 아니라 유일한 차단막이다.</b>
     * 구현 중에는 「그 줄만 지워도 전부 초록」이라 그물이 없는 줄로 알았는데, <b>스위치가 둘이라
     * 그 설정에서만 안 보인 것</b>이었다 — 감사 2회차가 세 갈래로 갈라 반증했다:
     *
     * <table>
     *   <caption>{@code root=TRACE} 고정, 나머지 둘을 갈라 실측</caption>
     *   <tr><th>{@code spring.mvc.log-request-details}</th><th>그 핀</th><th>결과</th></tr>
     *   <tr><td>기본(false)</td><td>제거</td><td>유출 0</td></tr>
     *   <tr><td><b>true</b></td><td><b>제거</b></td><td>🔴 <b>JWT 유출</b></td></tr>
     *   <tr><td>true</td><td>유지</td><td>유출 0 — 그 줄이 막는다</td></tr>
     * </table>
     *
     * <p>{@code spring.mvc.log-request-details}는 Boot의 공식 프로퍼티이고 <b>그 자신의 문서가
     * 「민감 정보를 찍을 수 있다」고 경고한다.</b> 사람이 그것을 켜는 순간은 정확히 이 문을
     * 디버깅할 때이고, 그것은 {@code org.apache.coyote} 핀을 정당화한 근거와 <b>같은 논증</b>이다.
     *
     * <p><b>yml로 켜지 않고 런타임에 토글한다</b>({@code @TestPropertySource}로 켜면 스프링
     * 컨텍스트가 하나 더 생긴다). 운영 설정을 바꾸는 것이 아니라 시험이 잠깐 켜는 것이다.
     */
    @Test
    void 요청_상세_로깅을_켜도_액세스_토큰이_로그에_안_남는다() throws Exception {
        박혀_있나(SPRING_WEB);

        String 토큰 = TestTokens.access("42");
        boolean 원래_상세 = dispatcherServlet.isEnableLoggingRequestDetails();
        dispatcherServlet.setEnableLoggingRequestDetails(true);
        try {
            // ① 핀이 살아 있는 채로 진짜 요청 → 안 샌다.
            try (LogCaptor captor = new LogCaptor()) {
                요청을_태운다(captor, 토큰, null);
                AuthClientTokenLeakTest.assertNoSecretsIn(captor, List.of(토큰));
            }

            // ② 양성 대조 — 핀이 없는 상태(= root=TRACE를 그대로 상속)를 만들면 실제로 샌다.
            //    이것이 없으면 ①은 「스프링이 원래 아무것도 안 찍는다」와 구분되지 않는다.
            try (LogCaptor captor = new LogCaptor()) {
                요청을_태운다(captor, 토큰, Level.TRACE);

                assertThat(누출된_로거(captor, 토큰))
                        .as("핀을 풀어도 안 샌다 — 스프링이 더 이상 헤더를 안 찍는 것이고, "
                                + "그때는 yml의 " + SPRING_WEB + " 줄이 아직 필요한지 다시 본다")
                        .isNotNull()
                        .startsWith(SPRING_WEB);
            }
        } finally {
            dispatcherServlet.setEnableLoggingRequestDetails(원래_상세);
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /**
     * 두 겹으로 본다 — yml에 박혀 있나({@code Environment}) + 부팅이 logback까지 박았나.
     * 기본값을 주지 않는다: 주면 yml에서 줄이 사라져도 초록이다.
     */
    private void 박혀_있나(String 로거) {
        String level = environment.getProperty("logging.level." + 로거);
        assertThat(level).as("application.yml에 " + 로거 + " 레벨이 박혀 있어야 root를 내려도 버틴다")
                .isNotNull();
        assertThat(Level.toLevel(level, Level.TRACE).toInt())
                .as(로거 + "가 이 레벨이면 양성 대조가 재현하는 유출이 열린다: " + level)
                .isGreaterThanOrEqualTo(Level.INFO.toInt());
        assertThat(AuthClientTokenLeakTest.levelOf(로거))
                .as(로거 + " 프로퍼티가 Environment에만 있고 logback까지 안 닿았다").isNotNull();
    }

    /**
     * {@code root=TRACE}에서 진짜 요청 하나를 태운다. {@code 푸는_로거_레벨}이 있으면
     * {@link #SPRING_WEB} 핀을 그 값으로 잠깐 눌러 「핀이 없는 상태」를 만든다.
     */
    private void 요청을_태운다(LogCaptor captor, String 토큰, Level 푸는_로거_레벨) throws Exception {
        Level rootBefore = AuthClientTokenLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
        Level webBefore = AuthClientTokenLeakTest.levelOf(SPRING_WEB);
        AuthClientTokenLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
        if (푸는_로거_레벨 != null) {
            AuthClientTokenLeakTest.setLevel(SPRING_WEB, 푸는_로거_레벨);
        }
        try {
            assertThat(get(토큰).statusCode())
                    .as("요청이 DispatcherServlet을 안 지나갔다면 이 갈래는 아무것도 안 본 것이다")
                    .isEqualTo(404);
        } finally {
            AuthClientTokenLeakTest.setLevel(SPRING_WEB, webBefore);
            AuthClientTokenLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
        }
    }

    /** 바늘이 실제로 찍힌 첫 로거의 이름. 없으면 실패시키기 좋게 {@code null}을 준다. */
    private static String 누출된_로거(LogCaptor captor, String 바늘) {
        return captor.events().stream()
                .filter(e -> AuthClientTokenLeakTest.renderFully(e).contains(바늘))
                .map(ILoggingEvent::getLoggerName)
                .findFirst()
                .orElse(null);
    }

    private static Handler 모으는_핸들러(List<LogRecord> 통) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                통.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // 핸들러 기본 레벨이 INFO다 — 안 내리면 FINER 기록이 여기서 잘린다.
        handler.setLevel(java.util.logging.Level.ALL);
        return handler;
    }

    /** 톰캣은 이미 이어 붙인 문자열을 넘긴다({@code DirectJDKLog}) — 파라미터 포맷이 없다. */
    private static String 본문(LogRecord record) {
        return String.valueOf(record.getMessage());
    }

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PROBE))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
