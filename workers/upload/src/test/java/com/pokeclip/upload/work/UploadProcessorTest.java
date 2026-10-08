package com.pokeclip.upload.work;

import com.pokeclip.upload.auth.YoutubeTokenClient;
import com.pokeclip.upload.clip.ClipUploadApi;
import com.pokeclip.upload.job.EnvelopeParser;
import com.pokeclip.upload.media.ProcessRunner;
import com.pokeclip.upload.media.SceneExtractor;
import com.pokeclip.upload.storage.S3Download;
import com.pokeclip.upload.support.FakeInternal;
import com.pokeclip.upload.support.FakeYoutube;
import com.pokeclip.upload.youtube.ResumableUploader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 업로드 처리기(POK-220). <b>재는 것의 중심은 「가짜 유튜브가 만든 영상 수가 1」이다</b>: 응답이 사라져도, 쪽지가 두 번 와도,
 * 일꾼이 도중에 멈췄다 다시 와도, 다른 일꾼이 주소를 먼저 적어 두었어도. 그리고 「실패」는 영상이 없다는 것을 확인했을 때만 나간다.
 *
 * <p>가짜 유튜브는 이어 올리기 규칙대로 돈다(바이트를 다 받은 주소만 영상을 만든다). clip·auth도 가짜지만 clip 판정 규칙은 진짜와 같다.
 */
@ExtendWith(OutputCaptureExtension.class)
class UploadProcessorTest {

    private static final long CHUNK = 256 * 1024;
    private static final int SIZE = (int) (CHUNK * 2 + 1000);
    private static final String VIDEO_KEY = "clips/5/t/o1.mp4";
    private static final String THUMB_KEY = "upload-thumbnails/9/abc.png";
    /** 가짜 장면 뽑기가 쓰는 JPEG 바이트(앞 셋이 JPEG 표지). */
    private static final byte[] SCENE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] USER_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9};

    @TempDir
    Path dir;

    private FakeYoutube youtube;
    private FakeInternal internal;
    private byte[] video;
    private int downloads;
    /** 창고 가짜가 영상 말고 줄 것(키 → 바이트). 없는 키는 NoSuchKey. */
    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<String> downloadedKeys = new ArrayList<>();
    /** 가짜 장면 뽑기가 받은 자리(ms). */
    private final List<Long> sceneOffsets = new ArrayList<>();
    /** false면 가짜 장면 뽑기가 실패한다. */
    private boolean sceneWorks = true;
    /** null이 아니면 가짜 대신 이 장면 뽑기를 쓴다(진짜 ffmpeg 시험). */
    private SceneExtractor realExtractor;

    @BeforeEach
    void 준비() throws Exception {
        youtube = new FakeYoutube();
        internal = new FakeInternal();
        video = new byte[SIZE];
        new Random(7).nextBytes(video);
        objects.put(THUMB_KEY, USER_PNG);
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

    // ── 시작 본문과 주문서 새 칸(POK-291) ─────────────────────────

    /**
     * 옛 주문서(태그·아동용·썸네일 칸 없음)는 지금과 똑같이 흐른다: 썸네일을 안 붙이고(thumbnails.set 0회), 비공개·아동용 아님,
     * 태그 칸은 아예 안 싣는다. 썸네일 결과는 「NONE」으로 알린다.
     */
    @Test
    void 옛_주문서는_썸네일을_안_붙이고_지금과_같다() {
        Disposition d = processor().process(주문서());

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("NONE");
        assertThat(youtube.thumbnailCalls()).isZero();
        assertThat(sceneOffsets).isEmpty();
        FakeYoutube.Session s = youtube.session(internal.sessionUri);
        assertThat(s.privacy).isEqualTo("private");
        assertThat(s.madeForKids).isFalse();
        assertThat(s.tags).as("빈 태그는 칸을 안 싣는다").isNull();
        assertThat(s.description).isEqualTo("설명");
    }

    /** 고른 공개 범위·태그·아동용이 시작 본문에 그대로 실린다(ObjectMapper로 읽어 배열·불리언까지 잰다). */
    @Test
    void 시작_본문에_태그와_공개_범위와_아동용이_실린다() {
        processor().process(주문서("""
                "privacyStatus":"unlisted","tags":["롤","펜타 킬"],"madeForKids":true""", "null"));

        FakeYoutube.Session s = youtube.session(internal.sessionUri);
        assertThat(s.privacy).isEqualTo("unlisted");
        assertThat(s.tags).containsExactly("롤", "펜타 킬");
        assertThat(s.madeForKids).isTrue();
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
    }

    @Test
    void 공개를_고르면_공개로_싣는다() {
        processor().process(주문서("""
                "privacyStatus":"public","tags":[],"madeForKids":false""", "null"));

        FakeYoutube.Session s = youtube.session(internal.sessionUri);
        assertThat(s.privacy).isEqualTo("public");
        assertThat(s.madeForKids).isFalse();
        assertThat(s.tags).isNull();
    }

    /**
     * 🔴 새 칸 모양이 틀려도 쪽지를 지우지 않는다(지우면 clip 줄이 queued로 영원히 남는다). 너그럽게 읽어 기본값으로 올린다:
     * 태그 []·아동용 false·공개 범위 private·썸네일 없음.
     */
    @Test
    void 새_칸_모양이_틀려도_지우지_않고_기본값으로_올린다() {
        Disposition d = processor().process(주문서(
                "\"privacyStatus\":\"secret\",\"tags\":\"롤\",\"madeForKids\":\"yes\"",
                "{\"source\":\"scene\",\"offsetMs\":\"abc\"}"));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("NONE");
        assertThat(youtube.thumbnailCalls()).isZero();
        FakeYoutube.Session s = youtube.session(internal.sessionUri);
        assertThat(s.privacy).isEqualTo("private");
        assertThat(s.tags).isNull();
        assertThat(s.madeForKids).isFalse();
    }

    // ── 썸네일(POK-291) ────────────────────────────────────────

    /**
     * 장면 썸네일: 받은 mp4에서 고른 자리를 뽑아 thumbnails.set으로 붙이고, <b>붙인 뒤에</b> clip에 보고한다(보고 시점에 이미 붙어 있다).
     * 받은 그림 파일은 끝나면 지운다.
     */
    @Test
    void 장면_썸네일을_뽑아_붙인_뒤에_보고한다() throws Exception {
        internal.probeAtResult = () -> youtube.thumbnailsSet().size();

        Disposition d = processor().process(주문서("", 장면(12000)));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(sceneOffsets).containsExactly(12000L);
        assertThat(youtube.thumbnailsSet()).hasSize(1);
        FakeYoutube.Thumbnail t = youtube.thumbnailsSet().getFirst();
        assertThat(t.videoId()).isEqualTo("vid1");
        assertThat(t.contentType()).isEqualTo("image/jpeg");
        assertThat(t.bytes()).isEqualTo(SCENE_JPEG);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("SET");
        assertThat(internal.atResult).as("보고하는 순간 썸네일이 이미 붙어 있어야 한다").containsExactly(1);
        assertThat(youtube.videosCreated()).isEqualTo(1);
        try (Stream<Path> left = Files.list(dir)) {
            assertThat(left).as("임시 파일이 남았다").isEmpty();
        }
    }

    /** 이미지 썸네일: 창고에서 사용자 그림을 받아 그 형식(PNG)대로 붙인다. */
    @Test
    void 올린_이미지_썸네일은_창고에서_받아_붙인다() throws Exception {
        Disposition d = processor().process(주문서("", 파일_썸네일()));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(downloadedKeys).containsExactly(VIDEO_KEY, THUMB_KEY);
        FakeYoutube.Thumbnail t = youtube.thumbnailsSet().getFirst();
        assertThat(t.contentType()).isEqualTo("image/png");
        assertThat(t.bytes()).isEqualTo(USER_PNG);
        assertThat(internal.thumbnailResults).containsExactly("SET");
        try (Stream<Path> left = Files.list(dir)) {
            assertThat(left).isEmpty();
        }
    }

    /** 🔴 사용자 그림이 창고에 없다(탈퇴 정리 등). 영상은 올라갔으니 올림으로 보고하고 쪽지는 지운다: 쪽지를 남기면 실패 큐에서 확인 중이 된다. */
    @Test
    void 썸네일_그림이_창고에_없으면_영상은_올림이고_SOURCE_MISSING이다() {
        objects.remove(THUMB_KEY);

        Disposition d = processor().process(주문서("", 파일_썸네일()));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("FAILED:THUMBNAIL_SOURCE_MISSING");
        assertThat(youtube.thumbnailCalls()).isZero();
    }

    @Test
    void 장면을_못_뽑으면_영상은_올림이고_EXTRACT_FAILED다() {
        sceneWorks = false;

        Disposition d = processor().process(주문서("", 장면(0)));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("FAILED:THUMBNAIL_EXTRACT_FAILED");
    }

    /**
     * 썸네일 실패를 사유로 가른다. 🔴 <b>사유를 상태 코드보다 먼저 본다</b>: 403 하나에 쿼터와 권한 없음이 같이 온다. 잠깐 풀리는 것
     * (404·429·속도 제한·5xx)은 일꾼 안에서 짧게 다시 해 본다(모두 세 번). 🔴 어떤 경우도 쪽지를 남기지 않는다(DELAY가 아니다):
     * 영상은 이미 올라갔고, 쪽지가 실패 큐로 가면 정리기가 영상이 있는데도 확인 중으로 닫는다.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "403 forbidden                                         | FAILED:THUMBNAIL_FORBIDDEN       | 1",
            "403 quotaExceeded                                     | FAILED:THUMBNAIL_QUOTA_EXCEEDED  | 1",
            "400 dailyLimitExceeded                                | FAILED:THUMBNAIL_QUOTA_EXCEEDED  | 1",
            "400 invalidImage                                      | FAILED:THUMBNAIL_INVALID_IMAGE   | 1",
            "400 mediaBodyRequired                                 | FAILED:THUMBNAIL_INVALID_IMAGE   | 1",
            "404 videoNotFound;404 videoNotFound;404 videoNotFound | FAILED:THUMBNAIL_VIDEO_NOT_FOUND | 3",
            "429 uploadRateLimitExceeded;429 x;429 x               | FAILED:THUMBNAIL_RATE_LIMITED    | 3",
            "403 rateLimitExceeded;403 x;403 userRateLimitExceeded | FAILED:THUMBNAIL_FORBIDDEN       | 2",
            "403 rateLimitExceeded;403 rateLimitExceeded;403 rateLimitExceeded | FAILED:THUMBNAIL_RATE_LIMITED | 3",
            "503 backendError;500 x;502 x                          | FAILED:THUMBNAIL_UNAVAILABLE     | 3",
            "404 videoNotFound                                     | SET                              | 2",
            "503 backendError;429 x                                | SET                              | 3",
    })
    void 썸네일_실패를_사유로_가르고_쪽지는_남기지_않는다(String replies, String expected, int calls) {
        for (String reply : replies.split(";")) {
            youtube.thumbnailReplies.add(reply.strip());
        }

        Disposition d = processor().process(주문서("", 장면(500)));

        assertThat(d).as("썸네일 때문에 쪽지를 남기면 안 된다").isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly(expected.strip());
        assertThat(youtube.thumbnailCalls()).isEqualTo(calls);
        assertThat(youtube.videosCreated()).isEqualTo(1);
    }

    /** 긴 업로드 뒤 토큰이 끝났다(401). auth에 한 번 다시 물어 새 토큰으로 붙인다. 새 토큰도 로그에 안 남는다. */
    @Test
    void 썸네일이_401이면_토큰을_다시_받아_붙인다(CapturedOutput output) {
        youtube.thumbnailToken = "ya29.fresh-token";
        internal.accessTokenAfterFirst = "ya29.fresh-token";

        processor().process(주문서("", 장면(500)));

        assertThat(internal.thumbnailResults).containsExactly("SET");
        assertThat(internal.resolves).isEqualTo(2);
        assertThat(youtube.thumbnailCalls()).isEqualTo(2);
        assertThat(output.getAll()).contains("upload.done").contains("upload.thumbnail")
                .doesNotContain(FakeYoutube.GOOD_TOKEN).doesNotContain("ya29.fresh-token").doesNotContain("/session/");
    }

    @Test
    void 다시_받은_토큰도_401이면_UNAUTHORIZED다() {
        youtube.thumbnailToken = "ya29.never";

        Disposition d = processor().process(주문서("", 장면(500)));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(internal.thumbnailResults).containsExactly("FAILED:THUMBNAIL_UNAUTHORIZED");
        assertThat(internal.resolves).isEqualTo(2);
        assertThat(youtube.thumbnailCalls()).isEqualTo(2);
    }

    /**
     * 🔴 썸네일을 붙인 뒤 clip 보고 전에 멈췄다(clip이 답을 안 한다). 쪽지가 다시 오면 clip은 아직 올리는 중이라 주소를 주고, 주소에
     * 물으면 다 받았다고 하니 썸네일을 <b>다시 붙이고</b> 올림을 보고한다. 영상은 하나다. 보고를 먼저 했으면 다시 온 쪽지는
     * 「끝난 주문」을 받아 썸네일을 영영 못 붙인다.
     */
    @Test
    void 썸네일을_붙인_뒤_보고_전에_멈추면_다시_와서_또_붙이고_올림을_보고한다() {
        internal.resultDown = 2;
        internal.probeAtResult = () -> youtube.thumbnailsSet().size();
        String order = 주문서("", 장면(500));

        Disposition 첫째 = processor().process(order);
        assertThat(첫째.kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(internal.results).isEmpty();
        assertThat(youtube.thumbnailsSet()).as("보고 전에 이미 붙였다").hasSize(1);

        Disposition 둘째 = processor().process(order);

        assertThat(둘째).isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("SET");
        assertThat(internal.atResult).containsExactly(2);
        assertThat(youtube.videosCreated()).isEqualTo(1);
        assertThat(youtube.sessionsStarted()).isEqualTo(1);
    }

    /** 마지막 응답이 사라져 주소에 다시 물어 끝을 안 길(drive의 끝)에서도 썸네일이 붙는다. */
    @Test
    void 마지막_응답이_사라져도_썸네일을_붙인다() {
        youtube.dropFinalResponseOnce = true;

        processor().process(주문서("", 장면(500)));

        assertThat(youtube.videosCreated()).isEqualTo(1);
        assertThat(internal.thumbnailResults).containsExactly("SET");
        assertThat(youtube.thumbnailsSet()).hasSize(1);
    }

    /**
     * 끝을 아는 다른 자리(이어 갈 수 없을 때 결론 내기): 연동이 끊겨 토큰이 없는데 주소가 이미 다 받았다. 영상은 올림이다. 썸네일은
     * auth에 한 번 더 물어 그래도 없으면 {@code THUMBNAIL_NO_TOKEN}.
     */
    @Test
    void 토큰이_없고_주소가_이미_다_받았으면_올림에_NO_TOKEN이다() {
        internal.accessToken = null;
        internal.refusal = "BROKEN";
        internal.sessionUri = youtube.openSession(SIZE);
        youtube.completeSession(internal.sessionUri, video);

        Disposition d = processor().process(주문서("", 장면(500)));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("FAILED:THUMBNAIL_NO_TOKEN");
        assertThat(internal.resolves).isEqualTo(2);
        assertThat(youtube.thumbnailCalls()).isZero();
    }

    /** 같은 자리인데 다시 물으니 토큰이 생겼다: 그 토큰으로 붙인다. 이 자리를 빼먹으면 썸네일이 「NONE」이 된다. */
    @Test
    void 토큰이_없던_결론_자리에서도_다시_받은_토큰으로_썸네일을_붙인다() {
        internal.accessToken = null;
        internal.refusal = "BROKEN";
        internal.accessTokenAfterFirst = FakeYoutube.GOOD_TOKEN;
        internal.sessionUri = youtube.openSession(SIZE);
        youtube.completeSession(internal.sessionUri, video);

        processor().process(주문서("", 장면(500)));

        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(internal.thumbnailResults).containsExactly("SET");
        assertThat(youtube.thumbnailsSet()).hasSize(1);
    }

    /** 진짜 ffmpeg로 끝까지: 완성 영상 끝을 넘는 자리를 골라도 JPEG가 뽑혀 붙는다. ffmpeg가 없으면 건너뛴다. */
    @Test
    void 진짜_ffmpeg로_뽑은_장면이_붙는다() throws Exception {
        assumeTrue(ffmpeg가_있다(), "ffmpeg가 없다");
        Path mp4 = dir.resolve("src.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i",
                "testsrc=size=320x180:rate=30:duration=1", "-pix_fmt", "yuv420p", mp4.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("gen.log").toFile()).start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        video = Files.readAllBytes(mp4);
        Files.delete(mp4);
        Files.delete(dir.resolve("gen.log"));
        realExtractor = new SceneExtractor(new ProcessRunner(), "ffmpeg", "ffprobe", Duration.ofSeconds(30));

        processor().process(주문서("", 장면(999_999)));

        assertThat(internal.thumbnailResults).containsExactly("SET");
        byte[] jpg = youtube.thumbnailsSet().getFirst().bytes();
        assertThat(jpg).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        try (Stream<Path> left = Files.list(dir)) {
            assertThat(left).isEmpty();
        }
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
     * 보낸다: 자기 주소는 바이트를 안 받아 영상이 안 생긴다.
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
     * 🔴 이 일꾼이 시작을 청하는 사이 다른 일꾼이 주소를 적어 두었다. 이 일꾼은 한도에 걸렸지만 실패를 보내면 안 된다: 그 주소로 이어
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

    /** 조각 전송 중 403 속도 제한도 거절이 아니다. 주소에 다시 물어 받은 데부터 잇는다(PR #199 codex 3판). */
    @Test
    void 조각_전송의_속도_제한도_이어서_끝낸다() {
        youtube.rateLimitOnChunk = 1;

        processor().process(주문서());

        assertThat(internal.results).containsExactly("UPLOADED:vid1");
        assertThat(youtube.videosCreated()).isEqualTo(1);
    }

    /** 유튜브 속도 제한(403 rateLimitExceeded)은 시간이 지나면 풀린다. 거절로 닫지 않고 잠시 뒤 다시 한다(PR #199 codex 2판). */
    @Test
    void 속도_제한은_실패가_아니라_잠시_뒤_다시다() {
        youtube.rateLimitOnStart = true;

        assertThat(processor().process(주문서()).kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(internal.results).isEmpty();
    }

    /**
     * 🔴 연동이 끊겨 실패를 보내려는데, 그사이 겹친 일꾼이 주소를 적어 두었다. 실패를 보내면 clip이 확인 중으로 닫고 쪽지가 지워져 그 주소로
     * 이어 갈 길이 막힌다(PR #199 codex 2판). 실패를 보내지 않고 쪽지를 남긴다.
     */
    @Test
    void 연동이_끊겨도_그사이_적힌_주소가_있으면_실패를_안_보낸다() {
        internal.accessToken = null;
        internal.refusal = "BROKEN";
        internal.lateSession = youtube.openSession(SIZE);

        assertThat(processor().process(주문서()).kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(internal.results).isEmpty();
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
        // 주소가 없고 연동도 없으면 영상 바이트가 필요 없다. 창고에서 받지 않는다(PR #199 codex 3판).
        assertThat(downloads).isZero();
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
        return processor(new ResumableUploader(new ObjectMapper(), youtube.startUrl(), youtube.thumbnailUrl()));
    }

    private UploadProcessor processor(ResumableUploader uploader) {
        ObjectMapper mapper = new ObjectMapper();
        Sleeper noWait = d -> { };
        InternalHttp http = new InternalHttp(mapper, FakeInternal.TOKEN, List.of(Duration.ZERO), noWait);
        S3Download storage = new S3Download(null) {
            @Override
            public long download(String bucket, String key, Path target) {
                downloadedKeys.add(key);
                byte[] bytes;
                if (VIDEO_KEY.equals(key)) {
                    downloads++;
                    bytes = video;
                } else if (objects.containsKey(key)) {
                    bytes = objects.get(key);
                } else {
                    throw NoSuchKeyException.builder().message("없다").build();
                }
                try {
                    Files.write(target, bytes);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                return bytes.length;
            }
        };
        SceneExtractor extractor = realExtractor != null ? realExtractor
                : new SceneExtractor(null, "ffmpeg", "ffprobe", Duration.ofSeconds(30)) {
                    @Override
                    public boolean extract(Path source, long offsetMs, Path picture) {
                        sceneOffsets.add(offsetMs);
                        if (!sceneWorks) {
                            return false;
                        }
                        try {
                            Files.write(picture, SCENE_JPEG);
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                        return true;
                    }
                };
        YoutubeTokenClient auth = new YoutubeTokenClient(http, internal.baseUrl());
        ThumbnailStep thumbnails = new ThumbnailStep(extractor, storage, uploader, auth, dir,
                List.of(Duration.ZERO, Duration.ZERO), noWait);
        return new UploadProcessor(new EnvelopeParser(mapper), new ClipUploadApi(http, internal.baseUrl()), auth,
                storage, uploader, thumbnails, dir, CHUNK, List.of(Duration.ZERO, Duration.ZERO), noWait);
    }

    private static boolean ffmpeg가_있다() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 첫 조각만 받고 그 뒤 조각은 5xx를 끝없이 주는 유튜브(도중에 멈춘 일꾼). */
    private UploadProcessor processorFailingAfterFirstChunk() {
        return processor(new ResumableUploader(new ObjectMapper(), youtube.startUrl(), youtube.thumbnailUrl()) {
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

    /**
     * 새 칸이 든 주문서(POK-291 명세 §4).
     *
     * @param videoExtra video 안에 덧붙일 칸들(비면 비공개 기본)
     * @param thumbnail  thumbnail 칸의 JSON
     */
    private static String 주문서(String videoExtra, String thumbnail) {
        String extra = videoExtra.isBlank() ? "\"privacyStatus\":\"private\"" : videoExtra;
        return """
                {"schemaVersion":1,"jobType":"UPLOAD","uploadId":"41","clipId":"5","streamId":"s1",
                 "channelOwnerUserId":"9","source":{"bucket":"b","s3Key":"clips/5/t/o1.mp4","outputId":"o1"},
                 "video":{"title":"펜타킬 순간","description":"설명",%s},
                 "thumbnail":%s,"requestedAt":"2026-10-08T00:00:00Z"}""".formatted(extra, thumbnail);
    }

    private static String 장면(long offsetMs) {
        return "{\"source\":\"scene\",\"offsetMs\":" + offsetMs + "}";
    }

    private static String 파일_썸네일() {
        return "{\"source\":\"file\",\"bucket\":\"b\",\"s3Key\":\"" + THUMB_KEY + "\",\"contentType\":\"image/png\"}";
    }
}
