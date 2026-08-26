package com.pokeclip.auth.profile;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조립한 클라이언트에 <b>시한과 「재시도 안 함」이 실제로 박혔는지</b>를 잰다. 값을 상수와 비교하는
 * 것이 아니라 SDK가 들고 있는 설정을 되읽는다 — 빌더에서 한 줄을 지우면 여기가 빨간불이어야 한다.
 *
 * <p>연결·소켓 시한(Apache5 빌더 쪽)은 이 자리에서 되읽을 수 없다. 그쪽은 태스크 5의 실측이 잰다.
 */
class PhotoS3ClientsTest {

    private static PhotoProperties props(String endpoint) {
        return new PhotoProperties("bucket", "ap-northeast-2", endpoint, true, "unused-by-this-test-but-32-bytes", "http://localhost:8082");
    }

    @Test
    void 창고_주소를_주면_그_주소로_붙는다() {
        try (S3Client s3 = PhotoS3Clients.create(props("http://localhost:14566"))) {
            assertThat(s3.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://localhost:14566"));
        }
    }

    /** 비면 진짜 AWS다 — 주소를 덮어쓰지 않는다. */
    @Test
    void 창고_주소가_비면_주소를_덮어쓰지_않는다() {
        try (S3Client s3 = PhotoS3Clients.create(props(""))) {
            assertThat(s3.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    /**
     * 사용자 요청 경로라 최악 대기가 8초로 묶여 있어야 하고, SDK 자체 재시도가 그 8초를 몇 배로
     * 늘리면 안 된다(재시도를 켜면 사용자는 기다리기만 한다).
     */
    @Test
    void 호출_시한과_재시도_끄기가_클라이언트에_박힌다() {
        try (S3Client s3 = PhotoS3Clients.create(props(""))) {
            S3ServiceClientConfiguration config = s3.serviceClientConfiguration();
            assertThat(config.region().id()).isEqualTo("ap-northeast-2");
            assertThat(config.overrideConfiguration().apiCallTimeout())
                    .contains(Duration.ofSeconds(8));
            assertThat(config.overrideConfiguration().apiCallAttemptTimeout())
                    .contains(Duration.ofSeconds(8));
            assertThat(config.overrideConfiguration().retryStrategy())
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .as("재시도가 켜져 있다 — 사용자가 기다리는 경로에서 대기만 몇 배가 된다")
                    .isEqualTo(1);
        }
    }

    /**
     * chat-collector의 S3Clients와 값이 갈린 자리다. 저쪽은 백그라운드 아카이브라 4초를 매달려도
     * 아무도 안 기다리지만, 여기는 사용자가 화면 앞에서 기다린다 — 대신 최대 2MB를 올리므로
     * 소켓 시한은 저쪽보다 넉넉해야 느린 회선의 정상 업로드가 안 끊긴다.
     */
    @Test
    void 시한_넷이_사용자_요청_경로의_값이다() {
        assertThat(PhotoS3Clients.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
        assertThat(PhotoS3Clients.SOCKET_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(PhotoS3Clients.ATTEMPT_TIMEOUT).isEqualTo(Duration.ofSeconds(8));
        assertThat(PhotoS3Clients.CALL_TIMEOUT).isEqualTo(Duration.ofSeconds(8));
    }
}
