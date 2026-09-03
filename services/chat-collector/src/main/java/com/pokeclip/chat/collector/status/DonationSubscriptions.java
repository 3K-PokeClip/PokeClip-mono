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

    public void remove(String streamId) {
        if (streamId == null) {
            return;
        }
        byStream.remove(streamId);
    }
}
