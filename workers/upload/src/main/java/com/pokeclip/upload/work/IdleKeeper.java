package com.pokeclip.upload.work;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.CountDownLatch;

/**
 * 볼 줄이 하나도 없을 때 프로세스를 붙들어 둔다(POK-332). 이 일꾼은 웹 문이 없어 줄 스레드가 없으면 기동하자마자 종료 코드 0으로
 * 끝난다. dev compose는 {@code restart: unless-stopped}라 그러면 끝없이 다시 띄우고, 배포 확인(「running이 아니면 실패」)이
 * 배포를 막는다. 그래서 줄 주소가 다 비면 경고 한 줄을 남기고 일 없이 떠 있는다. 끌 때(SIGTERM · 컨텍스트 닫기)는 놓아준다.
 *
 * <p>로컬에서 줄 없이 띄우면 이제 끝나지 않는다(Ctrl+C로 끈다).
 */
public class IdleKeeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IdleKeeper.class);

    private final String event;
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Thread holder;

    /** @param event 경고 줄. 무엇이 비었는지(변수 이름)를 싣는다. 값은 안 싣는다 */
    public IdleKeeper(String event) {
        this.event = event;
    }

    @Override
    public void start() {
        log.warn(event);
        // 데몬이 아닌 스레드 하나가 JVM을 붙든다(main이 끝나도 안 내린다)
        holder = Thread.ofPlatform().name("upload-idle").daemon(false).start(() -> {
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void stop() {
        released.countDown();
    }

    @Override
    public boolean isRunning() {
        return holder != null && released.getCount() > 0;
    }

    /** 붙드는 스레드. 시작 전이면 null(기동 시험이 데몬 여부 · 놓아줌을 본다) */
    public Thread holder() {
        return holder;
    }
}
