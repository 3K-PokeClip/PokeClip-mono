package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.support.StallingTcpProxy;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static com.pokeclip.chat.collector.support.LocalStackFixture.BUCKET;
import static com.pokeclip.chat.collector.support.LocalStackFixture.S3;
import static com.pokeclip.chat.collector.support.LocalStackFixture.download;
import static com.pokeclip.chat.collector.support.LocalStackFixture.propertiesFor;
import static com.pokeclip.chat.collector.support.LocalStackFixture.s3ClientVia;
import static com.pokeclip.chat.collector.support.LocalStackFixture.stallingProxy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** LocalStack만 쓴다 — PG도 스프링도 안 띄운다. 시한을 재는 검사는 공유 클라이언트가 아니라 자기 것을 만든다. */
class S3ArchiveUploaderTest {

    @Test
    void 올린_바이트를_내려받으면_같다() throws Exception {
        byte[] body = "{\"receivedAtMillis\":1,\"raw\":\"{\\\"a\\\":\\\"ㅋㅋ 😀\\\"}\"}\n".getBytes(StandardCharsets.UTF_8);
        ArchiveObject object = new ArchiveObject("chat/CH/2026-08-15/10/1023-t1.jsonl", body, 1);
        new S3ArchiveUploader(S3, BUCKET).upload(object);
        assertThat(download(object.key())).isEqualTo(body);
    }

    @Test
    void 없는_버킷이면_예외이고_원인은_NoSuchBucket이다() {
        assertThatThrownBy(() -> new S3ArchiveUploader(S3, "no-such-bucket-" + UUID.randomUUID())
                .upload(new ArchiveObject("k", new byte[]{1}, 1)))
                .isInstanceOf(ArchiveUploadException.class)
                .hasCauseInstanceOf(NoSuchBucketException.class);
    }

    @Test
    void 죽은_포트면_접속_시한_안에_예외로_끝난다() {
        // 127.0.0.1:1 — 아무도 안 듣는다. 커널이 RST로 즉시 거부한다(실측 19ms) — 접속 시한을 재는 것이
        // 아니라 죽은 창고에서 틱이 안 매달린다는 것(SdkClientException으로 즉시 끝남)을 잰다.
        // connect 2s가 실제로 거는지는 블랙홀 192.0.2.1(TEST-NET-1)로 2004ms 실측(2026-08-16 reviewer) —
        // 상시 테스트로는 안 넣는다(egress 없는 CI에서 ENETUNREACH로 즉시 실패해 반대로 빨강).
        try (S3Client s3 = S3Clients.create(propertiesFor("http://127.0.0.1:1"))) {
            long t = System.nanoTime();
            assertThatThrownBy(() -> new S3ArchiveUploader(s3, BUCKET)
                    .upload(new ArchiveObject("k", new byte[]{1}, 1)))
                    .isInstanceOf(ArchiveUploadException.class)
                    .hasCauseInstanceOf(SdkClientException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(4));
        }
    }

    /**
     * 반개방 — 연결은 받고 응답을 안 한다. POK-84가 JDBC에서 실측한 바로 그 함정: 시한이 어느
     * 층에서 끊는지는 문서가 아니라 행동으로 잰다. 여기서는 "4.5초 안에 예외"만 단언하고
     * <b>어느 타입으로 끊겼는지를 로그로 남긴다</b>(socketTimeout 3s → SocketTimeoutException을
     * 감싼 SdkClientException / apiCallAttempt 4s → ApiCallAttemptTimeoutException). 값 단언이 아니라
     * 행동 단언이다 — CollectorRunner의 RestClient 함정과 같은 이유.
     */
    @Test
    void 연결은_되는데_응답이_없으면_명시_시한_안에_예외로_끊긴다() throws Exception {
        try (StallingTcpProxy proxy = stallingProxy();
             S3Client s3 = s3ClientVia(proxy)) {
            S3ArchiveUploader uploader = new S3ArchiveUploader(s3, BUCKET);
            uploader.upload(new ArchiveObject("chat/CH/2026-08-15/10/1023-p0.jsonl", new byte[]{1}, 1));  // 프록시 경유 정상 1회

            proxy.stallResponsesAfter("PUT /");     // path-style이라 요청 첫 줄이 "PUT /pokeclip-chat-test/..."
            long t = System.nanoTime();
            Throwable thrown = catchThrowable(() -> uploader.upload(
                    new ArchiveObject("chat/CH/2026-08-15/10/1024-p0.jsonl", new byte[]{1}, 1)));
            Duration took = Duration.ofNanos(System.nanoTime() - t);

            assertThat(proxy.stalledAt()).as("스톨이 실제로 걸렸어야 시한을 잰 것이다").isNotNull();
            assertThat(thrown).isInstanceOf(ArchiveUploadException.class);
            assertThat(took).as("명시 시한(apiCall 4s) 안에 끊겨야 아카이브 스레드가 안 매달린다")
                    .isLessThan(Duration.ofMillis(4_500));
            // 실측 기록 — 어느 층이 끊었나
            LoggerFactory.getLogger(getClass()).info("half-open cut by {} after {}ms",
                    thrown.getCause() == null ? "?" : thrown.getCause().getClass().getSimpleName(), took.toMillis());
        }
    }
}
