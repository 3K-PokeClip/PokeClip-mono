package com.pokeclip.render.storage;

import com.pokeclip.render.job.RenderFailure;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 받기·올리기가 주문의 남은 시한을 넘지 않는다(PR #194 codex). 소켓 시한은 느리게라도 오는 전송을 못 끊는다. */
class S3StoreTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3Store store = new S3Store(s3);

    @Test
    void 받기에_남은_시한을_건다() {
        store.download("b", "k", Path.of("x"), Instant.now().plus(Duration.ofMinutes(3)));
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture(), any(Path.class));
        Duration timeout = request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow();
        assertThat(timeout).isBetween(Duration.ofMinutes(2), Duration.ofMinutes(3));
    }

    @Test
    void 올리기에도_건다() {
        store.upload("b", "k", Path.of("build.gradle"), "video/mp4", Instant.now().plus(Duration.ofMinutes(1)));
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout()).isPresent();
    }

    @Test
    void 시한을_이미_넘겼으면_부르지_않고_일시_실패() {
        assertThatThrownBy(() -> store.download("b", "k", Path.of("x"), Instant.now().minusSeconds(1)))
                .isInstanceOfSatisfying(RenderFailure.class, f -> assertThat(f.retryable()).isTrue());
        verifyNoInteractions(s3);
    }
}
