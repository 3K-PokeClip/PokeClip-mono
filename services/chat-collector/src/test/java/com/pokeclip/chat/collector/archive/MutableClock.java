package com.pokeclip.chat.collector.archive;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 손으로 돌리는 시계 — 단위 테스트가 분을 넘긴다. */
public final class MutableClock implements LongSupplier {

    private final AtomicLong now;

    public MutableClock(long start) {
        now = new AtomicLong(start);
    }

    @Override
    public long getAsLong() {
        return now.get();
    }

    public void advance(Duration d) {
        now.addAndGet(d.toMillis());
    }

    public void set(long millis) {
        now.set(millis);
    }
}
