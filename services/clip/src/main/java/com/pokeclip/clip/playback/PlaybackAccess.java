package com.pokeclip.clip.playback;

import java.time.Instant;
import java.util.Map;

/**
 * 발급된 출입증 한 장. {@code cookies}는 CloudFront가 정한 이름 셋
 * ({@code CloudFront-Policy} · {@code CloudFront-Signature} · {@code CloudFront-Key-Pair-Id}) → 값.
 *
 * @param resource 정책이 여는 범위. 웹이 디버그할 때 보는 값이고 비밀이 아니다(쿠키 값이 비밀이다)
 */
public record PlaybackAccess(String streamId, Instant expiresAt, String resource, Map<String, String> cookies) {
}
