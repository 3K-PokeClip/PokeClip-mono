package com.pokeclip.upload;

import com.pokeclip.upload.auth.YoutubeTokenClient;
import com.pokeclip.upload.clip.ClipUploadApi;
import com.pokeclip.upload.job.EnvelopeParser;
import com.pokeclip.upload.media.ProcessRunner;
import com.pokeclip.upload.media.SceneExtractor;
import com.pokeclip.upload.storage.S3Download;
import com.pokeclip.upload.work.InternalHttp;
import com.pokeclip.upload.work.QueueWorker;
import com.pokeclip.upload.work.Sleeper;
import com.pokeclip.upload.work.ThumbnailStep;
import com.pokeclip.upload.work.UploadProcessor;
import com.pokeclip.upload.youtube.ResumableUploader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** 부품 조립. AWS 클라이언트마다 HTTP 스택을 URL connection으로 못박는다(렌더 일꾼과 같은 이유). */
@Configuration
class WorkerConfiguration {

    @Bean
    S3Client s3Client(UploadProperties p) {
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
    SqsClient sqsClient(UploadProperties p) {
        var builder = SqsClient.builder()
                .region(Region.of(p.region()))
                // 롱 폴링(20초)보다 길어야 한다.
                .httpClient(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(30)).build());
        if (p.queueEndpoint() != null && !p.queueEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(p.queueEndpoint()));
        }
        return builder.build();
    }

    /** 썸네일이 잠깐 안 될 때(404·속도 제한·5xx) 일꾼 안에서 다시 해 보는 간격. 세 번까지. 쪽지는 남기지 않는다. */
    static final List<Duration> THUMBNAIL_RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));
    /** ffprobe·ffmpeg 한 번의 시한. 넘기면 썸네일 실패로 본다. */
    static final Duration FFMPEG_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    UploadProcessor uploadProcessor(UploadProperties p, ObjectMapper mapper, S3Client s3) {
        InternalHttp http = new InternalHttp(mapper, p.internalToken(), p.retryDelays(), Sleeper.real());
        YoutubeTokenClient auth = new YoutubeTokenClient(http, p.authBaseUrl());
        S3Download storage = new S3Download(s3);
        ResumableUploader youtube = new ResumableUploader(mapper, p.youtubeUploadUrl(), p.youtubeThumbnailUrl());
        ThumbnailStep thumbnails = new ThumbnailStep(
                new SceneExtractor(new ProcessRunner(), p.ffmpegPath(), p.ffprobePath(), FFMPEG_TIMEOUT),
                storage, youtube, auth, p.workDir(), THUMBNAIL_RETRY_DELAYS, Sleeper.real());
        return new UploadProcessor(new EnvelopeParser(mapper), new ClipUploadApi(http, p.clipBaseUrl()), auth,
                storage, youtube, thumbnails, p.workDir(), p.chunkSize().toBytes(), p.retryDelays(), Sleeper.real());
    }

    /** 줄 주소가 비면 줄을 안 본다. 부품만 띄워 보는 로컬 기동용. */
    @Bean
    @ConditionalOnExpression("!'${pokeclip.upload.queue-url:}'.isBlank()")
    QueueWorker queueWorker(UploadProperties p, SqsClient sqs, UploadProcessor processor) {
        return new QueueWorker(sqs, p.queueUrl(), processor, p.visibilityTimeout(), p.pollWait());
    }
}
