package com.pokeclip.clip.broadcast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라이브 띠의 「편집자 N명 접속 중」(POK-398) 셈 규칙. 재료는 그 방송 대시보드 연결마다의 회원 번호(JWT {@code sub} 원문)다.
 * 같은 사람의 여러 탭은 1명, 스트리머 본인과 묻는 사람은 뺀다. 번호는 숫자로 읽어 비교한다: 자격 판정이 {@code parseLong}으로
 * 받으므로 {@code "007"}도 연결이 되고, 문자열로 비교하면 같은 사람이 둘로 세어진다.
 */
class EditorsOnlineCountTest {

    private static final long 요청자 = 4174L;

    @Test
    void 같은_사람의_여러_연결은_한_명이다() {
        assertThat(BroadcastListService.countEditors(List.of("11", "11", "12"), "7", 요청자)).isEqualTo(2);
    }

    @Test
    void 표기가_달라도_같은_번호면_한_명이다() {
        assertThat(BroadcastListService.countEditors(List.of("11", "011"), "7", 요청자)).isEqualTo(1);
    }

    @Test
    void 스트리머_본인은_안_센다() {
        assertThat(BroadcastListService.countEditors(List.of("7", "11"), "7", 요청자)).isEqualTo(1);
    }

    /** 줄의 스트리머 번호는 문자열이라, 선행 0이 붙은 연결 번호와도 숫자로 맞춰 뺀다. */
    @Test
    void 스트리머_번호도_숫자로_맞춰_뺀다() {
        assertThat(BroadcastListService.countEditors(List.of("007", "11"), "7", 요청자)).isEqualTo(1);
    }

    @Test
    void 묻는_사람은_안_센다() {
        assertThat(BroadcastListService.countEditors(List.of("4174", "4174", "11"), "7", 요청자)).isEqualTo(1);
    }

    /** 숫자가 아닌 번호는 자격 판정을 못 지나 연결이 될 수 없지만, 들어오면 셈에서 뺀다(죽지 않는다). */
    @Test
    void 숫자가_아닌_번호는_셈에서_뺀다() {
        assertThat(BroadcastListService.countEditors(List.of("abc", "", "11"), "7", 요청자)).isEqualTo(1);
    }

    /** 줄의 스트리머 번호가 숫자가 아니면 뺄 사람이 없다. 나머지는 그대로 센다. */
    @Test
    void 스트리머_번호가_숫자가_아니어도_죽지_않는다() {
        assertThat(BroadcastListService.countEditors(List.of("7", "11"), "x", 요청자)).isEqualTo(2);
    }

    @Test
    void 아무도_없으면_0이다() {
        assertThat(BroadcastListService.countEditors(List.of(), "7", 요청자)).isZero();
    }
}
