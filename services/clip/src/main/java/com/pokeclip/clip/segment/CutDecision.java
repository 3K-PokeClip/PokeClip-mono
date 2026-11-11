package com.pokeclip.clip.segment;

import java.util.List;
import java.util.Optional;

/**
 * 컷 판정({@link CutPlanner}) 결과 — 렌더 주문을 낼 계획 · 아직 준비 안 됨(409) · 종국 거절(422) 셋 가운데 하나(POK-196 · 설계 r7
 * 9.2 · 9.5). 주문서에 옮겨 쓰는 일은 부르는 쪽(렌더 주문 배선) 몫이다.
 */
public sealed interface CutDecision {

    /**
     * 주문을 낼 계획. 좌표 원점은 늘 {@code FILE_HEAD}(편집기 출처 — 경계 표 5판 B6).
     *
     * @param in 시작 프레임 지시
     * @param out 끝 경계
     * @param media 결과에 드는 회차 행, seq 순(주문서 {@code mediaSeqs} · {@code segment} 항목)
     * @param subtitles 컷과 겹친 레시피 자막만, 레시피 순서
     * @param lengthMs 예상 결과 길이 — 관측에만 쓰고 거절에 쓰지 않는다
     * @param capture 끝 소리 확보 조각({@code audio_capture})
     * @param primes 소리 묶음 머리의 디코더 준비 조각({@code audio_prime}) — 머리마다 많아야 하나
     * @param primeSkips 같은 녹화인데 준비 조각을 쓰지 못한 머리(열화 — {@code clip.render.prime_skipped})
     * @param gaps 원래 범위가 걸친 끊긴 이웃(로그 · 이음 방식이 하나라 주문서에 싣지 않는다)
     * @param runHeads 소리 묶음 머리와 그 종류(로그 · 집계 분모)
     */
    record Ready(MediaCoord in, End out, List<RoundRow> media, List<SubtitleSpan> subtitles, long lengthMs,
                 Optional<RoundRow> capture, List<Prime> primes, List<PrimeSkip> primeSkips, List<Gap> gaps,
                 List<RunHead> runHeads) implements CutDecision {
        public Ready {
            media = List.copyOf(media);
            subtitles = List.copyOf(subtitles);
            primes = List.copyOf(primes);
            primeSkips = List.copyOf(primeSkips);
            gaps = List.copyOf(gaps);
            runHeads = List.copyOf(runHeads);
        }
    }

    /** 기다리면 된다(409 {@code source_not_ready}). {@code reason}은 진단용 고정 문자열이다. */
    record NotReady(String reason) implements CutDecision {
    }

    /** 다시 물어도 같다(422 {@code source_unrenderable}). */
    record Unrenderable(Reason reason) implements CutDecision {
    }

    /** 422 {@code reason} 닫힌 목록(설계 r7 9.5) — 늘리면 웹 선배포가 먼저다. */
    enum Reason {
        /** 회차 밖 · 녹화 밖. */
        OUTSIDE_RECORDING("outside_recording"),
        /** 컷 안에 영상 조각이 없음. */
        NO_FOOTAGE("no_footage");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** 끝 경계. {@code offsetUs}가 null이면 그 조각 마지막 프레임까지({@code toSegmentEnd}). */
    record End(long seq, Long offsetUs) {
        public boolean toSegmentEnd() {
            return offsetUs == null;
        }
    }

    /** 자막 끝점 — 매체 자리이거나, 끊긴 틈 안 자리(앞 조각 {@code afterSeq} 끝에서 {@code intoGapMs}). */
    sealed interface SubtitlePoint {
        record AtMedia(MediaCoord at) implements SubtitlePoint {
        }

        record InGap(long afterSeq, long intoGapMs) implements SubtitlePoint {
        }
    }

    /** @param index 레시피 자막 목록 안 자리 */
    record SubtitleSpan(int index, SubtitlePoint start, SubtitlePoint end) {
    }

    /**
     * 끊긴 이웃 하나 — 컷 안 자리 · 전체 틈 · 컷 안 몫(이어 붙여 결과에서 빠지는 길이). 틈이 0 이하면 컷 안 몫도 0 이다. 자리 이름이
     * {@code Location} 인 것은 공통 모듈의 장부 자리 {@code Place}(F 의 결과)와 가르려는 것이다.
     */
    record Gap(long beforeSeq, long afterSeq, Location location, long gapMs, long inCutMs) {
        public enum Location { HEAD, MIDDLE, TAIL }
    }

    /** 소리 묶음 머리. */
    record RunHead(long seq, Kind kind) {
        public enum Kind {
            /** 같은 녹화 흐름 — 장부 바로 앞 행이 있고 머리 {@code is_discontinuity} 가 거짓. */
            CONTINUED("continued"),
            /** 진짜 새 녹화 — 머리 {@code is_discontinuity} 가 참. */
            NEW_RECORDING("new_recording"),
            /** 장부에 앞 행이 없다 — 이 방송 안에서 진짜 시작. */
            NO_PREV_ROW("no_prev_row");

            private final String wire;

            Kind(String wire) {
                this.wire = wire;
            }

            public String wire() {
                return wire;
            }
        }
    }

    /** 장부 바로 앞 행(디코더 준비 후보). 읽는 칸은 이것뿐이다(설계 r7 9.1 읽기 5). */
    record LedgerNeighbor(long seq, long wallMs, int durationMs, String uploadState, String s3Key) {
    }

    /** 머리 {@code ownerSeq} 묶음의 디코더 준비 조각. */
    record Prime(long ownerSeq, LedgerNeighbor row) {
    }

    /** 쓰지 못한 준비 조각. {@code reason}: {@code failed} · {@code other_broadcast}. */
    record PrimeSkip(long ownerSeq, long seq, String reason) {
    }
}
