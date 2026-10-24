package com.pokeclip.clip.broadcast;

import java.time.Instant;

/**
 * 방송 목록 한 줄의 켜진 플랫폼 하나와 그 플랫폼 시청자 수(POK-397). 응답 {@code platforms[]}의 원소 그대로다.
 *
 * @param viewers    수집기가 그 플랫폼에서 마지막으로 본 시청자 수. {@code null}은 「모른다」이지 0명이 아니다
 * @param observedAt 그 값을 본 시각(수집기 시계). 모르면 {@code null}. 화면은 오래된 값이면 그 플랫폼을 꺼진 것으로 본다
 */
public record PlatformViewers(String platform, Integer viewers, Instant observedAt) {
}
