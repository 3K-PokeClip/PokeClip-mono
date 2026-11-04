package com.pokeclip.render.media;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 조각 하나의 패킷 판독(정수 틱 · PDT 설계 r7 7.2). 새 꼴 주문의 자르기 · 이음 · 길이 대조가 이 값만 쓴다. 시각은 영상이면 영상
 * 시간 단위 틱, 소리면 그 트랙 시간 단위 틱, 이름에 {@code Us} 가 붙으면 µs 정수다. 같은 파일 안 트랙들은 녹화기의 공통 기준 위에
 * 있어 파일 안 µs 하나로 서로 바꿀 수 있다(7.2 끝).
 *
 * <p>앞 일곱 칸이 설계 3.3 의 칸이고, 그 뒤는 같은 판독에서 함께 거두는 값이다.
 *
 * @param videoTimescale     영상 time_base 분모({@code ts_v}). 분자는 늘 1 이다(판독이 아니면 거절)
 * @param videoFirstDts      영상 첫 패킷 DTS({@code D0} · 틱)
 * @param fileHeadUs         모든 스트림 첫 DTS 의 최솟값({@code Hd} · 파일 머리 · µs 반올림)
 * @param videoPtsSorted     영상 PTS 를 표시 순으로 정렬한 것({@code P[]} · 틱)
 * @param videoLengthTicks   마지막 영상 DTS + 그 길이 − D0({@code V}). 마지막 길이가 0 이면 그 자리에 {@code frameIntervalTicks}
 * @param streamDurationUs   영상 스트림 {@code duration}({@code U}). 녹화기 조각은 파일 머리부터 잰 값이다
 * @param audio              소리 트랙. 순서 = 소리 스트림 순서(트랙 번호 N = N번째 소리 스트림)
 * @param formatStartUs      파일 {@code format.start_time}({@code st}). 소리 묶음 흐름 간격의 σ = D0 − st 에만 쓴다(7.4 1)
 * @param headLeadUs         {@code m} = D0 − Hd(µs · 정확값에서 한 번 반올림 · ≥ 0). {@code FILE_HEAD} 좌표를 영상 첫 DTS 기준으로
 *                           옮길 때 뺀다(7.3 1)
 * @param videoPacketTicks   영상 패킷 길이의 합(틱). V 와 다르면 그 차가 녹화기 tfdt 몫이다(설계 13.5 보정식 τ = V − 이 값)
 * @param frameIntervalTicks 영상 PTS 간격 중앙값(틱). {@code toSegmentEnd} 끝 = 마지막 PTS + 이 값(7.3 4)
 * @param width              표시 폭(회전 반영)
 * @param height             표시 높이(회전 반영)
 * @param color              영상 색 표지. 새 꼴 최종 단계가 첫 결과 조각 값을 결과 영상에 되살린다
 *                           ({@link RenderCommands#renderJoined} · 13.5 「색 표지」 · P34)
 */
public record SegmentTiming(long videoTimescale, long videoFirstDts, long fileHeadUs, long[] videoPtsSorted,
                            long videoLengthTicks, long streamDurationUs, List<TrackRange> audio, long formatStartUs,
                            long headLeadUs, long videoPacketTicks, long frameIntervalTicks, int width, int height,
                            ColorTags color) {

    /** 조각 길이 대조의 허용 차(ms · 7.2 · 옛 경로 {@code ClipRenderer.TOLERANCE_MS} 와 같은 값). */
    public static final long LENGTH_TOLERANCE_MS = 100;

    private static final BigInteger MICROS = BigInteger.valueOf(1_000_000);

    public SegmentTiming {
        videoPtsSorted = videoPtsSorted.clone();
        audio = List.copyOf(audio);
        Objects.requireNonNull(color, "color");
    }

    /**
     * 설계 3.3 의 일곱 칸으로 만든다. 나머지는 판독 정의에서 낸다 — {@code st} = Hd · {@code m} = D0 − Hd · 패킷 길이 합 = V ·
     * 간격 = PTS 간격 중앙값 · 화면 0 · 색 모름. 실제 조각은 {@link MediaProbe#timing} 이 모든 칸을 판독으로 채운다.
     */
    public SegmentTiming(long videoTimescale, long videoFirstDts, long fileHeadUs, long[] videoPtsSorted,
                         long videoLengthTicks, long streamDurationUs, List<TrackRange> audio) {
        this(videoTimescale, videoFirstDts, fileHeadUs, videoPtsSorted, videoLengthTicks, streamDurationUs, audio,
                fileHeadUs, ticksToMicros(videoFirstDts, videoTimescale) - fileHeadUs, videoLengthTicks,
                medianInterval(videoPtsSorted), 0, 0, ColorTags.UNKNOWN);
    }

    @Override
    public long[] videoPtsSorted() {
        return videoPtsSorted.clone();
    }

    public int frameCount() {
        return videoPtsSorted.length;
    }

    public long lastPts() {
        return videoPtsSorted[videoPtsSorted.length - 1];
    }

    /**
     * 장부 길이가 이 조각과 맞는가(7.2 · cx-4): {@code |ledgerMs − V| ≤ 100} 이면 {@link LengthMatch#VIDEO}, 아니고
     * {@code |ledgerMs − U| ≤ 100} 이면 {@link LengthMatch#STREAM}. 판 2 녹화 조각은 장부가 V 이고 U 는 머리 앞섬 m 만큼 길어
     * U 만으로는 거절된다. ms 로 반올림하지 않고 정수로 비교한다.
     */
    public LengthMatch lengthMatch(long ledgerMs) {
        long videoDiffScaled = Math.abs(Math.subtractExact(Math.multiplyExact(ledgerMs, videoTimescale),
                Math.multiplyExact(videoLengthTicks, 1_000)));
        if (videoDiffScaled <= Math.multiplyExact(LENGTH_TOLERANCE_MS, videoTimescale)) {
            return LengthMatch.VIDEO;
        }
        long streamDiffUs = Math.abs(Math.subtractExact(Math.multiplyExact(ledgerMs, 1_000), streamDurationUs));
        return streamDiffUs <= LENGTH_TOLERANCE_MS * 1_000 ? LengthMatch.STREAM : LengthMatch.NONE;
    }

    /** {@code num / den} 을 반 올림한 정수(바닥(x + 1/2) · 음수도 같은 식). 큰 틱 값의 곱이 넘치지 않게 BigInteger 로 센다. */
    static long roundHalfUp(BigInteger num, BigInteger den) {
        BigInteger twice = num.shiftLeft(1).add(den);
        BigInteger[] qr = twice.divideAndRemainder(den.shiftLeft(1));
        BigInteger q = qr[0];
        if (qr[1].signum() < 0) {
            q = q.subtract(BigInteger.ONE);
        }
        return q.longValueExact();
    }

    static long ticksToMicros(long ticks, long timescale) {
        return roundHalfUp(BigInteger.valueOf(ticks).multiply(MICROS), BigInteger.valueOf(timescale));
    }

    /** 정렬된 PTS 의 이웃 간격 중앙값(짝수 개면 위쪽 가운데 · 실험 원형과 같음). 프레임이 하나뿐이면 0. */
    static long medianInterval(long[] sortedPts) {
        if (sortedPts.length < 2) {
            return 0;
        }
        long[] gaps = new long[sortedPts.length - 1];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = sortedPts[i + 1] - sortedPts[i];
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    /** 길이 대조 결과. 로그 {@code render.source_length matched=} 값이 {@link #label()} 이다. */
    public enum LengthMatch {
        VIDEO, STREAM, NONE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * ffprobe 색 칸 넷({@code color_range} · {@code color_space} · {@code color_transfer} · {@code color_primaries}).
     * ffprobe 가 칸을 내지 않으면(미지정) null.
     */
    public record ColorTags(String range, String space, String transfer, String primaries) {
        public static final ColorTags UNKNOWN = new ColorTags(null, null, null, null);
    }

    /**
     * 소리 트랙 하나(7.2 「소리 트랙 N」).
     *
     * @param index       소리 스트림 순번(0 부터 · 필터 그래프 {@code [r:a:N]} 의 N)
     * @param timescale   time_base 분모(1/R 이 평시)
     * @param firstPts    첫 패킷 PTS(틱). 패킷이 없으면 0
     * @param endPts      마지막 패킷 PTS + 그 길이(틱). 패킷이 없으면 0
     * @param packets     패킷 수. 0 이면 그 조각에 이 트랙 소리가 없다(7.4 6 · P28)
     * @param streamIndex 파일 안 스트림 번호. 모르면 −1
     * @param codec       코덱 이름. 모르면 null
     * @param sampleRate  표본율
     * @param channels    채널 수. 모르면 0
     * @param configHash  코덱 설정 바이트(extradata)의 SHA256. 모르면 null
     */
    public record TrackRange(int index, long timescale, long firstPts, long endPts, long packets, int streamIndex,
                             String codec, int sampleRate, int channels, String configHash) {

        /** 설계 3.3 의 다섯 칸. 표본율은 시간 단위로 두고 설정은 모름으로 둔다. */
        public TrackRange(int index, long timescale, long firstPts, long endPts, long packets) {
            this(index, timescale, firstPts, endPts, packets, -1, null, Math.toIntExact(timescale), 0, null);
        }

        /**
         * 준비 조각의 소리 설정이 머리와 같은가(7.4 7 — 코덱 · 표본율 · 채널 수 · 코덱 설정 바이트). concat 스트림 복사는 첫 파일
         * 설정으로 흐름 전체를 디코드하므로 하나라도 다르면 쓰지 않는다. 모르는 칸이 있으면 같다고 보지 않는다.
         */
        public boolean sameConfig(TrackRange other) {
            if (codec == null || configHash == null || channels <= 0) {
                return false;
            }
            return codec.equals(other.codec) && sampleRate == other.sampleRate && channels == other.channels
                    && configHash.equals(other.configHash);
        }
    }
}
