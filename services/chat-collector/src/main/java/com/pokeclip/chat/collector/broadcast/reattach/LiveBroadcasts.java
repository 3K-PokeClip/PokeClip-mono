package com.pokeclip.chat.collector.broadcast.reattach;

import java.time.Instant;
import java.util.List;

/**
 * clip의 {@code GET /internal/broadcasts/live} 응답. 정본은 clip의
 * {@code broadcast/api/LiveBroadcastsResponse}이고 <b>칸 이름을 마음대로 바꾸지 않는다</b> —
 * 2번(web)이 아니라 우리 서버끼리의 계약이다.
 *
 * <p><b>{@code streamerId}가 문자열이다.</b> 명부의 그 칸이 문자열이고 숫자로 못 읽는 줄도
 * 온다 — clip이 그런 줄을 빼면 그 방송은 영영 안 걷히고 우리는 그런 줄이 있었다는 것조차
 * 모른다. 숫자로 읽는 것은 {@code StreamerId.parse}의 몫이고, 못 읽으면 세어서 드러낸다.
 *
 * <p><b>{@code startedAt}은 {@code null}일 수 있다.</b> clip이 그 칸을 지우지 않고
 * {@code null}로 싣는다(그쪽 record 주석이 못박았다). 그런 줄을 버리지 않는다.
 *
 * <p>{@code truncated}는 clip이 상한(500)에서 잘랐다는 뜻이다. <b>개수 제한이 아니라
 * 「명부가 이상하다」의 눈금이다</b> — 종료 알림을 놓친 방송이 영원히 {@code live}로 남고
 * 치우는 장치가 없다(POK-218이 찾아 clip 쪽 별도 카드가 났다).
 *
 * <h2>🔴 <b>compact 생성자를 일부러 두지 않는다</b> — 계약 위반은 여기서 안 접는다</h2>
 *
 * {@code broadcasts} 칸이 빠지거나 {@code null}로 오면 Jackson 3가 그 칸을 <b>그대로 비운다</b>
 * (재현함. {@code truncated}는 primitive라 그 칸이 빠지면 Jackson이 먼저 거절하는 것과 갈린다).
 * 여기서 빈 리스트로 정규화하면 <b>「clip이 계약을 어겼다」와 「방송이 하나도 없다」가 같아진다</b> —
 * {@link LiveBroadcastClient} javadoc이 막으려는 바로 그것이다. 그래서 그 칸의 검사는
 * <b>클라이언트가 던지면서</b> 하고({@code chat.reattach.live_list_incomplete}), 이 record는
 * 받은 그대로를 담는다. <b>정규화를 여기에 넣지 마라 — 그 로그가 조용히 사라진다.</b>
 *
 * <h2>무엇을 막고 무엇을 안 막았나 — 세 겹이고 겹마다 하는 일이 다르다</h2>
 *
 * <b>봉투</b>(이 record 자체) · <b>원소</b>(리스트 칸) · <b>칸</b>(줄 안쪽). 정본 표는
 * {@code services/README.md}「방송 중 목록 창구」에 있고 여기는 <b>왜</b>만 적는다.
 *
 * <ul>
 *   <li><b>봉투</b> — {@code broadcasts}가 빠지거나 {@code null}. <b>막는다, 던져서</b>
 *       ({@link LiveBroadcastClient}, 위 문단). 회차가 성립하지 않는다</li>
 *   <li><b>원소</b> — 줄 자체가 {@code null}({@code [null]}). <b>막는다, 세고 넘어간다</b>
 *       ({@code chat.reattach.null_row}). <b>Jackson 3가 배열 원소의 {@code null}을 리스트에
 *       그대로 넣는다</b>(재현함: {@code [null,{…}]} → {@code size=2}). 그러면 아래
 *       {@code streamId} 거름망이 <b>자기 자신</b>에서 NPE를 던져 같은 결말이 된다.
 *       <b>지금 clip은 이런 줄을 못 만든다</b>({@code LiveBroadcastsResponse.from}이
 *       {@code row -> new Item(…)}) — 그래서 실제로 오면 직렬화 계층이 바뀌었다는 뜻이고,
 *       <b>그 진단이 사라지지 않게 아래 {@code stream_id_missing}과 이름을 갈랐다</b>
 *       (로컬 리뷰 라운드 3)</li>
 *   <li><b>칸</b> {@code streamId} — <b>막는다, 세고 넘어간다</b>
 *       ({@code chat.reattach.stream_id_missing}). {@code Reattacher.sweepOnce}의 첫 거름망이
 *       JDK 불변 Set이라 {@code contains(null)}이 NPE이고, <b>줄 하나가 그 회차 전부를 죽인다</b></li>
 *   <li><b>칸</b> {@code streamerId} — <b>안 막는다. 막을 것이 없다</b>(실측):
 *       {@code StreamerId.parse(null)}이 INVALID를, {@code LaneKey.of(null)}이 빈 문자열을 준다.
 *       그 줄은 {@code streamer_id_unreadable}로 세어져 건너뛴다</li>
 *   <li><b>칸</b> {@code startedAt} — <b>안 막는다. 계약이 허용한다</b>(위 문단). 없으면 EPOCH로
 *       읽어 살아 있는 세션을 못 뺏게 한다</li>
 * </ul>
 *
 * <p><b>{@code truncated}는 이 표에 없다</b> — primitive라 그 칸이 빠지면 Jackson이 먼저
 * 거절한다(위 문단). 우리가 막을 자리가 아니다.
 */
public record LiveBroadcasts(List<Item> broadcasts, boolean truncated) {

    public record Item(String streamId, String streamerId, Instant startedAt) {
    }
}
