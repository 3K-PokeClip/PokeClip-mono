package com.pokeclip.upload.work;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * clip·auth {@code /internal/**}에 JSON을 POST하는 공용 부품. 재시도는 5xx·끊김에만 하고, 4xx는 확정 응답이라 그대로 돌려준다.
 *
 * <p>JDK 클라이언트이고 리다이렉트를 끈다: {@code X-Internal-Token}이 다른 출처로 따라가지 않게(렌더 일꾼 {@code ClipReporter}와 같은 이유).
 * 본문·헤더는 로그에 안 찍는다(유튜브 토큰·이어 올리기 주소가 실린다).
 */
public class InternalHttp {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String internalToken;
    private final List<Duration> retryDelays;
    private final Sleeper sleeper;

    public InternalHttp(ObjectMapper mapper, String internalToken, List<Duration> retryDelays, Sleeper sleeper) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapper = mapper;
        this.internalToken = internalToken;
        this.retryDelays = retryDelays;
        this.sleeper = sleeper;
    }

    public record Reply(int status, JsonNode body) {
    }

    /** @throws Unavailable 재시도 끝까지 5xx·끊김 */
    public Reply post(String url, Object body) {
        String json = mapper.writeValueAsString(body);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retryDelays.size(); attempt++) {
            if (attempt > 0) {
                sleeper.sleep(retryDelays.get(attempt - 1));
            }
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .header("X-Internal-Token", internalToken)
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 5) {
                    String text = response.body();
                    return new Reply(response.statusCode(), text == null || text.isBlank() ? null : mapper.readTree(text));
                }
                last = new IllegalStateException("HTTP " + response.statusCode());
            } catch (IOException e) {
                last = new IllegalStateException(e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("중단됐다", e);
            }
        }
        // 주소만 남긴다: 본문에는 토큰·이어 올리기 주소가 있을 수 있다.
        throw new Unavailable(URI.create(url).getPath(), last);
    }
}
