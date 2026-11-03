package com.pokeclip.clip.render;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 원본 조각 주소 한 벌(POK-389). 편집기가 조각을 받아 소리 트랙을 하나씩 꺼내 미리 듣는다.
 *
 * <p>창고 좌표({@code s3Key})는 싣지 않는다: 화면은 주소만 있으면 받는다.
 *
 * <p><b>축 표지(POK-196 · 설계 r7 9.6).</b> 요청 표지 {@code axis=fileHead} 를 그 축으로 처리했을 때만 뿌리에 {@code axis} 메아리와
 * {@code audioCapture} · {@code gapEvidence} 를, 조각마다 {@code role} · {@code isDiscontinuity} · {@code followsLedgerPrevious} 를
 * 싣는다. 웹은 메아리가 있을 때만 새 갈래를 쓴다(칸 유무가 신호가 아니다). 표지가 없으면 이 칸들이 빠져 develop 응답과 같고,
 * 조각마다 {@code startWallUtcMs} 만 더 실린다(두 갈래 모두). 빈 칸은 싣지 않는다 — 옛 응답 바이트를 지키려는 것이다.
 *
 * @param axis 메아리 — {@code "fileHead"} 또는 없음
 * @param audioCapture 끝 소리 확보 후보 상태 — {@code attached}(확보 조각을 실음) · {@code not_ready}(아직 준비 안 됨) ·
 *                     {@code skipped}(없이 진행)
 * @param gapEvidence 확정 공백 — 창 안 회차 행 이웃 가운데 같은 녹화로 이어지지 않은 쌍. 쌍만 싣는다(모든 틈을 이어 붙인다)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SegmentAudioAccess(Instant expiresAt, List<Segment> segments, String axis, String audioCapture,
                                 List<GapPair> gapEvidence) {

    /** 표지 없는 응답(develop 꼴). */
    public SegmentAudioAccess(Instant expiresAt, List<Segment> segments) {
        this(expiresAt, segments, null, null, null);
    }

    /**
     * @param startAtMs 재생 축 시각(UTC epoch ms). 편집본의 {@code cut}과 같은 축이라 화면이 그대로 맞춘다. 표지가 있어도 뜻을
     *                  바꾸지 않는다
     * @param url       미리서명 주소. 조각 파일 하나 전체(모든 소리 트랙)를 준다
     * @param startWallUtcMs 파일명 시각({@code start_wall_utc} ms 내림) — 편집기 {@code /get} 축. 두 갈래 모두 싣는다
     * @param role {@code segment} 또는 {@code audio_capture}(끝 소리 확보 조각) — 표지 갈래만
     * @param isDiscontinuity 장부 {@code is_discontinuity} — 표지 갈래만
     * @param followsLedgerPrevious 응답의 바로 앞 조각이 장부에서 이 조각의 바로 앞 행인가(첫 조각 거짓) — 표지 갈래만
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(long seq, long startAtMs, int durationMs, String url, long startWallUtcMs, String role,
                          Boolean isDiscontinuity, Boolean followsLedgerPrevious) {

        Segment withUrl(String signed) {
            return new Segment(seq, startAtMs, durationMs, signed, startWallUtcMs, role, isDiscontinuity,
                    followsLedgerPrevious);
        }
    }

    /** 확정 공백 한 쌍. */
    public record GapPair(long beforeSeq, long afterSeq) {
    }
}
