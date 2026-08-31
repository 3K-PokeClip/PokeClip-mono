package com.pokeclip.chat.collector.broadcast.attach;

import com.pokeclip.chat.collector.broadcast.StreamerId;

/**
 * 줄 이름. <b>{@link StreamerId#parse}가 같은 회원으로 읽는 원문은 전부 같은 줄이 된다.</b>
 *
 * <p><b>왜 파싱 결과로 정규화하나</b>: 알림 경로와 재부착이 <b>다른 시스템이 만든 문자열</b>을
 * 줄 이름으로 쓴다(1번의 SQS 봉투 vs clip 명부의 칸). {@code trim()}만 하면
 * {@code "07"}·{@code "007"}·{@code "+7"}·{@code "0000000007"}이 전부 회원 7인데
 * <b>각각 다른 줄</b>이 된다 — 실측으로 8종 중 4종이 갈렸다. 그러면 「재부착과 알림이 같은
 * 줄을 쓴다」는 이 카드 설계의 기둥이 무너진다.
 *
 * <p><b>줄이 갈리면 무엇이 깨지나</b>(규칙 4 — 코드로 확인한 것만 적는다):
 * 자리 자체는 {@code SessionRegistry.sessions}가 {@code ConcurrentHashMap<Long, Entry>} +
 * {@code putIfAbsent}(`:90`·`:269`)라 <b>세션이 둘 서지는 않는다</b>. 깨지는 것은
 * {@code retargetOrSkip}(`:357-420`)이다 — 그 안이 <b>원자적이 아니고</b>(자리를 읽고,
 * 이름을 바꾸고, 자리를 다시 확인한다), 그 {@code SEAT_STOPPING} 주석 자신이
 * <b>「지금 도달 경로가 없는 이유는 상태가 아니라 스레드 수다 … 수립을 워커로 빼는 날
 * 이 자리를 다시 본다」</b>고 못박아 뒀다. <b>줄이 바로 그 「스레드 수」의 대체물이다</b> —
 * 갈리면 그 전제가 무너진다.
 *
 * <p><b>못 읽는 식별자는 뭉치지 않는다.</b> 원문(공백만 턴 것)을 그대로 줄에 태운다 —
 * 판정기가 그것을 세어야 하는 값이고, 여기서 하나로 뭉치면 그 카운터가 층을 넘어온다.
 */
public final class LaneKey {
    private LaneKey() { }

    public static String of(String rawStreamerId) {
        StreamerId parsed = StreamerId.parse(rawStreamerId);
        if (parsed.valid()) {
            return Long.toString(parsed.value());
        }
        return rawStreamerId == null ? "" : rawStreamerId.trim();
    }
}
