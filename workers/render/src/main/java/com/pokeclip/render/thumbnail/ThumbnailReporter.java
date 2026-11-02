package com.pokeclip.render.thumbnail;

import com.pokeclip.render.RenderProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 「올렸다」를 clip {@code POST /internal/thumbnails}에 알린다. HTTP 스택을 JDK로 못박고 리다이렉트를 끄는 이유는
 * {@code ClipReporter}와 같다(토큰이 헤더에 실린다). 재전송은 하지 않는다: 실패하면 메시지를 두고 숨김 시간 뒤 통째로 다시 한다.
 */
public class ThumbnailReporter {

    /** GONE: 대상이 없다(404, 탈퇴로 지워졌다, POK-256). 방금 올린 사진을 치워야 한다. */
    enum Outcome { ACCEPTED, REJECTED, GONE, UNAVAILABLE }

    /** 보고 한 번이 걸릴 수 있는 최대 시간(연결 3초 + 읽기 10초). 처리기가 상한 안에 들어오는지 이것으로 잰다 */
    static final Duration MAX_DURATION = Duration.ofSeconds(13);

    private final RestClient client;

    public ThumbnailReporter(RenderProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)) // MAX_DURATION과 같이 바꾼다
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10)); // MAX_DURATION과 같이 바꾼다
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.clipBaseUrl())
                .defaultHeader("X-Internal-Token", properties.internalToken())
                .build();
    }

    /**
     * 2xx는 받음, 4xx는 확정 거절(다시 해도 같다), 그 밖은 clip이 못 받았다. 🔴 3xx를 따로 본다: 리다이렉트를 끄면 {@code retrieve()}는
     * 3xx를 오류로 안 던져 그대로 「받음」이 되고 메시지가 지워진다(PR #212 codex). clip 주소가 잘못 잡힌 것이라 못 받은 것으로 둔다.
     */
    Outcome captured(String kind, String targetId, Instant capturedAt) {
        try {
            var response = client.post().uri("/internal/thumbnails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("kind", kind, "targetId", targetId, "capturedAt", capturedAt.toString()))
                    .retrieve()
                    .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful() ? Outcome.ACCEPTED : Outcome.UNAVAILABLE;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Outcome.GONE;
            }
            return e.getStatusCode().is4xxClientError() ? Outcome.REJECTED : Outcome.UNAVAILABLE;
        } catch (RestClientException e) {
            return Outcome.UNAVAILABLE;
        }
    }
}
