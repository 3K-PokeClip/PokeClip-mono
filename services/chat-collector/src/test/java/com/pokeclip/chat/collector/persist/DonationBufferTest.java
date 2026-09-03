package com.pokeclip.chat.collector.persist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatBuffer}의 쌍둥이다 — 규칙이 글자 그대로 같아야 한다.
 *
 * <p><b>버린 수를 세는 것이 특히 중요하다.</b> 후원은 아카이브에 안 쌓으므로
 * (검산 등식을 지키려고, {@code StreamSession} 주석) 여기서 버려지면
 * 표에도 원본에도 없다 — 범위 창구가 메워 줄 길이 없는 유일한 유실이다.
 */
class DonationBufferTest {

    private static PersistableDonation donation(int i) {
        return new PersistableDonation("s", "CH", "D" + i, "n", "CHAT", 1L, "t", 1000L + i);
    }

    @Test
    void 상한을_넘으면_가장_오래된_것부터_버리고_센다() {
        DonationBuffer buffer = new DonationBuffer(2);
        buffer.offer(donation(1));
        buffer.offer(donation(2));
        buffer.offer(donation(3));

        assertThat(buffer.droppedCount())
                .as("안 세면 되찾을 길 없는 유실이 로그 0줄로 지나간다")
                .isEqualTo(1);
        assertThat(buffer.drain(10))
                .extracting(PersistableDonation::donatorChannelId)
                .as("최근 것이 판별에 더 가치 있다 — 오래된 쪽을 버린다")
                .containsExactly("D2", "D3");
    }

    @Test
    void 되돌린_배치가_앞으로_간다() {
        DonationBuffer buffer = new DonationBuffer(10);
        buffer.offer(donation(3));
        buffer.restoreFront(java.util.List.of(donation(1), donation(2)));

        assertThat(buffer.drain(10))
                .extracting(PersistableDonation::donatorChannelId)
                .as("뒤로 되돌리면 시각 순서가 뒤집혀 상한 초과가 더 새 후원을 버린다")
                .containsExactly("D1", "D2", "D3");
    }
}
