package com.pokeclip.auth.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** 루트 로거에 붙어 로그를 모은다. try-with-resources로 쓰고 반드시 뗀다. */
public final class LogCaptor implements AutoCloseable {

    private final ch.qos.logback.classic.Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    public LogCaptor() {
        root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.INFO);
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        root.addAppender(appender);
    }

    /**
     * appender에 락을 걸고 복사한다. ListAppender.list는 평범한 ArrayList이고
     * doAppend만 synchronized라, 통합 테스트에서 살아 있는 Hikari·Tomcat 스레드가
     * 로그를 찍는 순간 락 없이 읽으면 ConcurrentModificationException이 난다.
     * 간헐 실패는 재현이 안 돼 추적 비용이 크다.
     */
    public List<ILoggingEvent> events() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    /** 포맷이 끝난 한 줄들. */
    public List<String> messages() {
        return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** 이 이벤트 이름으로 찍힌 첫 줄의 레벨. 없으면 실패시키기 좋게 null을 준다. */
    public Level levelOf(String eventPrefix) {
        return events().stream()
                .filter(e -> e.getFormattedMessage().startsWith(eventPrefix))
                .map(ILoggingEvent::getLevel)
                .findFirst()
                .orElse(null);
    }

    /** 이 이벤트 이름으로 찍힌 첫 줄의 MDC 값. 메시지 본문에는 MDC가 안 들어 있다. */
    public String mdcOf(String eventPrefix, String key) {
        return events().stream()
                .filter(e -> e.getFormattedMessage().startsWith(eventPrefix))
                .map(e -> e.getMDCPropertyMap().get(key))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        // 레벨을 되돌린다. 안 되돌리면 나중에 application-test.yml로 테스트를
        // 조용하게 만들었을 때, LogCaptor를 한 번 쓴 시점부터 그 JVM의 남은
        // 테스트가 전부 INFO로 시끄러워진다.
        root.setLevel(previousLevel);
    }
}
