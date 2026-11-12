package com.pokeclip.clip.render;

import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.segment.CutDecision.End;
import com.pokeclip.clip.segment.CutDecision.LedgerNeighbor;
import com.pokeclip.clip.segment.CutDecision.Prime;
import com.pokeclip.clip.segment.CutDecision.Ready;
import com.pokeclip.clip.segment.CutDecision.SubtitlePoint;
import com.pokeclip.clip.segment.CutDecision.SubtitleSpan;
import com.pokeclip.clip.segment.MediaCoord;
import com.pokeclip.clip.segment.RoundRow;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 주문서(계약1 2절 봉투)를 만든다. 칸 이름이 계약이다 — 일꾼(렌더 워커)이 이 이름으로 읽는다.
 *
 * <p><b>{@code sourceKeys}는 계약1 3절 초안과 다르다.</b> 초안은 1번이 만들 「중간본 파일」(video 1 + audio N)을
 * 전제했는데 그 파일은 아직 없고 렌더 일꾼도 우리 것이 됐다(2026-09-14). 그래서 조각 장부의 <b>조각 파일 목록을
 * 그대로</b> 싣는다 — 조각 하나가 영상+소리 트랙 전부를 담은 완전한 fMP4라(실측 2026-09-15: {@code ftyp+moov+moof…})
 * 일꾼이 이어붙이면 된다. 트랙 번호 ↔ 소리 스트림 대응은 방송의 {@code trackManifest}를 같이 실어 일꾼이 푼다.
 * 이 결정은 계약1 갱신(위키)으로 남긴다.
 *
 * <p><b>새 꼴(POK-196 · PDT 설계 r7 5.1 · 5.2).</b> 컷 판정({@code CutPlanner})의 계획을 {@code plan} 으로 싣고, 조각은 역할
 * 셋({@code segment} · {@code audio_prime} · {@code audio_capture})으로 싣는다 — {@code sourceStartAtMs} 는 없다. 렌더 계획
 * 검사가 fail-closed 라 칸 묶음이 역할마다 정해져 있다: 준비 조각에는 두 표지({@code isDiscontinuity} ·
 * {@code followsLedgerPrevious})를 싣지 않고 {@code ownerSeq} 를 싣는다. 끝은 {@code offsetUs} 와 {@code toSegmentEnd} 가운데
 * 하나만, 자막이 없어도 {@code subtitles} 는 빈 배열이다.
 */
final class RenderEnvelope {

    static final int SCHEMA_VERSION = 1;

    /** 렌더 계획 판(설계 r7 5.2 {@code plan.planVersion}). */
    static final int PLAN_VERSION = 1;

    private RenderEnvelope() {
    }

    static String build(ObjectMapper mapper, UUID jobId, long clipId, String streamId, int recipeVersion,
                        RecipeDocument recipe, Ready plan, String segmentBucket, String outputBucket,
                        String trackManifestJson, Instant requestedAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("jobId", jobId.toString());
        root.put("jobType", "RENDER");
        root.put("clipId", String.valueOf(clipId));
        root.put("correlationId", String.valueOf(clipId));
        root.put("idempotencyKey", clipId + ":RENDER:" + recipeVersion);
        root.put("requestedAt", requestedAt.toString());
        root.put("streamId", streamId);
        root.put("recipeVersion", recipeVersion);
        root.put("outputPrefix", "s3://" + outputBucket + "/" + outputPrefixKey(clipId));
        sourceKeys(root.putArray("sourceKeys"), plan, segmentBucket);
        root.set("trackManifest", trackManifestJson == null ? mapper.nullNode() : mapper.readTree(trackManifestJson));
        plan(root.putObject("plan"), plan);
        root.set("recipe", mapper.valueToTree(recipe));
        return mapper.writeValueAsString(root);
    }

    /** 완성 영상이 놓이는 자리(버킷 안 키). 실행마다 그 아래 {@code {executionToken}/}이 붙는다. */
    static String outputPrefixKey(long clipId) {
        return "clips/" + clipId;
    }

    /**
     * 매체 조각을 순서대로 싣고, 묶음 머리의 준비 조각은 그 머리 항목 바로 앞에, 끝 소리 확보 조각은 맨 끝에 둔다(렌더 5.3 규칙 3).
     * 첫 매체의 {@code followsLedgerPrevious} 는 거짓으로 고친다 — 주문 안 바로 앞 매체가 없다(K5 · 회차 목록에서는 참일 수 있다).
     */
    private static void sourceKeys(ArrayNode sources, Ready plan, String bucket) {
        Map<Long, LedgerNeighbor> primes = new HashMap<>();
        for (Prime prime : plan.primes()) {
            primes.put(prime.ownerSeq(), prime.row());
        }
        for (int i = 0; i < plan.media().size(); i++) {
            RoundRow row = plan.media().get(i);
            LedgerNeighbor prime = primes.get(row.seq());
            if (prime != null) {
                ObjectNode node = file(sources, "audio_prime", bucket, prime.s3Key(), prime.seq(), prime.durationMs());
                node.put("ownerSeq", row.seq());
            }
            flagged(sources, "segment", bucket, row, i > 0 && row.followsLedgerPrevious());
        }
        plan.capture().ifPresent(row -> flagged(sources, "audio_capture", bucket, row, row.followsLedgerPrevious()));
    }

    private static void flagged(ArrayNode sources, String role, String bucket, RoundRow row, boolean follows) {
        ObjectNode node = file(sources, role, bucket, row.s3Key(), row.seq(), row.durationMs());
        node.put("isDiscontinuity", row.isDiscontinuity());
        node.put("followsLedgerPrevious", follows);
    }

    private static ObjectNode file(ArrayNode sources, String role, String bucket, String s3Key, long seq,
                                   int durationMs) {
        ObjectNode node = sources.addObject();
        node.put("role", role);
        node.put("bucket", bucket);
        node.put("s3Key", s3Key);
        node.put("seq", seq);
        node.put("durationMs", durationMs);
        return node;
    }

    private static void plan(ObjectNode node, Ready plan) {
        node.put("planVersion", PLAN_VERSION);
        node.put("offsetOrigin", plan.offsetOrigin().name());
        coord(node.putObject("in"), plan.in());
        end(node.putObject("out"), plan.out());
        ArrayNode seqs = node.putArray("mediaSeqs");
        plan.media().forEach(row -> seqs.add(row.seq()));
        ArrayNode subtitles = node.putArray("subtitles");
        for (SubtitleSpan span : plan.subtitles()) {
            ObjectNode s = subtitles.addObject();
            s.put("index", span.index());
            point(s.putObject("start"), span.start());
            point(s.putObject("end"), span.end());
        }
        node.put("lengthMs", plan.lengthMs());
    }

    private static void coord(ObjectNode node, MediaCoord at) {
        node.put("seq", at.seq());
        node.put("offsetUs", at.offsetUs());
    }

    /** 끝 — {@code offsetUs} 와 {@code toSegmentEnd} 는 둘 중 하나만 싣는다(렌더 5.2 상호 배제). */
    private static void end(ObjectNode node, End out) {
        node.put("seq", out.seq());
        if (out.toSegmentEnd()) {
            node.put("toSegmentEnd", true);
        } else {
            node.put("offsetUs", out.offsetUs());
        }
    }

    private static void point(ObjectNode node, SubtitlePoint point) {
        switch (point) {
            case SubtitlePoint.AtMedia m -> coord(node, m.at());
            case SubtitlePoint.InGap g -> {
                node.put("afterSeq", g.afterSeq());
                node.put("intoGapMs", g.intoGapMs());
            }
        }
    }
}
