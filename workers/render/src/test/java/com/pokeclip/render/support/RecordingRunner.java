package com.pokeclip.render.support;

import com.pokeclip.render.media.ProcessRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 실제로 돌리면서 명령과 작업 폴더를 적어 두는 {@link ProcessRunner}. {@link #intercept} 로 한 명령을 가로채 실패를 주입한다 —
 * 가로챈 쪽이 {@link #proceed} 로 실제 실행을 이어 갈 수 있다.
 */
public class RecordingRunner extends ProcessRunner {

    /** 명령 하나를 가로챈다. null 을 돌려주면 실제로 돌린다. */
    @FunctionalInterface
    public interface Interceptor {
        Result intercept(List<String> command, Path workDir, Instant deadline, RecordingRunner runner);
    }

    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<Path> workDirs = new CopyOnWriteArrayList<>();
    private volatile Interceptor interceptor;

    public void intercept(Interceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Result run(List<String> command, Path workDir, Instant deadline) {
        commands.add(List.copyOf(command));
        if (!workDirs.contains(workDir)) {
            workDirs.add(workDir);
        }
        Interceptor i = interceptor;
        if (i != null) {
            Result r = i.intercept(command, workDir, deadline, this);
            if (r != null) {
                return r;
            }
        }
        return proceed(command, workDir, deadline);
    }

    /** 가로채지 않고 실제로 돌린다. */
    public Result proceed(List<String> command, Path workDir, Instant deadline) {
        return super.run(command, workDir, deadline);
    }

    public List<List<String>> commands() {
        return new ArrayList<>(commands);
    }

    /** 이 실행기가 쓴 작업 폴더(처음 쓴 차례). */
    public List<Path> workDirs() {
        return new ArrayList<>(workDirs);
    }

    /** 영상 조각 단계 명령인가(FFV1 · NUT 중간 파일). */
    public static boolean isCutPiece(List<String> command) {
        return command.contains("ffv1") && command.contains("nut");
    }

    /** 최종 단계 명령인가(출력 영상 시간 단위를 90000 으로 두는 출력). */
    public static boolean isFinalStage(List<String> command) {
        return command.contains("-movie_timescale");
    }
}
