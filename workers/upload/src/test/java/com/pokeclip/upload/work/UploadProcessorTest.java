package com.pokeclip.upload.work;

import com.pokeclip.upload.auth.YoutubeTokenClient;
import com.pokeclip.upload.clip.ClipUploadApi;
import com.pokeclip.upload.job.EnvelopeParser;
import com.pokeclip.upload.storage.S3Download;
import com.pokeclip.upload.support.FakeInternal;
import com.pokeclip.upload.support.FakeYoutube;
import com.pokeclip.upload.youtube.ResumableUploader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업로드 처리기(POK-220). <b>재는 것의 중심은 「가짜 유튜브가 만든 영상 수가 1」이다</b> — 응답이 사라져도, 쪽지가 두 번 와도,
 * 일꾼이 도중에 멈췄다 다시 와도, 다른 일꾼이 주소를 먼저 적어 두었어도. 그리고 「실패」는 영상이 없다는 것을 확인했을 때만 나간다.
 *
 * <p>가짜 유튜브는 이어 올리기 규칙대로 돈다(바이트를 다 받은 주소만 영상을 만든다). clip·auth도 가짜지만 clip 판정 규칙은 진짜와 같다.
 */
@ExtendWith(OutputCaptureExtension.class)
class UploadProcessorTest {

    private static final long CHUNK = 256 * 1024;
    private static final int SIZE = (int) (CHUNK * 2 + 1000);

    @TempDir
    Path dir;

    private FakeYoutube youtube;
    private FakeInternal internal;
    private byte[] video;

    @BeforeEach
    void 준비() throws Exception {
        youtube = new FakeYoutube();
        internal = new FakeInternal();
        video = new byte[SIZE];
        new Random(7).nextBytes(video);
    }

    @AfterEach
    void 정리() {
        youtube.close();
        internal.close();
    }

    /** 조각 셋(256KiB·256KiB·1000B)으로 나눠 보내고, 영상 하나가 비공개·제목 그대로 생기고, 바이트가 원본과 같다. */
    @Test
    void 이어_올리기로_조각을_나눠_보내고_영상_하나를_보고한다() {
        Disposition d = processor().process(주문서());

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(youtube.videosCreated()).isEqualTo(1);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        FakeYoutube.Session s = youtube.session(internal.sessionUri);
        assertThat(s.bytes.toByteArray()).isEqualTo(video);
        assertThat(s.privacy).isEqualTo("private");
        assertThat(s.title).isEqualTo("펜타킬 순간");
    }

    /**
     * 🔴 마지막 조각을 받고 영상을 만들었는데 <b>응답만 사라졌다</b>. 새로 올리면 영상이 둘이다. 처리기는 끊김을 보고 주소에 다시
     * 물어 영상 번호를 받는다.
     */
    @Test
    void 마지막_응답이_사라져도_다시_올리지_않고_주소에_물어_영상_번호를_받는다() {
        youtube.dropFinalResponseOnce = true;

        processor().process(주문서());

        assertThat(youtube.videosCreated()).isEqualTo(1);
        assertThat(youtube.sessionsStarted()).isEqualTo(1);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
    }

    /** 쪽지가 두 번 온다(SQS는 적어도 한 번). 두 번째는 clip이 끝난 주문이라고 해서 아무것도 안 한다. */
    @Test
    void 끝난_주문의_쪽지가_다시_와도_아무것도_안_한다() {
        processor().process(주문서());
        Disposition 두번째 = processor().process(주문서());

        assertThat(두번째).isEqualTo(Disposition.DELETE);
        assertThat(youtube.videosCreated()).isEqualTo(1);
        assertThat(youtube.sessionsStarted()).isEqualTo(1);
        assertThat(internal.results).hasSize(1);
    }

    /**
     * 🔴 일꾼이 도중에 멈췄다(유튜브 5xx가 재시도보다 길게 이어짐). 쪽지를 남기고, 다시 받으면 <b>새 주소를 만들지 않고</b> clip에
     * 적힌 주소에서 받은 데까지 잇는다.
     */
    @Test
    void 도중에_멈췄다_다시_오면_같은_주소에서_받은_데부터_잇는다() {
        Disposition 첫째 = processorFailingAfterFirstChunk().process(주문서());
        assertThat(첫째.kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(internal.sessionUri).isNotNull();
        assertThat(youtube.session(internal.sessionUri).bytes.size()).isEqualTo((int) CHUNK);
        assertThat(internal.results).isEmpty();

        Disposition 둘째 = processor().process(주문서());

        assertThat(둘째).isEqualTo(Disposition.DELETE);
        assertThat(youtube.sessionsStarted()).as("새 주소를 만들었다").isEqualTo(1);
        assertThat(youtube.videosCreated()).isEqualTo(1);
        assertThat(youtube.session(internal.sessionUri).bytes.toByteArray()).isEqualTo(video);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
    }

    /**
     * 🔴 다른 일꾼이 같은 주문을 겹쳐 잡아 주소를 먼저 적었다. 이 일꾼은 자기 주소를 받았지만 clip이 돌려준 <b>먼저 적힌 주소</b>로
     * 보낸다 — 자기 주소는 바이트를 안 받아 영상이 안 생긴다.
     */
    @Test
    void 다른_일꾼이_먼저_적은_주소가_있으면_그리로_보낸다() {
        String 먼저 = youtube.openSession(SIZE);
        // start가 주소를 안 주고(아직 없었다), 이 일꾼이 새 주소를 받아 적으려는 순간 먼저 것이 이미 적혀 있다.
        internal.sessionUri = null;
        UploadProcessor p = processor();
        internalWillRecordFirst(먼저);

        p.process(주문서());

        assertThat(internal.sessionUri).isEqualTo(먼저);
        assertThat(youtube.session(먼저).bytes.toByteArray()).isEqualTo(video);
        assertThat(youtube.videosCreated()).isEqualTo(1);
    }

    /** 하루 한도(쿼터)에 걸리면 조용히 넘어가지 않고 {@code QUOTA_EXCEEDED}로 드러난다. 주소를 못 받았으니 영상은 확실히 없다. */
    @Test
    void 쿼터에_걸리면_QUOTA_EXCEEDED로_실패한다() {
        youtube.quotaOnStart = true;

        processor().process(주문서());

        assertThat(internal.results).containsExactly("FAILED:QUOTA_EXCEEDED");
        assertThat(youtube.videosCreated()).isZero();
    }

    /** 채널 업로드 한도(`uploadLimitExceeded`)는 유튜브가 400으로 준다. 그것도 한도로 드러나야 한다(PR #199 codex). */
    @Test
    void 채널_업로드_한도는_400이어도_QUOTA_EXCEEDED다() {
        youtube.uploadLimitOnStart = true;

        processor().process(주문서());

        assertThat(internal.results).containsExactly("FAILED:QUOTA_EXCEEDED");
    }

    /**
     * 🔴 이 일꾼이 시작을 청하는 사이 다른 일꾼이 주소를 적어 두었다. 이 일꾼은 한도에 걸렸지만 실패를 보내면 안 된다 — 그 주소로 이어
     * 갈 길이 막힌다(PR #199 codex). 실패 전에 clip에 다시 물어 적힌 주소가 있으면 그 주소로 잇는다.
     */
    @Test
    void 한도에_걸려도_그사이_다른_일꾼이_적은_주소가_있으면_그리로_잇는다() {
        String 다른_일꾼 = youtube.openSession(SIZE);
        internal.lateSession = 다른_일꾼;
        youtube.quotaOnStart = true;

        processor().process(주문서());

        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(youtube.session(다른_일꾼).bytes.toByteArray()).isEqualTo(video);
        assertThat(youtube.videosCreated()).isEqualTo(1);
    }

    /** 연동이 끊긴 채널: 주소가 없으면 영상도 없으니 실패. 잠깐의 갱신 실패는 다시 온다(아무것도 보고 안 한다). */
    @Test
    void 연동이_끊겼으면_실패_잠깐의_갱신_실패면_다시_온다() {
        internal.accessToken = null;
        internal.refusal = "REFRESH_UNAVAILABLE";
        assertThat(processor().process(주문서()).kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(internal.results).isEmpty();

        internal.refusal = "BROKEN";
        processor().process(주문서());
        assertThat(internal.results).containsExactly("FAILED:YOUTUBE_BROKEN");
        assertThat(youtube.sessionsStarted()).isZero();
    }

    /**
     * 유튜브가 바이트를 거절했다. 주소가 이미 있으니 🔴 실패로 닫지 않고 확인 중이다(같은 주소로 다른 일꾼이 올리는 중일 수 있다).
     * 주소에 물어 다 받았으면 올림이다.
     */
    @Test
    void 주소가_생긴_뒤_바이트를_거절당하면_실패가_아니라_확인_중이다() {
        youtube.rejectChunks = true;
        processor().process(주문서());
        assertThat(internal.results).containsExactly("CHECKING:YOUTUBE_REJECTED");
        assertThat(youtube.videosCreated()).isZero();
    }

    @Test
    void 주소가_사라지면_확인_중이다() {
        internal.sessionUri = youtube.openSession(SIZE);
        youtube.goneSessions = true;

        processor().process(주문서());

        assertThat(internal.results).containsExactly("CHECKING:SESSION_GONE");
    }

    @Test
    void 못_읽는_쪽지는_지운다() {
        assertThat(processor().process("{이건 아니다")).isEqualTo(Disposition.DELETE);
        assertThat(processor().process("{\"schemaVersion\":1,\"jobType\":\"RENDER\"}")).isEqualTo(Disposition.DELETE);
    }

    /** clip이 답을 안 하면 쪽지를 남긴다. 아무것도 안 올린다. */
    @Test
    void clip이_답을_안_하면_쪽지를_남긴다() {
        internal.clipDown = 100;
        assertThat(processor().process(주문서()).kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(youtube.sessionsStarted()).isZero();
    }

    /** 🔴 유튜브 토큰과 이어 올리기 주소는 로그 어디에도 안 남는다. 주소 자체가 올리기 권한이다. */
    @Test
    void 토큰과_주소는_로그에_안_남는다(CapturedOutput output) {
        youtube.dropFinalResponseOnce = true;
        processor().process(주문서());

        assertThat(output.getAll()).contains("upload.done").doesNotContain(FakeYoutube.GOOD_TOKEN).doesNotContain("/session/");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private UploadProcessor processor() {
        return processor(new ResumableUploader(new ObjectMapper(), youtube.startUrl()));
    }

    private UploadProcessor processor(ResumableUploader uploader) {
        ObjectMapper mapper = new ObjectMapper();
        Sleeper noWait = d -> { };
        InternalHttp http = new InternalHttp(mapper, FakeInternal.TOKEN, List.of(Duration.ZERO), noWait);
        S3Download storage = new S3Download(null) {
            @Override
            public long download(String bucket, String key, Path target) {
                try {
                    Files.write(target, video);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                return video.length;
            }
        };
        return new UploadProcessor(new EnvelopeParser(mapper), new ClipUploadApi(http, internal.baseUrl()),
                new YoutubeTokenClient(http, internal.baseUrl()), storage, uploader, dir, CHUNK,
                List.of(Duration.ZERO, Duration.ZERO), noWait);
    }

    /** 첫 조각만 받고 그 뒤 조각은 5xx를 끝없이 주는 유튜브(도중에 멈춘 일꾼). */
    private UploadProcessor processorFailingAfterFirstChunk() {
        return processor(new ResumableUploader(new ObjectMapper(), youtube.startUrl()) {
            private int puts;

            @Override
            public Progress put(String sessionUri, String accessToken, Path file, long offset, int length, long size) {
                if (puts++ >= 1) {
                    return new Progress.Transient("HTTP 503");
                }
                return super.put(sessionUri, accessToken, file, offset, length, size);
            }
        });
    }

    /** 이 일꾼이 주소를 적으려는 순간 다른 일꾼의 주소가 이미 적혀 있게 한다. start에는 아직 없었다. */
    private void internalWillRecordFirst(String first) {
        internal.preRecordOnSession = first;
    }

    private static String 주문서() {
        return """
                {"schemaVersion":1,"jobType":"UPLOAD","uploadId":"41","clipId":"5","streamId":"s1",
                 "channelOwnerUserId":"9","source":{"bucket":"b","s3Key":"clips/5/t/o1.mp4","outputId":"o1"},
                 "video":{"title":"펜타킬 순간","description":"설명","privacyStatus":"private"},"requestedAt":"2026-09-26T00:00:00Z"}""";
    }
}
