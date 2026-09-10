package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.chzzk.DonationSubscription;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DonationSubscriptionsTest {

    @Test
    void 없는_방송은_NONE이다() {
        assertThat(new DonationSubscriptions().of("never-heard")).isEqualTo(DonationSubscription.NONE);
    }

    @Test
    void 넣은_값을_돌려주고_지우면_NONE으로_돌아간다() {
        DonationSubscriptions donations = new DonationSubscriptions();
        donations.set("s-1", DonationSubscription.REFUSED);
        assertThat(donations.of("s-1")).isEqualTo(DonationSubscription.REFUSED);
        donations.remove("s-1");
        assertThat(donations.of("s-1")).isEqualTo(DonationSubscription.NONE);
    }

    /**
     * 옛 경로({@code SessionKey.legacy()})는 방송 번호가 <b>null</b>이다.
     * ConcurrentHashMap이 null 열쇠에 NPE를 던지므로, 안 막으면 그 예외가 수립
     * 한가운데서 나가 옛 경로의 수집이 통째로 죽는다 — 실측으로 모듈 전체 58건이
     * 빨간불이었다. 셋 다 조용히 넘어가야 한다.
     */
    @Test
    void 방송_번호가_null이면_던지지_않고_NONE이다() {
        DonationSubscriptions donations = new DonationSubscriptions();
        assertThatCode(() -> donations.set(null, DonationSubscription.SUBSCRIBED)).doesNotThrowAnyException();
        assertThat(donations.of(null)).isEqualTo(DonationSubscription.NONE);
        assertThatCode(() -> donations.remove(null)).doesNotThrowAnyException();
    }

    /**
     * 🔴 <b>옮기는 동안 낀 갱신이 사라지지 않는다</b>(봇 codex).
     *
     * <p>읽고·지우고·쓰는 셋을 따로 하면 그 사이의 갱신이 없어진다: 갈아끼움이
     * {@code FAILED} 를 읽은 뒤 후원 재시도가 성공해 옛 열쇠에 {@code SUBSCRIBED} 를 쓰면,
     * 이어지는 지우기가 그것을 없애고 <b>낡은 {@code FAILED} 를 새 방송에 설치한다.</b>
     * 재시도 스레드는 성공하면 끝나므로 그 방송은 후원을 받는데도 창구가 계속 「실패」다.
     *
     * <p>🔴 <b>이 검사가 못 재는 것</b>: 읽기와 지우기를 갈라 놓아도 <b>초록</b>이다.
     * 순차 코드라 그 <b>사이에</b> 갱신이 끼는 창이 안 열린다 — 여기서 재는 것은
     * 「옮긴 뒤 새 자리에 최신 값이 있고 옛 자리가 빈다」는 계약이지 경합 자체가 아니다.
     * 경합을 결정적으로 열려면 이 클래스에 시험용 훅이 필요하다.
     */
    @Test
    void 옮기면_최신_값이_새_자리로_가고_옛_자리는_빈다() {
        DonationSubscriptions subs = new DonationSubscriptions();
        subs.set("old", DonationSubscription.FAILED);

        // 재시도가 옮기기 직전에 성공한 모양 — 옛 열쇠에 최신 값이 들어와 있다.
        subs.set("old", DonationSubscription.SUBSCRIBED);
        subs.retarget("old", "new");

        assertThat(subs.of("new"))
                .as("옮기기가 읽어 둔 낡은 값을 설치하면 그 방송이 영영 failed 로 보인다")
                .isEqualTo(DonationSubscription.SUBSCRIBED);
        assertThat(subs.of("old"))
                .as("옛 자리는 비워야 한다 — 끝난 방송 번호가 창구 메모리에 남는다")
                .isEqualTo(DonationSubscription.NONE);
    }
}
