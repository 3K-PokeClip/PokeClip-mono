package com.pokeclip.render.support;

import com.pokeclip.render.media.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 녹화기를 거치지 않고 ffmpeg 로 바로 쓴 합성 조각(생성 경로 「바로 씀」 · PDT 설계 r7 13.5 판정선 「적용 범위」). 녹화 하나를 연속
 * fMP4 로 한 번 인코딩하고 moof 자리에서 바이트로 나눠 4초 조각을 만든다 — 조각마다 머리(ftyp + moov) + moof + mdat 이고, tfdt 는
 * 녹화 안 절대 시각 그대로라 이웃 조각이 같은 녹화로 이어진다(J). 소리는 한 인코더 흐름을 나눈 것이라 연속 디코드 기준이 곧 연속
 * fMP4 의 소리다.
 *
 * <p>영상은 90000 틱 · 30fps · B 프레임 없음 · 4초마다 키프레임. {@code offGridFrom} 번째 프레임부터 모든 프레임을 +90틱(1.000ms)
 * 밀어 1/30 초 격자에서 어긋나게 한다(설계 16.2 P32 픽스처 — 시험 안에서 만든다). 소리는 트랙마다 다른 분홍 잡음(겹치지 않는
 * 무늬라 자리 찾기가 하나로 정해진다) · 48kHz 스테레오 AAC. 소리 입력을 1024표본 늦춰 넣어 인코더 앞 프레임이 음수 시각에 놓이지
 * 않게 한다(음수면 fMP4 쓰기가 첫 표본 길이를 늘려 자리를 바꾼다).
 */
public final class DirectSegments {

    public static final long TIMESCALE = 90_000;
    public static final long FRAME_TICKS = 3_000;
    public static final int SEGMENT_SECONDS = 4;
    /** 1/30 초 격자에서 어긋나게 미는 틱(1.000ms). */
    public static final long OFF_GRID_TICKS = 90;

    /** 생성 경로(설계 13.5 「가르는 기준」). 오라클의 보정 여부를 이 칸으로 가른다. */
    public enum Origin { DIRECT, RECORDER, RECORDER_COPY }

    /**
     * @param continuous 연속 fMP4(소리 연속 디코드 기준)
     * @param pieces     4초 조각들(차례대로)
     */
    public record Recording(String name, Origin origin, Path continuous, List<Path> pieces) {
    }

    private DirectSegments() {
    }

    /**
     * @param videoSource lavfi 영상 원천(크기 · 30fps 는 여기서 준다)
     * @param offGridFrom 이 번째 프레임부터 +90틱. 음수면 밀지 않는다
     * @param seed        소리 트랙 잡음 씨앗의 첫 값(트랙마다 +1)
     */
    public static Recording write(Path dir, String name, String videoSource, int seconds, int offGridFrom,
                                  int seed, int tracks) {
        Path continuous = dir.resolve(name + "_all.mp4");
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", videoSource));
        for (int t = 0; t < tracks; t++) {
            cmd.addAll(List.of("-f", "lavfi", "-i", "anoisesrc=color=pink:seed=" + (seed + t) + ":sample_rate=48000"));
        }
        cmd.addAll(List.of("-t", String.valueOf(seconds), "-map", "0:v"));
        for (int t = 0; t < tracks; t++) {
            cmd.addAll(List.of("-map", (t + 1) + ":a"));
        }
        String shift = offGridFrom < 0 ? "" : "+if(gte(N\\," + offGridFrom + ")\\," + OFF_GRID_TICKS + "\\,0)";
        cmd.addAll(List.of("-vf", "settb=1/" + TIMESCALE + ",setpts=N*" + FRAME_TICKS + shift,
                "-fps_mode", "passthrough", "-enc_time_base:v", "1/" + TIMESCALE,
                "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "120", "-keyint_min", "120",
                "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-af", "asetpts=PTS+1024", "-c:a", "aac", "-ac", "2", "-b:a", "96k", "-threads", "1",
                "-video_track_timescale", String.valueOf(TIMESCALE),
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", continuous.toString()));
        new ProcessRunner().run(cmd, dir, Instant.now().plus(Duration.ofMinutes(2)));
        List<Path> pieces = split(continuous, dir, name);
        if (pieces.size() != seconds / SEGMENT_SECONDS) {
            throw new IllegalStateException(name + " 조각 수 " + pieces.size());
        }
        return new Recording(name, Origin.DIRECT, continuous, pieces);
    }

    /** 연속 fMP4 를 moof 자리에서 나눈다. 조각 i = ftyp + moov + moof_i + mdat_i. */
    static List<Path> split(Path continuous, Path dir, String name) {
        byte[] data = read(continuous);
        ByteBuffer buf = ByteBuffer.wrap(data);
        List<byte[]> head = new ArrayList<>();
        List<List<byte[]>> fragments = new ArrayList<>();
        for (int at = 0; at < data.length; at += buf.getInt(at)) {
            String type = type(buf, at);
            byte[] box = java.util.Arrays.copyOfRange(data, at, at + buf.getInt(at));
            if (type.equals("ftyp") || type.equals("moov")) {
                head.add(box);
            } else if (type.equals("moof")) {
                fragments.add(new ArrayList<>(List.of(box)));
            } else if (type.equals("mdat") && !fragments.isEmpty()) {
                fragments.getLast().add(box);
            }
        }
        List<Path> pieces = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            Path piece = dir.resolve(name + "_" + i + ".m4s");
            try (var out = Files.newOutputStream(piece)) {
                for (byte[] box : head) {
                    out.write(box);
                }
                for (byte[] box : fragments.get(i)) {
                    out.write(box);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            pieces.add(piece);
        }
        return pieces;
    }

    /** mp4 의 {@code moov/mvhd} timescale(동영상 전체 시간 단위). */
    public static long movieTimescale(Path mp4) {
        byte[] data = read(mp4);
        ByteBuffer buf = ByteBuffer.wrap(data);
        for (int at = 0; at < data.length; at += buf.getInt(at)) {
            if (type(buf, at).equals("moov")) {
                for (int in = at + 8; in < at + buf.getInt(at); in += buf.getInt(in)) {
                    if (type(buf, in).equals("mvhd")) {
                        boolean wide = buf.get(in + 8) == 1;
                        return Integer.toUnsignedLong(buf.getInt(in + 12 + (wide ? 16 : 8)));
                    }
                }
            }
        }
        throw new IllegalStateException("mvhd 가 없다: " + mp4);
    }

    /** 32비트 부동 소수 리틀 엔디언 원시 PCM(채널 섞임 그대로). */
    public static float[] pcm(Path raw) {
        byte[] data = read(raw);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[data.length / 4];
        buf.asFloatBuffer().get(out);
        return out;
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
}
