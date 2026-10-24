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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * 업로드 주문 하나를 끝까지 처리한다(POK-220).
 *
 * <p><b>🔴 판단 원칙: 채널에 영상이 둘 생기면 안 된다.</b>
 * <ol>
 *   <li>바이트는 clip에 적힌 이어 올리기 주소 하나로만 보낸다. 새 주소를 받으면 clip에 적고 <b>clip이 돌려준 주소</b>(먼저 적힌 것)를 쓴다.</li>
 *   <li>보내기 전에 그 주소에 「어디까지 받았나」를 먼저 묻는다. 이미 다 받았으면(재배달·응답 유실) 영상 번호만 보고한다.</li>
 *   <li>{@code FAILED}는 <b>주소를 받기 전</b>의 실패에만, 「주소가 없을 때만」으로 보낸다(바이트가 갈 곳이 없었으니 영상이 없다.
 *       보내는 사이 주소가 생기면 clip이 그 주소를 돌려준다, POK-250). 주소가 생긴 뒤 더 못 가면
 *       주소에 물어 다 받았으면 올림, 아니면 {@code CHECKING}이다: 같은 주소로 다른 일꾼이 아직 올리는 중일 수 있어 「덜 받았다」도
 *       영상이 없다는 증거가 못 된다(PR #198 codex P1, clip도 같은 규칙으로 받는다). 모르면 쪽지를 남긴다.</li>
 * </ol>
 *
 * <p><b>썸네일(POK-291)은 영상이 끝난 뒤, clip에 올림을 보고하기 전에 붙인다</b>({@link #finish}). 보고를 먼저 하면 clip이 끝난 주문이
 * 되어, 붙이기 전에 멈춘 일꾼의 쪽지가 다시 와도 {@code proceed:false}라 영영 못 붙인다. 보고 전이면 다시 온 쪽지가 주소에 물어
 * 「다 받았다」를 듣고 이 자리로 다시 온다(같은 그림을 두 번 붙여도 해가 없다). 🔴 예외 하나: 두 번째 붙이기가 <b>실패</b>하면 첫 번째가
 * 붙었는지 알 수 없다. 그래서 바이트를 안 보낸 배달의 썸네일 실패는 {@code THUMBNAIL_UNCONFIRMED}로 보고한다({@link #finish}).
 * 썸네일 실패는 쪽지를 남기지 않는다({@link ThumbnailStep}).
 *
 * <p>토큰과 주소는 로그에 안 찍는다. 받은 파일은 끝나면 지운다.
 */
public class UploadProcessor {

    private static final Logger log = LoggerFactory.getLogger(UploadProcessor.class);
    /** 이번에 못 붙였지만 지난 배달에서 붙었을 수 있다({@link #finish}). */
    static final String THUMBNAIL_UNCONFIRMED = "THUMBNAIL_UNCONFIRMED";
    /**
     * 지난 배달에서도 붙었을 리 없는 실패(POK-291 로컬 리뷰 2라운드). 채널 권한(전화 인증)과 그림 자체는 몇 분 사이에 안 바뀐다.
     * 이것까지 {@value #THUMBNAIL_UNCONFIRMED}로 덮으면 보관함이 「전화 인증이 필요해요」 같은 고칠 길을 잃는다.
     */
    private static final Set<String> NEVER_SET_BEFORE = Set.of("THUMBNAIL_FORBIDDEN", "THUMBNAIL_INVALID_IMAGE");

    private final EnvelopeParser parser;
    private final ClipUploadApi clip;
    private final YoutubeTokenClient auth;
    private final S3Download storage;
    private final ResumableUploader youtube;
    private final ThumbnailStep thumbnails;
    private final Path workDir;
    private final long chunkSize;
    private final List<Duration> retryDelays;
    private final Sleeper sleeper;

    /**
     * 이 배달에서 resolve가 준 유튜브 채널(POK-423). 올림 보고에 싣는다: 연동을 해제하거나 채널을 바꾸면 clip이 그 채널의 영상 번호만
     * 골라 지운다. 이 배달이 주소를 새로 만들었을 때만 채운다: 이어받는 주소(재배달 · 겹친 일꾼)는 그 사이 채널을 바꿨을 수 있어 {@code null}
     * (모름)이고, 모르는 줄은 해제 기한에 같이 지워진다. 배달 하나 동안만 쓰고 {@link #process} 처음에 비운다. 처리는 줄 스레드 하나({@code QueueWorker}의 {@code upload-queue})만
     * 하므로 배달끼리 섞이지 않는다. 결론 내기 갈래처럼 토큰을 못 받은 배달은 {@code null}(채널 모름)이다.
     */
    private String channel;

    public UploadProcessor(EnvelopeParser parser, ClipUploadApi clip, YoutubeTokenClient auth, S3Download storage,
                           ResumableUploader youtube, ThumbnailStep thumbnails, Path workDir, long chunkSize,
                           List<Duration> retryDelays, Sleeper sleeper) {
        this.parser = parser;
        this.clip = clip;
        this.auth = auth;
        this.storage = storage;
        this.youtube = youtube;
        this.thumbnails = thumbnails;
        this.workDir = workDir;
        this.chunkSize = chunkSize;
        this.retryDelays = retryDelays;
        this.sleeper = sleeper;
    }

    /**
     * 처리 중이라는 신호를 clip에 보낸다(POK-333, {@link QueueWorker}가 처리하는 동안 1분마다 부른다). 🔴 <b>무엇이 와도 던지지 않는다</b>:
     * 신호가 실패해도 업로드는 그대로 간다(못 보내면 사람이 30분 뒤 「안 올라갔다」를 적을 수 있게 될 뿐이고, 그 위험은 WARN으로 남긴다).
     */
    public void heartbeat(String body) {
        long uploadId;
        try {
            uploadId = parser.parse(body).uploadId();
        } catch (RuntimeException e) {
            return; // 못 읽는 쪽지는 process가 지운다.
        }
        try {
            int status = clip.heartbeat(uploadId);
            if (status != 200) {
                log.warn("upload.heartbeat_rejected uploadId={} status={}", uploadId, status);
            }
        } catch (RuntimeException e) {
            log.warn("upload.heartbeat_failed uploadId={} err={}", uploadId, e.getMessage());
        }
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
        channel = null;
        try {
            return run(job, file);
        } catch (Unavailable e) {
            // clip·auth·S3가 답을 안 준다. 쪽지를 남긴다: 다시 와도 clip에 적힌 주소로 잇는다.
            log.warn("upload.unavailable uploadId={} what={}", job.uploadId(), e.getMessage());
            return retryLater();
        } finally {
            for (Path temp : Stream.concat(Stream.of(file), ThumbnailStep.files(workDir, job.uploadId()).stream())
                    .toList()) {
                try {
                    Files.deleteIfExists(temp);
                } catch (java.io.IOException e) {
                    log.warn("upload.cleanup_failed uploadId={}", job.uploadId());
                }
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
        // 이 배달이 주소를 새로 만들 때만 채널을 안다. 이어받는 주소는 그 사이 채널이 바뀌었을 수 있어 「모름」으로 둔다(PR #375 codex)
        channel = session == null ? token.channelId() : null;
        String code = "YOUTUBE_" + token.reason();
        String message = "유튜브 연동이 없거나 끊겼다. 스트리머가 다시 연동해야 한다";
        // 영상 바이트가 필요 없는 갈래는 창고에서 받기 전에 끝낸다. 재배달마다 수십 MB를 헛받지 않게(PR #199 codex 3판).
        if (!token.valid()) {
            if (token.transientRefusal()) {
                return retryLater();
            }
            if (session == null) {
                // 🔴 「주소가 없을 때만 실패」로 보낸다(POK-250). 그사이 겹친 일꾼이 주소를 적었으면 실패가 그 주소로 이어 갈 길을 막는다
                // (PR #199 codex 2판). 판정은 clip이 줄 잠금 안에서 한 번에 한다: 다시 묻고 나서 실패하면 그 사이에 적힌 주소를 못 잡는다.
                return switch (clip.failIfNoSession(id, code, message)) {
                    case ClipUploadApi.FailOutcome.Failed f -> Disposition.DELETE;
                    case ClipUploadApi.FailOutcome.Terminal t -> Disposition.DELETE;
                    case ClipUploadApi.FailOutcome.SessionExists s -> {
                        // 이 일꾼은 토큰이 없어 이을 수 없다. 그 일꾼이 끝내면 끝난 주문이 되고, 멈췄으면 다음 배달이 주소에 묻는다.
                        log.info("upload.session_appeared_while_unlinked uploadId={}", id);
                        yield retryLater();
                    }
                };
            }
        }

        long size;
        try {
            Files.createDirectories(workDir);
            size = storage.download(job.bucket(), job.s3Key(), file);
        } catch (Exception e) {
            throw new Unavailable("S3 " + e.getClass().getSimpleName(), e);
        }
        log.info("upload.begin uploadId={} bytes={} resuming={} tokenValid={}", id, size, session != null, token.valid());

        if (!token.valid()) {
            // 주소는 있는데 토큰이 없다: 이어 갈 수는 없지만 끝났는지는 물어 볼 수 있다.
            return settleWithoutContinuing(job, file, session, null, size, false, code, message, false);
        }

        String access = token.accessToken();
        // 🔴 강제 갱신(401 뒤 새 토큰)은 이 배달 전체에 한 번이다(POK-250). 자리마다 한 번이면 시작 · 조각 · 결론 내기를 돌며 끝없이 부른다.
        // 썸네일은 자기 한 번을 따로 쓴다(ThumbnailStep): finish가 토큰 없이 부르면 썸네일이 resolve로 받은 토큰이 401일 때 한 번 더 갱신한다.
        boolean refreshed = false;
        if (session == null) {
            Start start = youtube.start(access, job.title(), job.description(), job.categoryId(),
                    job.privacyStatus(), job.publishAt(), job.tags(), job.madeForKids(), size);
            if (start instanceof Start.Unauthorized) {
                log.warn("upload.start_unauthorized uploadId={}", id);
                Token fresh = fresh(job, access);
                refreshed = true;
                if (!fresh.valid()) {
                    return fresh.transientRefusal() ? retryLater()
                            : failUnlessAdopted(job, null, file, size, "YOUTUBE_" + fresh.reason(), message, true);
                }
                access = fresh.accessToken();
                start = youtube.start(access, job.title(), job.description(), job.categoryId(),
                        job.privacyStatus(), job.publishAt(), job.tags(), job.madeForKids(), size);
            }
            switch (start) {
                case Start.Session s -> {
                    session = clip.session(id, s.uri());
                    if (session != null && !session.equals(s.uri())) {
                        channel = null; // 겹친 일꾼이 먼저 적은 주소다: 어느 채널 토큰으로 만들었는지 모른다
                    }
                    if (session == null) {
                        log.info("upload.skip_after_session uploadId={}", id);
                        return Disposition.DELETE;
                    }
                }
                case Start.Quota q -> {
                    return failUnlessAdopted(job, access, file, size, "QUOTA_EXCEEDED",
                            "유튜브 하루 올리기 한도에 걸렸다(" + q.reason() + "). 내일 다시 올린다", refreshed);
                }
                case Start.Rejected r -> {
                    return failUnlessAdopted(job, access, file, size, "YOUTUBE_REJECTED",
                            "유튜브가 올리기를 거절했다: HTTP " + r.status() + " " + r.reason(), refreshed);
                }
                case Start.Unauthorized u -> {
                    // 강제 갱신한 새 토큰도 401이다. 주소가 없으니 영상도 없다: 연동이 끊긴 것으로 실패한다(이 토큰으로는 못 잇는다).
                    log.warn("upload.start_unauthorized_after_refresh uploadId={}", id);
                    return failUnlessAdopted(job, null, file, size, "YOUTUBE_BROKEN", message, true);
                }
                case Start.Transient t -> {
                    log.warn("upload.start_transient uploadId={} what={}", id, t.what());
                    return retryLater();
                }
            }
        }
        return drive(job, session, access, file, size, refreshed);
    }

    /** 401을 받은 토큰 대신 새 토큰(POK-250). 부르는 쪽이 배달에 한 번만 부른다. */
    private Token fresh(UploadEnvelope job, String rejected) {
        return auth.resolveAfterRejection(job.channelOwnerUserId(), rejected);
    }

    /**
     * 주소에 먼저 묻고, 덜 받았으면 그 자리부터 조각을 보낸다. 끊기면 다시 묻는다.
     *
     * @param refreshed 이 배달에서 강제 갱신을 이미 썼다
     */
    private Disposition drive(UploadEnvelope job, String session, String token, Path file, long size, boolean refreshed) {
        long id = job.uploadId();
        int failures = 0;
        // 이 배달에서 바이트를 하나라도 보냈나. 안 보냈는데 끝났으면 지난 배달(또는 겹친 일꾼)이 영상을 끝낸 것이다(finish 주석).
        boolean sentBytes = false;
        Progress progress = youtube.status(session, token, size);
        while (true) {
            switch (progress) {
                case Progress.Done d -> {
                    return finish(job, d.videoId(), file, token, !sentBytes);
                }
                case Progress.Incomplete inc when inc.next() < size -> {
                    int length = (int) Math.min(chunkSize, size - inc.next());
                    sentBytes = true;
                    progress = youtube.put(session, token, file, inc.next(), length, size);
                    if (!(progress instanceof Progress.Transient)) {
                        failures = 0;
                    }
                }
                case Progress.Incomplete inc -> {
                    // 다 보냈다는데 덜 받았다고 한다: 응답이 꼬였다. 다시 묻는다.
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
                    // 올리는 도중 토큰이 끝났다(POK-250). 새 토큰으로 주소에 먼저 묻고 받은 데부터 잇는다. 이미 한 번 갱신했으면 이어 갈
                    // 수 없다: 결론을 낸다. 401을 받은 토큰은 넘기지 않는다(주소 물음은 토큰 없이 묻는다).
                    log.warn("upload.unauthorized_midway uploadId={} refreshed={}", id, refreshed);
                    String reason = "유튜브가 토큰을 받지 않는다. 스트리머가 다시 연동해야 할 수 있다";
                    if (refreshed) {
                        return settleWithoutContinuing(job, file, session, null, size, sentBytes, "YOUTUBE_BROKEN", reason, true);
                    }
                    Token fresh = fresh(job, token);
                    refreshed = true;
                    if (!fresh.valid()) {
                        return fresh.transientRefusal() ? retryLater()
                                : settleWithoutContinuing(job, file, session, null, size, sentBytes,
                                        "YOUTUBE_" + fresh.reason(), reason, true);
                    }
                    token = fresh.accessToken();
                    progress = youtube.status(session, token, size);
                }
                case Progress.Gone g -> {
                    clip.checking(id, "SESSION_GONE", "유튜브 이어 올리기 주소가 사라졌다(HTTP " + g.status()
                            + "). 채널에서 올라갔는지 확인해야 한다");
                    return Disposition.DELETE;
                }
                case Progress.Rejected r -> {
                    return settleWithoutContinuing(job, file, session, token, size, sentBytes, "YOUTUBE_REJECTED",
                            "유튜브가 바이트를 거절했다: HTTP " + r.status() + " " + r.reason(), refreshed);
                }
            }
        }
    }

    /**
     * 시작이 거절됐다. 🔴 실패를 「주소가 없을 때만」으로 보낸다(POK-250): 이 일꾼이 시작을 청하는 사이 겹친 일꾼이 주소를 적어 두었으면
     * 실패 보고는 그 주소로 이어 갈 길을 막는다(clip이 확인 중으로 닫고 쪽지가 지워진다, PR #199 codex). 그때 clip은 상태를 안 바꾸고
     * 주소를 돌려주므로 그리로 잇는다. 토큰이 없으면 이을 수 없어 쪽지를 남긴다.
     */
    private Disposition failUnlessAdopted(UploadEnvelope job, String token, Path file, long size, String code,
                                          String message, boolean refreshed) {
        long id = job.uploadId();
        return switch (clip.failIfNoSession(id, code, message)) {
            case ClipUploadApi.FailOutcome.SessionExists s -> {
                log.info("upload.adopt_session uploadId={} after={}", id, code);
                channel = null; // 겹친 일꾼의 주소다: 어느 채널 토큰으로 만들었는지 모른다
                yield token == null ? retryLater() : drive(job, s.sessionUri(), token, file, size, refreshed);
            }
            case ClipUploadApi.FailOutcome.Failed f -> Disposition.DELETE;
            case ClipUploadApi.FailOutcome.Terminal t -> Disposition.DELETE;
        };
    }

    /**
     * 주소가 생긴 뒤 더 보낼 수 없을 때 결론 내기. 주소에 물어 다 받았으면 올림, 아니면 확인 중(사람이 채널을 본다).
     * 🔴 실패로 닫지 않는다: 자리가 비면 다시 올리고, 같은 주소로 다른 일꾼이 끝내면 영상이 둘 뜬다.
     *
     * <p>토큰을 들고 물었는데 401이면(POK-250) 이 배달에서 강제 갱신을 아직 안 썼을 때만 새 토큰으로 한 번 더 묻는다.
     *
     * @param refreshed 이 배달에서 강제 갱신을 이미 썼다
     */
    private Disposition settleWithoutContinuing(UploadEnvelope job, Path file, String session, String token, long size,
                                                boolean sentBytes, String code, String message, boolean refreshed) {
        long id = job.uploadId();
        switch (youtube.status(session, token, size)) {
            case Progress.Done d -> {
                return finish(job, d.videoId(), file, token, !sentBytes);
            }
            case Progress.Unauthorized u when token != null && !refreshed -> {
                Token fresh = fresh(job, token);
                if (fresh.valid()) {
                    return settleWithoutContinuing(job, file, session, fresh.accessToken(), size, sentBytes, code, message, true);
                }
                if (fresh.transientRefusal()) {
                    return retryLater();
                }
                // 처음 사유가 아니라 갱신 거절 사유로 남긴다(drive의 같은 자리와 같다): 사람이 「다시 연동」이 필요함을 알게.
                clip.checking(id, "YOUTUBE_" + fresh.reason(), message + ". 끝났는지 확인하지 못했다");
            }
            case Progress.Incomplete inc -> clip.checking(id, code, message + ". 유튜브는 덜 받았다고 한다");
            case Progress.Transient t -> {
                // 지금은 모른다. 쪽지를 남긴다: 다시 와서 물으면 된다. 끝내 모르면 실패 큐에서 checking이 된다.
                return retryLater();
            }
            default -> clip.checking(id, code, message + ". 끝났는지 확인하지 못했다");
        }
        return Disposition.DELETE;
    }

    /**
     * 영상이 끝난 자리 둘(이어 올리기의 끝 · 이어 갈 수 없을 때 결론 내기)이 모이는 곳. 🔴 썸네일을 <b>먼저</b> 붙이고 그 결과를 실어
     * 올림을 보고한다(클래스 주석). 썸네일은 실패해도 쪽지를 남기지 않는다. 보고가 안 되면(clip 무응답) 지금처럼 쪽지를 남긴다:
     * 다시 오면 주소가 「다 받았다」고 해 이 자리로 다시 온다.
     *
     * <p>🔴 이 배달에서 바이트를 하나도 안 보냈는데 끝났으면(지난 배달이 영상을 끝냈다) 썸네일 실패를 그 코드 그대로 보내지 않고
     * {@value #THUMBNAIL_UNCONFIRMED}로 보낸다. 지난 배달이 붙인 뒤 보고만 못 했을 수 있다: 그때의 SET은 clip에 닿은 적이 없어
     * 이번 실패가 첫 값으로 남고, 보관함이 채널에 붙은 그림을 「못 붙였어요」로 안내한다(로컬 리뷰 1라운드). 단 지난 배달에서도 붙었을 리
     * 없는 사유(권한 없음·그림 거절)는 원래 코드를 둔다(2라운드).
     *
     * @param token          영상을 올린 토큰. 결론 내기 자리에서는 null일 수 있다
     * @param finishedBefore 이 배달에서 바이트를 하나도 안 보냈다
     */
    private Disposition finish(UploadEnvelope job, String videoId, Path file, String token, boolean finishedBefore) {
        ClipUploadApi.ThumbnailReport thumbnail = thumbnails.attach(job, videoId, file, token);
        if (finishedBefore && "FAILED".equals(thumbnail.outcome()) && !NEVER_SET_BEFORE.contains(thumbnail.errorCode())) {
            log.info("upload.thumbnail_unconfirmed uploadId={} code={}", job.uploadId(), thumbnail.errorCode());
            thumbnail = ClipUploadApi.ThumbnailReport.failed(THUMBNAIL_UNCONFIRMED);
        }
        clip.uploaded(job.uploadId(), videoId, thumbnail, channel);
        log.info("upload.done uploadId={} videoId={} thumbnail={}", job.uploadId(), videoId, thumbnail.outcome());
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
