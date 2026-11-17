package com.pokeclip.render.work;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.report.ClipReporter;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.FakeClip;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 줄 → S3 조각 받기 → 진짜 ffmpeg 렌더 → S3 올리기 → 보고 → 줄에서 지우기를 한 번에 돈다.
 * SQS·S3는 LocalStack, clip은 {@link FakeClip}. 이미지 태그는 clip 시험과 같은 4.14.0(커뮤니티 마지막 SemVer).
 */
class QueueWorkerLocalStackTest {

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.14.0").withServices("sqs", "s3");
    /** 숨김 연장 시험의 줄 숨김 — 연장 상수(2분)보다 짧아 첫 연장이 받자마자 걸린다. */
    private static final Duration SHORT_HIDE = Duration.ofSeconds(2);

    private static S3Client s3;
    private static SqsClient sqs;
    private static FakeClip clip;

    @TempDir
    Path work;

    @BeforeAll
    static void start() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        LOCALSTACK.start();
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
        s3 = S3Client.builder().region(Region.of(LOCALSTACK.getRegion())).endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(credentials).forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.builder().build()).build();
        sqs = SqsClient.builder().region(Region.of(LOCALSTACK.getRegion())).endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(credentials).httpClient(UrlConnectionHttpClient.builder().build()).build();
        s3.createBucket(b -> b.bucket(Fixtures.BUCKET));
        s3.createBucket(b -> b.bucket(Fixtures.OUTPUT_BUCKET));
        for (int i = 0; i < Fixtures.DURATIONS.length; i++) {
            int n = i;
            s3.putObject(b -> b.bucket(Fixtures.BUCKET).key(Fixtures.key(n)),
                    RequestBody.fromFile(Path.of("src/test/resources/segments/seg_" + i + ".m4s")));
        }
        clip = new FakeClip();
    }

    @AfterAll
    static void stop() {
        if (clip != null) {
            clip.close();
        }
        LOCALSTACK.stop();
    }

    @Test
    void 주문_하나가_mp4가_되어_올라가고_메시지가_지워진다() {
        String queueUrl = sqs.createQueue(b -> b.queueName("jobs-render-" + UUID.randomUUID())).queueUrl();
        UUID jobId = UUID.randomUUID();
        ObjectNode recipe = Fixtures.recipe("s1");
        if (!Ffmpeg.canBurnSubtitles()) {
            ((ObjectNode) recipe.get("subtitles")).put("mode", "CC_ONLY");
        }
        sqs.sendMessage(b -> b.queueUrl(queueUrl)
                .messageBody(Fixtures.MAPPER.writeValueAsString(Fixtures.envelope(jobId, recipe))));

        assertThat(worker(queueUrl).pollOnce()).isTrue();

        HeadObjectResponse video = s3.headObject(b -> b.bucket(Fixtures.OUTPUT_BUCKET)
                .key("clips/42/" + FakeClip.TOKEN + "/o1.mp4"));
        assertThat(video.contentType()).isEqualTo("video/mp4");
        assertThat(video.contentLength()).isPositive();
        s3.headObject(b -> b.bucket(Fixtures.OUTPUT_BUCKET).key("clips/42/" + FakeClip.TOKEN + "/o1.srt"));
        assertThat(clip.last("SUCCEEDED").jobId()).isEqualTo(jobId.toString());
        assertThat(clip.last("SUCCEEDED").body().path("result")).hasSize(2);
        assertThat(visibleAndHidden(queueUrl)).as("지워졌다").isZero();
    }

    @Test
    void 조각이_저장소에_없으면_SOURCE_EXPIRED로_종결하고_지운다() {
        String queueUrl = sqs.createQueue(b -> b.queueName("jobs-render-" + UUID.randomUUID())).queueUrl();
        ObjectNode envelope = Fixtures.envelope(UUID.randomUUID(), Fixtures.recipe("s1"));
        ((ObjectNode) envelope.get("sourceKeys").get(1)).put("s3Key", "streams/rendertest/없음.m4s");
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(Fixtures.MAPPER.writeValueAsString(envelope)));

        assertThat(worker(queueUrl).pollOnce()).isTrue();

        assertThat(clip.last("TERMINAL_FAILED").body().path("error").path("code").asString())
                .isEqualTo("SOURCE_EXPIRED");
        assertThat(visibleAndHidden(queueUrl)).isZero();
    }

    /**
     * 숨김 시간보다 오래 걸리는 처리 동안 메시지가 다시 보이지 않는다(계약1 1절 · 계획 13절 R11). 줄 숨김을 2초로 줄이면 첫 연장이
     * 받자마자 걸려(숨김 − 2분이 음수) 숨김이 2분이 된다. 처리를 숨김의 세 배 동안 끌며 다른 소비자로 계속 받아 봐도 0 통이어야
     * 한다 — 연장이 없으면 2초 뒤 다른 소비자가 같은 주문을 받는다. 1분마다 다시 거는 연장은 주기가 상수(1분)라 이 시험이 덮지 않는다.
     */
    @Test
    void 숨김보다_긴_처리_동안_메시지가_다시_보이지_않는다() {
        String queueUrl = sqs.createQueue(b -> b.queueName("jobs-render-" + UUID.randomUUID())
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(SHORT_HIDE.toSeconds()))))
                .queueUrl();
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody("{}"));
        List<Integer> seen = new ArrayList<>();
        MessageHandler slow = body -> {
            Instant until = Instant.now().plus(SHORT_HIDE.multipliedBy(3));
            while (Instant.now().isBefore(until)) {
                seen.add(sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(1))
                        .messages().size());
            }
            return Disposition.DELETE;
        };
        QueueWorker worker = new QueueWorker(sqs, queueUrl, slow, SHORT_HIDE, Duration.ofSeconds(1), "test");

        try {
            assertThat(worker.pollOnce()).isTrue();
        } finally {
            worker.stop();
        }

        assertThat(seen).as("처리 중 다른 소비자가 받은 통 수").hasSizeGreaterThanOrEqualTo(3).containsOnly(0);
        assertThat(visibleAndHidden(queueUrl)).as("끝나고 지워졌다").isZero();
    }

    private QueueWorker worker(String queueUrl) {
        RenderProperties props = new RenderProperties(queueUrl, null, null, true, LOCALSTACK.getRegion(),
                clip.baseUrl(), "t", work, "ffmpeg", "ffprobe", Duration.ofMinutes(2), Duration.ofSeconds(600),
                Duration.ofSeconds(1), List.of(), null);
        ProcessRunner runner = new ProcessRunner();
        S3Store store = new S3Store(s3);
        ClipRenderer renderer = new ClipRenderer(store, new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"), runner,
                Fixtures.MAPPER, "ffmpeg", null);
        JobProcessor processor = new JobProcessor(new EnvelopeParser(Fixtures.MAPPER),
                new ClipReporter(props, Fixtures.MAPPER, d -> { }), renderer, store, work, props.jobTimeout(),
                Clock.systemUTC());
        return new QueueWorker(sqs, queueUrl, processor, props.visibilityTimeout(), props.pollWait(), "test");
    }

    private static int visibleAndHidden(String queueUrl) {
        Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE).build()).attributes();
        return Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }
}
