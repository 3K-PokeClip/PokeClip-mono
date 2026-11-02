package com.pokeclip.chat.collector.broadcast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 보관 기간이 지난 끝난-방송 메모를 주기적으로 치운다.
 *
 * <p><b>{@code @Component}가 아니다.</b> {@code Duration}·{@code Supplier<Instant>}는 스프링이
 * 만들 수 있는 타입이 아니라 부팅이 죽는다(2026-08-19 실측:
 * {@code APPLICATION FAILED TO START — Parameter 1 of constructor ... required a bean of type
 * 'java.time.Duration'}). 조립은 {@code CollectorApplication}의 {@code @Bean}이 한다 —
 * 러너를 거기서 만드는 것과 같은 이유다. <b>생성자를 직접 부르는 검사는 이 실패를 못 본다.</b>
 */
public class EndedStreamSweeper {

    private static final Logger log = LoggerFactory.getLogger(EndedStreamSweeper.class);

    private final EndedStreamStore store;
    private final Duration retention;
    private final Supplier<Instant> clock;

    public EndedStreamSweeper(EndedStreamStore store, Duration retention, Supplier<Instant> clock) {
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * <b>Throwable까지 잡는다.</b> {@code @Scheduled}는 태스크가 한 번이라도 던지면 그 뒤 주기가
     * 안 돈다 — 메모가 영영 안 치워지는데 아무 신호도 없다. {@code ChatArchiver.safeTick}과 같은
     * 이유이자 같은 폭이다(그쪽은 {@code ChatPersister}의 틱 래퍼와 폭이 어긋나 있고, 여기서는
     * 넓은 쪽에 맞춘다).
     *
     * <p>{@code initialDelay}도 준다 — 기본값 0이면 컨텍스트가 뜨자마자 DELETE가 나가 부팅과 겹친다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.broadcast.ended-sweep-interval}",
            initialDelayString = "${pokeclip.broadcast.ended-sweep-interval}")
    public void sweep() {
        try {
            int swept = store.sweepOlderThan(clock.get().minus(retention));
            if (swept > 0) {
                log.info("chat.broadcast.ended_swept count={}", swept);
            }
        } catch (Throwable t) {
            log.warn("chat.broadcast.ended_sweep_failed causeType={}", t.getClass().getSimpleName());
        }
    }
}
