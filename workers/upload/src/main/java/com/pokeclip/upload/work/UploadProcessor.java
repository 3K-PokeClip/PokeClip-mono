package com.pokeclip.upload.work;

import com.pokeclip.upload.auth.YoutubeTokenClient;
import com.pokeclip.upload.auth.YoutubeTokenClient.Token;
import com.pokeclip.upload.clip.ClipUploadApi;
import com.pokeclip.upload.job.EnvelopeParser;
import com.pokeclip.upload.job.UploadEnvelope;
import com.pokeclip.upload.storage.S3Download;
import com.pokeclip.upload.youtube.ResumableUploader;
import com.pokeclip.upload.youtube.ResumableUploader.Progress;
import com.pokeclip.upload.youtube.ResumableUploader.Start;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 업로드 주문 하나를 끝까지 처리한다(POK-220).
 *
 * <p><b>🔴 판단 원칙: 채널에 영상이 둘 생기면 안 된다.</b>
 * <ol>
 *   <li>바이트는 clip에 적힌 이어 올리기 주소 하나로만 보낸다. 새 주소를 받으면 clip에 적고 <b>clip이 돌려준 주소</b>(먼저 적힌 것)를 쓴다.</li>
 *   <li>보내기 전에 그 주소에 「어디까지 받았나」를 먼저 묻는다. 이미 다 받았으면(재배달·응답 유실) 영상 번호만 보고한다.</li>
 *   <li>{@code FAILED}는 <b>주소를 받기 전</b>의 실패에만 보낸다(바이트가 갈 곳이 없었으니 영상이 없다). 주소가 생긴 뒤 더 못 가면
 *       주소에 물어 다 받았으면 올림, 아니면 {@code CHECKING}이다 — 같은 주소로 다른 일꾼이 아직 올리는 중일 수 있어 「덜 받았다」도
 *       영상이 없다는 증거가 못 된다(PR #198 codex P1, clip도 같은 규칙으로 받는다). 모르면 쪽지를 남긴다.</li>
 * </ol>
 *
 * <p>토큰과 주소는 로그에 안 찍는다. 받은 파일은 끝나면 지운다.
 */
public class UploadProcessor {

    private static final Logger log = LoggerFactory.getLogger(UploadProcessor.class);

    private final EnvelopeParser parser;
    private final ClipUploadApi clip;
    private final YoutubeTokenClient auth;
    private final S3Download storage;
    private final ResumableUploader youtube;
    private final Path workDir;
    private final long chunkSize;
    private final List<Duration> retryDelays;
    private final Sleeper sleeper;

    public UploadProcessor(EnvelopeParser parser, ClipUploadApi clip, YoutubeTokenClient auth, S3Download storage,
                           ResumableUploader youtube, Path workDir, long chunkSize, List<Duration> retryDelays,
                           Sleeper sleeper) {
        this.parser = parser;
        this.clip = clip;
        this.auth = auth;
        this.storage = storage;
        this.youtube = youtube;
        this.workDir = workDir;
        this.chunkSize = chunkSize;
        this.retryDelays = retryDelays;
        this.sleeper = sleeper;
    }

    public Disposition process(String body) {
        UploadEnvelope job;
        try {
            job = parser.parse(body);
        } catch (EnvelopeParser.Unreadable e) {
            log.warn("upload.unreadable reason={}", e.getMessage());
            return Disposition.DELETE;
        }
        Path file = workDir.resolve("upload-" + job.uploadId() + ".mp4");
        try {
            return run(job, file);
        } catch (Unavailable e) {
            // clip·auth·S3가 답을 안 준다. 쪽지를 남긴다 — 다시 와도 clip에 적힌 주소로 잇는다.
            log.warn("upload.unavailable uploadId={} what={}", job.uploadId(), e.getMessage());
            return retryLater();
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (java.io.IOException e) {
                log.warn("upload.cleanup_failed uploadId={}", job.uploadId());
            }
        }
    }

    private Disposition run(UploadEnvelope job, Path file) {
        long id = job.uploadId();
        ClipUploadApi.Start started = clip.start(id);
        if (!started.found() || !started.proceed()) {
            log.info("upload.skip uploadId={} found={} status={}", id, started.found(), started.status());
            return Disposition.DELETE;
        }
        String session = started.sessionUri();

        Token token = auth.resolve(job.channelOwnerUserId());
        long size;
        try {
            Files.createDirectories(workDir);
            size = storage.download(job.bucket(), job.s3Key(), file);
        } catch (Exception e) {
            throw new Unavailable("S3 " + e.getClass().getSimpleName(), e);
        }
        log.info("upload.begin uploadId={} bytes={} resuming={} tokenValid={}", id, size, session != null, token.valid());

        if (!token.valid()) {
            if (token.transientRefusal()) {
                return retryLater();
            }
            String code = "YOUTUBE_" + token.reason();
            String message = "유튜브 연동이 없거나 끊겼다. 스트리머가 다시 연동해야 한다";
            if (session == null) {
                clip.failed(id, code, message);
                return Disposition.DELETE;
            }
            // 주소는 있는데 토큰이 없다 — 이어 갈 수는 없지만 끝났는지는 물어 볼 수 있다.
            return settleWithoutContinuing(id, session, null, size, code, message);
        }

        if (session == null) {
            Start start = youtube.start(token.accessToken(), job.title(), job.description(), job.privacyStatus(), size);
            switch (start) {
                case Start.Session s -> {
                    session = clip.session(id, s.uri());
                    if (session == null) {
                        log.info("upload.skip_after_session uploadId={}", id);
                        return Disposition.DELETE;
                    }
                }
                case Start.Quota q -> {
                    return failUnlessAdopted(id, token.accessToken(), file, size, "QUOTA_EXCEEDED",
                            "유튜브 하루 올리기 한도에 걸렸다(" + q.reason() + "). 내일 다시 올린다");
                }
                case Start.Rejected r -> {
                    return failUnlessAdopted(id, token.accessToken(), file, size, "YOUTUBE_REJECTED",
                            "유튜브가 올리기를 거절했다: HTTP " + r.status() + " " + r.reason());
                }
                case Start.Unauthorized u -> {
                    log.warn("upload.start_unauthorized uploadId={}", id);
                    return retryLater();
                }
                case Start.Transient t -> {
                    log.warn("upload.start_transient uploadId={} what={}", id, t.what());
                    return retryLater();
                }
            }
        }
        return drive(id, session, token.accessToken(), file, size);
    }

    /** 주소에 먼저 묻고, 덜 받았으면 그 자리부터 조각을 보낸다. 끊기면 다시 묻는다. */
    private Disposition drive(long id, String session, String token, Path file, long size) {
        int failures = 0;
        Progress progress = youtube.status(session, token, size);
        while (true) {
            switch (progress) {
                case Progress.Done d -> {
                    clip.uploaded(id, d.videoId());
                    log.info("upload.done uploadId={} videoId={}", id, d.videoId());
                    return Disposition.DELETE;
                }
                case Progress.Incomplete inc when inc.next() < size -> {
                    int length = (int) Math.min(chunkSize, size - inc.next());
                    progress = youtube.put(session, token, file, inc.next(), length, size);
                    if (!(progress instanceof Progress.Transient)) {
                        failures = 0;
                    }
                }
                case Progress.Incomplete inc -> {
                    // 다 보냈다는데 덜 받았다고 한다 — 응답이 꼬였다. 다시 묻는다.
                    progress = transientProgress("incomplete_at_end");
                }
                case Progress.Transient t -> {
                    if (failures >= retryDelays.size()) {
                        log.warn("upload.transient_exhausted uploadId={} what={}", id, t.what());
                        return retryLater();
                    }
                    sleeper.sleep(retryDelays.get(failures++));
                    // 응답을 못 받았다. 바이트가 다 갔을 수 있으니 새로 보내지 말고 먼저 묻는다.
                    progress = youtube.status(session, token, size);
                }
                case Progress.Unauthorized u -> {
                    // 올리는 도중 토큰이 끝났다. 다음 배달에 새 토큰으로 같은 주소를 잇는다.
                    log.warn("upload.unauthorized_midway uploadId={}", id);
                    return retryLater();
                }
                case Progress.Gone g -> {
                    clip.checking(id, "SESSION_GONE", "유튜브 이어 올리기 주소가 사라졌다(HTTP " + g.status()
                            + "). 채널에서 올라갔는지 확인해야 한다");
                    return Disposition.DELETE;
                }
                case Progress.Rejected r -> {
                    return settleWithoutContinuing(id, session, token, size, "YOUTUBE_REJECTED",
                            "유튜브가 바이트를 거절했다: HTTP " + r.status() + " " + r.reason());
                }
            }
        }
    }

    /**
     * 시작이 거절됐다. 🔴 실패를 보내기 전에 clip에 다시 묻는다 — 이 일꾼이 시작을 청하는 사이 겹친 일꾼이 주소를 적어 두었으면
     * 실패 보고는 그 주소로 이어 갈 길을 막는다(clip이 확인 중으로 닫고 쪽지가 지워진다, PR #199 codex). 적힌 주소가 있으면 그리로 잇는다.
     */
    private Disposition failUnlessAdopted(long id, String token, Path file, long size, String code, String message) {
        ClipUploadApi.Start again = clip.start(id);
        if (!again.found() || !again.proceed()) {
            return Disposition.DELETE;
        }
        if (again.sessionUri() != null) {
            log.info("upload.adopt_session uploadId={} after={}", id, code);
            return drive(id, again.sessionUri(), token, file, size);
        }
        clip.failed(id, code, message);
        return Disposition.DELETE;
    }

    /**
     * 주소가 생긴 뒤 더 보낼 수 없을 때 결론 내기. 주소에 물어 다 받았으면 올림, 아니면 확인 중(사람이 채널을 본다).
     * 🔴 실패로 닫지 않는다 — 자리가 비면 다시 올리고, 같은 주소로 다른 일꾼이 끝내면 영상이 둘 뜬다.
     */
    private Disposition settleWithoutContinuing(long id, String session, String token, long size, String code,
                                                String message) {
        switch (youtube.status(session, token, size)) {
            case Progress.Done d -> clip.uploaded(id, d.videoId());
            case Progress.Incomplete inc -> clip.checking(id, code, message + ". 유튜브는 덜 받았다고 한다");
            case Progress.Transient t -> {
                // 지금은 모른다. 쪽지를 남긴다 — 다시 와서 물으면 된다. 끝내 모르면 실패 큐에서 checking이 된다.
                return retryLater();
            }
            default -> clip.checking(id, code, message + ". 끝났는지 확인하지 못했다");
        }
        return Disposition.DELETE;
    }

    private static Progress transientProgress(String what) {
        return new Progress.Transient(what);
    }

    /** 60초 + 0~60초 뒤 다시(렌더 일꾼과 같은 간격). 여럿이 한꺼번에 다시 오지 않게 흩는다. */
    private static Disposition retryLater() {
        return Disposition.delay(Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(61)));
    }
}
