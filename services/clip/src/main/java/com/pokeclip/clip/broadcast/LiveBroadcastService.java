package com.pokeclip.clip.broadcast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「지금 방송 중인 줄」 한 장을 만든다. 부르는 쪽은 사람이 아니라 <b>재시작한 수집기</b>다.
 *
 * <p><b>자격 판정이 없다.</b> 부르는 쪽이 우리 서버라 물어볼 회원 번호가 애초에 없다 —
 * {@code resolve}를 붙이면 없는 번호로 auth를 두드린다(PRD 결정). 그래서 이 클래스는
 * {@code delegation}을 <b>안 가진다</b>: 필드가 없으면 나중에 누가 무심코 부를 수도 없다.
 *
 * <p>{@code @Transactional}이 없는 것도 의도다. 질의가 하나뿐이라 얻는 것이 없다
 * ({@code BroadcastListService}의 그 절이 자세하다 — 다만 그쪽의 이유였던 auth 왕복은
 * 여기엔 아예 없다).
 */
@Service
public class LiveBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(LiveBroadcastService.class);

    /**
     * 한 번에 주는 줄 수의 상한. 설계 전제인 동시 방송 100의 다섯 배다.
     *
     * <p><b>이 값은 「방송이 이만큼 많다」가 아니라 「명부가 이상하다」를 재는 눈금이다.</b>
     * 종료 알림을 놓친 방송은 영원히 방송 중으로 남고 그 줄을 치우는 장치가 없다
     * (POK-218에서 찾았고 별도 카드로 뺐다) — 상한에 닿는 것은 그것이 쌓였다는 신호다.
     *
     * <p><b>{@code private}이 아닌 것은 시험이 읽기 때문이다</b>({@code LiveBroadcastServiceTest}).
     * 경계 시험이 상한을 숫자로 베끼면 상한이 바뀌는 날 씨앗만 그대로 남아 <b>경계를 안 넘는
     * 시험</b>이 된다. {@code BroadcastListService.DEFAULT_LIMIT}이 {@code private}인 것과
     * 규칙이 갈리지 않는다 — 그 규칙은 「읽는 곳이 생기는 날 넓힌다」이고 여기가 그 날이다.
     * <b>값의 정본은 커밋되는 {@code services/README.md}</b>이고 수집기는 거기서 읽는다.
     */
    static final int MAX_ROWS = 500;

    /**
     * DB에 실제로 던지는 개수. <b>상한 하나를 더 받아 「잘렸나」를 본다</b> — 개수를 따로 세면
     * 질의가 하나 더 돈다({@code BroadcastListService.list}와 같은 수법).
     *
     * <p>🔴 <b>{@code MAX_ROWS + 1}을 호출 자리에 적지 않고 상수로 뽑은 이유는 쌍둥이다.</b>
     * {@code LiveBroadcastQueryTest.실행계획()}이 「이 질의가 부분 색인을 타는가」를 재는데,
     * 그 시험이 숫자를 베끼면 여기와 갈린다. 그래서 <b>이 상수를 참조하게 했다</b> —
     * 파생값이라 {@link #MAX_ROWS}와 갈릴 수가 없다.
     *
     * <p><b>상한이 그 시험의 판정을 실제로 움직이는 것을 쟀다</b>(2026-08-31, Testcontainers
     * PostgreSQL 17). 계획의 <b>모양</b>(Index Scan · {@code Sort} 없음)은 상한 20~100,000에서
     * <b>안 갈렸다</b> — 즉 「색인 이름」 단언은 상한과 무관해 보인다. 🔴 <b>갈리는 상한을 못
     * 찾은 것이지 「없다」고 잰 것이 아니다.</b> 갈리는 것은 <b>버퍼 수</b>이고, 그 시험의 셋째
     * 단언이 정확히 그 값이다 — {@code live} 10만 줄에서 상한 20이면 42 · 501이면 407 ·
     * 5,000이면 475 · 100,000이면 1,907이었다. 다른 수로 재면 그 천장이 <b>운영과 다른 질의</b>를
     * 재게 된다.
     */
    static final int FETCH_ROWS = MAX_ROWS + 1;

    private final BroadcastRepository broadcasts;

    LiveBroadcastService(BroadcastRepository broadcasts) {
        this.broadcasts = broadcasts;
    }

    public LiveBroadcastPage list() {
        List<LiveBroadcastRow> rows = broadcasts.findLive(FETCH_ROWS);
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            // WARN인 이유 — 이것이 울면 사람이 명부를 봐야 한다. 조용히 잘라 보내면
            // 「목록은 정상인데 어떤 방송만 안 걷히는」 상태가 되고 원인이 안 보인다.
            log.warn("clip.live_broadcasts.truncated limit={}", MAX_ROWS);
        }
        // 앞에서 자른다 — 쿼리가 최근 시작 순이므로 남는 쪽이 최근이다. 뒤에서 자르면
        // 종료를 놓쳐 쌓인 옛 줄이 자리를 다 먹고 진짜 방송이 잘린다(PRD가 고치려는 실패).
        return new LiveBroadcastPage(truncated ? rows.subList(0, MAX_ROWS) : rows, truncated);
    }
}
