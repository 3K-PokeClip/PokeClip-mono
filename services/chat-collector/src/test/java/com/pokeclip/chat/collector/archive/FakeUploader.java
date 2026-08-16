package com.pokeclip.chat.collector.archive;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 성공/실패/느림 스위치. 올린 객체를 순서대로 기억한다. */
public final class FakeUploader implements ArchiveUploader {

    public volatile boolean failing;
    public volatile Duration delay = Duration.ZERO;
    public final List<ArchiveObject> uploaded = new CopyOnWriteArrayList<>();
    public final AtomicInteger attempts = new AtomicInteger();

    @Override
    public void upload(ArchiveObject object) throws ArchiveUploadException {
        attempts.incrementAndGet();
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failing) {
            throw new ArchiveUploadException(new ConnectException("fake"));
        }
        uploaded.add(object);
    }
}
