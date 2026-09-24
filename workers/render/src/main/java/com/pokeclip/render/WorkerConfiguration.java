package com.pokeclip.render;

import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.report.ClipReporter;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.work.ClipRenderer;
import com.pokeclip.render.work.JobProcessor;
import com.pokeclip.render.work.QueueWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * 부품 조립. AWS 클라이언트마다 HTTP 스택을 URL connection으로 못박는다. SDK가 끌고 오는 Apache가 뽑히면
 * 그쪽 wire 로거가 DEBUG에서 요청 서명을 찍는다(clip·chat-collector와 같은 이유).
 */
@Configuration
class WorkerConfiguration {

    @Bean
    S3Client s3Client(RenderProperties p) {
        var builder = S3Client.builder()
                .region(Region.of(p.region()))
                .forcePathStyle(p.s3PathStyle())
                .httpClient(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(60)).build());
        if (p.s3Endpoint() != null && !p.s3Endpoint().isBlank()) {
            builder.endpointOverride(URI.create(p.s3Endpoint()));
        }
        return builder.build();
    }

    @Bean
    SqsClient sqsClient(RenderProperties p) {
        var builder = SqsClient.builder()
                .region(Region.of(p.region()))
                // 롱 폴링(20초)보다 길어야 한다. 짧으면 기다리는 도중 소켓이 끊긴다.
                .httpClient(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(30)).build());
        if (p.queueEndpoint() != null && !p.queueEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(p.queueEndpoint()));
        }
        return builder.build();
    }

    @Bean
    JobProcessor jobProcessor(RenderProperties p, ObjectMapper mapper, S3Client s3) {
        ProcessRunner runner = new ProcessRunner();
        S3Store store = new S3Store(s3);
        ClipRenderer renderer = new ClipRenderer(store, new MediaProbe(runner, mapper, p.ffprobe()), runner, mapper,
                p.ffmpeg(), p.fontsDir());
        ClipReporter reporter = new ClipReporter(p, mapper, ClipReporter.Sleeper.real());
        return new JobProcessor(new EnvelopeParser(mapper), reporter, renderer, store, p.workDir(), p.jobTimeout(),
                Clock.systemUTC());
    }

    /** 줄 주소가 비면 줄을 안 본다. 부품만 띄워 보는 로컬 기동용. */
    @Bean
    @ConditionalOnExpression("!'${pokeclip.render.queue-url:}'.isBlank()")
    QueueWorker queueWorker(RenderProperties p, SqsClient sqs, JobProcessor processor) {
        return new QueueWorker(sqs, p.queueUrl(), processor, p.visibilityTimeout(), p.pollWait());
    }
}
