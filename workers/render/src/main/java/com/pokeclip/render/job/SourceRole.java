package com.pokeclip.render.job;

import java.util.Arrays;
import java.util.Optional;

/** 주문서 {@code sourceKeys[].role}(PDT 설계 r7 3.3 · 5.2). */
public enum SourceRole {
    /** 결과에 드는 조각. */
    SEGMENT("segment"),
    /** 마지막 조각 뒤 같은 녹화 이웃. 끝 소리를 채우는 데만 쓴다. */
    AUDIO_CAPTURE("audio_capture"),
    /** 소리 묶음 머리 앞 같은 녹화 파일. 디코더 준비에만 쓴다. */
    AUDIO_PRIME("audio_prime");

    private final String json;

    SourceRole(String json) {
        this.json = json;
    }

    static Optional<SourceRole> of(String json) {
        return Arrays.stream(values()).filter(r -> r.json.equals(json)).findFirst();
    }
}
