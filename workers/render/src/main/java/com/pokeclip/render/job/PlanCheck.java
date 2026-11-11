package com.pokeclip.render.job;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 렌더 계획 검사(PDT 설계 r7 5.3 규칙 2–6). 봉투 안 값만 보므로 preflight이고, 어기면 {@link PlanViolation}이다. 규칙 1(새 꼴 ·
 * 옛 꼴 판별 · 원점 표지)은 {@link EnvelopeParser} · {@link PlanReader}가 읽으면서 본다.
 *
 * <p>렌더는 장부를 읽지 않는다. 그래서 「준비 조각이 장부 바로 앞 행인가」 · 「틈 끝점이 회차 바로 앞 조각인가」는 꼴과 자리만
 * 보고, 행 자체는 clip이 보장한다(설계 5.3 끝 문단 · 9.4).
 */
final class PlanCheck {

    /** 좌표 오프셋 상한 = 조각 길이 + 이만큼(설계 5.3 4). 파일 머리 원점이 영상 첫 DTS보다 앞설 수 있는 몫이다. */
    static final long OFFSET_SLACK_US = 1_000_000;

    /**
     * ms 칸(조각 길이 · 틈 안 거리 · 예상 길이) 상한. µs로 바꾸고 {@link #OFFSET_SLACK_US}를 더해도 long에 담기는 가장 큰
     * 값이다 — 오프셋 상한 산식이 감겨 거대 · 음수 길이가 통과하지 않게 하는 구조 상한이지 정책 값이 아니다.
     */
    static final long MAX_MS = (Long.MAX_VALUE - OFFSET_SLACK_US) / 1000;

    private PlanCheck() {
    }

    /**
     * @param sources        주문서 {@code sourceKeys} 순서 그대로
     * @param recipeSubtitles 레시피 자막 구간 수
     */
    static void check(RenderPlan plan, List<SourceSegment> sources, int recipeSubtitles) {
        durations(sources);
        List<SourceSegment> media = sources.stream().filter(s -> s.ref().role() == SourceRole.SEGMENT).toList();
        mediaSeqs(plan, media);
        // 매체 열은 위에서 엄격히 오름 · segment 항목과 하나씩 맞물림을 봤다 — seq 로 찾는 표가 겹치지 않는다.
        Map<Long, Integer> positions = new HashMap<>();
        Map<Long, SourceSegment> mediaBySeq = new HashMap<>();
        for (int i = 0; i < media.size(); i++) {
            positions.put(media.get(i).seq(), i);
            mediaBySeq.put(media.get(i).seq(), media.get(i));
        }
        capture(sources, media);
        primes(sources, plan.mediaSeqs(), positions, mediaBySeq);
        range(plan, media);
        subtitles(plan, recipeSubtitles, mediaBySeq);
        if (plan.lengthMs() < 1 || plan.lengthMs() > MAX_MS) {
            throw new PlanViolation(6, "plan.lengthMs가 1 이상 상한 이하가 아니다");
        }
    }

    /** 규칙 4 의 앞 조건: 모든 역할의 {@code durationMs} 는 0 이상 {@link #MAX_MS} 이하다(오프셋 상한 산식이 넘치지 않게). */
    private static void durations(List<SourceSegment> sources) {
        for (SourceSegment s : sources) {
            if (s.durationMs() < 0 || s.durationMs() > MAX_MS) {
                throw new PlanViolation(4, "sourceKeys[].durationMs가 범위 밖이다");
            }
        }
    }

    /** 규칙 2: 매체 열은 비지 않고 엄격히 오르며 segment 항목과 하나씩 맞물린다. 처음 = in · 끝 = out. */
    private static void mediaSeqs(RenderPlan plan, List<SourceSegment> media) {
        List<Long> seqs = plan.mediaSeqs();
        if (seqs.isEmpty()) {
            throw new PlanViolation(2, "plan.mediaSeqs가 비었다");
        }
        for (int i = 1; i < seqs.size(); i++) {
            if (seqs.get(i) <= seqs.get(i - 1)) {
                throw new PlanViolation(2, "plan.mediaSeqs가 엄격히 오르지 않는다");
            }
        }
        if (!seqs.equals(media.stream().map(SourceSegment::seq).toList())) {
            throw new PlanViolation(2, "plan.mediaSeqs가 sourceKeys의 segment 항목과 맞물리지 않는다");
        }
        if (seqs.getFirst() != plan.in().seq() || seqs.getLast() != plan.out().seq()) {
            throw new PlanViolation(2, "plan.mediaSeqs의 처음 · 끝이 plan.in · plan.out과 다르다");
        }
    }

    /** 규칙 3 앞 절(r6 4.4 8): 확보 조각은 많아야 하나 · 맨 끝 · 마지막 매체 뒤 같은 녹화 이웃. */
    private static void capture(List<SourceSegment> sources, List<SourceSegment> media) {
        for (int i = 0; i < sources.size(); i++) {
            SourceSegment s = sources.get(i);
            if (s.ref().role() != SourceRole.AUDIO_CAPTURE) {
                continue;
            }
            if (i != sources.size() - 1) {
                throw new PlanViolation(3, "audio_capture는 sourceKeys 맨 끝 하나뿐이다");
            }
            if (s.seq() <= media.getLast().seq() || s.isDiscontinuity() || !s.followsLedgerPrevious()) {
                throw new PlanViolation(3, "audio_capture가 마지막 매체의 같은 녹화 이웃이 아니다");
            }
        }
    }

    /**
     * 규칙 3 뒤 절: 준비 조각 p 마다 머리 o 가 매체 열 안 소리 묶음 머리(첫 매체이거나 J 거짓)이고 새 녹화가 아니며, p 는 o 앞
     * (첫 매체가 아니면 o 바로 앞 매체 뒤)에 있고, 한 머리에 하나뿐이며 sourceKeys에서 o 항목 바로 앞이다. 첫 매체는 표지와
     * 무관하게 늘 머리다 — 머리 표지 {@code followsLedgerPrevious}를 이 검사에 쓰지 않는다.
     */
    private static void primes(List<SourceSegment> sources, List<Long> mediaSeqs, Map<Long, Integer> positions,
                               Map<Long, SourceSegment> mediaBySeq) {
        Set<Long> owners = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            SourceSegment p = sources.get(i);
            if (p.ref().role() != SourceRole.AUDIO_PRIME) {
                continue;
            }
            long owner = p.ref().ownerSeq();
            SourceSegment o = mediaBySeq.get(owner);
            if (o == null) {
                throw new PlanViolation(3, "audio_prime의 ownerSeq가 매체 열에 없다");
            }
            int at = positions.get(owner);
            boolean head = at == 0 || !o.joinsPrevious();
            if (!head || o.isDiscontinuity()) {
                throw new PlanViolation(3, "audio_prime의 머리가 같은 녹화로 이어지는 묶음 머리가 아니다");
            }
            if (p.seq() >= owner || (at > 0 && p.seq() <= mediaSeqs.get(at - 1))) {
                throw new PlanViolation(3, "audio_prime의 seq가 머리와 그 앞 매체 사이가 아니다");
            }
            if (!owners.add(owner)) {
                throw new PlanViolation(3, "한 머리에 audio_prime이 둘이다");
            }
            if (i + 1 >= sources.size() || !sources.get(i + 1).ref().equals(o.ref())) {
                throw new PlanViolation(3, "audio_prime이 머리 항목 바로 앞에 있지 않다");
            }
        }
    }

    /** 규칙 4: in < out(사전 순 · toSegmentEnd는 그 조각의 무한대) · 오프셋은 0 이상 조각 길이 + 1초 이하. */
    private static void range(RenderPlan plan, List<SourceSegment> media) {
        if (!plan.out().isAfter(plan.in())) {
            throw new PlanViolation(4, "plan.in이 plan.out보다 앞이 아니다");
        }
        offset(plan.in().offsetUs(), media.getFirst(), "plan.in");
        if (plan.out().offsetUs() != null) {
            offset(plan.out().offsetUs(), media.getLast(), "plan.out");
        }
    }

    private static void offset(long offsetUs, SourceSegment segment, String where) {
        if (!inside(offsetUs, segment)) {
            throw new PlanViolation(4, where + ".offsetUs가 조각 밖이다");
        }
    }

    /** {@code 0 ≤ offsetUs ≤ durationMs × 1000 + 1초}. {@link #durations}가 길이를 묶어 산식이 넘치지 않는다. */
    private static boolean inside(long offsetUs, SourceSegment segment) {
        return offsetUs >= 0 && offsetUs <= segment.durationMs() * 1000 + OFFSET_SLACK_US;
    }

    /**
     * 규칙 5: 번호는 유일하고 레시피 구간 수 미만. 매체 끝점은 매체 열 안이고 오프셋은 그 조각의 규칙 4 범위 안, 틈 끝점은
     * 매체 열의 조각이거나 첫 매체 앞 조각(장부 seq 는 0 부터라 음수는 아니다)이고 틈 안 거리는 0 이상 {@link #MAX_MS} 이하.
     * 「첫 매체 앞 조각」 이 바로 앞 회차 seq 인지는 장부를 읽지 않아 보지 못한다(clip 보장 · 설계 5.3 「5 의 보장 나눔」).
     * 끝점 순서는 보지 않는다(결과 0 이하면 그 자막만 뺀다 · P18).
     */
    private static void subtitles(RenderPlan plan, int recipeSubtitles, Map<Long, SourceSegment> mediaBySeq) {
        Set<Integer> indexes = new HashSet<>();
        for (SubtitleSpan span : plan.subtitles()) {
            if (span.index() < 0 || span.index() >= recipeSubtitles || !indexes.add(span.index())) {
                throw new PlanViolation(5, "plan.subtitles[].index " + span.index() + "가 겹치거나 레시피 밖이다");
            }
            point(span.start(), plan, mediaBySeq);
            point(span.end(), plan, mediaBySeq);
        }
    }

    private static void point(SubtitlePoint point, RenderPlan plan, Map<Long, SourceSegment> mediaBySeq) {
        switch (point) {
            case SubtitlePoint.AtMedia m -> {
                SourceSegment segment = mediaBySeq.get(m.at().seq());
                if (segment == null) {
                    throw new PlanViolation(5, "plan.subtitles[] 끝점이 매체 열 밖을 가리킨다");
                }
                if (!inside(m.at().offsetUs(), segment)) {
                    throw new PlanViolation(5, "plan.subtitles[] 끝점의 offsetUs가 조각 밖이다");
                }
            }
            case SubtitlePoint.InGap g -> {
                boolean known = mediaBySeq.containsKey(g.afterSeq()) || g.afterSeq() < plan.in().seq();
                if (g.afterSeq() < 0 || !known) {
                    throw new PlanViolation(5, "plan.subtitles[] 끝점이 매체 열 밖을 가리킨다");
                }
                if (g.intoGapMs() < 0 || g.intoGapMs() > MAX_MS) {
                    throw new PlanViolation(5, "plan.subtitles[] 끝점의 intoGapMs가 범위 밖이다");
                }
            }
        }
    }
}
