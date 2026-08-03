package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.StreamKeyException;
import com.pokeclip.auth.streamkey.StreamKeyFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스프링을 띄우지 않는다. 매핑이 순수 함수라 그럴 이유가 없다.
 *
 * <p>이 테스트가 T5에 있는 이유: 핸들러는 T5 커밋에 들어오는데 T5의 통합
 * 테스트가 태우는 것은 429 하나뿐이다. 나머지 네 사유는 T6·T7에서야 HTTP로
 * 검증되므로, 그때까지 매핑이 틀려 있어도 아무도 모른다.
 */
class StreamKeyExceptionHandlerTest {

    private final StreamKeyExceptionHandler handler = new StreamKeyExceptionHandler();

    @Test
    void 사유마다_정해진_상태_코드로_내보낸다() {
        assertThat(statusOf(StreamKeyFailure.PAIRING_CODE_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(StreamKeyFailure.STREAM_KEY_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(StreamKeyFailure.PAIRING_CODE_EXPIRED)).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(StreamKeyFailure.PAIRING_CODE_ALREADY_USED)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(StreamKeyFailure.PAIRING_CODE_RATE_LIMITED))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * 사유를 나누어 내보내는 것이 POK-72 인수 기준이다. 본문에서 reason이
     * 사라지면 2번(웹)이 "만료됐으니 새로 받으세요"와 "이미 썼습니다"를
     * 구분해 보여줄 수 없다.
     */
    @Test
    void 본문에_사유_이름이_실린다() {
        ResponseEntity<Map<String, String>> response = handle(StreamKeyFailure.PAIRING_CODE_EXPIRED);

        assertThat(response.getBody()).containsEntry("reason", "PAIRING_CODE_EXPIRED");
    }

    /** 예외 메시지는 한국어이고 거부된 값이 딸려 올 수 있다. 본문에 넣지 않는다. */
    @Test
    void 본문에_예외_메시지가_실리지_않는다() {
        ResponseEntity<Map<String, String>> response = handler.handle(new StreamKeyException(
                StreamKeyFailure.PAIRING_CODE_NOT_FOUND, "모르는 코드다 ABCD-EFGH"));

        assertThat(response.getBody()).doesNotContainValue("모르는 코드다 ABCD-EFGH");
        assertThat(response.getBody().values()).noneMatch(v -> v.contains("ABCD-EFGH"));
    }

    /**
     * 새 사유를 enum에 더하고 매핑을 빠뜨리는 것을 막는다. switch가 enum을
     * 전부 다루지 않으면 컴파일이 안 되지만, default를 넣는 변경이 들어오면
     * 그 방어가 사라진다. 여기서 한 번 더 잡는다.
     */
    @Test
    void 모든_사유가_매핑된다() {
        for (StreamKeyFailure failure : StreamKeyFailure.values()) {
            assertThat(statusOf(failure)).as(failure.name() + "에 상태 코드가 없다").isNotNull();
        }
    }

    private HttpStatus statusOf(StreamKeyFailure failure) {
        return HttpStatus.valueOf(handle(failure).getStatusCode().value());
    }

    private ResponseEntity<Map<String, String>> handle(StreamKeyFailure failure) {
        return handler.handle(new StreamKeyException(failure, "테스트"));
    }
}
