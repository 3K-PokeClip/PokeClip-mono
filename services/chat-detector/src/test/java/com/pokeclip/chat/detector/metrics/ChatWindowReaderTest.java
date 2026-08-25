package com.pokeclip.chat.detector.metrics;

import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 서버가 chat_messages를 읽는다. 그 표는 수집 서버가 만들지만 chat 계열의 공동 소유다
 * (V301 주석 — 한 소유자의 두 프로세스). 테스트에서는 우리 Flyway가 그 표를 안 만들므로
 * 여기서 직접 만든다.
 */
@SpringBootTest
class ChatWindowReaderTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-08-25T12:00:00Z");

    private final JdbcTemplate jdbc;
    private final ChatWindowReader reader;

    ChatWindowReaderTest(JdbcTemplate jdbc, ChatWindowReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    @BeforeEach
    void 채팅_표를_만들고_비운다() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    channel_id        TEXT        NOT NULL,
                    sender_channel_id TEXT        NOT NULL,
                    content           TEXT        NOT NULL,
                    message_time      TIMESTAMPTZ NOT NULL,
                    received_at       TIMESTAMPTZ NOT NULL,
                    content_sha256    VARCHAR(64) NOT NULL,
                    stream_id         VARCHAR(128)
                )
                """);
        jdbc.update("DELETE FROM chat_messages");
    }

    /** 전달 지연은 실측 중앙값 175ms를 쓴다. 두 축을 갈라야 하는 검사는 아래 갈래를 쓴다. */
    private void 채팅(String streamId, String sender, Instant messageTime) {
        채팅(streamId, sender, messageTime, messageTime.plusMillis(175));
    }

    /** 치지직이 찍은 시각과 우리가 받은 시각을 <b>따로</b> 준다. 시계가 어긋난 상황을 만든다. */
    private void 채팅(String streamId, String sender, Instant messageTime, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO chat_messages
                    (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES ('c1', ?, 'hi', ?, ?, repeat('a', 64), ?)
                """, sender, java.sql.Timestamp.from(messageTime),
                java.sql.Timestamp.from(receivedAt), streamId);
    }

    @Test
    void 최근_채팅이_있는_방송만_활성이다() {
        채팅("live", "u1", T0);
        채팅("old", "u1", T0.minusSeconds(600));

        assertThat(reader.activeStreams(T0.minusSeconds(60))).containsExactly("live");
    }

    /** 방송 번호를 모르는 옛 채팅(stream_id가 NULL)은 셀 대상이 아니다. */
    @Test
    void 방송_번호가_없는_채팅은_활성_목록에_안_들어간다() {
        채팅(null, "u1", T0);

        assertThat(reader.activeStreams(T0.minusSeconds(60))).isEmpty();
    }

    @Test
    void 창별로_메시지_수와_말한_사람_수를_따로_센다() {
        // [T0, T0+5초) 창: u1이 셋, u2가 하나 → 메시지 4 · 사람 2
        채팅("s1", "u1", T0);
        채팅("s1", "u1", T0.plusMillis(100));
        채팅("s1", "u1", T0.plusMillis(200));
        채팅("s1", "u2", T0.plusMillis(300));
        // 다음 창: 하나
        채팅("s1", "u3", T0.plusSeconds(6));

        List<MetricRow> rows = reader.countWindows("s1", 5_000L, T0, T0.plusSeconds(10));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).messageCount()).isEqualTo(4);
        assertThat(rows.get(0).chatterCount()).isEqualTo(2);
        assertThat(rows.get(1).messageCount()).isEqualTo(1);
        assertThat(rows.get(1).chatterCount()).isEqualTo(1);
    }

    /**
     * 1인 도배가 메시지 수만 올린다는 것을 잰다. 팀 실측(실경기 18,112건)의 근거이고,
     * 두 값을 한 칸으로 접으면 이 구분이 사라진다.
     */
    @Test
    void 한_사람이_도배하면_메시지만_오르고_사람_수는_1이다() {
        for (int i = 0; i < 50; i++) {
            채팅("s1", "spammer", T0.plusMillis(i * 10L));
        }

        List<MetricRow> rows = reader.countWindows("s1", 5_000L, T0, T0.plusSeconds(5));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).messageCount()).isEqualTo(50);
        assertThat(rows.get(0).chatterCount()).isEqualTo(1);
    }

    /** 창 눈금이 message_time 기준이어야 한다. received_at으로 자르면 전달 지연만큼 밀린다. */
    @Test
    void 눈금은_치지직이_찍은_시각_기준이다() {
        // message_time은 창 [T0, T0+5초)의 끝자락, received_at은 그 다음 창으로 넘어간다.
        채팅("s1", "u1", T0.plusMillis(4_900));

        List<MetricRow> rows = reader.countWindows("s1", 5_000L, T0, T0.plusSeconds(10));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).windowStartMs()).isEqualTo(T0.toEpochMilli());
    }

    /**
     * <b>활성 판단은 우리 시계({@code received_at}) 기준이다</b> — 시각 축 표 1번.
     * 치지직 시계가 우리보다 느려 {@code message_time}이 한참 과거인데 방금 도착한 채팅을
     * 만든다. {@code message_time}으로 재면 이 방송은 <b>통째로 안 보이고</b>, 판별기는
     * 그 방송에 카드를 하나도 안 낸다 — 오류도 로그도 없다.
     *
     * <p>이 갈래가 없으면 두 칸을 바꿔 써도 <b>다른 검사 열여덟이 전부 초록</b>이다
     * (2026-08-25 결함 주입 J4로 실측). 기존 도우미가 두 시각을 175ms로 묶어 둬서
     * 축이 갈라지는 상황 자체를 만들지 못했다.
     */
    @Test
    void 활성_판단은_치지직_시계가_아니라_우리가_받은_시각_기준이다() {
        // 치지직이 10분 전으로 찍었지만 우리에게는 방금 왔다.
        채팅("skewed", "u1", T0.minusSeconds(600), T0);

        assertThat(reader.activeStreams(T0.minusSeconds(60))).containsExactly("skewed");
    }

    /**
     * 창 경계는 {@code >= from AND < to}다 — 시각 축 문항 5. 오른쪽이 {@code <=}가 되면
     * 경계에 딱 걸린 채팅이 <b>두 창에</b> 들어가 같은 반응이 두 번 세어진다.
     *
     * <p>바깥 경계에서는 「다음 바퀴가 그 창을 다시 집계할 때 겹친다」는 뜻이고, 겹친 값이
     * {@code ON CONFLICT DO NOTHING}에 막혀 <b>먼저 들어간 틀린 값이 남는다.</b>
     */
    @Test
    void 창_오른쪽_경계의_채팅은_이번_창에_안_들어간다() {
        채팅("s1", "u1", T0);
        채팅("s1", "u2", T0.plusSeconds(5));   // [T0, T0+5초) 의 바로 바깥

        List<MetricRow> rows = reader.countWindows("s1", 5_000L, T0, T0.plusSeconds(5));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).windowStartMs()).isEqualTo(T0.toEpochMilli());
        assertThat(rows.get(0).messageCount()).isEqualTo(1);
    }

    /**
     * <b>{@code RECEIVED_SLACK}(집계 WHERE의 {@code received_at} 여유)을 잰다.</b>
     *
     * <p>집계 범위의 {@code received_at} 쪽은 <b>인덱스를 타려고</b> 거는 것이지 경계를 자르려고
     * 거는 것이 아니다. 그래서 여유가 필요하다 — 전달이 늦은 채팅은 {@code message_time}이 창
     * 안인데 {@code received_at}은 창 <b>바깥</b>이다. 여유가 0이면 그런 채팅이 통째로 빠지고,
     * 그 반쪽 값이 {@code ON CONFLICT DO NOTHING}으로 <b>영구 고정</b>된다.
     *
     * <p>이 갈래가 없으면 여유를 5분에서 0으로 바꿔도 <b>다른 검사 스물넷이 전부 초록</b>이다
     * (감사 1회차 C-1, 내가 재현해서 확인했다). 그리고 그 여유는 양쪽 5분이라 인덱스가 필요한
     * 것의 11배를 읽으므로(감사 A-4) <b>누군가 줄이고 싶어질 자리</b>다.
     *
     * <h2>🔴 이 검사가 지키는 바닥은 60초다 — 근거가 있는 숫자다</h2>
     *
     * 처음에는 전달 지연을 10초로 뒀는데, 감사자가 <b>그러면 여유를 6초로 줄여도 초록</b>임을
     * 실측했다(5분→6초·30초 전부 통과). 실제로 줄일 사람은 0초가 아니라 10초·30초로 줄이므로
     * 그 구간이 정확히 비어 있었다.
     *
     * <p>바닥을 <b>65초</b>로 잡았다 — 여유가 60초 이하이면 여기가 빨개진다.
     *
     * <h3>이 숫자가 무엇에서 왔고, 무엇이 확인되지 않았나</h3>
     *
     * <b>확인된 것</b>: {@code chat-collector}의 {@code reconnect-max-delay} 기본값이
     * {@code 60s}다({@code application.yml:139}). 실재하는 값이고 지어내지 않았다.
     *
     * <p><b>🔴 확인되지 않은 것 둘 — 감사 1회차가 짚었고 나도 재지 않았다.</b>
     * <ul>
     *   <li>그 값은 <b>재시도 사이 백오프의 상한</b>이지 <b>총 단절 시간의 상한이 아니다.</b>
     *       {@code ReconnectPolicy}에 시도 횟수 상한이 없다("백오프에 맡긴다") — 재시도가
     *       거듭되면 단절은 60초의 몇 배가 된다. <b>즉 60초는 상한이 아니라 하한에 가깝다.</b></li>
     *   <li>치지직이 재연결 때 <b>놓친 채팅을 되돌려 주는지</b>를 확인하지 않았다. 안 준다면
     *       재연결로 전달 지연이 커지는 일 자체가 없고 이 근거는 딴 데를 가리킨다.
     *       수집 서버 코드에서 백필로 보이는 자리를 찾지 못했다 — <b>없다는 뜻이 아니라
     *       안 찾아졌다는 뜻이다.</b></li>
     * </ul>
     *
     * <p><b>그래서 65초는 「옳은 바닥」이 아니라 「근거 없는 5초보다 나은 바닥」이다.</b>
     * 실측이 나오기 전까지 그 이상을 주장하지 않는다 — 이 주석이 검증 없이 정설이 되는 것을
     * 막으려고 여기 적는다. 전달 지연을 실제로 재는 카드가 나오면 그 값으로 갈아 끼운다.
     */
    @Test
    void 전달이_늦은_채팅도_창_안이면_세어진다() {
        // message_time은 창 [T0, T0+5초)의 끝자락인데, 재연결 때문에 65초 뒤에 도착했다.
        채팅("s1", "u1", T0.plusMillis(4_900), T0.plusSeconds(65));

        List<MetricRow> rows = reader.countWindows("s1", 5_000L, T0, T0.plusSeconds(5));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).windowStartMs()).isEqualTo(T0.toEpochMilli());
        assertThat(rows.get(0).messageCount()).isEqualTo(1);
    }

    @Test
    void 남의_방송_채팅은_안_섞인다() {
        채팅("s1", "u1", T0);
        채팅("s2", "u2", T0);

        List<MetricRow> rows = reader.countWindows("s1", 5_000L, T0, T0.plusSeconds(5));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).messageCount()).isEqualTo(1);
    }
}
