package com.pokeclip.upload.work;

import java.time.Duration;

/** 재시도 대기. 시험은 바로 넘긴다. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration);

    static Sleeper real() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("대기 중 중단됐다", e);
            }
        };
    }
}
