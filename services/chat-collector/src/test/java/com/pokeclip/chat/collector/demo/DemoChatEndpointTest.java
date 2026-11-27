package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창구를 밖에서 쳐서 임시 채팅이 <b>실제 표</b>({@code chat_messages})에 들어가는 데까지 잰다. 판별기는 이 표만 읽는다.
 * 프로퍼티는 채팅 목록 창구 시험과 같게 둬 스프링 컨텍스트를 같이 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"pokeclip.link.internal-token=" + DemoChatEndpointTest.TOKEN,
                "pokeclip.sync.default-offset-ms=0"})
@ActiveProfiles("test")
class DemoChatEndpointTest extends IntegrationTestSupport {

    static final String TOKEN = "test-internal-token";

    private static final String STREAM = "api-demo-1";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatPersister persister;
    @Autowired DemoHighlight.Sink cardSink;

    /**
     * 중계가 꺼진(시험 기본) 운영 컨텍스트에서도 내부 토큰 · clip 주소가 있으면 카드를 clip에 넣는 클라이언트가 물린다(PR #558 리뷰).
     * 화면은 중계 없이도 저장된 채팅을 5초마다 읽어 데모 채팅을 띄우므로, 여기서 {@code NONE}이면 그 배포에서 카드가 영영 안 선다
     */
    @Test
    void 중계가_꺼져도_내부_토큰이_있으면_카드를_clip에_넣는_클라이언트가_물린다() {
        assertThat(cardSink).isInstanceOf(ClipDemoCardClient.class);
    }

    @BeforeEach
    void 내_방송만_비운다() {
        jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", STREAM);
    }

    @Test
    void 내부_토큰이_없으면_안_열린다() throws Exception {
        assertThat(post(null).statusCode()).isEqualTo(401);
    }

    @Test
    void 누르면_임시_채팅이_표에_들어가고_흐르는_동안_또_누르면_무시한다() throws Exception {
        HttpResponse<String> first = post(TOKEN);
        HttpResponse<String> again = post(TOKEN);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).contains("\"started\":false");

        int total = DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (count() < total) {
            assertThat(System.nanoTime()).as("30초 안에 %d건이 안 들어갔다(지금 %d)", total, count()).isLessThan(deadline);
            persister.flushOnce();
            Thread.sleep(200);
        }
        // 두 플랫폼을 다 요구하지 않는다 — 닉네임별 무작위라 한쪽으로 쏠릴 수 있다. 이 방송의 배정에 나온 플랫폼과 같으면 된다
        List<String> assigned = DemoChatScript.audienceOf(STREAM).byNickname().stream()
                .map(Enum::name).distinct().sorted().toList();
        assertThat(jdbc.queryForList("SELECT DISTINCT platform FROM chat_messages WHERE stream_id = ? ORDER BY 1",
                String.class, STREAM)).isEqualTo(assigned);
    }

    private int count() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE stream_id = ?", Integer.class, STREAM);
        return n == null ? 0 : n;
    }

    private HttpResponse<String> post(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/internal/streams/" + STREAM + "/demo-chat-burst"));
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
