package com.pokeclip.clip.playback;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 출입증 재료가 비어 있는 <b>기본 컨텍스트</b>(application-test.yml이 {@code playback.*}를 안 채운다)
 * — 로컬·CI가 뜨는 모양 그대로다. 문은 살아 있되 503이고, <b>자격 판정은 그보다 먼저</b>다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaybackAccessDisabledTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String 요청자 = "4183";
    private static final String 내_방송 = "s-play-off";

    private final int port;
    private final JdbcTemplate jdbc;
    private final PlaybackAccessSigner signer;

    PlaybackAccessDisabledTest(@LocalServerPort int port, JdbcTemplate jdbc, PlaybackAccessSigner signer) {
        this.port = port;
        this.jdbc = jdbc;
        this.signer = signer;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                내_방송, TestIds.STREAMER,
                OffsetDateTime.ofInstant(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void 재료가_비면_503이고_404로_안_접는다() throws Exception {
        assertThat(signer.enabled()).as("기본 컨텍스트에 재료가 채워져 있다 — 이 시험은 꺼짐을 재야 한다").isFalse();
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        HttpResponse<String> 응답 = 부른다();

        assertThat(응답.statusCode()).isEqualTo(503);
        assertThat(응답.body()).contains("playback_signing_unavailable");
        assertThat(응답.headers().allValues("set-cookie")).isEmpty();
    }

    /** 🔴 판정이 서명보다 앞이다 — 꺼진 상태에서도 남남은 503이 아니라 404를 받는다. 뒤집히면 여기가 503이다. */
    @Test
    void 꺼져_있어도_자격이_없으면_404다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");

        HttpResponse<String> 응답 = 부른다();

        assertThat(응답.statusCode()).isEqualTo(404);
        assertThat(응답.body()).contains("broadcast_not_found");
    }

    private HttpResponse<String> 부른다() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/clip/broadcasts/" + 내_방송 + "/playback-access"))
                .header("Authorization", "Bearer " + TestTokens.access(요청자))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
