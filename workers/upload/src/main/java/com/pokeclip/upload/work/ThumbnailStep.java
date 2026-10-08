package com.pokeclip.upload.work;

import com.pokeclip.upload.auth.YoutubeTokenClient;
import com.pokeclip.upload.clip.ClipUploadApi.ThumbnailReport;
import com.pokeclip.upload.job.UploadEnvelope;
import com.pokeclip.upload.job.UploadEnvelope.Thumbnail;
import com.pokeclip.upload.media.SceneExtractor;
import com.pokeclip.upload.storage.S3Download;
import com.pokeclip.upload.youtube.ResumableUploader;
import com.pokeclip.upload.youtube.ResumableUploader.ThumbnailSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 영상이 다 올라간 뒤 썸네일을 붙인다(POK-291 명세 §7). 처리기가 clip에 올림을 보고하기 <b>전에</b> 부른다.
 *
 * <p>🔴 <b>무슨 일이 있어도 예외를 던지지 않고 결과만 돌려준다.</b> 영상은 이미 올라갔다. 썸네일 때문에 쪽지를 남기면(retryLater)
 * 세 번 돈 뒤 실패 큐로 가고, clip 정리기가 영상이 있는데도 「확인 중」으로 닫는다. 그래서 잠깐 풀리는 실패도 여기서 짧게
 * 다시 해 보고 끝낸다. 창고에서 그림을 못 받아도 {@link Unavailable}로 감싸지 않는다.
 */
public class ThumbnailStep {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailStep.class);

    private final SceneExtractor extractor;
    private final S3Download storage;
    private final ResumableUploader youtube;
    private final YoutubeTokenClient auth;
    private final Path workDir;
    private final List<Duration> retryDelays;
    private final Sleeper sleeper;

    /** @param retryDelays 잠깐 풀리는 실패의 짧은 재시도 간격. 횟수 = 1 + 이 목록 길이 */
    public ThumbnailStep(SceneExtractor extractor, S3Download storage, ResumableUploader youtube,
                         YoutubeTokenClient auth, Path workDir, List<Duration> retryDelays, Sleeper sleeper) {
        this.extractor = extractor;
        this.storage = storage;
        this.youtube = youtube;
        this.auth = auth;
        this.workDir = workDir;
        this.retryDelays = List.copyOf(retryDelays);
        this.sleeper = sleeper;
    }

    /** 이 주문이 만들 수 있는 썸네일 임시 파일들. 처리기가 끝날 때 지운다. */
    static List<Path> files(Path workDir, long uploadId) {
        return List.of(workDir.resolve("upload-" + uploadId + "-thumb.jpg"),
                workDir.resolve("upload-" + uploadId + "-thumb.png"));
    }

    /**
     * @param video 받아 둔 완성 영상
     * @param token 영상을 올린 토큰. 없으면(이어 갈 수 없을 때 결론 내는 자리) auth에 한 번 더 묻는다
     */
    public ThumbnailReport attach(UploadEnvelope job, String videoId, Path video, String token) {
        if (job.thumbnail() == null) {
            return ThumbnailReport.NONE;
        }
        ThumbnailReport report;
        try {
            report = prepareAndSet(job, videoId, video, token);
        } catch (RuntimeException e) {
            // 마지막 그물: 무엇이 새도 영상 보고를 막지 않는다.
            log.warn("upload.thumbnail_unexpected uploadId={} what={}", job.uploadId(), e.getClass().getSimpleName());
            report = ThumbnailReport.failed("THUMBNAIL_UNAVAILABLE");
        }
        log.info("upload.thumbnail uploadId={} outcome={} code={}", job.uploadId(), report.outcome(), report.errorCode());
        return report;
    }

    private ThumbnailReport prepareAndSet(UploadEnvelope job, String videoId, Path video, String token) {
        long id = job.uploadId();
        Path picture;
        String contentType;
        switch (job.thumbnail()) {
            case Thumbnail.Scene scene -> {
                picture = files(workDir, id).get(0);
                contentType = "image/jpeg";
                if (!extractor.extract(video, scene.offsetMs(), picture)) {
                    return ThumbnailReport.failed("THUMBNAIL_EXTRACT_FAILED");
                }
            }
            case Thumbnail.File file -> {
                picture = files(workDir, id).get("image/png".equals(file.contentType()) ? 1 : 0);
                contentType = file.contentType();
                try {
                    Files.createDirectories(workDir);
                    storage.download(file.bucket(), file.s3Key(), picture);
                } catch (IOException | RuntimeException e) {
                    // 🔴 Unavailable로 감싸지 않는다(클래스 주석). 키가 없어도 읽기 권한이 없으면 S3는 403을 주므로 모두 「없음」으로 본다.
                    log.warn("upload.thumbnail_source_missing uploadId={} what={}", id, e.getClass().getSimpleName());
                    return ThumbnailReport.failed("THUMBNAIL_SOURCE_MISSING");
                }
            }
        }

        String current = token != null ? token : resolveQuietly(job);
        if (current == null) {
            return ThumbnailReport.failed("THUMBNAIL_NO_TOKEN");
        }
        boolean refreshed = false;
        int retries = 0;
        while (true) {
            switch (youtube.setThumbnail(current, videoId, picture, contentType)) {
                case ThumbnailSet.Done d -> {
                    return ThumbnailReport.SET;
                }
                case ThumbnailSet.Failed f -> {
                    return ThumbnailReport.failed(f.code());
                }
                case ThumbnailSet.Retry r -> {
                    if (retries >= retryDelays.size()) {
                        return ThumbnailReport.failed(r.code());
                    }
                    sleeper.sleep(retryDelays.get(retries++));
                }
                case ThumbnailSet.Unauthorized u -> {
                    // 긴 업로드 동안 토큰이 끝났을 수 있다. 한 번만 새로 받는다.
                    String fresh = refreshed ? null : resolveQuietly(job);
                    refreshed = true;
                    if (fresh == null) {
                        return ThumbnailReport.failed("THUMBNAIL_UNAUTHORIZED");
                    }
                    current = fresh;
                }
            }
        }
    }

    /** @return 토큰. 못 받으면(거절·auth 무응답) null. auth가 답을 안 해도 쪽지를 남기지 않는다 */
    private String resolveQuietly(UploadEnvelope job) {
        try {
            YoutubeTokenClient.Token token = auth.resolve(job.channelOwnerUserId());
            return token.valid() ? token.accessToken() : null;
        } catch (RuntimeException e) {
            log.warn("upload.thumbnail_token_unavailable uploadId={} what={}", job.uploadId(),
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
