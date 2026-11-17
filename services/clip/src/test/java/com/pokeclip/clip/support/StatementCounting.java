package com.pokeclip.clip.support;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code JdbcTemplate} 문장 수를 세는 {@code DataSource} 감싸개(계획 착수 체크리스트 C5). 관례 측정기(Hibernate
 * {@code Statistics})는 {@code JdbcTemplate} 문장을 세지 않는다 — 연결이 문장을 만들 때마다({@code prepareStatement} ·
 * {@code createStatement} · {@code prepareCall}) 왕복 하나로 센다.
 */
public final class StatementCounting {

    private static final Set<String> STATEMENT_MAKERS = Set.of("prepareStatement", "createStatement", "prepareCall");

    /** 연결이 문장을 만들 때마다 {@code counter}를 하나 올린다. */
    public static DataSource counting(DataSource real, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (result instanceof Connection connection) {
                        return countingConnection(connection, counter);
                    }
                    return result;
                });
    }

    private static Connection countingConnection(Connection real, AtomicInteger counter) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (STATEMENT_MAKERS.contains(method.getName())) {
                        counter.incrementAndGet();
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private StatementCounting() {
    }
}
