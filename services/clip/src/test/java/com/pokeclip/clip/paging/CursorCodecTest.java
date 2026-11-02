package com.pokeclip.clip.paging;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이어받기 표시를 감싸고 푸는 자리. 목록 둘이 나눠 쓴다.
 *
 * <p><b>재는 것은 「돌려 푼다」가 아니라 「안 받아 준다」다.</b> 왕복 갈래 둘은 양성 대조이고,
 * 나머지는 전부 거절이다 — 이 클래스의 값은 <b>웹이 만들어 보낸 문자열을 우리가 정한 모양으로
 * 좁히는 것</b>에 있다.
 */
class CursorCodecTest {

    /** 아무 값이나 감싸는 자리 — 이 클래스 밖에서는 못 만드는 모양을 시험이 직접 만든다. */
    private static String 감싼다(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ── 양성 대조: 이것이 없으면 「전부 거절」인 구현이 나머지를 다 통과한다 ──

    @Test
    void 감싼_것을_그대로_돌려_푼다() {
        String 표시 = CursorCodec.encode(CursorCodec.Kind.BROADCAST, 42L);

        assertThat(CursorCodec.decode(CursorCodec.Kind.BROADCAST, 표시)).containsExactly(42L);
    }

    /** 카드 문은 값이 <b>둘</b>이다(방송 시간 + 줄 번호). 둘이 함께 오는 것이 이 문의 계약이다. */
    @Test
    void 카드_커서는_두_값을_돌려_푼다() {
        String 표시 = CursorCodec.encode(CursorCodec.Kind.CARD, 1000L, 7L);

        assertThat(CursorCodec.decode(CursorCodec.Kind.CARD, 표시)).containsExactly(1000L, 7L);
    }

    /** 웹이 풀어 보지 않기를 바라는 값이라 <b>표시 자체는 원래 값과 다르게 생겨야</b> 한다. */
    @Test
    void 감싼_표시는_원래_값과_다르게_생겼다() {
        String 표시 = CursorCodec.encode(CursorCodec.Kind.BROADCAST, 42L);

        assertThat(표시).isNotEqualTo("b:42").doesNotContain(":");
    }

    // ── 거절 ────────────────────────────────────────────────────

    @Test
    void 형식이_깨졌으면_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, "!!!not-base64!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 빈_문자열도_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, ""))
                .isInstanceOf(InvalidCursorException.class);
    }

    /** 풀리기는 하는데 우리가 아는 모양이 아니다 — base64가 통과했다고 값이 우리 것은 아니다. */
    @Test
    void 유효한_base64인데_우리_모양이_아니면_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, 감싼다("hello")))
                .isInstanceOf(InvalidCursorException.class);
    }

    /**
     * 🔴 <b>종류 태그가 있는 이유 그 자체다.</b> 태그가 없으면 방송 목록에서 받은 표시를
     * 카드 문에 넣어도 <b>숫자 하나로 읽혀 통과한다</b> — 그러면 그 방송의 카드가
     * 엉뚱한 자리부터 나온다. 두 문의 표시가 같은 base64 알파벳을 쓰므로 컴파일러도 못 잡는다.
     */
    @Test
    void 방송_커서를_카드_문에_넣으면_거절한다() {
        String 방송_표시 = CursorCodec.encode(CursorCodec.Kind.BROADCAST, 42L);

        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.CARD, 방송_표시))
                .isInstanceOf(InvalidCursorException.class);
    }

    /** 반대 방향도 막는다 — 한쪽만 막으면 「같은 뿌리인데 한 자리만 고침」이 된다. */
    @Test
    void 카드_커서를_방송_문에_넣으면_거절한다() {
        String 카드_표시 = CursorCodec.encode(CursorCodec.Kind.CARD, 1000L, 7L);

        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, 카드_표시))
                .isInstanceOf(InvalidCursorException.class);
    }

    /**
     * 🔴 <b>칸 수를 세는 이유.</b> 카드 조회의 이어받기 조건은
     * {@code (ts = :afterTs AND id > :afterId)}인데 {@code afterId}가 비면 그 절이 NULL로
     * 평가돼 <b>같은 방송 시간의 뒷줄이 조용히 빠진다</b>(계획 검증 실측: 2건이 와야 하는데 1건).
     * 태그만 보고 칸 수를 안 세면 그 커서가 여기를 그냥 지나간다.
     */
    @Test
    void 카드_커서에_칸이_하나뿐이면_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.CARD, 감싼다("c:1000")))
                .isInstanceOf(InvalidCursorException.class);
    }

    /** 칸이 남아도 거절한다 — 「모자라면」만 막으면 늘어난 쪽이 조용히 통과한다. */
    @Test
    void 칸이_남아도_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, 감싼다("b:42:99")))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 숫자가_아닌_칸은_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, 감싼다("b:abc")))
                .isInstanceOf(InvalidCursorException.class);
    }

    /**
     * {@code long} 범위를 넘는 값도 같은 거절이다. {@code NumberFormatException}이 그대로
     * 새어 나가면 전역 조언이 그것을 400으로 안 잡아 <b>500</b>이 된다.
     */
    @Test
    void long_범위를_넘는_값도_거절한다() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST,
                감싼다("b:99999999999999999999")))
                .isInstanceOf(InvalidCursorException.class);
    }

    /**
     * 🔴 <b>거절이 {@code IllegalArgumentException}으로 새면 안 된다.</b> 전역 조언은 그것을
     * 일부러 안 잡으므로(내부 버그가 400으로 둔갑하는 것을 막는 규칙) 그대로 500이 된다.
     * {@code Base64}가 던지는 것이 바로 그 타입이라 여기서 반드시 갈아입혀야 한다.
     */
    @Test
    void 거절은_전부_InvalidCursorException_한_타입이다() {
        List<String> 깨진_것들 = List.of("!!!not-base64!!!", "", 감싼다("hello"),
                감싼다("b:abc"), 감싼다("b:42:99"));

        assertThat(깨진_것들).allSatisfy(값 ->
                assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.Kind.BROADCAST, 값))
                        .isExactlyInstanceOf(InvalidCursorException.class));
    }
}
