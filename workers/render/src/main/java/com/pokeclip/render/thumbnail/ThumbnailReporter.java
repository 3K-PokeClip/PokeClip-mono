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

    enum Outcome { ACCEPTED, REJECTED, UNAVAILABLE }

    private final RestClient client;

    public ThumbnailReporter(RenderProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.clipBaseUrl())
                .defaultHeader("X-Internal-Token", properties.internalToken())
                .build();
    }

    /** 200은 받음, 4xx는 확정 거절(다시 해도 같다), 그 밖은 clip이 못 받았다. */
    Outcome captured(String kind, String targetId, Instant capturedAt) {
        try {
            client.post().uri("/internal/thumbnails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("kind", kind, "targetId", targetId, "capturedAt", capturedAt.toString()))
                    .retrieve()
                    .toBodilessEntity();
            return Outcome.ACCEPTED;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().is4xxClientError() ? Outcome.REJECTED : Outcome.UNAVAILABLE;
        } catch (RestClientException e) {
            return Outcome.UNAVAILABLE;
        }
    }
}
