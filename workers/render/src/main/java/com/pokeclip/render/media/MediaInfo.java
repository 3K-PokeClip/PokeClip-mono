package com.pokeclip.render.media;

/**
 * ffprobe로 잰 조각 하나. {@code width}·{@code height}는 회전을 반영한 표시 치수다(계약6 crop 좌표계 = 표시 평면).
 *
 * @param audioStreams    소리 스트림 수. 트랙 번호 N = N번째 소리 스트림이다(계약9: 6트랙 고정, audio1이 트랙0)
 * @param videoDurationMs 영상 스트림 길이. 조각 장부의 {@code duration_ms}가 이 값이다(인덱서가 영상 상자로 잰다)
 * @param audioLeadUs     파일 첫 시각에서 소리가 시작하기까지(마이크로초). 보통 0이다
 * @param audioDurationUs 첫 소리 스트림 길이(마이크로초). 조각을 이을 때 이 값이 다음 조각의 자리를 정한다
 */
public record MediaInfo(int width, int height, int audioStreams, long videoDurationMs, long audioLeadUs,
                        long audioDurationUs) {

    /** 이 조각의 소리가 끝나는 자리(파일 첫 시각 기준, 마이크로초). */
    public long audioEndUs() {
        return audioLeadUs + audioDurationUs;
    }
}
