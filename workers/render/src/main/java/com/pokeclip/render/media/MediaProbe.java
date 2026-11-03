package com.pokeclip.render.media;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.media.SegmentTiming.LengthMatch;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ffprobe 로 조각을 잰다. 옛 꼴 주문은 {@link #probe}(해상도 · 소리 스트림 수 · 영상 길이 · 소리 자리), 새 꼴 주문은
 * {@link #timing}(패킷 판독 · PDT 설계 r7 7.2)을 쓴다. 옛 판독은 develop 그대로 둔다(옛 꼴 명령이 바이트 같음 · P9).
 *
 * <p>ffprobe 자체가 죽거나 시한을 넘기면 {@link ProcessRunner.Failed} · {@link ProcessRunner.Timeout} 이 그대로 올라간다(다시 해 볼
 * 실패). 출력은 받았는데 판독 꼴이 어긋나면 그 조각은 다시 받아도 같으므로 {@code SOURCE_MISMATCH}(종결)다.
 */
public class MediaProbe {

    /** 7.2 판독 칸 + 준비 조각 설정 대조 칸(코덱 · 채널 · 설정 바이트 — 7.4 7) + 색 표지 넷(13.5 「색 표지」). */
    static final String TIMING_ENTRIES = "format=start_time:stream=index,codec_type,codec_name,time_base,start_time,"
            + "duration,width,height,sample_rate,channels,extradata_hash,color_range,color_space,color_transfer,"
            + "color_primaries:stream_side_data=rotation:packet=stream_index,pts,dts,duration";

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);
    private static final BigInteger MICROS = BigInteger.valueOf(1_000_000);
    /**
     * ffprobe 가 찍는 초 꼴({@code %f} — 부호 · 정수부 · 소수부). 지수 표기는 받지 않는다(거대 지수가 큰 수 계산으로 번진다 —
     * sec L3). 정수부 12자리면 µs 로 옮겨도 long 안이다.
     */
    private static final Pattern SECONDS = Pattern.compile("-?[0-9]{1,12}(\\.[0-9]{1,9})?");

    private final ProcessRunner runner;
    private final ObjectMapper mapper;
    private final String ffprobe;

    public MediaProbe(ProcessRunner runner, ObjectMapper mapper, String ffprobe) {
        this.runner = runner;
        this.mapper = mapper;
        this.ffprobe = ffprobe;
    }

    public MediaInfo probe(Path file, Path workDir, Instant deadline) {
        ProcessRunner.Result result = runner.run(List.of(ffprobe, "-v", "error",
                "-show_entries",
                "format=start_time:stream=codec_type,width,height,start_time,duration:stream_side_data=rotation",
                "-of", "json", file.toString()), workDir, deadline);
        return parse(mapper.readTree(result.stdout()));
    }

    static MediaInfo parse(JsonNode root) {
        int width = 0;
        int height = 0;
        long videoDurationMs = 0;
        int audio = 0;
        double audioStart = 0;
        double audioDuration = 0;
        boolean videoSeen = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asString("");
            if ("video".equals(type) && !videoSeen) {
                videoSeen = true;
                width = stream.path("width").asInt(0);
                height = stream.path("height").asInt(0);
                videoDurationMs = Math.round(seconds(stream, "duration") * 1000);
                int rotation = 0;
                for (JsonNode side : stream.path("side_data_list")) {
                    rotation = side.path("rotation").asInt(rotation);
                }
                if (Math.abs(rotation) % 180 == 90) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
            } else if ("audio".equals(type)) {
                if (audio == 0) {
                    audioStart = seconds(stream, "start_time");
                    audioDuration = seconds(stream, "duration");
                }
                audio++;
            }
        }
        double fileStart = seconds(root.path("format"), "start_time");
        return new MediaInfo(width, height, audio, videoDurationMs,
                Math.round((audioStart - fileStart) * 1_000_000), Math.round(audioDuration * 1_000_000));
    }

    /** 새 꼴 주문의 조각 판독(7.2). 꼴이 어긋나면 {@code SOURCE_MISMATCH}. */
    public SegmentTiming timing(Path file, Path workDir, Instant deadline) {
        ProcessRunner.Result result = runner.run(List.of(ffprobe, "-v", "error", "-show_data_hash", "SHA256",
                "-show_entries", TIMING_ENTRIES, "-of", "json", file.toString()), workDir, deadline);
        JsonNode root;
        try {
            root = mapper.readTree(result.stdout());
        } catch (JacksonException e) {
            throw mismatch("영상 조각을 읽지 못했다");
        }
        return parseTiming(root);
    }

    /**
     * 판독 JSON → {@link SegmentTiming}. 합 · 차는 {@code …Exact}, 큰 정수 계산 끝 변환은 {@code longValueExact} 라 넘침이
     * {@link ArithmeticException} 으로 오고, 그 조각은 다시 받아도 같으므로 여기서 {@code SOURCE_MISMATCH} 로 바꾼다(sec M1).
     */
    static SegmentTiming parseTiming(JsonNode root) {
        try {
            return readTiming(root);
        } catch (ArithmeticException e) {
            throw mismatch("영상 조각의 시각을 읽지 못했다");
        }
    }

    private static SegmentTiming readTiming(JsonNode root) {
        JsonNode video = firstVideo(root);
        long ts = timescale(video);
        Map<Integer, List<JsonNode>> packets = packetsByStream(root);
        List<JsonNode> videoPackets = packets.getOrDefault(streamIndex(video), List.of());
        if (videoPackets.isEmpty()) {
            throw mismatch("영상 조각에 화면이 없다");
        }
        long d0 = integer(videoPackets.getFirst(), "dts");
        long[] pts = new long[videoPackets.size()];
        long packetTicks = 0;
        for (int i = 0; i < pts.length; i++) {
            pts[i] = integer(videoPackets.get(i), "pts");
            packetTicks = Math.addExact(packetTicks, integer(videoPackets.get(i), "duration"));
        }
        Arrays.sort(pts);
        long interval = SegmentTiming.medianInterval(pts);
        JsonNode last = videoPackets.getLast();
        long lastDuration = integer(last, "duration");
        long tail = lastDuration > 0 ? lastDuration : interval;
        if (tail <= 0) {
            throw mismatch("영상 조각의 프레임 길이를 알 수 없다");
        }
        long videoLength = Math.subtractExact(Math.addExact(integer(last, "dts"), tail), d0);

        List<TrackRange> audio = new ArrayList<>();
        Head head = new Head(d0, ts);
        for (JsonNode stream : root.path("streams")) {
            if ("audio".equals(stream.path("codec_type").asString(""))) {
                List<JsonNode> own = packets.getOrDefault(streamIndex(stream), List.of());
                TrackRange track = track(audio.size(), stream, own);
                audio.add(track);
                if (!own.isEmpty()) {
                    head = head.earlier(integer(own.getFirst(), "dts"), track.timescale());
                }
            }
        }
        int[] size = displaySize(video);
        return new SegmentTiming(ts, d0, head.micros(), pts, videoLength, micros(video, "duration"), audio,
                micros(root.path("format"), "start_time"), head.leadMicros(d0, ts), packetTicks, interval, size[0],
                size[1], colorTags(video));
    }

    /**
     * 조각 길이 대조(7.2)를 남기고, 맞지 않으면 {@code SOURCE_MISMATCH} 로 끝낸다. 신호 {@code render.source_length}(15절 —
     * 어긋나면 WARN · 아니면 DEBUG). 준비 조각처럼 어긋나도 실패로 만들지 않는 자리는 {@link SegmentTiming#lengthMatch} 를 쓴다.
     */
    public static LengthMatch requireLength(String jobId, long seq, long ledgerMs, SegmentTiming timing) {
        LengthMatch match;
        long videoMs;
        long streamMs;
        try {
            match = timing.lengthMatch(ledgerMs);
            videoMs = SegmentTiming.roundHalfUp(BigInteger.valueOf(timing.videoLengthTicks()).multiply(
                    BigInteger.valueOf(1_000)), BigInteger.valueOf(timing.videoTimescale()));
            streamMs = SegmentTiming.roundHalfUp(BigInteger.valueOf(timing.streamDurationUs()),
                    BigInteger.valueOf(1_000));
        } catch (ArithmeticException e) {
            // 대조 정수식이 넘치는 판독 — 그 조각은 다시 받아도 같다(sec M1)
            throw mismatch("영상 조각 길이가 기록과 다르다");
        }
        if (match == LengthMatch.NONE) {
            log.warn("render.source_length jobId={} seq={} ledgerMs={} videoMs={} streamMs={} matched={}", jobId, seq,
                    ledgerMs, videoMs, streamMs, match.label());
            throw mismatch("영상 조각 길이가 기록과 다르다");
        }
        log.debug("render.source_length jobId={} seq={} ledgerMs={} videoMs={} streamMs={} matched={}", jobId, seq,
                ledgerMs, videoMs, streamMs, match.label());
        return match;
    }

    /**
     * 결과에 드는 조각들이 한 결과로 이어질 수 있는 꼴인가: 화면이 있고, 화면 크기와 영상 시간 단위가 모두 같다(7.2 · 7.3 끝).
     * 시간 단위가 같아야 영상 이동량을 틱 정수로 잇는다.
     */
    public static void requireUniform(List<SegmentTiming> timings) {
        if (timings.isEmpty()) {
            return;
        }
        SegmentTiming first = timings.getFirst();
        if (first.width() <= 0 || first.height() <= 0) {
            throw mismatch("영상 조각에 화면이 없다");
        }
        for (SegmentTiming t : timings) {
            if (t.videoTimescale() != first.videoTimescale()) {
                throw mismatch("방송 중에 영상 시간 단위가 바뀌었다");
            }
            if (t.width() != first.width() || t.height() != first.height()) {
                throw mismatch("방송 중에 화면 크기가 바뀌었다");
            }
        }
    }

    private static JsonNode firstVideo(JsonNode root) {
        for (JsonNode stream : root.path("streams")) {
            if ("video".equals(stream.path("codec_type").asString(""))) {
                return stream;
            }
        }
        throw mismatch("영상 조각에 화면이 없다");
    }

    private static int streamIndex(JsonNode stream) {
        return smallInteger(stream, "index");
    }

    private static Map<Integer, List<JsonNode>> packetsByStream(JsonNode root) {
        Map<Integer, List<JsonNode>> byStream = new HashMap<>();
        for (JsonNode packet : root.path("packets")) {
            byStream.computeIfAbsent(smallInteger(packet, "stream_index"), k -> new ArrayList<>()).add(packet);
        }
        return byStream;
    }

    private static TrackRange track(int ordinal, JsonNode stream, List<JsonNode> own) {
        long tsA = timescale(stream);
        long firstPts = 0;
        long endPts = 0;
        if (!own.isEmpty()) {
            JsonNode last = own.getLast();
            firstPts = integer(own.getFirst(), "pts");
            endPts = Math.addExact(integer(last, "pts"), integer(last, "duration"));
        }
        return new TrackRange(ordinal, tsA, firstPts, endPts, own.size(), streamIndex(stream),
                text(stream, "codec_name"), parseIntOrZero(text(stream, "sample_rate")),
                smallIntegerOrZero(stream, "channels"), text(stream, "extradata_hash"));
    }

    private static int[] displaySize(JsonNode video) {
        int width = smallIntegerOrZero(video, "width");
        int height = smallIntegerOrZero(video, "height");
        int rotation = 0;
        for (JsonNode side : video.path("side_data_list")) {
            if (side.has("rotation")) {
                rotation = smallInteger(side, "rotation");
            }
        }
        return Math.abs(rotation) % 180 == 90 ? new int[] {height, width} : new int[] {width, height};
    }

    private static ColorTags colorTags(JsonNode video) {
        return new ColorTags(text(video, "color_range"), text(video, "color_space"), text(video, "color_transfer"),
                text(video, "color_primaries"));
    }

    /** {@code time_base} 의 분모. 정수 틱 산식이 기대므로 분자 1 · 분모 양수만 받는다. */
    private static long timescale(JsonNode stream) {
        String timeBase = stream.path("time_base").asString("");
        int slash = timeBase.indexOf('/');
        if (slash < 0 || !"1".equals(timeBase.substring(0, slash))) {
            throw mismatch("영상 조각의 시간 단위를 읽지 못했다");
        }
        long den = parseIntOrZero(timeBase.substring(slash + 1));
        if (den <= 0) {
            throw mismatch("영상 조각의 시간 단위를 읽지 못했다");
        }
        return den;
    }

    /**
     * 정수 칸(long). {@code isIntegralNumber()} 만 보고 {@code asLong()} 을 부르면 범위 밖 값이 잘리거나 Jackson 예외로 새어
     * 일시 실패(재시도)가 된다 — 그래서 long 에 정확히 담기는지까지 본다. 확인은 {@code job.JsonIntegers} 와 같다(media 쪽 사본).
     */
    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw mismatch("영상 조각의 시각을 읽지 못했다");
        }
        return value.asLong();
    }

    /** 정수 칸(int — 스트림 번호 · 회전). 있어야 하고 int 에 정확히 담겨야 한다. */
    private static int smallInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw mismatch("영상 조각의 스트림 정보를 읽지 못했다");
        }
        return value.asInt();
    }

    /** 없으면 0 인 정수 칸(int — 화면 크기 · 채널 수). 있는데 int 에 담기지 않으면 판독 꼴이 어긋난 것이다. */
    private static int smallIntegerOrZero(JsonNode node, String field) {
        return node.has(field) ? smallInteger(node, field) : 0;
    }

    /** ffprobe 의 초 문자열(소수 6자리)을 µs 정수로. 실수를 거치지 않는다. 꼴은 {@link #SECONDS} 만 받는다. */
    private static long micros(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || !SECONDS.matcher(value).matches()) {
            throw mismatch("영상 조각의 길이를 읽지 못했다");
        }
        try {
            return new BigDecimal(value).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw mismatch("영상 조각의 길이를 읽지 못했다");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static int parseIntOrZero(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static RenderFailure mismatch(String message) {
        return RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, message);
    }

    /** 파일 머리 Hd — 지금까지 본 스트림 첫 DTS 의 최솟값을 정확한 분수 {@code ticks / timescale}(초)로 든다. */
    private record Head(long ticks, long timescale) {

        Head earlier(long otherTicks, long otherTimescale) {
            BigInteger mine = BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(otherTimescale));
            BigInteger theirs = BigInteger.valueOf(otherTicks).multiply(BigInteger.valueOf(timescale));
            return theirs.compareTo(mine) < 0 ? new Head(otherTicks, otherTimescale) : this;
        }

        long micros() {
            return SegmentTiming.roundHalfUp(BigInteger.valueOf(ticks).multiply(MICROS), BigInteger.valueOf(timescale));
        }

        /** m = D0 − Hd(µs) — 반올림한 두 값의 차가 아니라 정확값에서 한 번 반올림한다. */
        long leadMicros(long d0, long ts) {
            BigInteger num = BigInteger.valueOf(d0).multiply(BigInteger.valueOf(timescale))
                    .subtract(BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(ts))).multiply(MICROS);
            return SegmentTiming.roundHalfUp(num, BigInteger.valueOf(ts).multiply(BigInteger.valueOf(timescale)));
        }
    }

    private static double seconds(JsonNode node, String field) {
        String value = node.path(field).asString("0");
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
