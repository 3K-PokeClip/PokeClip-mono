package com.pokeclip.chat.collector.liveinfo;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 치지직 방송 정보 REST 둘 — 방송 설정(스트리머 토큰)과 전체 라이브 목록(앱 인증).
 *
 * <p><b>응답 본문을 예외·로그에 싣지 않는다.</b> 토큰이 되비칠 수 있고 제목은 개인 창작물이다
 * ({@code ChzzkSessionClient}와 같은 규칙).
 */
public class ChzzkLiveInfoClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 문서 상한이다. 한 바퀴 페이지 수를 줄이려고 늘 최대로 부른다. */
    static final int PAGE_SIZE = 20;

    private final RestClient restClient;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;

    public ChzzkLiveInfoClient(RestClient restClient, String baseUrl, String clientId, String clientSecret) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** @param categoryImageUrl 카테고리 사진 주소({@code category.posterImageUrl}, POK-462). 없으면 null */
    public record LiveSetting(String title, List<String> tags, String category, String categoryImageUrl) {

        public LiveSetting(String title, List<String> tags, String category) {
            this(title, tags, category, null);
        }
    }

    /** @param setting 성공했을 때만 있다. {@code status}는 실패 갈래를 가르려고 준다(0 = 응답 없음) */
    public record SettingResult(LiveSetting setting, int status) {
        public boolean refused() {
            return status == 401 || status == 403;
        }
    }

    public record LiveEntry(String channelId, String liveTitle, List<String> tags, String category, int viewers) { }

    public record LivePage(List<LiveEntry> entries, String next) { }

    /** 목록이 429를 줬다. 부르는 쪽이 다음 회차를 미룬다. */
    public static class TooManyRequestsException extends RuntimeException {
        public TooManyRequestsException() {
            super("chzzk lives 429");
        }
    }

    /** 목록이 429 밖의 HTTP 실패를 줬다. 응답 본문은 버리고 상태 코드만 든다 — 부르는 쪽이 그 코드를 로그에 남긴다. */
    public static class ListFailedException extends RuntimeException {
        private final int status;

        public ListFailedException(int status) {
            super("chzzk lives status=" + status);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 던지지 않는다 — 한 스트리머의 거부가 다른 스트리머의 기록을 막으면 안 된다. */
    public SettingResult setting(String accessToken) {
        try {
            String body = restClient.get()
                    .uri(baseUrl + "/open/v1/lives/setting")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            JsonNode content = MAPPER.readTree(body).path("content");
            return new SettingResult(new LiveSetting(
                    textOrNull(content.path("defaultLiveTitle")),
                    strings(content.path("tags")),
                    textOrNull(content.path("category").path("categoryValue")),
                    textOrNull(content.path("category").path("posterImageUrl"))), 200);
        } catch (RestClientResponseException e) {
            return new SettingResult(null, e.getStatusCode().value());
        } catch (Exception e) {
            return new SettingResult(null, 0);
        }
    }

    /**
     * @param next 첫 장이면 null. 429면 {@link TooManyRequestsException}, 그 밖 HTTP 실패는 상태 코드만 든
     *             {@link ListFailedException}(본문은 버린다), 연결·해석 실패는 그대로 던진다
     */
    public LivePage lives(String next) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + "/open/v1/lives")
                .queryParam("size", PAGE_SIZE);
        // 🔴 커서는 값 자리({next})로 넘긴다. 커서는 base64라 +가 섞이는데, 주소 틀에 바로 넣으면 encode()가 +를
        // 쿼리에 써도 되는 글자로 보고 그대로 둔다 → 치지직이 공백으로 읽고 400 「잘못된 next 값입니다」(POK-325: 회차마다
        // 2~4장째에서 끊겨 작은 방송의 시청자 수를 한 번도 못 채웠다). 값 자리를 예약 문자까지 바꾸는(+ → %2B) 것도
        // encode()다. 지우거나 buildAndExpand 뒤로 옮기면 +가 다시 그대로 나간다.
        Map<String, String> values = Map.of();
        if (next != null) {
            uri.queryParam("next", "{next}");
            values = Map.of("next", next);
        }
        String body;
        try {
            body = restClient.get()
                    .uri(uri.encode().buildAndExpand(values).toUri())
                    .header("Client-Id", clientId)
                    .header("Client-Secret", clientSecret)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new TooManyRequestsException();
            }
            throw new ListFailedException(e.getStatusCode().value());
        }
        JsonNode content = MAPPER.readTree(body).path("content");
        List<LiveEntry> entries = new ArrayList<>();
        for (JsonNode item : content.path("data")) {
            entries.add(new LiveEntry(
                    textOrNull(item.path("channelId")),
                    textOrNull(item.path("liveTitle")),
                    strings(item.path("tags")),
                    textOrNull(item.path("liveCategoryValue")),
                    item.path("concurrentUserCount").asInt(0)));
        }
        String nextPage = textOrNull(content.path("page").path("next"));
        return new LivePage(entries, nextPage == null || nextPage.isBlank() ? null : nextPage);
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isNull()) {
                out.add(item.asString());
            }
        }
        return out;
    }
}
