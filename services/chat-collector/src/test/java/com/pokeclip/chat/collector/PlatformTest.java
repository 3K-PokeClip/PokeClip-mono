package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.session.SessionKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 창구의 {@code ?platform=} 같은 바깥 값을 플랫폼으로 읽는 규칙.
 *
 * <p>값이 없으면 치지직이다: 플랫폼 칸이 생기기 전의 auth는 플랫폼을 안 보낸다. 소문자를 받지 않는 이유는 표의 CHECK가
 * 대문자만 받기 때문이다(V313). 여기서 소문자를 받아 대문자로 바꿔 주면 「soop」과 「SOOP」이 같은 것으로 보이다가 다른 자리에서
 * 그대로 쓰일 때 갈린다.
 */
class PlatformTest {

    @Test
    void 값이_없거나_비면_치지직이다() {
        assertThat(Platform.parse(null)).contains(Platform.CHZZK);
        assertThat(Platform.parse("")).contains(Platform.CHZZK);
    }

    @Test
    void 대문자_이름과_정확히_같을_때만_그_플랫폼이다() {
        assertThat(Platform.parse("CHZZK")).contains(Platform.CHZZK);
        assertThat(Platform.parse("SOOP")).contains(Platform.SOOP);
    }

    @Test
    void 소문자_공백_섞임_모르는_이름은_없음이다() {
        assertThat(Platform.parse("soop")).isEqualTo(Optional.empty());
        assertThat(Platform.parse("Soop")).isEqualTo(Optional.empty());
        assertThat(Platform.parse(" SOOP")).isEqualTo(Optional.empty());
        assertThat(Platform.parse("X Y")).isEqualTo(Optional.empty());
        assertThat(Platform.parse("YOUTUBE")).isEqualTo(Optional.empty());
        assertThat(Platform.parse(" ")).isEqualTo(Optional.empty());
    }

    /** 옛 경로(CHZZK_ENABLED)는 치지직 설정 토큰으로만 붙는다. */
    @Test
    void 옛_경로_세션은_치지직이다() {
        assertThat(SessionKey.legacy().platform()).isEqualTo(Platform.CHZZK);
    }
}
