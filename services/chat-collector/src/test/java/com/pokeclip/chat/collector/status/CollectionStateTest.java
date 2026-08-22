package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.StopReason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안쪽 상태를 밖으로 나가는 이름 여섯으로 옮기는 규칙. <b>스프링을 안 띄운다</b> —
 * 순수 함수 셋이라 컨텍스트가 잴 것이 없다.
 *
 * <p><b>다중 세션 문항</b>({@code .claude/skills/multi-session-test-reality}) — 여기에는
 * 세션도 스레드도 없다. 문항 1·3은 <b>잴 대상이 없어</b> 해당하지 않는다(재 보지 않은 것이
 * 아니다). 문항 2·4·5는 검사마다 주석으로 답을 남겼다.
 */
class CollectionStateTest {

    // 문항 4: 다섯 중 넷만 맞고 하나가 UNKNOWN으로 새는 구현도 「어떤」 단언 하나만 보면 통과한다 —
    //         다섯 갈래를 다 본다. switch가 exhaustive라 State가 늘면 컴파일이 먼저 막는다.
    // 문항 5: RECONNECTING 갈래를 COLLECTING으로 되돌리면 넷째 줄이 빨간불(확인함).
    @Test
    void 세션_상태_다섯이_응답_상태로_빠짐없이_간다() throws Exception {
        assertThat(CollectionState.of(snap(CollectionStatus.State.DISABLED))).isEqualTo(CollectionState.ESTABLISHING);
        assertThat(CollectionState.of(snap(CollectionStatus.State.ESTABLISHING))).isEqualTo(CollectionState.ESTABLISHING);
        assertThat(CollectionState.of(snap(CollectionStatus.State.COLLECTING))).isEqualTo(CollectionState.COLLECTING);
        assertThat(CollectionState.of(snap(CollectionStatus.State.RECONNECTING))).isEqualTo(CollectionState.RECONNECTING);
        assertThat(CollectionState.of(snap(CollectionStatus.State.STOPPED))).isEqualTo(CollectionState.STOPPED);
    }

    // 문항 4: 둘만 못박으면 <b>나머지 넷의 이름이 바뀌어도 초록</b>이다. 이 여섯 글자가
    //         2번(web)·clip과의 약속이라 열거값 이름을 고치는 순간 밖으로 나가는 글자가 조용히 바뀐다 —
    //         여섯을 다 리터럴로 못박아 그 변경이 이 검사를 지나가지 못하게 한다.
    @Test
    void 밖으로_나가는_이름은_소문자다() throws Exception {
        assertThat(CollectionState.ESTABLISHING.wireName()).isEqualTo("establishing");
        assertThat(CollectionState.COLLECTING.wireName()).isEqualTo("collecting");
        assertThat(CollectionState.RECONNECTING.wireName()).isEqualTo("reconnecting");
        assertThat(CollectionState.STOPPED.wireName()).isEqualTo("stopped");
        assertThat(CollectionState.ENDED.wireName()).isEqualTo("ended");
        assertThat(CollectionState.UNKNOWN.wireName()).isEqualTo("unknown");
    }

    // 재연동이 필요한 넷만 true. 나머지 전부 false — 열거값이 늘어도 기본은 false다.
    // 문항 2: 「늘 false」는 넷에서, 「늘 true」는 나머지에서 빨간불이라 한쪽으로 공짜가 안 된다.
    //
    // <b>이 검사가 허용 목록의 잠금장치다.</b> @EnumSource 전수라 StopReason에 값을 더하면
    // 여기가 반드시 같이 빨간불이 된다 — LINK_UNAVAILABLE을 넣었을 때 실제로 그랬다(봇 1판 C1).
    // 기대값을 구현에서 뽑아 오면(needsRelink를 그대로 부르면) 그 잠금이 사라지므로 <b>리터럴로</b> 둔다.
    //
    // 오버로드 <b>둘 다</b> 여기서 잰다. 아래 검사가 글자 오버로드를 세 값으로만 보는데
    // 그중 「아는 이름이면서 false여야 하는 값」이 하나도 없어, 글자 쪽만 「아는 이름이면 무조건
    // true」로 바꿔도 모듈 486검사가 전부 초록이었다(2026-08-22 감사 주입 I11c, 이 줄을 넣기 전에
    // 재현함). 그런데 <b>창구가 실제로 부르는 쪽이 글자 오버로드다</b> — 열거값 쪽은 등록부가
    // 지워지기 직전 밀리초짜리 찰나에만 쓰이고, 메모는 24시간 살아 clip이 사실상 늘 그것을 본다.
    // 회귀하면 우리 버그(SEND_MISUSE)로 멈춘 방송에 「치지직 연동을 다시 하세요」가 24시간 뜬다.
    @ParameterizedTest
    @EnumSource(StopReason.class)
    void 재연동_필요는_토큰거부_구독거부_철회_연동없음_넷뿐이다(StopReason reason) {
        // LINK_UNAVAILABLE만 세션 단계가 아니다 — auth가 열쇠를 영구히 거절해 붙어 보지도
        // 못한 것이다. 스트리머가 할 일(치지직 연동을 손본다)이 셋과 같아 같은 답으로 둔다.
        boolean expected = reason == StopReason.SESSION_AUTH_REJECTED
                || reason == StopReason.SUBSCRIBE_REJECTED
                || reason == StopReason.REVOKED
                || reason == StopReason.LINK_UNAVAILABLE;
        assertThat(CollectionState.needsRelink(reason)).isEqualTo(expected);
        assertThat(CollectionState.needsRelink(reason.name()))
                .as("메모 경로(글자)도 같은 답이어야 한다 — clip이 24시간 보는 쪽이다")
                .isEqualTo(expected);
    }

    // 메모 표의 사유는 <b>글자</b>다 — 열거값 이름을 바꾸거나 지운 뒤에도 옛 이름이 표에 남아 있다.
    // 문항 4: 셋째 줄(양성 대조)이 없으면 「무조건 false」인 구현도 통과한다.
    @Test
    void 메모에_적힌_모르는_사유_이름은_재연동_아님으로_읽는다() throws Exception {
        assertThat(CollectionState.needsRelink("OLD_NAME_FROM_PAST")).isFalse();
        assertThat(CollectionState.needsRelink((String) null)).isFalse();
        assertThat(CollectionState.needsRelink("REVOKED")).isTrue();
    }

    private static CollectionStatus.Snapshot snap(CollectionStatus.State state) {
        return new CollectionStatus.Snapshot(state, null, null, 0);
    }
}
