package com.pokeclip.clip.segment;

import com.pokeclip.clip.segment.CutDecision.End;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 컷 끝 소리 확보 — 판 2 에서 컷 끝이 영상 경계 바로 앞이면 다음 조각 머리의 소리(최대 m)가 소재에서 빠지므로, 같은 녹화로 이어진
 * 다음 seq 행을 「소리 확보 조각」 으로 더한다(계약-세그먼트인덱스 6-6-8 · 설계 r7 9.4). 렌더 주문({@link CutPlanner})과 편집기 소리
 * 주소 API 가 이 판정 <b>하나</b>를 같이 쓴다(6-6-8 8항).
 *
 * <p>순서가 계약이다 — 거리 조건 → 다음 행 유무 → 이어짐 → 가용성. 거리 조건이 거짓이면 다음 행을 묻지도 않는다.
 * <ul>
 *   <li>판 2: 거리 조건 {@code out 오프셋 > dur(k) − H}(H = 1000ms — 같은 녹화기 둘째 이후 조각의 머리 앞섬 상한). 렌더 주문의
 *       판 2 컷 좌표는 영상 첫 표본 기준({@code VIDEO_START} — kty 2026-11-12 D1)이라 계약 그대로다. 소리 주소 표지 갈래는 파일명
 *       시각 축 좌표(파일 머리 기준)라 그만큼(m) 보수적이다.</li>
 *   <li>판 NULL: 거리 조건을 보지 않는다 — 다음 행이 녹화 이어짐이면 늘 싣는다.</li>
 *   <li>다음 행이 {@code failed}: 종국이 아니라 스위퍼가 회수한다. 그 행 파일명 시각에서 {@link #CAPTURE_FAILED_GRACE} 넘게
 *       지나도록 {@code failed} 면 확보 없이 진행한다 — 두 판 모두(영구 409 를 없앰).</li>
 *   <li>다음 행이 없음: 회차가 끝났으면(clip 방송 {@code ended} 이거나 회차 완결 술어 참 — 6-6-8 5항) 진행, 아니면 기다린다.</li>
 * </ul>
 */
public final class AudioCaptureRule {

    /** H — 같은 녹화기 둘째 이후 조각의 머리 앞섬 상한(계약 6-6-7). */
    static final long HEAD_LEAD_LIMIT_MS = 1000;

    /** 확보 후보가 {@code failed} 로 남아도 기다리는 시간. 스위퍼(30초 주기) 회수가 여러 번 돌 시간이다. */
    static final Duration CAPTURE_FAILED_GRACE = Duration.ofMinutes(15);

    private AudioCaptureRule() {
    }

    /** 판정 결과. */
    public sealed interface Outcome {
        /** 확보 조각으로 싣는다. */
        record Attach(RoundRow row) implements Outcome {
        }

        /** 확보 조각 없이 진행한다. */
        record Proceed() implements Outcome {
        }

        /** 후보가 문턱 넘게 {@code failed} 라 확보 없이 진행한다({@code clip.render.capture_skipped}). */
        record FailedSkip(RoundRow row, long ageMinutes) implements Outcome {
        }

        /** 아직 준비 안 됨 — 기다린다. */
        record Wait() implements Outcome {
        }
    }

    /**
     * @param last 결과에 드는 마지막 회차 행 k
     * @param out 컷 끝 경계(k 위 · 판 원점 기준 — 판 2 영상 첫 표본 · 판 NULL 파일 머리)
     * @param next k 의 다음 seq 행(회차로 자르지 않음). 거리 조건이 참일 때만 부른다
     * @param endedOrComplete k 의 회차가 끝났나 — clip 방송 {@code ended} 이거나 회차 완결 술어 참(6-6-8 5항). 다음 행이 장부에 없을
     *        때만 묻는다(회차 완결은 장부 왕복이다)
     * @param now 지금 — {@code failed} 문턱을 잰다
     */
    public static Outcome decide(RoundRow last, End out, Supplier<Optional<RoundRow>> next,
                                 BooleanSupplier endedOrComplete, Instant now) {
        if (last.v2() && !nearSegmentEnd(last, out)) {
            return new Outcome.Proceed();
        }
        Optional<RoundRow> candidate = next.get();
        if (candidate.isEmpty()) {
            return endedOrComplete.getAsBoolean() ? new Outcome.Proceed() : new Outcome.Wait();
        }
        RoundRow row = candidate.get();
        if (row.isDiscontinuity()) {
            return new Outcome.Proceed();
        }
        if (row.uploaded()) {
            return new Outcome.Attach(row);
        }
        if (RoundRow.FAILED.equals(row.uploadState())) {
            Duration age = Duration.between(Instant.ofEpochMilli(row.wallMs()), now);
            if (age.compareTo(CAPTURE_FAILED_GRACE) > 0) {
                return new Outcome.FailedSkip(row, age.toMinutes());
            }
        }
        return new Outcome.Wait();
    }

    /** 판 2 거리 조건 — 컷 끝이 조각 끝에서 H 안쪽(엄격 부등식). 조각 끝까지면 늘 참이다. */
    private static boolean nearSegmentEnd(RoundRow last, End out) {
        if (out.toSegmentEnd()) {
            return true;
        }
        return out.offsetUs() / 1000 > last.durationMs() - HEAD_LEAD_LIMIT_MS;
    }
}
