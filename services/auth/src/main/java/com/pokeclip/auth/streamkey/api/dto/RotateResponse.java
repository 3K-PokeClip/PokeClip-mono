package com.pokeclip.auth.streamkey.api.dto;

import java.time.Instant;

/** 새 키 값은 실리지 않는다. 사람은 페어링 코드로만 받는다(ADR-019). */
public record RotateResponse(Instant rotatedAt) {
}
