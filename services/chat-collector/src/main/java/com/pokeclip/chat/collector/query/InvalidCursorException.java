package com.pokeclip.chat.collector.query;

/**
 * 우리가 만든 커서가 아니다. 400 {@code {"error":"cursor"}}로 나간다.
 *
 * <p><b>첫 장(빈 커서)과 가른다.</b> 남이 지어낸 커서를 조용히 첫 장으로 접으면, 페이징이
 * 어긋난 자리에서 <b>같은 장을 영원히 다시 주는 것</b>이 정상 동작처럼 보인다.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
