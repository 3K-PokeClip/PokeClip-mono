package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.query.ChatWindowCursor.Cursor;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.sync.SyncProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 범위 조회를 <b>실 PostgreSQL</b>에서 잰다. 가짜로 못 재는 것이 셋이다 — {@code timestamptz}
 * 왕복, <b>행 비교({@code (t, id) > (?, ?)})의 실제 순서</b>, 그리고 <b>조회가 색인을 타는가</b>.
 *
 * <p><b>방송 번호 접두는 {@code win-}이다</b>(문항 7). {@code chat_messages}·{@code chat_donations}는
 * Flyway가 만들고 컨테이너는 JVM에 하나뿐이라, 앞 검사가 남긴 줄이 조용히 섞인다.
 * 그래서 검사마다 이 클래스가 쓰는 방송 번호를 <b>전부</b> 비운다.
 *
 * <p>보정값은 <b>검사가 직접 만든 {@link ChatWindowQuery}</b>가 정한다. 컨텍스트 프로퍼티로
 * 박지 않는 이유는 한 파일 안에서 보정 0과 3900을 <b>같이</b> 재야 하기 때문이다(문항 2-B) —
 * 두 값의 결과가 「다르다」가 아니라 「무엇인가」를 재려면 한 검사 안에 둘이 있어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatWindowQueryTest extends IntegrationTestSupport {

    private static final Set<String> ALL = Set.of("chat", "donation");

    private static final List<String> STREAMS =
            List.of("win-off", "win-same", "win-axis", "win-axis-d", "win-idx", "win-kinds");

    /** 지문(channel_id, sender_channel_id, message_time, content_sha256)이 겹치면 접힌다. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final JdbcTemplate jdbc;

    ChatWindowQueryTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 내_방송만_비운다() {
        STREAMS.forEach(streamId -> {
            jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", streamId);
            jdbc.update("DELETE FROM chat_donations WHERE stream_id = ?", streamId);
        });
    }

    private ChatWindowQuery queryWith(long offsetMs) {
        return new ChatWindowQuery(jdbc, new SyncProperties(offsetMs, Map.of()));
    }

    /**
     * 🔴 <b>문항 2-B.</b> 「두 결과가 다르다」로 재면 <b>부호를 뒤집어도 초록</b>이라
     * 화면과 정반대편 채팅이 뜬다. 그래서 기대가 <b>정확한 집합</b>이고, 심은 데이터가
     * 부호를 가른다 — T+1s는 보정 0에서만, T+4.5s는 보정 3900에서만 창에 든다.
     */
    @Test
    void 보정값이_결과를_바꾼다() {
        Instant T = Instant.parse("2026-09-03T15:12:00Z");
        insertChat("win-off", T.plusMillis(1000), "이른것");
        insertChat("win-off", T.plusMillis(4500), "반응");
        WindowRequest w = new WindowRequest(T, T.plusSeconds(2));

        ChatWindowPage 보정0 = queryWith(0).find("win-off", null, w, ALL, 200, null);
        ChatWindowPage 보정3900 = queryWith(3900).find("win-off", null, w, ALL, 200, null);

        assertThat(보정0.items()).extracting(ChatWindowItem::text).containsExactly("이른것");
        assertThat(보정3900.items()).extracting(ChatWindowItem::text).containsExactly("반응");
        assertThat(보정0.appliedOffsetMs()).isZero();
        assertThat(보정3900.appliedOffsetMs()).isEqualTo(3900);
    }

    /**
     * 🔴 <b>계획 검증 F1(치명)의 갈래를 실제로 만드는 데이터다.</b> 채팅 5 + 후원 3 +
     * {@code limit 2}면 2장째가 {@code (c5, d1)}로 끝나 3장째 커서가 <b>{@code d}</b>다.
     * 원래 계획(채팅 50 + 후원 1 + limit 7)은 마지막 장이 커서 없이 끝나 그 갈래를
     * <b>구조적으로 못 만들었다</b> — 규칙이 틀려도 초록이었다.
     *
     * <p><b>장 수 상한이 없으면 이 검사가 끝나지 않는다.</b> 규칙이 틀리면 2↔3장을 영원히
     * 왕복한다(실 PG 재현). 상한을 걸어 순환이 <b>무한 대기가 아니라 실패</b>로 드러나게 한다.
     */
    @Test
    void 커서가_후원으로_끝나도_누락_중복이_없다() {
        Instant T = Instant.parse("2026-09-03T15:00:00Z");
        for (int i = 0; i < 5; i++) insertChat("win-same", T, "m" + i);
        for (int i = 0; i < 3; i++) insertDonation("win-same", T, "d" + i);
        WindowRequest w = new WindowRequest(T.minusSeconds(1), T.plusSeconds(1));

        List<String> seen = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        Cursor after = null;
        int pages = 0;
        do {
            ChatWindowPage page = queryWith(0).find("win-same", null, w, ALL, 2, after);
            page.items().forEach(i -> seen.add(i.kind() + i.id()));
            page.items().forEach(i -> kinds.add(i.kind()));
            after = page.nextCursor() == null ? null
                    : ChatWindowCursor.decodeOrFirstPage(page.nextCursor());
            pages++;
        } while (after != null && pages <= 10);

        assertThat(pages).as("장이 10을 넘으면 커서가 순환하고 있다").isLessThanOrEqualTo(10);
        assertThat(seen).hasSize(8).doesNotHaveDuplicates();
        assertThat(kinds)
                .as("정렬이 (시각, 종류, id)라 같은 시각의 채팅이 전부 후원보다 앞이다")
                .containsExactly("chat", "chat", "chat", "chat", "chat",
                        "donation", "donation", "donation");
    }

    /**
     * 🔴 <b>계획 검증 F4.</b> 응답 시각은 <b>표에 찍힌 원본</b>이고 화면 위치는
     * {@code 시각 − appliedOffsetMs}로 얻는다. 목록과 차트가 같은 규칙이다 —
     * 한쪽만 미리 빼서 내보내면 프론트가 한쪽만 되돌려 3.9초 어긋난다.
     */
    @Test
    void 응답_시각은_표_축이고_화면_위치는_빼서_얻는다() {
        Instant T = Instant.parse("2026-09-03T15:12:00Z");
        insertChat("win-axis", T.plusMillis(4500), "반응");

        ChatWindowPage page = queryWith(3900)
                .find("win-axis", null, new WindowRequest(T, T.plusSeconds(2)), ALL, 200, null);

        assertThat(page.items()).singleElement().satisfies(i -> {
            assertThat(i.time()).as("표 축 그대로").isEqualTo(T.plusMillis(4500));
            assertThat(i.timeBasis()).isEqualTo("message");
            assertThat(i.time().minusMillis(page.appliedOffsetMs()))
                    .as("화면 위치").isBetween(T, T.plusSeconds(2));
        });
    }

    /**
     * 🔴 <b>문항 9 — 같은 이름의 값에 축이 둘이다.</b> 채팅은 치지직 시계
     * ({@code message_time}), 후원은 <b>우리 기계 시계</b>({@code received_at})다.
     * 둘의 차는 전달 지연(175ms)만이 아니라 기계 시계 오프셋까지 포함한다
     * (POK-92 실측에서 이 기계가 4초 느렸다). 칸으로 밝히지 않으면 후원이 채팅 사이
     * 엉뚱한 자리에 끼어드는 것을 아무도 못 본다.
     */
    @Test
    void 후원의_시각_축은_received다() {
        Instant T = Instant.parse("2026-09-03T15:12:00Z");
        insertDonation("win-axis-d", T.plusMillis(4500), "가즈아");

        ChatWindowPage page = queryWith(3900)
                .find("win-axis-d", null, new WindowRequest(T, T.plusSeconds(2)), ALL, 200, null);

        assertThat(page.items()).singleElement().satisfies(i -> {
            assertThat(i.timeBasis()).isEqualTo("received");
            assertThat(i.kind()).isEqualTo("donation");
            assertThat(i.amount()).isEqualTo(1000L);
            assertThat(i.donationType()).isEqualTo("CHAT");
        });
    }

    /**
     * {@code kinds}가 한쪽뿐인데 커서 종류가 다른 쪽인 경우도 {@code afterFor}의 같은 표를
     * 따른다(F1 처방 3). 채팅만 달라고 하면서 {@code d} 커서를 주면 <b>그 시각 채팅은
     * 이미 다 나갔으므로</b> 아무것도 안 나와야 한다 — {@code (t0, 0)}을 주면 통째로 재출력된다.
     */
    @Test
    void kinds가_한쪽뿐이어도_커서_규칙은_같다() {
        Instant T = Instant.parse("2026-09-03T15:00:00Z");
        insertChat("win-kinds", T, "c1");
        insertChat("win-kinds", T, "c2");
        insertDonation("win-kinds", T, "d1");
        WindowRequest w = new WindowRequest(T.minusSeconds(1), T.plusSeconds(1));

        ChatWindowPage 후원커서 = queryWith(0).find("win-kinds", null, w, Set.of("chat"), 200,
                new Cursor(T.toEpochMilli(), "d", 1));
        assertThat(후원커서.items()).as("그 시각 채팅은 후원보다 앞이라 이미 다 나갔다").isEmpty();

        ChatWindowPage 채팅만 = queryWith(0).find("win-kinds", null, w, Set.of("chat"), 200, null);
        assertThat(채팅만.items()).as("양성 대조 — 데이터가 없어서 빈 것이 아니다").hasSize(2);
        assertThat(채팅만.items()).extracting(ChatWindowItem::kind).containsOnly("chat");
    }

    /**
     * 🔴 <b>내가 고른 주입이 초록으로 나와서 생긴 검사다</b>(문항 1의 뜻 ① — 그 자리를 재는
     * 검사가 없었다). {@code LIMIT limit + 1}을 {@code LIMIT limit}으로 되돌려도 위 검사들이
     * 전부 초록이었다 — <b>둘을 같이 물으면 다른 표가 한 줄을 더 주기 때문</b>이다.
     * 한 종류만 물으면 그 대타가 없어 <b>마지막 장이 조용히 잘리고 페이징이 일찍 끝난다.</b>
     *
     * <p>양쪽 표를 다 잰다 — 한쪽만 재면 쌍둥이 중 한쪽이 무방비다.
     */
    @Test
    void 한_종류만_물어도_다음_장_판정이_선다() {
        Instant T = Instant.parse("2026-09-03T15:00:00Z");
        for (int i = 0; i < 3; i++) insertChat("win-kinds", T.plusMillis(i), "c" + i);
        for (int i = 0; i < 3; i++) insertDonation("win-kinds", T.plusMillis(i), "d" + i);
        WindowRequest w = new WindowRequest(T.minusSeconds(1), T.plusSeconds(1));

        for (String kind : new String[] {"chat", "donation"}) {
            ChatWindowPage first = queryWith(0).find("win-kinds", null, w, Set.of(kind), 2, null);
            assertThat(first.items()).as(kind).hasSize(2);
            assertThat(first.nextCursor()).as(kind + " — 셋 중 둘만 줬으니 다음 장이 있다").isNotNull();

            ChatWindowPage second = queryWith(0).find("win-kinds", null, w, Set.of(kind), 2,
                    ChatWindowCursor.decodeOrFirstPage(first.nextCursor()));
            assertThat(second.items()).as(kind).hasSize(1);
            assertThat(second.nextCursor()).as(kind + " — 다 줬으니 끝이다").isNull();
        }
    }

    /**
     * 이 조회가 색인을 못 타면 채팅 표 전체를 훑는다. 방송이 길면 창구가 통째로 느려진다.
     *
     * <p>🔴 <b>SQL을 여기 다시 적지 마라 — {@link ChatWindowQuery#CHATS}를 그대로
     * {@code EXPLAIN} 한다</b>(문항 8). 베낀 문자열을 재는 것은 아무것도 재지 않는 것과 같다.
     * 리터럴이 아니라 파라미터({@code ?})로 재는 것도 같은 이유다 — 앱이 던지는 것이 그것이다.
     *
     * <p>🔴 <b>심은 2만 행을 반드시 지운다</b>(계획 검증 F15). 공유 컨테이너라 남기면 뒤에 도는
     * 검사의 계획과 시간에 조용히 얹힌다. 지운 뒤 {@code ANALYZE}도 다시 돌린다 —
     * 통계만 남으면 다음 검사의 계획이 없는 행을 전제로 선다.
     */
    @Test
    void 조회가_색인을_탄다() {
        Instant base = Instant.parse("2026-09-03T00:00:00Z");
        try {
            seedManyChats("win-idx", base, 20_000);
            jdbc.execute("ANALYZE chat_messages");

            String plan = String.join("\n", jdbc.queryForList(
                    "EXPLAIN " + ChatWindowQuery.CHATS, String.class,
                    "win-idx",
                    Timestamp.from(base.plusSeconds(300)), Timestamp.from(base.plusSeconds(360)),
                    Timestamp.from(base.plusSeconds(300)), 0L, 201));

            assertThat(plan).contains("idx_chat_messages_stream_message_time");
            assertThat(plan).doesNotContain("Seq Scan");
        } finally {
            jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", "win-idx");
            jdbc.execute("ANALYZE chat_messages");
        }
    }

    private void insertChat(String streamId, Instant messageTime, String content) {
        int n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO chat_messages
                  (channel_id, sender_channel_id, content, message_time, received_at,
                   content_sha256, stream_id, nickname, user_role)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "win-ch", "win-sender", content,
                Timestamp.from(messageTime), Timestamp.from(messageTime),
                String.format("%064d", n), streamId, "닉" + n, "common_user");
    }

    private void insertDonation(String streamId, Instant receivedAt, String text) {
        jdbc.update("""
                INSERT INTO chat_donations
                  (stream_id, channel_id, donator_channel_id, donator_nickname,
                   donation_type, pay_amount, donation_text, received_at, donation_sha256)
                -- 지문은 매번 유일한 값이면 된다. 이 픽스처가 재는 것은 창구 조회이지
                -- 지문 규칙이 아니다 — 그쪽은 DonationPersisterTest가 잰다. 운영 지문
                -- 계산을 여기 베끼면 사본만 맞고 운영 계산은 아무도 안 보게 된다.
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, md5(random()::text))
                """,
                streamId, "win-ch", "win-donator", "후원자", "CHAT", 1000L, text,
                Timestamp.from(receivedAt));
    }

    /** 2만 행을 한 문장으로 심는다. 배치 INSERT로 돌리면 검사 하나가 수 초를 먹는다. */
    private void seedManyChats(String streamId, Instant base, int rows) {
        jdbc.update("""
                INSERT INTO chat_messages
                  (channel_id, sender_channel_id, content, message_time, received_at,
                   content_sha256, stream_id)
                SELECT 'idx-ch', 'idx-sender', 'x',
                       ?::timestamptz + (g * interval '50 milliseconds'),
                       ?::timestamptz + (g * interval '50 milliseconds'),
                       lpad(g::text, 64, 'f'), ?
                  FROM generate_series(0, ? - 1) g
                """,
                Timestamp.from(base), Timestamp.from(base), streamId, rows);
    }
}
