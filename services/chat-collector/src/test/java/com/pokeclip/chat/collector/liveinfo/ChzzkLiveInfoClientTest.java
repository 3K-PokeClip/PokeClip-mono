package com.pokeclip.chat.collector.liveinfo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

    /**
     * 운영과 같은 JDK 요청 팩토리로 잰다(쌍둥이 시험 {@code ChzzkLinkClientTest}와 같다). {@code RestClient.create()}는
     * 클래스패스의 Apache HC5를 골라 429를 1초 뒤 스스로 다시 보내는 등 운영에 없는 길을 지난다.
     */
    private ChzzkLiveInfoClient client() {
        return new ChzzkLiveInfoClient(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build(),
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
        assertThat(result.setting().categoryImageUrl()).as("사진 칸이 없으면 null이다(빈 글자가 아니다)").isNull();
        assertThat(seenHeaders.getFirst().get("auth")).isEqualTo("Bearer user-token");
    }

    /** 카테고리 사진(POK-462). 공식 문서의 칸 이름 {@code category.posterImageUrl}을 그대로 읽는다. */
    @Test
    void 방송_설정의_카테고리_사진_주소를_읽는다() {
        bodyByQuery.put("/open/v1/lives/setting", """
                {"code":200,"content":{"defaultLiveTitle":"t","category":{"categoryType":"GAME","categoryId":"lol",
                 "categoryValue":"리그 오브 레전드","posterImageUrl":"https://img.example/lol.jpg"},"tags":[]}}""");

        assertThat(client().setting("user-token").setting().categoryImageUrl()).isEqualTo("https://img.example/lol.jpg");
    }

    /**
     * 카테고리 검색(POK-462, SOOP 카테고리 사진을 치지직에서 빌린다). 앱 인증 헤더 둘로 묻고, 검색어는 값 자리로 인코딩한다
     * (이름에 공백·「/」·「+」가 든다: 「토크/캠방」). 이름과 사진을 읽는다.
     */
    @Test
    void 카테고리_검색은_앱_헤더로_묻고_이름과_사진을_읽는다() {
        bodyByQuery.put("/open/v1/categories/search?size=50&query=%ED%86%A0%ED%81%AC%2F%EC%BA%A0%EB%B0%A9%20%2B", """
                {"code":200,"content":{"data":[{"categoryType":"ETC","categoryId":"talk","categoryValue":"토크/캠방 +",
                 "posterImageUrl":"https://img.example/talk.jpg"},{"categoryType":"ETC","categoryId":"x","categoryValue":"다른 것"}]}}""");

        List<ChzzkLiveInfoClient.CategoryHit> hits = client().searchCategories("토크/캠방 +");

        assertThat(hits).containsExactly(new ChzzkLiveInfoClient.CategoryHit("토크/캠방 +", "https://img.example/talk.jpg"),
                new ChzzkLiveInfoClient.CategoryHit("다른 것", null));
        assertThat(seenQueries).as("검색어는 값 자리로 예약 문자까지 인코딩한다")
                .containsExactly("/open/v1/categories/search?size=50&query=%ED%86%A0%ED%81%AC%2F%EC%BA%A0%EB%B0%A9%20%2B");
        assertThat(seenHeaders.getFirst().get("clientId")).isEqualTo("app-id");
        assertThat(seenHeaders.getFirst().get("clientSecret")).isEqualTo("app-secret");
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
    void 커서의_더하기를_그대로_두지_않고_인코딩해_다음_장을_읽는다() {
        // 치지직 커서는 base64라 + / = 가 섞인다. +를 그대로 보내면 치지직이 공백으로 읽고 400 「잘못된 next 값입니다」를
        // 준다(2026-10-12 실측: 회차마다 2~4장째에서 끊겨 시청자 수를 한 번도 못 채웠다). 위 시험의 abc==로는 이 갈래가 안 보인다.
        bodyByQuery.put("/open/v1/lives?size=20&next=ytwg%2BGEd%2FBQ%3D%3D", """
                {"content":{"data":[{"channelId":"c-3","concurrentUserCount":1}],"page":{"next":null}}}""");

        ChzzkLiveInfoClient.LivePage page = client().lives("ytwg+GEd/BQ==");

        assertThat(seenQueries).containsExactly("/open/v1/lives?size=20&next=ytwg%2BGEd%2FBQ%3D%3D");
        assertThat(page.entries()).extracting(ChzzkLiveInfoClient.LiveEntry::channelId).containsExactly("c-3");
    }

    @Test
    void 목록_429는_전용_예외다() {
        statusByPath.put("/open/v1/lives", 429);

        assertThatThrownBy(() -> client().lives(null)).isInstanceOf(ChzzkLiveInfoClient.TooManyRequestsException.class);
    }

    @Test
    void 목록_429_밖의_실패는_상태_코드만_든_전용_예외다() {
        // 상태 코드가 로그까지 가야 400(우리 요청이 틀림)과 5xx(기다리면 되는 장애)가 갈린다(POK-325). 본문은 싣지 않는다.
        statusByPath.put("/open/v1/lives", 400);
        bodyByQuery.put("/open/v1/lives", """
                {"code":400,"message":"잘못된 next 값입니다.","echo":"app-secret"}""");

        assertThatThrownBy(() -> client().lives("ytwg+GEd/BQ=="))
                .isInstanceOfSatisfying(ChzzkLiveInfoClient.ListFailedException.class,
                        e -> assertThat(e.status()).isEqualTo(400))
                .hasMessageNotContaining("app-secret")
                .hasMessageNotContaining("잘못된");
    }
}
