package com.pokeclip.render.media;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.media.SegmentTiming.LengthMatch;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조각 판독(PDT 설계 r7 7.2) — ffprobe JSON 꼴을 손으로 짜서 읽는다. 기대값은 7.2 표의 정의에서 손으로 셈한 값이다.
 *
 * <p>기본 자료는 판 2 녹화 조각 꼴이다: 소리는 파일 머리 0 에서, 영상은 D0 = 19,919틱(221.322ms) 뒤에서 시작한다. 그래서 영상
 * 길이 V(4,000ms)와 영상 스트림 길이 U(파일 머리부터 · 4,221ms)가 100ms 넘게 다르다(P12).
 */
class MediaProbeTimingTest {

    private static final long D0 = 19_919;

    @Test
    void 영상_시간_단위와_첫_DTS_와_표시_순_PTS_를_읽는다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.videoTimescale()).isEqualTo(90_000);
        assertThat(t.videoFirstDts()).isEqualTo(D0);
        // 파일 순서는 D0, D0+6000, D0+3000, D0+9000 … (둘째 · 셋째가 B 프레임 꼴로 뒤바뀜) — 표시 순으로 정렬한다
        assertThat(t.videoPtsSorted()).hasSize(120);
        assertThat(t.videoPtsSorted()[0]).isEqualTo(D0);
        assertThat(t.videoPtsSorted()[1]).isEqualTo(D0 + 3_000);
        assertThat(t.videoPtsSorted()[2]).isEqualTo(D0 + 6_000);
        assertThat(t.videoPtsSorted()[119]).isEqualTo(D0 + 119 * 3_000);
    }

    @Test
    void 영상_길이_V_는_마지막_DTS_더하기_그_길이_빼기_D0_이다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        // 마지막 DTS = D0 + 119·3000, 그 길이 3000 → V = 120·3000
        assertThat(t.videoLengthTicks()).isEqualTo(360_000);
        assertThat(t.videoPacketTicks()).isEqualTo(360_000);
        assertThat(t.frameIntervalTicks()).isEqualTo(3_000);
    }

    @Test
    void 마지막_패킷_길이가_0_이면_PTS_간격_중앙값을_쓴다() {
        ObjectNode root = standard();
        ArrayNode packets = (ArrayNode) root.get("packets");
        ObjectNode last = lastVideoPacket(packets);
        last.put("duration", 0);

        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.videoLengthTicks()).isEqualTo(360_000);
        // 패킷 길이 합은 적힌 값 그대로다(보정식 τ = V − 패킷 길이 합의 입력)
        assertThat(t.videoPacketTicks()).isEqualTo(357_000);
    }

    @Test
    void 마지막_패킷_길이가_간격과_다르면_적힌_길이를_쓴다() {
        ObjectNode root = standard();
        lastVideoPacket((ArrayNode) root.get("packets")).put("duration", 2_910);

        SegmentTiming t = MediaProbe.parseTiming(root);

        // 마지막 DTS = D0 + 119·3000, 그 길이 2910 → V = 119·3000 + 2910(간격 3000 이 아니다)
        assertThat(t.videoLengthTicks()).isEqualTo(359_910);
        assertThat(t.frameIntervalTicks()).isEqualTo(3_000);
    }

    @Test
    void 패킷_길이_합은_V_와_따로_센다() {
        // 녹화기 파트 tfdt 내림으로 마지막 DTS 가 1틱 일찍 적힌 조각 꼴 — V = 359,999 · 패킷 길이 합 = 360,000 · τ = −1
        ObjectNode root = standard();
        ObjectNode last = lastVideoPacket((ArrayNode) root.get("packets"));
        last.put("dts", last.get("dts").asLong() - 1);
        last.put("pts", last.get("pts").asLong() - 1);

        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.videoLengthTicks()).isEqualTo(359_999);
        assertThat(t.videoPacketTicks()).isEqualTo(360_000);
    }

    @Test
    void 영상_스트림_길이_U_와_파일_시작_st_를_µs_로_읽는다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.streamDurationUs()).isEqualTo(4_221_322);
        assertThat(t.formatStartUs()).isEqualTo(0);
    }

    @Test
    void 파일_머리_Hd_는_모든_스트림_첫_DTS_의_최솟값이고_m_은_D0_빼기_Hd_다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.fileHeadUs()).isEqualTo(0);
        // 19919 / 90000 s = 221,322.2µs → 221,322
        assertThat(t.headLeadUs()).isEqualTo(221_322);
    }

    @Test
    void 소리가_늦게_시작하면_Hd_는_그_소리_첫_DTS_다() {
        ObjectNode root = standard();
        ArrayNode packets = (ArrayNode) root.get("packets");
        // 첫 소리 트랙 패킷을 지워 늦게 시작하는 둘째 트랙(3072표본 = 64ms)과 영상만 남긴다
        removeStream(packets, 1);

        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.fileHeadUs()).isEqualTo(64_000);
        assertThat(t.headLeadUs()).isEqualTo(221_322 - 64_000);
    }

    @Test
    void m_은_반올림한_두_값의_차가_아니라_정확값에서_한_번_반올림한다() {
        // Hd = 3/48000 s = 62.5µs, D0 = 9/90000 s = 100µs → m = 37.5 → 38(반 올림).
        // 반올림한 값끼리 빼면 100 − 63 = 37 이 되어 1µs 어긋난다
        ObjectNode root = root("0.000000");
        video(root, 0, "1/90000", "0.000100", "0.133433");
        audio(root, 1, "1/48000", 48_000, 1, "SHA256:aa");
        for (int k = 0; k < 4; k++) {
            packet(root, 0, 9 + 3_000L * k, 9 + 3_000L * k, 3_000);
        }
        packet(root, 1, 3, 3, 1_024);

        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.fileHeadUs()).isEqualTo(63);
        assertThat(t.headLeadUs()).isEqualTo(38);
    }

    @Test
    void 소리_트랙은_소리_스트림_순번으로_시간_단위_첫_끝_PTS_패킷_수_설정을_싣는다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.audio()).containsExactly(
                new TrackRange(0, 48_000, 0, 188 * 1_024, 188, 1, "aac", 48_000, 1, "SHA256:aa"),
                new TrackRange(1, 48_000, 3_072, 3_072 + 185 * 1_024, 185, 2, "aac", 48_000, 1, "SHA256:aa"),
                new TrackRange(2, 48_000, 0, 0, 0, 3, "aac", 48_000, 2, "SHA256:bb"));
    }

    @Test
    void 화면_크기는_회전을_반영한다() {
        ObjectNode root = standard();
        ObjectNode v = (ObjectNode) root.get("streams").get(0);
        v.putArray("side_data_list").addObject().put("rotation", -90);

        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.width()).isEqualTo(360);
        assertThat(t.height()).isEqualTo(640);
    }

    @Test
    void 색_표지_넷을_읽고_없는_칸은_비운다() {
        ObjectNode root = standard();
        ObjectNode v = (ObjectNode) root.get("streams").get(0);
        v.put("color_range", "tv").put("color_space", "bt709").put("color_transfer", "bt709");

        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.color()).isEqualTo(new ColorTags("tv", "bt709", "bt709", null));
    }

    @Test
    void 영상_스트림이_없으면_SOURCE_MISMATCH() {
        ObjectNode root = root("0.000000");
        audio(root, 0, "1/48000", 48_000, 1, "SHA256:aa");
        packet(root, 0, 0, 0, 1_024);

        assertMismatch(root);
    }

    @Test
    void 영상_패킷이_없으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        removeStream((ArrayNode) root.get("packets"), 0);

        assertMismatch(root);
    }

    @Test
    void 시간_단위_분자가_1_이_아니면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).put("time_base", "1001/30000");

        assertMismatch(root);
    }

    @Test
    void 소리_시간_단위가_읽히지_않으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(1)).put("time_base", "0/0");

        assertMismatch(root);
    }

    @Test
    void 패킷_시각이_없으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("packets").get(5)).remove("pts");

        assertMismatch(root);
    }

    @Test
    void 영상_스트림_길이가_없으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).remove("duration");

        assertMismatch(root);
    }

    @Test
    void 프레임_하나에_길이_0_이면_간격을_정할_수_없어_SOURCE_MISMATCH() {
        ObjectNode root = root("0.000000");
        video(root, 0, "1/90000", "0.000000", "0.000000");
        packet(root, 0, 0, 0, 0);

        assertMismatch(root);
    }

    // ── 범위 밖 값(sec M1 · L3) — 방송자가 정할 수 있는 값이라 잘리거나 일시 실패로 새지 않고 종결이다 ──

    @Test
    void 패킷_시각이_long_범위를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("packets").get(5)).put("pts", BigInteger.ONE.shiftLeft(64));

        assertMismatch(root);
    }

    @Test
    void 스트림_번호가_int_범위를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(1)).put("index", 1L << 32);

        assertMismatch(root);
    }

    @Test
    void 패킷_스트림_번호가_int_범위를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("packets").get(5)).put("stream_index", 1L << 32);

        assertMismatch(root);
    }

    @Test
    void 영상_길이_V_가_long_을_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        lastVideoPacket((ArrayNode) root.get("packets")).put("dts", Long.MAX_VALUE - 1_000);

        assertMismatch(root);
    }

    @Test
    void 패킷_길이_합이_long_을_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("packets").get(0)).put("duration", Long.MAX_VALUE);

        assertMismatch(root);
    }

    @Test
    void 소리_끝_PTS_가_long_을_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ArrayNode packets = (ArrayNode) root.get("packets");
        ObjectNode lastAudio = null;
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i).get("stream_index").asInt() == 1) {
                lastAudio = (ObjectNode) packets.get(i);
            }
        }
        lastAudio.put("pts", Long.MAX_VALUE - 10);

        assertMismatch(root);
    }

    /** 시간 단위 1/1 · D0 = 10¹³틱 → 파일 머리 µs = 10¹⁹ 이 long 을 넘는다(판독 안 큰 정수 계산의 끝 변환). */
    @Test
    void 파일_머리_µs_가_long_을_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = root("0.000000");
        video(root, 0, "1/1", "0.000000", "2.000000");
        packet(root, 0, 10_000_000_000_000L, 10_000_000_000_000L, 1);
        packet(root, 0, 10_000_000_000_001L, 10_000_000_000_001L, 1);

        assertMismatch(root);
    }

    /** 시간 단위 1/1 · V = 10¹⁶ + 1틱 → 길이 대조의 V × 1000 이 long 을 넘는다. 다시 받아도 같은 조각이라 종결이다. */
    @Test
    void 길이_대조가_long_을_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = root("0.000000");
        video(root, 0, "1/1", "0.000000", "2.000000");
        packet(root, 0, 0, 0, 1);
        packet(root, 0, 10_000_000_000_000_000L, 10_000_000_000_000_000L, 1);
        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThatThrownBy(() -> MediaProbe.requireLength("job", 1, 4_000, t))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    void 화면_크기가_int_범위를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).put("width", 1L << 32);

        assertMismatch(root);
    }

    @Test
    void 소리_채널_수가_int_범위를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(1)).put("channels", 1L << 32);

        assertMismatch(root);
    }

    @Test
    void 회전값이_int_범위를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).putArray("side_data_list").addObject().put("rotation", 1L << 32);

        assertMismatch(root);
    }

    /** ffprobe 는 초를 소수 꼴({@code %f})로만 찍는다. 지수 표기는 판독 꼴이 아니다(거대 지수는 큰 수 계산으로 번진다). */
    @Test
    void 초_문자열이_지수_표기면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).put("duration", "4.221322E0");

        assertMismatch(root);
    }

    @Test
    void 초_문자열의_정수부가_12자리를_넘으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).put("duration", "1234567890123.000000");

        assertMismatch(root);
    }

    @Test
    void 음수_초_문자열은_µs_로_읽는다() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("format")).put("start_time", "-0.021000");

        assertThat(MediaProbe.parseTiming(root).formatStartUs()).isEqualTo(-21_000);
    }

    @Test
    void 판독_명령은_설계_7_2_칸에_색_표지_코덱_설정을_더한_것이다() {
        List<List<String>> commands = new ArrayList<>();
        MediaProbe probe = new MediaProbe(new Recording(commands, standard().toString()), Fixtures.MAPPER, "ffprobe");

        probe.timing(Path.of("seg.m4s"), Path.of("."), Instant.MAX);

        assertThat(commands).containsExactly(List.of("ffprobe", "-v", "error", "-show_data_hash", "SHA256",
                "-show_entries", "format=start_time:stream=index,codec_type,codec_name,time_base,start_time,duration,"
                        + "width,height,sample_rate,channels,extradata_hash,color_range,color_space,color_transfer,"
                        + "color_primaries:stream_side_data=rotation:packet=stream_index,pts,dts,duration",
                "-of", "json", "seg.m4s"));
    }

    @Test
    void 옛_꼴_판독_명령은_그대로다() {
        List<List<String>> commands = new ArrayList<>();
        String legacy = "{\"format\":{\"start_time\":\"0.000000\"},\"streams\":[]}";
        MediaProbe probe = new MediaProbe(new Recording(commands, legacy), Fixtures.MAPPER, "ffprobe");

        probe.probe(Path.of("seg.m4s"), Path.of("."), Instant.MAX);

        // develop 의 명령 그대로(옛 꼴 주문 명령이 바이트 같음 · P9)
        assertThat(commands).containsExactly(List.of("ffprobe", "-v", "error", "-show_entries",
                "format=start_time:stream=codec_type,width,height,start_time,duration:stream_side_data=rotation",
                "-of", "json", "seg.m4s"));
    }

    @Test
    void 장부_길이가_V_와_100ms_안이면_U_가_어긋나도_통과한다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.lengthMatch(4_000)).isEqualTo(LengthMatch.VIDEO);
        assertThat(MediaProbe.requireLength("job-1", 7, 4_000, t)).isEqualTo(LengthMatch.VIDEO);
    }

    @Test
    void 장부_길이가_U_와만_100ms_안이면_U_로_통과한다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.lengthMatch(4_221)).isEqualTo(LengthMatch.STREAM);
    }

    @Test
    void 둘_다_100ms_를_넘으면_SOURCE_MISMATCH() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        assertThat(t.lengthMatch(4_400)).isEqualTo(LengthMatch.NONE);
        assertThatThrownBy(() -> MediaProbe.requireLength("job-1", 7, 4_400, t))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    void 길이_대조는_ms_로_반올림하지_않고_틱_정수로_가른다() {
        // V = 359,999틱 = 3,999.9889ms. 장부 3,899 는 차 100.9889 로 넘고 3,900 은 99.9889 로 든다. 4,100 은 차 100.0111 로
        // 넘는다 — V 를 ms 로 반올림(4,000)하면 4,100 이 차 100 으로 들어온다. U 는 9초로 멀리 둬 V 갈래만 본다
        ObjectNode root = standard();
        ObjectNode last = lastVideoPacket((ArrayNode) root.get("packets"));
        last.put("dts", last.get("dts").asLong() - 1);
        ((ObjectNode) root.get("streams").get(0)).put("duration", "9.000000");
        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThat(t.lengthMatch(3_900)).isEqualTo(LengthMatch.VIDEO);
        assertThat(t.lengthMatch(3_899)).isEqualTo(LengthMatch.NONE);
        assertThat(t.lengthMatch(4_099)).isEqualTo(LengthMatch.VIDEO);
        assertThat(t.lengthMatch(4_100)).isEqualTo(LengthMatch.NONE);
    }

    @Test
    void U_경계는_100ms_를_포함한다() {
        SegmentTiming t = MediaProbe.parseTiming(standard());

        // U = 4,221,322µs → 4,321 은 차 99.678 · 4,322 는 100.678
        assertThat(t.lengthMatch(4_321)).isEqualTo(LengthMatch.STREAM);
        assertThat(t.lengthMatch(4_322)).isEqualTo(LengthMatch.NONE);
    }

    @Test
    void 계획_안_조각끼리_영상_시간_단위가_다르면_SOURCE_MISMATCH() {
        SegmentTiming a = MediaProbe.parseTiming(standard());
        ObjectNode other = standard();
        ((ObjectNode) other.get("streams").get(0)).put("time_base", "1/15360");
        SegmentTiming b = MediaProbe.parseTiming(other);

        assertThatThrownBy(() -> MediaProbe.requireUniform(List.of(a, b)))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH));
    }

    @Test
    void 계획_안_조각끼리_화면_크기가_다르면_SOURCE_MISMATCH() {
        SegmentTiming a = MediaProbe.parseTiming(standard());
        ObjectNode other = standard();
        ((ObjectNode) other.get("streams").get(0)).put("width", 1280).put("height", 720);
        SegmentTiming b = MediaProbe.parseTiming(other);

        assertThatThrownBy(() -> MediaProbe.requireUniform(List.of(a, b)))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH));
    }

    @Test
    void 화면이_없으면_SOURCE_MISMATCH() {
        ObjectNode root = standard();
        ((ObjectNode) root.get("streams").get(0)).put("width", 0);
        SegmentTiming t = MediaProbe.parseTiming(root);

        assertThatThrownBy(() -> MediaProbe.requireUniform(List.of(t)))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH));
    }

    @Test
    void 같은_꼴의_조각들은_통과한다() {
        SegmentTiming a = MediaProbe.parseTiming(standard());
        SegmentTiming b = MediaProbe.parseTiming(standard());

        MediaProbe.requireUniform(List.of(a, b));
    }

    // ---------------------------------------------------------------- 자료

    /**
     * 판 2 녹화 조각 꼴. 영상 640x360 · 1/90000 · 120프레임(3000틱 간격 · 둘째와 셋째가 표시 순서와 뒤바뀜) · 첫 DTS 19,919.
     * 소리 셋: 트랙0 0 부터 188패킷 · 트랙1 3072 부터 185패킷 · 트랙2 패킷 0(스테레오 · 설정 다름).
     */
    private static ObjectNode standard() {
        ObjectNode root = root("0.000000");
        video(root, 0, "1/90000", "0.221322", "4.221322");
        audio(root, 1, "1/48000", 48_000, 1, "SHA256:aa");
        audio(root, 2, "1/48000", 48_000, 1, "SHA256:aa");
        audio(root, 3, "1/48000", 48_000, 2, "SHA256:bb");
        for (int k = 0; k < 120; k++) {
            long dts = D0 + 3_000L * k;
            long pts = k == 1 ? dts + 3_000 : k == 2 ? dts - 3_000 : dts;
            packet(root, 0, pts, dts, 3_000);
        }
        for (int k = 0; k < 188; k++) {
            packet(root, 1, 1_024L * k, 1_024L * k, 1_024);
        }
        for (int k = 0; k < 185; k++) {
            packet(root, 2, 3_072 + 1_024L * k, 3_072 + 1_024L * k, 1_024);
        }
        return root;
    }

    private static ObjectNode root(String startTime) {
        ObjectNode root = Fixtures.MAPPER.createObjectNode();
        root.putObject("format").put("start_time", startTime);
        root.putArray("streams");
        root.putArray("packets");
        return root;
    }

    private static void video(ObjectNode root, int index, String timeBase, String startTime, String duration) {
        ((ArrayNode) root.get("streams")).addObject().put("index", index).put("codec_name", "h264")
                .put("codec_type", "video").put("width", 640).put("height", 360).put("time_base", timeBase)
                .put("start_time", startTime).put("duration", duration).put("extradata_hash", "SHA256:vv");
    }

    private static void audio(ObjectNode root, int index, String timeBase, int sampleRate, int channels, String hash) {
        ((ArrayNode) root.get("streams")).addObject().put("index", index).put("codec_name", "aac")
                .put("codec_type", "audio").put("sample_rate", Integer.toString(sampleRate)).put("channels", channels)
                .put("time_base", timeBase).put("start_time", "0.000000").put("duration", "4.000000")
                .put("extradata_hash", hash);
    }

    private static void packet(ObjectNode root, int stream, long pts, long dts, long duration) {
        ((ArrayNode) root.get("packets")).addObject().put("stream_index", stream).put("pts", pts).put("dts", dts)
                .put("duration", duration);
    }

    private static ObjectNode lastVideoPacket(ArrayNode packets) {
        ObjectNode last = null;
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i).get("stream_index").asInt() == 0) {
                last = (ObjectNode) packets.get(i);
            }
        }
        return last;
    }

    private static void removeStream(ArrayNode packets, int stream) {
        for (int i = packets.size() - 1; i >= 0; i--) {
            if (packets.get(i).get("stream_index").asInt() == stream) {
                packets.remove(i);
            }
        }
    }

    private static void assertMismatch(ObjectNode root) {
        assertThatThrownBy(() -> MediaProbe.parseTiming(root))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
                    assertThat(f.retryable()).isFalse();
                });
    }

    /** 명령을 적어 두고 정해 둔 표준출력을 돌려준다. */
    private static final class Recording extends ProcessRunner {
        private final List<List<String>> commands;
        private final String stdout;

        Recording(List<List<String>> commands, String stdout) {
            this.commands = commands;
            this.stdout = stdout;
        }

        @Override
        public Result run(List<String> command, Path workDir, Instant deadline) {
            commands.add(List.copyOf(command));
            return new Result(stdout, "");
        }
    }
}
