package com.pokeclip.render.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 이 기계의 ffmpeg가 무엇을 할 수 있는지. 홈브루 기본 빌드는 libass가 없어 번인 자막을 못 한다. */
public final class Ffmpeg {

    private Ffmpeg() {
    }

    public static boolean available() {
        return run("ffmpeg", "-hide_banner", "-version") != null;
    }

    public static boolean canBurnSubtitles() {
        String filters = run("ffmpeg", "-hide_banner", "-filters");
        return filters != null && filters.contains(" subtitles ");
    }

    /** ffprobe 출력 전문. */
    public static String probe(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "ffprobe";
        System.arraycopy(args, 0, command, 1, args.length);
        return run(command);
    }

    private static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
