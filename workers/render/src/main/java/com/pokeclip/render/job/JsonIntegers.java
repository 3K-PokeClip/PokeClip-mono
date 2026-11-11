package com.pokeclip.render.job;

import tools.jackson.databind.JsonNode;

/**
 * 주문서 정수 칸이 자바 정수에 정확히 담기는가. {@code isIntegralNumber()}만 보고 {@code asLong()} · {@code asInt()}를 부르면
 * 범위 밖 값에서 Jackson 예외가 나 preflight 거절({@link RenderFailure})로 바뀌지 않고 큐 루프 오류로 샌다. 그러면 종결 보고도
 * 메시지 삭제도 없이 다시 받기 · 실패 큐로 간다. 그래서 정수 칸은 모두 이 확인을 지난 뒤에만 변환한다(레시피 파서와 같은 확인).
 */
final class JsonIntegers {

    private JsonIntegers() {
    }

    /** 정수이고 long 에 담긴다. */
    static boolean isLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    /** 정수이고 int 에 담긴다. */
    static boolean isInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }
}
