package com.pokeclip.chat.collector.session;

import java.time.Instant;

/**
 * 이 세션이 <b>누구의 · 어느 방송 · 어느 채널</b>인가, 그리고 <b>그 방송이 언제 시작했나</b>.
 *
 * <p>{@code startedAt}은 <b>방송 사이의 앞뒤를 가르는 유일한 값이라 여기 든다</b> —
 * {@code sequence}는 방송 안에서만 뜻이 있어 다른 방송끼리 비교할 수 없고, SQS FIFO의
 * 그룹이 방송 번호라 <b>같은 스트리머의 두 방송은 순서 보장이 없다.</b> 이것이 없으면 늦게
 * 도착한 앞 방송의 시작 편지가 지금 걷는 방송을 자기 쪽으로 되돌린다(codex P1, 재현함).
 *
 * <h2>🔴 이 값을 채우는 곳이 <b>둘</b>이고, 둘이 같은 눈금이라는 것은 <b>안 재어진 전제다</b></h2>
 *
 * <table border="1">
 *   <caption>{@code startedAt}의 출처</caption>
 *   <tr><th>경로</th><th>무엇이 들어오나</th></tr>
 *   <tr><td>시작 알림</td><td>계약9 봉투의 {@code occurredAt}</td></tr>
 *   <tr><td>재부착(POK-219)</td><td>clip 명부({@code GET /internal/broadcasts/live})의 {@code startedAt}</td></tr>
 * </table>
 *
 * <p>{@link SessionRegistry}의 갈아끼움이 이 둘을 <b>직접 비교한다</b>(늦게 시작한 방송만
 * 자리를 가져간다). 그래서 둘이 같은 사건의 같은 시각이어야 하는데, <b>우리 코드도 우리
 * 문서도 그것을 확인하지 않는다.</b> 근거는 {@code services/README.md}의 「시작 알림의
 * 발생 시각({@code occurredAt})이 비면 그 알림은 버려진다」 한 줄뿐이고, 그것은 <b>1번과
 * clip이 각자 적은 것이지 우리가 대조한 것이 아니다.</b>
 *
 * <p><b>대조 코드를 안 넣었다</b>(감사 라운드 3 H8, 사용자 규칙 「안 하는 선택도 전제에
 * 기대면 적는다」). 넣으려면 두 값을 같은 방송에 대해 나란히 받아야 하는데, 알림은 이미
 * 소비된 뒤라야 재부착이 그 방송을 줍는다 — <b>둘이 우리 손에 같이 있는 순간이 없다.</b>
 *
 * <p><b>갈리는 날 어떻게 되나</b>: clip 쪽이 계통적으로 이르면 재부착이 살아 있는 세션을
 * 못 뺏고(안전한 쪽), 계통적으로 늦으면 <b>재부착이 멀쩡한 세션을 자기 쪽으로 되돌린다.</b>
 * 조용히 어긋나므로 {@code chat.registry.retargeted}·{@code stale_start_rejected}가 이유 없이
 * 늘면 여기를 먼저 본다.
 */
public record SessionKey(String streamId, long streamerId, String channelId, Instant startedAt) {

    /**
     * 설정 토큰 하나로 붙는 옛 경로({@code CHZZK_ENABLED})의 자리표.
     *
     * <p>{@code startedAt}이 {@link Instant#EPOCH}인 이유는 <b>그 경로에 방송 경계가 아예
     * 없기 때문</b>이다 — 편지를 안 받으므로 갈아끼움도 없고, 따라서 이 값이 비교에 쓰이는
     * 길이 없다. null 대신 값을 두는 것은 비교하는 쪽이 널 검사를 하지 않아도 되게 하려는 것이다.
     */
    public static SessionKey legacy() {
        return new SessionKey(null, 0L, null, Instant.EPOCH);
    }
}
