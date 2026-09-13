package com.pokeclip.chat.collector.relay;

import java.time.Duration;

/**
 * 중계 닫기 손잡이. <b>둘로 갈린 이유는 {@code archive.ChatArchive}와 같다</b> — 종료가 저장·아카이브와
 * <b>나란히</b> 닫고 공유 기한 안에서 기다리려고(계획 검증 F1: 종료 예산에 새 항을 만들지 않는다).
 * 러너에 잇는 것은 태스크 17이다. 꺼져 있으면 {@link #NONE}.
 */
public interface RelayLifecycle {

    /** 새로 받지 않는다 + 중계 스레드를 깨운다. 기다리지 않는다. */
    void beginClose();

    /** 담긴 것을 마저 보내는 동안 최대 {@code budget} 기다린다. 넘기면 남은 것을 버린 수로 세고 돌아온다. */
    void awaitClosed(Duration budget);

    RelayLifecycle NONE = new RelayLifecycle() {
        @Override public void beginClose() { }
        @Override public void awaitClosed(Duration budget) { }
    };
}
