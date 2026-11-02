package com.pokeclip.render.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * -14 LUFS 평준화(계약6 0절: 레시피 칸이 아니라 렌더 상수). 두 번 돈다: 먼저 재고, 잰 값으로 선형 보정한다.
 * 한 번에 하면 loudnorm이 동적 압축으로 떨어져 소리가 출렁인다.
 */
public final class Loudness {

    static final String TARGET = "I=-14:TP=-1.5:LRA=11";

    private Loudness() {
    }

    /** 첫 번째(측정) 필터. 결과는 표준에러 끝의 JSON 한 덩어리다. */
    public static String measureFilter() {
        return "loudnorm=" + TARGET + ":print_format=json";
    }

    /**
     * 잰 값으로 두 번째(보정) 필터를 만든다. 무음이면 측정값이 {@code -inf}라 보정할 것이 없다. null.
     */
    public static String correctFilter(String stderr, ObjectMapper mapper) {
        int end = stderr.lastIndexOf('}');
        int start = stderr.lastIndexOf('{', end);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("loudnorm 측정값을 못 찾았다");
        }
        JsonNode m = mapper.readTree(stderr.substring(start, end + 1));
        String i = m.path("input_i").asString("");
        String tp = m.path("input_tp").asString("");
        String lra = m.path("input_lra").asString("");
        String thresh = m.path("input_thresh").asString("");
        String offset = m.path("target_offset").asString("");
        if (!finite(i) || !finite(tp) || !finite(lra) || !finite(thresh) || !finite(offset)) {
            return null;
        }
        return "loudnorm=" + TARGET + ":measured_I=" + i + ":measured_TP=" + tp + ":measured_LRA=" + lra
                + ":measured_thresh=" + thresh + ":offset=" + offset + ":linear=true";
    }

    private static boolean finite(String value) {
        try {
            return Double.isFinite(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
