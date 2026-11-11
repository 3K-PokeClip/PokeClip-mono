package com.pokeclip.render.media;

import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.media.SegmentTiming.LengthMatch;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 이 기계의 실제 ffprobe 출력으로 조각 판독(PDT 설계 r7 7.2)을 본다. 손으로 짠 JSON 시험({@link MediaProbeTimingTest})이 기대한
 * 꼴을 ffprobe 가 실제로 내는지가 대상이다 — 운영 8.0.1 과 이 기계 판(홈브루 등)이 달라도 같은 칸 꼴이어야 한다.
 *
 * <p>자료: 로컬 MediaMTX 녹화 조각(src/test/resources/segments) · 그 영상 트랙 tfdt 만 민 사본(판 2 녹화의 머리 앞섬 꼴) · ffmpeg 로
 * 바로 쓴 조각(B 프레임 · 색 표지).
 */
class MediaProbeTimingFfprobeTest {

    /** 녹화 조각의 영상 트랙 번호(moov tkhd 1 = vide). */
    private static final int VIDEO_TRACK_ID = 1;

    @TempDir
    Path dir;

    private MediaProbe probe;

    @BeforeEach
    void setUp() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        probe = new MediaProbe(new ProcessRunner(), Fixtures.MAPPER, "ffprobe");
    }

    @Test
    void 녹화_조각의_판독_값() throws IOException {
        Path seg = copyResource("seg_1.m4s");

        SegmentTiming t = probe.timing(seg, dir, deadline());

        // 영상 첫 DTS 4800틱(53.333ms) · 120프레임 3000틱 간격 · 소리 6트랙은 파일 머리 0 에서 187패킷
        assertThat(t.videoTimescale()).isEqualTo(90_000);
        assertThat(t.videoFirstDts()).isEqualTo(4_800);
        assertThat(t.frameCount()).isEqualTo(120);
        assertThat(t.videoLengthTicks()).isEqualTo(360_000);
        assertThat(t.videoPacketTicks()).isEqualTo(360_000);
        assertThat(t.frameIntervalTicks()).isEqualTo(3_000);
        assertThat(t.streamDurationUs()).isEqualTo(4_053_333);
        assertThat(t.formatStartUs()).isZero();
        assertThat(t.fileHeadUs()).isZero();
        assertThat(t.headLeadUs()).isEqualTo(53_333);
        assertThat(t.width()).isEqualTo(640);
        assertThat(t.height()).isEqualTo(360);
        assertThat(t.audio()).hasSize(6);
        TrackRange first = t.audio().getFirst();
        assertThat(first.index()).isZero();
        assertThat(first.streamIndex()).isEqualTo(1);
        assertThat(first.timescale()).isEqualTo(48_000);
        assertThat(first.firstPts()).isZero();
        assertThat(first.endPts()).isEqualTo(190_464 + 1_024);
        assertThat(first.packets()).isEqualTo(187);
        assertThat(first.codec()).isEqualTo("aac");
        assertThat(first.sampleRate()).isEqualTo(48_000);
        assertThat(first.configHash()).startsWith("SHA256:");
        assertThat(t.audio()).allSatisfy(track -> assertThat(track.sameConfig(first)).isTrue());
        assertThat(t.lengthMatch(4_053)).isEqualTo(LengthMatch.VIDEO);
    }

    @Test
    void 영상이_머리보다_200ms_늦은_조각은_V_로만_길이가_맞는다() throws IOException {
        // seg_0 은 장부 3,999ms · V 359,999틱. 영상 tfdt 를 18,000틱(200ms) 밀면 U 는 파일 머리부터라 약 4,200ms 가 되고
        // V 는 그대로다 — 판 2 녹화(ra) 꼴. U 로만 대조하면 SOURCE_MISMATCH 로 거절된다(P12)
        Path seg = dir.resolve("late.m4s");
        Files.write(seg, shiftTfdt(resource("seg_0.m4s"), VIDEO_TRACK_ID, 18_000));

        SegmentTiming t = probe.timing(seg, dir, deadline());

        assertThat(t.videoFirstDts()).isEqualTo(18_000);
        assertThat(t.videoLengthTicks()).isEqualTo(359_999);
        assertThat(t.streamDurationUs()).isEqualTo(4_199_989);
        assertThat(t.headLeadUs()).isEqualTo(200_000);
        assertThat(t.lengthMatch(3_999)).isEqualTo(LengthMatch.VIDEO);
        assertThat(MediaProbe.requireLength("job-1", 0, 3_999, t)).isEqualTo(LengthMatch.VIDEO);
    }

    @Test
    void 바로_쓴_조각의_B_프레임_순서와_화소_꼴_색_표지() throws IOException {
        Path seg = dir.resolve("direct.m4s");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000", "-f", "lavfi", "-i", "anoisesrc=color=pink:sample_rate=48000",
                "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "4",
                "-vf", "setparams=range=tv:colorspace=bt709:color_trc=bt709:color_primaries=bt709",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-bf", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac:a:1", "2", "-video_track_timescale", "90000",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", seg.toString());

        SegmentTiming t = probe.timing(seg, dir, deadline());

        assertThat(t.frameCount()).isEqualTo(120);
        long[] pts = t.videoPtsSorted();
        long[] sorted = pts.clone();
        Arrays.sort(sorted);
        assertThat(pts).containsExactly(sorted);
        // B 프레임이 있으면 첫 표시 프레임이 첫 DTS 보다 늦다
        assertThat(pts[0]).isGreaterThan(t.videoFirstDts());
        assertThat(t.videoLengthTicks()).isEqualTo(120 * 3_000);
        assertThat(t.videoPacketTicks()).isEqualTo(120 * 3_000);
        assertThat(t.pixelFormat()).isEqualTo("yuv420p");
        assertThat(t.color()).isEqualTo(new ColorTags("tv", "bt709", "bt709", "bt709"));
        assertThat(t.audio()).extracting(TrackRange::channels).containsExactly(1, 2);
        assertThat(t.audio().get(0).sameConfig(t.audio().get(1))).isFalse();
    }

    @Test
    void 색_표지가_없는_녹화_조각은_넷_다_비운다() throws IOException {
        SegmentTiming t = probe.timing(copyResource("seg_0.m4s"), dir, deadline());

        assertThat(t.color()).isEqualTo(ColorTags.UNKNOWN);
    }

    // ---------------------------------------------------------------- 자료

    private static Instant deadline() {
        return Instant.now().plus(Duration.ofSeconds(30));
    }

    private Path copyResource(String name) throws IOException {
        Path target = dir.resolve(name);
        Files.write(target, resource(name));
        return target;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MediaProbeTimingFfprobeTest.class.getResourceAsStream("/segments/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private void ffmpeg(String... args) {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y"));
        command.addAll(List.of(args));
        new ProcessRunner().run(command, dir, deadline());
    }

    /** fMP4 의 한 트랙 tfdt(조각 안 트랙 첫 디코드 시각)를 {@code delta} 틱 민 사본. moof → traf → tfhd · tfdt 만 본다. */
    static byte[] shiftTfdt(byte[] file, int trackId, long delta) {
        byte[] out = file.clone();
        ByteBuffer buf = ByteBuffer.wrap(out);
        int shifted = 0;
        for (int moof : children(buf, 0, out.length, "moof")) {
            for (int traf : children(buf, moof + 8, moof + buf.getInt(moof), "traf")) {
                int end = traf + buf.getInt(traf);
                int tfhd = children(buf, traf + 8, end, "tfhd").getFirst();
                if (buf.getInt(tfhd + 12) != trackId) {
                    continue;
                }
                int tfdt = children(buf, traf + 8, end, "tfdt").getFirst();
                if (buf.get(tfdt + 8) == 1) {
                    buf.putLong(tfdt + 12, buf.getLong(tfdt + 12) + delta);
                } else {
                    buf.putInt(tfdt + 12, Math.toIntExact(buf.getInt(tfdt + 12) + delta));
                }
                shifted++;
            }
        }
        assertThat(shifted).as("tfdt 를 민 조각 수").isPositive();
        return out;
    }

    /** {@code [from, to)} 안의 상자 가운데 이름이 {@code type} 인 것의 시작 자리. 32비트 크기 상자만 다룬다(녹화 조각 꼴). */
    private static List<Integer> children(ByteBuffer buf, int from, int to, String type) {
        List<Integer> found = new ArrayList<>();
        for (int at = from; at < to; at += buf.getInt(at)) {
            byte[] name = new byte[4];
            buf.get(at + 4, name);
            if (type.equals(new String(name, StandardCharsets.US_ASCII))) {
                found.add(at);
            }
        }
        return found;
    }
}
