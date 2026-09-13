package com.pokeclip.chat.collector.liveinfo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 치지직 방송 설정·라이브 목록. 가짜 치지직은 루프백 JDK HttpServer다. */
class ChzzkLiveInfoClientTest {

    private HttpServer server;
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final Map<String, String> bodyByQuery = new ConcurrentHashMap<>();
    private final List<Map<String, String>> seenHeaders = new CopyOnWriteArrayList<>();
    private final List<String> seenQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            seenQueries.add(path + "?" + query);
            seenHeaders.add(Map.of(
                    "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "clientId", String.valueOf(exchange.getRequestHeaders().getFirst("Client-Id")),
                    "clientSecret", String.valueOf(exchange.getRequestHeaders().getFirst("Client-Secret"))));
            int status = statusByPath.getOrDefault(path, 200);
            String body = bodyByQuery.getOrDefault(path + "?" + query, bodyByQuery.getOrDefault(path, "{}"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ChzzkLiveInfoClient client() {
        return new ChzzkLiveInfoClient(RestClient.create(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "app-id", "app-secret");
    }

    @Test
    void 방송_설정을_읽는다() {
        bodyByQuery.put("/open/v1/lives/setting", """
                {"code":200,"content":{"defaultLiveTitle":"오늘은 랭크","category":{"categoryType":"GAME",
                 "categoryId":"lol","categoryValue":"리그 오브 레전드"},"tags":["롤","랭크"]}}""");

        ChzzkLiveInfoClient.SettingResult result = client().setting("user-token");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.setting().title()).isEqualTo("오늘은 랭크");
        assertThat(result.setting().category()).isEqualTo("리그 오브 레전드");
        assertThat(result.setting().tags()).containsExactly("롤", "랭크");
        assertThat(seenHeaders.getFirst().get("auth")).isEqualTo("Bearer user-token");
    }

    @Test
    void 설정_거부는_던지지_않고_상태로_알린다() {
        statusByPath.put("/open/v1/lives/setting", 403);

        ChzzkLiveInfoClient.SettingResult result = client().setting("user-token");

        assertThat(result.setting()).isNull();
        assertThat(result.refused()).isTrue();
        assertThat(seenQueries).hasSize(1);
    }

    @Test
    void 목록을_next로_넘기며_읽고_앱_헤더_둘을_싣는다() {
        bodyByQuery.put("/open/v1/lives?size=20", """
                {"content":{"data":[{"channelId":"c-1","liveTitle":"t1","tags":[],"liveCategoryValue":"LoL",
                 "concurrentUserCount":900}],"page":{"next":"abc=="}}}""");
        bodyByQuery.put("/open/v1/lives?size=20&next=abc%3D%3D", """
                {"content":{"data":[{"channelId":"c-2","liveTitle":"t2","tags":["x"],"concurrentUserCount":12}],
                 "page":{"next":null}}}""");

        ChzzkLiveInfoClient.LivePage first = client().lives(null);
        ChzzkLiveInfoClient.LivePage second = client().lives(first.next());

        assertThat(first.entries()).extracting(ChzzkLiveInfoClient.LiveEntry::channelId).containsExactly("c-1");
        assertThat(first.entries().getFirst().viewers()).isEqualTo(900);
        assertThat(first.next()).isEqualTo("abc==");
        assertThat(second.entries().getFirst().tags()).containsExactly("x");
        assertThat(second.next()).isNull();
        assertThat(seenHeaders).allSatisfy(h -> {
            assertThat(h.get("clientId")).isEqualTo("app-id");
            assertThat(h.get("clientSecret")).isEqualTo("app-secret");
        });
    }

    @Test
    void 목록_429는_전용_예외다() {
        statusByPath.put("/open/v1/lives", 429);

        assertThatThrownBy(() -> client().lives(null)).isInstanceOf(ChzzkLiveInfoClient.TooManyRequestsException.class);
    }
}
