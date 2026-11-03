package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 장면의 DB 왕복 수(cc rev8 부수 6 · 계획 착수 체크리스트 C1 ⑤ · C5). 목록 한 장은 카드가 몇 장이든 회차 고르기 한 번 +
 * 회차 행 한 번이다.
 *
 * <p>관례 측정기(Hibernate {@code Statistics})는 {@code JdbcTemplate} 문장을 세지 않는다. 그래서 연결이 문장을 만들 때마다
 * 세는 {@code DataSource} 감싸개로 잰다 — {@code prepareStatement} · {@code createStatement} · {@code prepareCall} 이 왕복
 * 하나씩이다.
 */
class CardPositionRoundTripTest extends IntegrationTestSupport {

    private static final String KEY = "TRIPKEY";
    private static final Set<String> STATEMENT_MAKERS = Set.of("prepareStatement", "createStatement", "prepareCall");

    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final AtomicInteger statements = new AtomicInteger();
    private final CardPositionReader reader;
    private final LedgerSeed seed;

    CardPositionRoundTripTest(BroadcastRepository broadcasts, JdbcTemplate jdbc, DataSource dataSource) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.reader = new CardPositionReader(new RoundRows(new JdbcTemplate(counting(dataSource, statements))));
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 심는다() {
        방송과_카드를_비운다(jdbc);
        seed.session("S-trip", KEY, "S-trip", null);
        seed.session("S-trip2", KEY, "S-trip", "S-trip");
        for (int i = 0; i < 30; i++) {
            seed.row(KEY, i + 1).pts(4_000L * i).wallAndPdt(4_000L * i).session(i < 15 ? "S-trip" : "S-trip2").insert();
        }
        statements.set(0);
    }

    @Test
    void 목록_한_장의_카드_값이_몇_개든_두_왕복이다() {
        Broadcast broadcast = 방송("S-trip");
        List<Long> values = List.of(1_000L, 0L, 21_000L, 30_000L, 10_000L, 50_000L, 70_000L, 30_000L, 90_000L);

        var scenes = reader.scenesOf(broadcast, values);

        assertThat(scenes).as("양성 대조 — 실제로 장면을 냈다").containsKeys(1_000L, 70_000L);
        assertThat(statements.get()).isEqualTo(2);
    }

    @Test
    void 머리_세션_행이_없는_옛_방송도_두_왕복이다() {
        seed.row("OLDTRIP", 1).pts(0).wall(1_000).insert();
        statements.set(0);

        var scenes = reader.scenesOf(broadcasts.save(Broadcast.startedNow("S-oldtrip", TestIds.STREAMER, "OLDTRIP",
                1L, at(0), null)), List.of(1_500L, 2_000L));

        assertThat(scenes).containsKeys(1_500L, 2_000L);
        assertThat(statements.get()).isEqualTo(2);
    }

    @Test
    void 값이_없으면_DB를_안_탄다() {
        reader.scenesOf(방송("S-trip"), List.of());

        assertThat(statements.get()).isZero();
    }

    private Broadcast 방송(String streamId) {
        Broadcast broadcast = broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, KEY, 1L, at(0), null));
        statements.set(0);
        return broadcast;
    }

    /** 연결이 문장을 만들 때마다 센다. */
    private static DataSource counting(DataSource real, AtomicInteger counter) {
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

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
