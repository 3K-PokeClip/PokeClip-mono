package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.chzzk.DonationSubscription;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 방송 번호 → 후원 구독 상태. <b>세션이 쓰고 창구가 읽는다.</b>
 *
 * <p>{@code CollectionStatus}에 안 넣은 이유: 그것은 「채팅 수집이 어떤가」의 상태 기계이고
 * 전이 규칙이 붙어 있다. 후원은 그 기계의 상태가 아니라 <b>수립 때 한 번 정해지는 곁가지</b>라
 * 섞으면 전이표에 뜻 없는 칸이 는다.
 *
 * <p>없는 방송은 {@link DonationSubscription#NONE}이다 — null을 주면 창구가 그것을 응답에
 * 실어 clip 배선이 갈린다.
 *
 * <p><b>방송 번호가 null이면 아무것도 안 한다.</b> 편지 없이 붙은 옛 경로
 * ({@code CHZZK_ENABLED} · {@link com.pokeclip.chat.collector.session.SessionKey#legacy()})에는
 * 방송 번호가 없고, {@code ConcurrentHashMap}은 null 열쇠에 NPE를 던진다 — 그 예외가
 * 수립 한가운데서 나가면 <b>옛 경로의 수집이 통째로 죽는다</b>(실측: 모듈 전체 58건 빨간불).
 * {@code PersistableChat.streamId}와 같은 규칙이다 — null이 곧 「모른다」이지 오류가 아니다.
 * <b>부르는 쪽마다 검사하지 않고 여기 한 곳에 둔다</b> — 부르는 자리가 셋(수립·갈아끼움·닫기)이라
 * 밖에 두면 한 자리를 빠뜨리는 모양이 된다.
 */
@Component
public class DonationSubscriptions {

    private final Map<String, DonationSubscription> byStream = new ConcurrentHashMap<>();

    public void set(String streamId, DonationSubscription state) {
        if (streamId == null) {
            return;
        }
        byStream.put(streamId, state);
    }

    public DonationSubscription of(String streamId) {
        if (streamId == null) {
            return DonationSubscription.NONE;
        }
        return byStream.getOrDefault(streamId, DonationSubscription.NONE);
    }

    /**
     * 🔴 <b>방송 번호를 바꾸며 값을 옮긴다 — 읽고·지우고·쓰는 셋을 따로 하지 않는다</b>(봇 codex).
     *
     * <p>따로 하면 그 사이에 낀 갱신이 사라진다: 갈아끼움이 {@code FAILED} 를 읽은 뒤
     * 후원 재시도가 성공해 옛 열쇠에 {@code SUBSCRIBED} 를 쓰면, 이어지는 지우기가 그것을
     * 없애고 <b>낡은 {@code FAILED} 를 새 방송에 설치한다.</b> 재시도 스레드는 성공하면
     * 끝나므로 그 방송은 후원을 받고 있는데도 창구가 계속 「실패」라고 답한다.
     *
     * <p>{@code remove} 가 <b>그 순간의 값</b>을 돌려주므로 늦게 들어온 갱신도 같이 따라간다.
     *
     * <p><b>남는 창 하나</b>: 부르는 쪽이 방송 번호를 먼저 바꾸므로 콜백은 새 열쇠를 본다.
     * 다만 콜백이 <b>바꾸기 직전에 옛 열쇠를 읽고</b> 이 메서드 뒤에 쓰면 그 값이 옛 자리에
     * 남는다 — 아무도 안 읽는 자리이고, 그것까지 막으려면 락이 필요하다.
     */
    public void retarget(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        DonationSubscription carried = byStream.remove(from);
        byStream.put(to, carried == null ? DonationSubscription.NONE : carried);
    }

    public void remove(String streamId) {
        if (streamId == null) {
            return;
        }
        byStream.remove(streamId);
    }
}
