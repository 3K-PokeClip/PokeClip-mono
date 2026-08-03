package com.pokeclip.auth.streamkey;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamIdTest {

    private static final String TOKEN = "7ZK3M9QW2XJ4NB6TC8VDFG5HRP";

    @Test
    void SRT_표준_문법으로_조립한다() {
        assertThat(new StreamId(TOKEN).toSrtFormat())
                .isEqualTo("#!::r=" + TOKEN + ",m=publish");
    }

    @Test
    void 조립한_것을_다시_파싱하면_같은_토큰이다() {
        assertThat(StreamId.parse(new StreamId(TOKEN).toSrtFormat()))
                .contains(new StreamId(TOKEN));
    }

    /** m=publish가 아니면 송출 요청이 아니다. 재생 모드로 들어오는 것을 막는다. */
    @Test
    void publish_모드가_아니면_파싱하지_않는다() {
        assertThat(StreamId.parse("#!::r=" + TOKEN + ",m=request")).isEmpty();
    }

    @Test
    void 형식이_깨졌으면_파싱하지_않는다() {
        assertThat(StreamId.parse(TOKEN)).isEmpty();
        assertThat(StreamId.parse("#!::m=publish")).isEmpty();
        assertThat(StreamId.parse("")).isEmpty();
        assertThat(StreamId.parse(null)).isEmpty();
    }

    /** 토큰에 Crockford 밖 문자가 있으면 우리가 발급한 것이 아니다. */
    @Test
    void 토큰이_Crockford가_아니면_파싱하지_않는다() {
        assertThat(StreamId.parse("#!::r=ABC!@#,m=publish")).isEmpty();
    }

    /** 키 순서를 바꿔 보내는 구현이 있다. 둘 다 받는다. */
    @Test
    void 키_순서가_바뀌어도_읽는다() {
        assertThat(StreamId.parse("#!::m=publish,r=" + TOKEN)).contains(new StreamId(TOKEN));
    }
}
