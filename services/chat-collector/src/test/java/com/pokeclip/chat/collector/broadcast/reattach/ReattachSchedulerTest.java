package com.pokeclip.chat.collector.broadcast.reattach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * <b>한 회차의 성패가 health가 읽는 자리에 실제로 옮겨지는가.</b>
 *
 * <p>🔴 <b>이 배선이 이 카드의 새 사각을 막는 유일한 자리다.</b> {@link Reattacher#sweep()}은
 * {@code @Scheduled}를 지키려고 어떤 실패든 삼킨다 — 그래서 clip에 몇 시간을 못 닿아도
 * 로그 말고는 아무 흔적이 없다. 스케줄러가 그 반환값을 {@link ReattachStatus}에 옮기지
 * 않으면 <b>health는 영원히 {@code starting}이거나 {@code ok}</b>이고, health 검사는
 * 상태를 손으로 넣어 재므로 <b>그것을 못 잡는다.</b>
 */
class ReattachSchedulerTest {

    private final Reattacher reattacher = mock(Reattacher.class);
    private final ReattachStatus status = new ReattachStatus(true);
    private final ReattachScheduler scheduler = new ReattachScheduler(reattacher, status);

    @Test
    void 회차가_실패하면_health가_읽는_자리에_남는다() {
        given(reattacher.sweep()).willReturn(false);

        scheduler.tick();

        assertThat(status.state()).isEqualTo(ReattachStatus.State.FAILING);
    }

    /**
     * <b>회복이 표시를 지운다.</b> 안 지우면 한 번 못 닿은 뒤로 영영 아프다고 말하고,
     * 그러면 이 항을 아무도 안 보게 된다.
     *
     * <p>문항 2: 「늘 {@code OK}」인 구현도 마지막 단언은 통과한다 — 그래서 실패를 먼저
     * 세워 두고 그것이 <b>지워지는 것</b>을 본다.
     */
    @Test
    void 다시_성공하면_아팠던_표시가_지워진다() {
        given(reattacher.sweep()).willReturn(false, true);

        scheduler.tick();
        assertThat(status.state()).isEqualTo(ReattachStatus.State.FAILING);

        scheduler.tick();
        assertThat(status.state()).isEqualTo(ReattachStatus.State.OK);
    }

    /**
     * 🔴 <b>못 읽은 스트리머 수도 같이 옮긴다.</b> 1번이 식별자 체계를 바꾸면 <b>모든 방송이
     * 이 길</b>인데, 그 값이 {@link Reattacher} 안에만 있으면 로그를 세는 것 말고는 밖에서
     * 볼 방법이 없다 — 로그로는 「체계가 바뀌었다」와 「한 건 이상했다」가 구분되지 않는다.
     * 알림 경로의 같은 이름 카운터는 이미 health에 실린다(감사 라운드 3 H2, 쌍둥이 중 한쪽만이었다).
     *
     * <p>문항 2: 「한 번만 옮기는」 구현도 첫 단언은 통과한다 — <b>값이 늘어나는 것</b>까지 본다.
     * 회차가 통째로 실패해도 옮긴다: 목록을 받은 뒤 못 읽은 것을 세고서 던졌을 수 있다.
     */
    @Test
    void 못_읽은_스트리머_수도_health가_읽는_자리에_옮겨진다() {
        given(reattacher.sweep()).willReturn(true, false);
        given(reattacher.unreadableStreamerIds()).willReturn(3L, 7L);

        scheduler.tick();
        assertThat(status.unreadableStreamerIds()).isEqualTo(3L);

        scheduler.tick();
        assertThat(status.unreadableStreamerIds())
                .as("회차가 실패해도 그 회차까지 센 값은 옮겨야 한다")
                .isEqualTo(7L);
    }

    /** 부팅 직후 첫 회차 전에는 「꺼짐」이 아니라 「아직 안 돎」이다 — 둘을 뭉치면 못 가른다. */
    @Test
    void 첫_회차_전에는_아직_안_돈_상태다() {
        assertThat(status.state()).isEqualTo(ReattachStatus.State.STARTING);
        assertThat(status.unreadableStreamerIds()).isZero();
        assertThat(new ReattachStatus(false).state()).isEqualTo(ReattachStatus.State.DISABLED);
    }
}
