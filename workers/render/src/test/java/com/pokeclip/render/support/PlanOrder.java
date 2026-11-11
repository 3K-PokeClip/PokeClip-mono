package com.pokeclip.render.support;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 새 꼴 렌더 주문서(PDT 설계 r7 5.1 · 5.2)를 짠다. 봉투 · 레시피는 {@link Fixtures#envelope}와 같고, {@code sourceKeys}를
 * 새 꼴(역할 셋 · {@code sourceStartAtMs} 없음)로 바꾸고 {@code plan}을 단다.
 *
 * <p>{@code plan.mediaSeqs}는 따로 주지 않으면 {@code segment} 항목의 seq 순서 그대로다. 조각 길이는 모두 4000ms다.
 */
public final class PlanOrder {

    public static final long DURATION_MS = 4000;

    private final ObjectNode envelope;
    private final ArrayNode sources;
    private final ObjectNode plan;
    private final List<Long> segmentSeqs = new ArrayList<>();
    private boolean mediaSeqsGiven;

    private PlanOrder(UUID jobId) {
        envelope = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        sources = envelope.putArray("sourceKeys");
        plan = envelope.putObject("plan");
        plan.put("planVersion", 1);
        plan.put("offsetOrigin", "FILE_HEAD");
        plan.putArray("subtitles");
        plan.put("lengthMs", 5000);
    }

    public static PlanOrder of(UUID jobId) {
        return new PlanOrder(jobId);
    }

    /** {@code role=segment} 항목. 두 표지는 주문서 그대로 싣는다. */
    public PlanOrder segment(long seq, boolean isDiscontinuity, boolean followsLedgerPrevious) {
        ObjectNode s = file("segment", seq);
        s.put("isDiscontinuity", isDiscontinuity);
        s.put("followsLedgerPrevious", followsLedgerPrevious);
        segmentSeqs.add(seq);
        return this;
    }

    /** {@code role=audio_prime} 항목 — 묶음 머리 {@code ownerSeq}의 디코더 준비 조각. */
    public PlanOrder prime(long seq, long ownerSeq) {
        file("audio_prime", seq).put("ownerSeq", ownerSeq);
        return this;
    }

    /** {@code role=audio_capture} 항목 — 마지막 조각 뒤 같은 녹화 이웃. */
    public PlanOrder capture(long seq, boolean isDiscontinuity, boolean followsLedgerPrevious) {
        ObjectNode s = file("audio_capture", seq);
        s.put("isDiscontinuity", isDiscontinuity);
        s.put("followsLedgerPrevious", followsLedgerPrevious);
        return this;
    }

    public PlanOrder in(long seq, long offsetUs) {
        plan.putObject("in").put("seq", seq).put("offsetUs", offsetUs);
        return this;
    }

    public PlanOrder out(long seq, long offsetUs) {
        plan.putObject("out").put("seq", seq).put("offsetUs", offsetUs);
        return this;
    }

    /** 끝 = 그 조각 마지막 프레임까지. */
    public PlanOrder outToSegmentEnd(long seq) {
        plan.putObject("out").put("seq", seq).put("toSegmentEnd", true);
        return this;
    }

    public PlanOrder mediaSeqs(long... seqs) {
        ArrayNode a = plan.putArray("mediaSeqs");
        for (long seq : seqs) {
            a.add(seq);
        }
        mediaSeqsGiven = true;
        return this;
    }

    /** 매체 자리에서 매체 자리까지인 자막. */
    public PlanOrder subtitleAtMedia(int index, long startSeq, long startUs, long endSeq, long endUs) {
        ObjectNode s = subtitle(index);
        s.putObject("start").put("seq", startSeq).put("offsetUs", startUs);
        s.putObject("end").put("seq", endSeq).put("offsetUs", endUs);
        return this;
    }

    /** 두 끝이 모두 틈 안 자리인 자막. */
    public PlanOrder subtitleInGap(int index, long startAfterSeq, long startIntoMs, long endAfterSeq, long endIntoMs) {
        ObjectNode s = subtitle(index);
        s.putObject("start").put("afterSeq", startAfterSeq).put("intoGapMs", startIntoMs);
        s.putObject("end").put("afterSeq", endAfterSeq).put("intoGapMs", endIntoMs);
        return this;
    }

    public PlanOrder lengthMs(long lengthMs) {
        plan.put("lengthMs", lengthMs);
        return this;
    }

    /** 고쳐 넣을 봉투 원본. {@code plan.mediaSeqs}를 채운 뒤 돌려준다. */
    public ObjectNode json() {
        if (!mediaSeqsGiven) {
            ArrayNode a = plan.putArray("mediaSeqs");
            segmentSeqs.forEach(a::add);
        }
        return envelope;
    }

    public String body() {
        return Fixtures.MAPPER.writeValueAsString(json());
    }

    private ObjectNode file(String role, long seq) {
        ObjectNode s = sources.addObject();
        s.put("role", role);
        s.put("bucket", Fixtures.BUCKET);
        s.put("s3Key", "streams/plantest/seg_" + seq + ".m4s");
        s.put("seq", seq);
        s.put("durationMs", DURATION_MS);
        return s;
    }

    private ObjectNode subtitle(int index) {
        ObjectNode s = ((ArrayNode) plan.get("subtitles")).addObject();
        s.put("index", index);
        return s;
    }
}
