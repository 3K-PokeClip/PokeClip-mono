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
}
