package com.pokeclip.render.support;

import com.pokeclip.render.media.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 좌우 채널에 서로 다른 무늬를 넣은 스테레오 트랙 녹화(PDT 설계 16.2 P23 — 모노 자료는 「한 채널만 밀림」 을 못 잡는다)와 그 조각을
 * 녹화기 사건처럼 손질하는 도구. 짜임은 {@link DirectSegments} 와 같다 — 연속 fMP4 를 한 번 인코딩하고 moof 자리에서 4초 조각으로
 * 나눈다(생성 경로 「바로 씀」). 트랙 t 의 왼쪽은 분홍 잡음 씨앗 {@code seed + 2t}, 오른쪽은 {@code seed + 2t + 1} 이다.
 *
 * <p>AAC 잡음 대체(PNS)는 끈다. 켠 채로는 자리 · 무음 단언은 통과했지만 패킷을 건너뛴 흐름(E9 · 한 조각만 패킷 0)의 그 뒤 PCM 만
 * 연속 디코드와 0.06–0.13 달랐고, 끄자 5/5 통과했다(R-c5 실측). 원인은 PNS 가 디코더 쪽에서 패킷 차례를 따라 이어지는 난수열로
 * 잡음을 다시 만들기 때문이라고 본다(추론 — 디코더 소스로 확인하지 않았다).
 *
 * <p>소리 패킷은 1024표본마다 0 부터 놓인다(입력을 1024표본 늦춰 인코더 앞 프레임이 0 에 온다). 그래서 48kHz 면 4초 조각의 첫
 * 소리는 영상 첫 프레임보다 512표본 늦고(4.0초 = 187.5 패킷), 8초 조각의 첫 소리는 영상 첫 프레임과 같은 자리다(375 패킷).
 * 44.1kHz 면 조각 경계가 패킷 경계에 맞지 않는다(4.0초 = 172.27 패킷).
 */
public final class StereoSegments {

    private StereoSegments() {
    }

    /** 48kHz 녹화. */
    public static DirectSegments.Recording write(Path dir, String name, int seconds, int seed, int tracks) {
        return write(dir, name, seconds, seed, tracks, 48_000);
    }

    /** @param sampleRate 소리 표본율(잡음 원천 · 인코딩 모두) */
    public static DirectSegments.Recording write(Path dir, String name, int seconds, int seed, int tracks,
                                                 int sampleRate) {
        Path continuous = dir.resolve(name + "_all.mp4");
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30"));
        for (int t = 0; t < tracks; t++) {
            int left = seed + 2 * t;
            cmd.addAll(List.of("-f", "lavfi", "-i", "anoisesrc=color=pink:seed=" + left + ":sample_rate=" + sampleRate
                    + "[l];anoisesrc=color=pink:seed=" + (left + 1) + ":sample_rate=" + sampleRate + "[r];"
                    + "[l][r]join=inputs=2:channel_layout=stereo[out0]"));
        }
        cmd.addAll(List.of("-t", String.valueOf(seconds), "-map", "0:v"));
        for (int t = 0; t < tracks; t++) {
            cmd.addAll(List.of("-map", (t + 1) + ":a"));
        }
        cmd.addAll(List.of("-vf", "settb=1/" + DirectSegments.TIMESCALE + ",setpts=N*" + DirectSegments.FRAME_TICKS,
                "-fps_mode", "passthrough", "-enc_time_base:v", "1/" + DirectSegments.TIMESCALE,
                "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "120", "-keyint_min", "120",
                "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-af", "asetpts=PTS+1024", "-c:a", "aac", "-aac_pns", "0", "-b:a", "128k", "-threads", "1",
                "-video_track_timescale", String.valueOf(DirectSegments.TIMESCALE),
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", continuous.toString()));
        new ProcessRunner().run(cmd, dir, Instant.now().plus(Duration.ofMinutes(2)));
        List<Path> pieces = DirectSegments.split(continuous, dir, name);
        if (pieces.size() != seconds / DirectSegments.SEGMENT_SECONDS) {
            throw new IllegalStateException(name + " 조각 수 " + pieces.size());
        }
        return new DirectSegments.Recording(name, DirectSegments.Origin.DIRECT, continuous, pieces);
    }

    /**
     * 조각 사본에서 소리 트랙 하나의 첫 {@code n} 표본(패킷)을 지운다 — 녹화기가 조각 머리 소리를 버린 사건(E9). trun 의 앞 항목
     * n 개를 지우고 tfdt 를 그 길이만큼, 그 트랙 data_offset 을 그 크기만큼 민다. trun · traf · moof 가 줄어든 만큼 이 moof 의 모든
     * data_offset 을 당긴다(default-base-is-moof). 지운 표본 바이트는 mdat 에 남되 아무도 가리키지 않는다.
     *
     * @param audioIndex 소리 트랙 차례(N번째 소리 스트림 · tfhd track_ID = N + 2)
     */
    public static Path dropHead(Path in, Path out, int audioIndex, int n) {
        byte[] data = read(in);
        ByteBuffer buf = ByteBuffer.wrap(data);
        int moof = find(buf, 0, data.length, "moof");
        int moofSize = buf.getInt(moof);
        int[] traf = traf(buf, moof, audioIndex + 2);
        int trun = find(buf, traf[0] + 8, traf[0] + traf[1], "trun");
        int tfdt = find(buf, traf[0] + 8, traf[0] + traf[1], "tfdt");
        int flags = buf.getInt(trun + 8) & 0xFFFFFF;
        if ((flags & 0x1) == 0 || (flags & 0x200) == 0 || (flags & 0x4) != 0) {
            throw new IllegalStateException("trun flags " + Integer.toHexString(flags));
        }
        boolean ownDuration = (flags & 0x100) != 0;
        long defaultDuration = ownDuration ? 0 : defaultDuration(buf, traf);
        int entry = 4 * Integer.bitCount(flags & 0xF00);
        int first = trun + 20;
        long duration = 0;
        int bytes = 0;
        for (int k = 0; k < n; k++) {
            int at = first + k * entry;
            duration += ownDuration ? Integer.toUnsignedLong(buf.getInt(at)) : defaultDuration;
            bytes += buf.getInt(ownDuration ? at + 4 : at);
        }
        int shrink = n * entry;
        if (buf.get(tfdt + 8) == 1) {
            buf.putLong(tfdt + 12, buf.getLong(tfdt + 12) + duration);
        } else {
            buf.putInt(tfdt + 12, (int) (Integer.toUnsignedLong(buf.getInt(tfdt + 12)) + duration));
        }
        buf.putInt(trun + 12, buf.getInt(trun + 12) - n);
        buf.putInt(trun + 16, buf.getInt(trun + 16) + bytes);
        for (int t = moof + 8; t < moof + moofSize; t += buf.getInt(t)) {
            if (type(buf, t).equals("traf")) {
                for (int c = t + 8; c < t + buf.getInt(t); c += buf.getInt(c)) {
                    if (type(buf, c).equals("trun")) {
                        buf.putInt(c + 16, buf.getInt(c + 16) - shrink);
                    }
                }
            }
        }
        buf.putInt(trun, buf.getInt(trun) - shrink);
        buf.putInt(traf[0], traf[1] - shrink);
        buf.putInt(moof, moofSize - shrink);
        byte[] edited = new byte[data.length - shrink];
        System.arraycopy(data, 0, edited, 0, first);
        System.arraycopy(data, first + shrink, edited, first, data.length - first - shrink);
        return write(out, edited);
    }

    /**
     * 조각 사본에서 소리 트랙 하나의 패킷을 모두 뺀다 — 그 트랙의 traf 상자 이름을 {@code free} 로 바꾼다(크기 · 다른 트랙 자리는
     * 그대로라 data_offset 을 고칠 것이 없다). 판독하면 그 트랙은 스트림은 있고 패킷이 0 이다.
     */
    public static Path dropTrack(Path in, Path out, int audioIndex) {
        byte[] data = read(in);
        ByteBuffer buf = ByteBuffer.wrap(data);
        int moof = find(buf, 0, data.length, "moof");
        int[] traf = traf(buf, moof, audioIndex + 2);
        buf.put(traf[0] + 4, "free".getBytes(StandardCharsets.US_ASCII));
        return write(out, data);
    }

    /** tfhd 의 기본 표본 길이(표본마다 길이를 trun 에 적지 않은 꼴). */
    private static long defaultDuration(ByteBuffer buf, int[] traf) {
        int tfhd = find(buf, traf[0] + 8, traf[0] + traf[1], "tfhd");
        int flags = buf.getInt(tfhd + 8) & 0xFFFFFF;
        if ((flags & 0x8) == 0) {
            throw new IllegalStateException("tfhd 에 기본 표본 길이가 없다: " + Integer.toHexString(flags));
        }
        int at = tfhd + 16 + ((flags & 0x1) != 0 ? 8 : 0) + ((flags & 0x2) != 0 ? 4 : 0);
        return Integer.toUnsignedLong(buf.getInt(at));
    }

    /** moof 안에서 tfhd track_ID 가 {@code trackId} 인 traf 의 {자리, 크기}. */
    private static int[] traf(ByteBuffer buf, int moof, int trackId) {
        for (int t = moof + 8; t < moof + buf.getInt(moof); t += buf.getInt(t)) {
            if (type(buf, t).equals("traf")) {
                int tfhd = find(buf, t + 8, t + buf.getInt(t), "tfhd");
                if (buf.getInt(tfhd + 12) == trackId) {
                    return new int[] {t, buf.getInt(t)};
                }
            }
        }
        throw new IllegalStateException("traf 없음: track " + trackId);
    }

    private static int find(ByteBuffer buf, int from, int to, String name) {
        for (int at = from; at < to; at += buf.getInt(at)) {
            if (type(buf, at).equals(name)) {
                return at;
            }
        }
        throw new IllegalStateException(name + " 없음");
    }

    private static String type(ByteBuffer buf, int at) {
        byte[] name = new byte[4];
        buf.get(at + 4, name);
        return new String(name, StandardCharsets.US_ASCII);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path write(Path file, byte[] data) {
        try {
            return Files.write(file, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
