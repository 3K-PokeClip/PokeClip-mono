package com.pokeclip.chat.collector.archive;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * S3 클라이언트 한 곳. <b>시한을 전부 명시한다 — 기본값에 맡기지 않는다.</b> 연결만 받고 응답을
 * 안 하는 반개방에서 시한이 없으면 아카이브 스레드가 무기한 매달려 대기 줄이 상한까지 차고
 * 버리기만 한다(POK-84가 JDBC에서 밟은 함정, PRD 결정). 값들은 종료 close 예산 5초 안에
 * 들도록 잡았다 — apiCall 4초가 상한이다. SDK 자체 재시도는 끈다: 재시도는 ChatArchiver의
 * 백오프가 맡는다(둘이 겹치면 한 번의 실패가 몇 배로 늘어진다).
 *
 * <p>HTTP 클라이언트는 s3 2.46.7이 runtime으로 끌어오는 <b>Apache 5</b>다(apache-client 4가
 * 아니다 — javap 실물 확인 2026-08-16). 자격증명은 DefaultCredentialsProvider — 코드·설정에 키
 * 없음. {@code create()}는 deprecated라 builder를 쓴다.
 *
 * <p><b>이 밖에서 {@code S3Client.builder()}를 부르지 마라</b> — 시한 넷이 여기 있다.
 * {@code RestClient.create()}로 설정을 통째로 우회했던 함정과 같은 모양이다.
 */
public final class S3Clients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(4);
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(4);

    private S3Clients() { }

    public static S3Client create(ArchiveProperties p) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .forcePathStyle(p.forcePathStyle())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(SOCKET_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .apiCallTimeout(CALL_TIMEOUT)
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .build());
        if (p.hasEndpoint()) {
            builder.endpointOverride(URI.create(p.endpoint()));
        }
        return builder.build();
    }
}
