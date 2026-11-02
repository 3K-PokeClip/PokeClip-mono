package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code CHAT_SYNC_OFFSET_MS}가 실제로 {@link SyncProperties}에 닿는지를 행동으로 잰다.</b>
 *
 * <p>이 검사가 없으면 {@code application.yml}에서 {@code default-offset-ms:} 줄을 <b>통째로
 * 지워도 관련 검사가 전부 초록이다</b>(실측). 계산기 검사는 {@code new SyncProperties(…)}로
 * 직접 만들어 yml을 안 지나가고, {@code DocumentedEnvVarsTest}는 「문서에 있는 변수가 yml에
 * 있나」 쪽만 보며, {@code SyncProperties} 빈은 바인딩 대상이 없어도 만들어져
 * {@code defaultOffsetMs}가 <b>조용히 0</b>이 된다. 부팅도 통과한다.
 *
 * <p>이 서버가 같은 모양으로 두 번 데였다 — {@code RestClient.create()}가 자동설정된 빌더를
 * 우회해 시한이 안 걸린 것과, {@code spring.http.client.*}(단수형)가 아무것도 바인딩하지 않은 것.
 * <b>둘 다 설정 파일은 완벽했고 값만 안 걸렸으며 서버는 떴다.</b> 그래서 여기도
 * {@code DatasourceTimeoutTest}와 같은 자세로 잰다 — <b>값을 단언하지 말고 그 값으로만 나오는
 * 결과가 나오는지 본다.</b>
 *
 * <h2>왜 음수인가 — 피해야 할 값이 <b>둘</b>이다</h2>
 * <b>0</b>을 쓰면 「설정을 못 읽어 0이 된」 상태와 구분되지 않고, <b>yml 기본값(3900)</b>을
 * 쓰면 「플레이스홀더가 환경변수를 못 읽고 자기 기본값으로 떨어진」 상태와 구분되지 않는다.
 * 음수는 그 둘 어느 쪽도 아니다. 음수를 실제로 쓰는 것이 이 카드의 결정이기도 하다 —
 * 보정값의 부호가 환경에 따라 뒤집힌다({@link SyncProperties} javadoc).
 *
 * <p>이 컨텍스트는 {@code properties}가 달라 캐시가 갈린다 — 그것이 목적이다. 실물
 * {@code application.yml}의 그 줄이 <b>플레이스홀더를 통해</b> 이 값을 읽어야만 초록이다.
 *
 * <p><b>🔴 「yml 기본값이 0으로 되돌아갔다」를 여기서 재려 하지 마라.</b> 이 컨텍스트는
 * {@code CHAT_SYNC_OFFSET_MS}를 직접 주므로 <b>플레이스홀더의 기본값 자리를 아예 안 지나간다</b> —
 * 여기에 {@code defaultOffsetMs != 0}을 걸면 그것은 언제나 참인 장식이다. 그 그물은
 * 보정값을 안 덮어쓰는 컨텍스트에 있다
 * ({@code VideoPositionCalculatorTest.실물_기본_보정값이_자리_표시_0이_아니다()}).
 */
@SpringBootTest(properties = "CHAT_SYNC_OFFSET_MS=" + SyncOffsetBindingTest.OFFSET_MS)
@ActiveProfiles("test")
class SyncOffsetBindingTest extends IntegrationTestSupport {

    /** yml 기본값(3900)과도, 「안 걸려서 0」과도 갈리는 값. 부호가 방향까지 검증한다. */
    static final String OFFSET_MS = "-1500";

    private static final long OFFSET = Long.parseLong(OFFSET_MS);
    private static final Instant T0 = Instant.parse("2026-08-24T00:00:00Z");

    /** 이 검사만 쓰는 접두. 조각 장부는 Flyway 밖이라 컨텍스트가 갈려도 표가 하나다. */
    private static final String STREAM = "bind-offset";

    private final JdbcTemplate jdbc;

    /**
     * <b>빈으로 받는다.</b> 다른 sync 검사들처럼 {@code new VideoPositionCalculator(…)}로
     * 만들면 yml → {@code SyncProperties} 경로를 통째로 건너뛰어 이 검사가 아무것도 안 잰다.
     */
    private final VideoPositionCalculator calculator;

    SyncOffsetBindingTest(JdbcTemplate jdbc, VideoPositionCalculator calculator) {
        this.jdbc = jdbc;
        this.calculator = calculator;
    }

    @BeforeEach
    void 표를_세우고_내_방송_행만_비운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        StreamSegmentsFixture.clear(jdbc, STREAM);
        StreamSegmentsFixture.insert(jdbc, STREAM, 1, 0, T0, 4000, false);
    }

    /**
     * 보정이 안 걸리면(0) 위치가 1000이고, 걸리면 −1500만큼 <b>미래로 밀려</b> 2500이다.
     * 조각 하나(길이 4000)라 둘 다 같은 조각 안이고 판정은 어느 쪽이든 {@code CONVERTED}다 —
     * <b>갈리는 것은 위치뿐이라</b> 이 검사가 보는 것이 정확히 보정값 하나다.
     */
    @Test
    void 환경변수로_준_보정값이_실제_변환에_걸린다() {
        VideoPosition position = calculator.locate(STREAM, null, T0.plusMillis(1000));

        assertThat(position.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(position.appliedOffsetMs())
                .as("yml의 default-offset-ms가 CHAT_SYNC_OFFSET_MS를 못 읽으면 여기가 0이다")
                .isEqualTo(OFFSET);
        assertThat(position.positionMs())
                .as("보정이 안 걸렸다면 1000이다 — 그것이 이 검사가 막는 상태다")
                .isEqualTo(1000 - OFFSET);
    }

    /**
     * 위 검사가 위치만 보면 「계산기가 어딘가에서 −1500을 주워 왔다」와 구분이 안 된다.
     * 스프링이 올린 {@link SyncProperties} 빈 자체가 그 값을 들고 있어야 한다.
     */
    @Test
    void 채널_덮어쓰기가_없어도_빈이_뜬다() {
        assertThat(calculator.locate(STREAM, "some-channel", T0.plusMillis(1000)).appliedOffsetMs())
                .as("yml에 channel-offset-ms 절이 없으면 compact 생성자가 빈 맵으로 받아 기본값이 나온다")
                .isEqualTo(OFFSET);
    }
}
