package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.thumbnail.ThumbnailUrls;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 장면의 DB 왕복 수(계획 착수 체크리스트 C1 ⑤ · C5). 목록 한 장은 카드가 몇 장이든 회차 고르기 한 번 +
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
    private final JumpCardService service;
    private final EntityManagerFactory emf;

    CardPositionRoundTripTest(BroadcastRepository broadcasts, JdbcTemplate jdbc, DataSource dataSource,
                              JumpCardRepository cards, ObjectMapper mapper, CardStreamRegistry registry,
                              BroadcastAccessGuard guard, TransactionTemplate transactions, ThumbnailUrls thumbnails,
                              ApplicationEventPublisher events, EntityManagerFactory emf) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.reader = new CardPositionReader(new RoundRows(new JdbcTemplate(counting(dataSource, statements))));
        this.seed = new LedgerSeed(jdbc);
        this.service = new JumpCardService(cards, broadcasts, mapper, registry, guard, transactions, thumbnails, events,
                reader);
        this.emf = emf;
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

    /**
     * 서비스 경로(목록 문) — 장 하나의 문장 수가 카드 한 장일 때와 다섯 장일 때 같다. 장면 몫은 장부 두 왕복(회차 고르기 + 회차
     * 행)이고, 그 앞에 방송 행 하나(JPA)가 붙는다. 장부 문장은 위 감싸개로, JPA 문장은 Hibernate 통계로 센다.
     */
    @Test
    void 목록_문의_문장_수는_카드_수와_무관하다() {
        방송("S-trip");
        AUTH.respondWith("/internal/editor-delegations/resolve", 200, "{\"relation\":\"OWNER\"}");
        카드(10_000);
        long[] one = 목록_문장();
        for (int i = 1; i < 5; i++) {
            카드(10_000 + 20_000L * i);
        }
        long[] five = 목록_문장();

        assertThat(one[0]).as("장부 두 왕복").isEqualTo(2);
        assertThat(five[0]).as("장부 — 카드 다섯 장").isEqualTo(one[0]);
        assertThat(one[1]).as("측정기가 JPA 문장을 세고 있다").isPositive();
        assertThat(five[1]).as("JPA — 카드 다섯 장").isEqualTo(one[1]);
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

    private void 카드(long position) {
        service.record("S-trip", new HighlightRequest(UUID.randomUUID().toString(), "auto", position,
                new HighlightRequest.Window(position, position + 5_000), 90, null));
    }

    /** 목록 한 장의 {장부 문장, JPA 문장}. 통계는 이 안에서만 켠다 — 세션 팩토리가 컨텍스트 공유물이다. */
    private long[] 목록_문장() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            statements.set(0);
            assertThat(service.listOf(TestIds.STREAMER, "S-trip", false, null, null).cards())
                    .allSatisfy(card -> assertThat(card.playerAtMs()).as("양성 대조 — 장면을 냈다").isNotNull());
            return new long[] {statements.get(), stats.getPrepareStatementCount()};
        } finally {
            stats.setStatisticsEnabled(false);
        }
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
