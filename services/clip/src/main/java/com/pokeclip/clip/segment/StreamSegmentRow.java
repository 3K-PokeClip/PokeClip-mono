package com.pokeclip.clip.segment;

/**
 * {@code stream_segments} 한 줄. <b>내부 모델이다</b> — {@code s3Key}가 들어 있으므로
 * 이 record를 사람용 응답 본문에 그대로 실으면 안 된다(응답 record는 따로 만든다).
 *
 * <p>표의 열한 칸 중 <b>여섯만</b> 담는다. 조회에 안 쓰는 칸(로컬 경로·업로드 시각·크기)까지
 * 끌고 오면 1번의 표에 우리가 더 넓게 묶인다 — 읽는 칸이 곧 우리가 의존한다고 선언하는 범위다.
 */
public record StreamSegmentRow(long seq, long startPtsMs, int durationMs,
                               String s3Key, String uploadState, boolean discontinuity) {
}
