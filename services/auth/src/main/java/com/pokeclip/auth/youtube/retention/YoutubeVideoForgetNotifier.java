package com.pokeclip.auth.youtube.retention;

import com.pokeclip.auth.config.InternalApiProperties;
import com.pokeclip.auth.withdrawal.purge.WithdrawalPurgeProperties;
import com.pokeclip.auth.youtube.retention.YoutubeRetentionStore.ForgetJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * clip의 영상 번호 지우기 문({@code POST /internal/youtube-videos/forget})을 부른다(POK-423). 2xx가 아니면 예외다: 정리 바퀴가 잡아
 * 나중에 다시 보낸다. clip 주소는 탈퇴 정리와 같은 값({@code CLIP_BASE_URL})이다: 같은 clip이고 환경변수를 하나 더 늘리지 않는다.
 *
 * <p>{@code RestClient.Builder}를 주입받는다. 그래야 전역 시한(connect 2s · read 5s)과 jdk 구현 핀이 걸린다.
 */
@Component
public class YoutubeVideoForgetNotifier {

    private final RestClient clip;

    @Autowired
    YoutubeVideoForgetNotifier(RestClient.Builder builder, WithdrawalPurgeProperties properties,
                               InternalApiProperties internal) {
        this(builder, properties.clipBaseUrl(), internal.token());
    }

    /** 시험이 가짜 clip 주소로 만든다. */
    YoutubeVideoForgetNotifier(RestClient.Builder builder, String clipBaseUrl, String internalToken) {
        this.clip = builder.clone().baseUrl(clipBaseUrl).defaultHeader("X-Internal-Token", internalToken).build();
    }

    void send(ForgetJob job) {
        clip.post().uri("/internal/youtube-videos/forget").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("owner", Long.toString(job.userId()), "channelId", job.channelId(),
                        "before", job.createdAt().toString()))
                .retrieve().toBodilessEntity();
    }
}
