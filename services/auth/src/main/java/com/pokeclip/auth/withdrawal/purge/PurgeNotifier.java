package com.pokeclip.auth.withdrawal.purge;

import com.pokeclip.auth.config.InternalApiProperties;
import com.pokeclip.auth.withdrawal.purge.PurgeJobRepository.Job;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * clip·수집기의 지우기 문을 부른다(POK-256). 두 문 다 명부에 적고 202로 바로 답하고, 실제 지우기는 그쪽 정리기가 한다.
 * 2xx가 아니면 예외다: 발송기가 잡아 나중에 다시 보낸다.
 *
 * <p>{@code RestClient.Builder}를 주입받는다. 그래야 전역 시한(connect 2s · read 5s)과 jdk 구현 핀이 걸린다.
 */
@Component
class PurgeNotifier {

    private final RestClient clip;
    private final RestClient collector;

    PurgeNotifier(RestClient.Builder builder, WithdrawalPurgeProperties properties, InternalApiProperties internal) {
        this.clip = builder.clone().baseUrl(properties.clipBaseUrl())
                .defaultHeader("X-Internal-Token", internal.token()).build();
        this.collector = builder.clone().baseUrl(properties.collectorBaseUrl())
                .defaultHeader("X-Internal-Token", internal.token()).build();
    }

    void send(Job job) {
        switch (job.target()) {
            case CLIP -> clip.delete().uri("/internal/streamers/{userId}/data", job.userId())
                    .retrieve().toBodilessEntity();
            case COLLECTOR -> collector.delete().uri("/internal/channels/{channelId}/chat-data", job.channelId())
                    .retrieve().toBodilessEntity();
        }
    }
}
