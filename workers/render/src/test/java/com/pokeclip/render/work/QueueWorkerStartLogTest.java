package com.pokeclip.render.work;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시작 로그에 ffmpeg 판을 싣는다(PDT 설계 15절 {@code render.worker_started} · 예측 P1). 배포 뒤 상자 로그 한 줄로 고정한 판이 실제로
 * 깔렸는지 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class QueueWorkerStartLogTest {

    private static final String VERSION = "ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025 the FFmpeg developers";

    @Test
    void 렌더_줄_시작_로그에_ffmpeg_판_첫_줄을_싣는다(CapturedOutput output) {
        QueueWorker worker = new QueueWorker(new IdleSqs(), "http://queue/render", body -> null, Duration.ofSeconds(600),
                Duration.ofSeconds(1), VERSION);

        worker.start();
        try {
            assertThat(output.getOut()).contains("render.worker_started queue=http://queue/render ffmpeg=" + VERSION);
        } finally {
            worker.stop();
        }
    }

    @Test
    void 사진_줄_시작_로그도_ffmpeg_판을_싣는다(CapturedOutput output) {
        QueueWorker worker = new QueueWorker("thumbnail", new IdleSqs(), "http://queue/thumb", body -> null,
                Duration.ofSeconds(600), Duration.ofSeconds(1), VERSION);

        worker.start();
        try {
            assertThat(output.getOut()).contains("thumbnail.worker_started queue=http://queue/thumb ffmpeg=" + VERSION);
        } finally {
            worker.stop();
        }
    }

    /** 받을 것이 없는 줄. 멈출 때(interrupt)까지 롱 폴링처럼 기다린다. */
    private static final class IdleSqs implements SqsClient {

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ReceiveMessageResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }
    }
}
