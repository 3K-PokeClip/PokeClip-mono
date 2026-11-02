package com.pokeclip.auth.profile;

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
 * 사진 창고 클라이언트 한 곳. <b>시한을 전부 명시한다 — 기본값에 맡기지 않는다.</b>
 * 연결만 받고 응답을 안 하는 반개방에서 시한이 없으면 요청 스레드가 무기한 매달린다.
 *
 * <p>chat-collector의 S3Clients를 베꼈지만 <b>시한 값 셋이 일부러 다르다.</b> 저쪽은
 * 백그라운드 아카이브라 4초를 매달려도 아무도 안 기다리고, 종료 close 예산 5초가 상한을 정했다.
 * 여기는 <b>사용자 요청 경로</b>라 상한을 정하는 것이 사람의 인내심이다 — 최악에 8초 기다린다.
 * 그런데도 소켓 시한이 저쪽(3초)보다 넉넉한 5초인 이유는 올리는 것이 최대 2MB여서다:
 * 느린 회선에서 정상 업로드가 시한에 끊기면 사용자는 「사진이 안 올라간다」만 보게 된다.
 * <b>저쪽 값을 통째로 베껴 오지 마라</b> — 전제가 다르다.
 *
 * <p>SDK 자체 재시도는 끈다 — 사용자가 기다리는 경로에서 재시도는 대기만 몇 배로 늘린다.
 * 실패는 그대로 알리고 사람이 다시 누르게 한다(저쪽은 아카이버의 백오프가 맡아서 끄지만,
 * 결론이 같아도 근거가 다르다).
 *
 * <p>HTTP 클라이언트는 <b>Apache 5</b>다 — s3가 runtime으로 끌어오는 것이 그것이고,
 * 시한을 박으려면 컴파일에서 보여야 해서 build.gradle이 apache5-client를 명시한다.
 *
 * <p><b>이 밖에서 {@code S3Client.builder()}를 부르지 마라</b> — 시한 넷이 여기 있다.
 */
public final class PhotoS3Clients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(8);
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

    private PhotoS3Clients() { }

    public static S3Client create(PhotoProperties p) {
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
