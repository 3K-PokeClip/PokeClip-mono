package com.pokeclip.clip.segment;

/**
 * 편집기 소리 주소 목록에 실을 조각 한 줄. {@link StreamSegmentRow}와 다른 점은 시각 축이다 — 저쪽은 파일 안 위치
 * ({@code start_pts_ms}, 파일마다 리셋)이고 이쪽은 <b>재생 축 절대 시각</b>({@code playback_pdt}, 없으면
 * {@code start_wall_utc}, UTC epoch ms)이다. 편집기가 보는 시각이 재생 축이라 레시피의 {@code cut}도 그 축이다(계약6 0절).
 *
 * <p>{@code s3Key}가 들어 있다 — 사람용 응답에 그대로 실으면 안 된다(소리 주소 서명 전용 — 응답에는 서명 주소만 싣는다).
 */
public record SegmentSource(long seq, long startAtMs, int durationMs, String s3Key, String uploadState) {
}
