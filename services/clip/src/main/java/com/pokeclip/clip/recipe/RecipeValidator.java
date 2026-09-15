package com.pokeclip.clip.recipe;

import com.pokeclip.clip.recipe.RecipeDocument.Crop;
import com.pokeclip.clip.recipe.RecipeDocument.Output;
import com.pokeclip.clip.recipe.RecipeDocument.Segment;
import com.pokeclip.clip.recipe.RecipeDocument.Track;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 계약6 <b>2층 검증</b>(3번 Clip — 1절 카디널리티 + 2절 규칙). 1층(편집기)이 이미 막았을 것을 다시 재고,
 * 3층(렌더)이 가진 픽셀식(종횡비 ±1%)은 여기서 <b>못 잰다</b> — 원본 해상도가 렌더 주문 때야 생긴다(계약6 3절).
 *
 * <p>거절은 전부 {@link RecipeErrors.InvalidRecipeException}이고 {@code field}는 <b>어느 덩어리</b>인지만
 * 말한다({@code cut}·{@code outputs}·{@code audio}·{@code subtitles}). 몇 번째 원소의 어느 값인지까지
 * 말하지 않는 것은 편집기(1층)가 같은 규칙을 이미 알고 있어서다 — 여기까지 온 거절은 편집기 버그거나
 * 손으로 만든 요청이다.
 *
 * <p>규칙마다 계약6 절 번호를 적어 둔다. <b>규칙을 바꾸는 권한은 1번(스키마 승인권)이다</b> — 여기 숫자를
 * 바꾸는 것은 계약6을 바꾼 뒤다.
 */
@Component
public class RecipeValidator {

    /** 지금 받는 유일한 모양. v2가 생기면 이 클래스가 갈래를 갖는다. */
    static final int SCHEMA_VERSION = 1;

    /** 계약6 2절 cut — 5초 이상 180초 이하(kty 확정 2026-08-24, Shorts 상한). */
    static final long MIN_CUT_MS = 5_000;
    static final long MAX_CUT_MS = 180_000;

    /** 계약6 1절 — {@code outputId} 형식. */
    private static final Pattern OUTPUT_ID = Pattern.compile("[a-z0-9-]{1,32}");

    /** 계약6 2절 outputs — v1 enum 둘뿐. 상하분할·장면캡쳐는 v2다. */
    private static final Set<String> ASPECTS = Set.of("VERT_9_16", "SQUARE_1_1");

    /** 계약6 2절 crop — 극소 crop 하한(rev6). */
    static final double MIN_CROP_SIDE = 0.05;

    /** 계약6 2절 audio — 0=최종 믹스, 1~5=소스별(ADR-017) · gain 0.0~2.0(rev3). */
    static final int MAX_TRACK_ID = 5;
    static final double MAX_GAIN = 2.0;

    /** 계약6 2절 subtitles — ADR-009가 확정한 3종. */
    private static final Set<String> SUBTITLE_MODES = Set.of("BURN_AND_CC", "BURN_ONLY", "CC_ONLY");

    /**
     * @param streamId 경로의 방송 번호. 본문의 {@code streamId}와 같아야 한다 — 다르면 편집기가 다른 방송의
     *                 화면에서 보낸 것이고, 어느 쪽을 믿어야 할지 서버가 정하면 안 된다
     * @throws RecipeErrors.InvalidRecipeException 어느 규칙이든 어긋나면. 첫 번째 어긋난 덩어리 이름을 싣는다
     */
    public void validate(RecipeDocument recipe, String streamId) {
        if (recipe.schemaVersion() == null || recipe.schemaVersion() != SCHEMA_VERSION) {
            throw invalid("schemaVersion");
        }
        if (recipe.streamId() == null || !recipe.streamId().equals(streamId)) {
            throw invalid("streamId");
        }
        validateCut(recipe.cut());
        validateOutputs(recipe.outputs());
        validateAudio(recipe.audio());
        validateSubtitles(recipe.subtitles());
    }

    /** 계약6 2절 cut. {@code null}은 템플릿이라 통과. 있으면 두 값 다 있고 순서·길이가 맞아야 한다. */
    private void validateCut(RecipeDocument.Cut cut) {
        if (cut == null) {
            return;
        }
        if (cut.inAtMs() == null || cut.outAtMs() == null || cut.inAtMs() < 0) {
            throw invalid("cut");
        }
        // 🔴 순서를 뺄셈 전에 따로 본다. 길이만 재면 뺄셈이 넘치는 값(in=Long.MAX, out=Long.MIN+4999)이
        // 정확히 5,000으로 접혀 통과하고, DB CHECK(in < out)에서 500이 난다(PR #187 1판 claude).
        if (cut.outAtMs() <= cut.inAtMs()) {
            throw invalid("cut");
        }
        long length = cut.outAtMs() - cut.inAtMs();
        if (length < MIN_CUT_MS || length > MAX_CUT_MS) {
            throw invalid("cut");
        }
    }

    /** 계약6 1·2절 outputs — 하나 이상 · outputId 형식·유일 · aspect enum·중복 금지 · crop 기하. */
    private void validateOutputs(List<Output> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            throw invalid("outputs");
        }
        Set<String> ids = new HashSet<>();
        Set<String> aspects = new HashSet<>();
        for (Output output : outputs) {
            if (output == null || output.outputId() == null || !OUTPUT_ID.matcher(output.outputId()).matches()
                    || !ids.add(output.outputId())) {
                throw invalid("outputs");
            }
            if (output.aspect() == null || !ASPECTS.contains(output.aspect()) || !aspects.add(output.aspect())) {
                throw invalid("outputs");
            }
            validateCrop(output.crop());
        }
    }

    /** 계약6 2절 crop — {@code x,y ∈ [0,1)} · {@code w,h ≥ 0.05} · {@code x+w ≤ 1} · {@code y+h ≤ 1}. */
    private void validateCrop(Crop crop) {
        if (crop == null || crop.x() == null || crop.y() == null || crop.w() == null || crop.h() == null) {
            throw invalid("outputs");
        }
        double x = crop.x();
        double y = crop.y();
        double w = crop.w();
        double h = crop.h();
        // NaN은 모든 비교에서 거짓이라 아래 조건을 전부 빠져나간다 — 먼저 잡는다.
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(w) || Double.isNaN(h)) {
            throw invalid("outputs");
        }
        if (x < 0 || x >= 1 || y < 0 || y >= 1 || w < MIN_CROP_SIDE || h < MIN_CROP_SIDE || x + w > 1 || y + h > 1) {
            throw invalid("outputs");
        }
    }

    /** 계약6 1·2절 audio — 하나 이상 · trackId 0~5 · 중복 금지 · 0과 1~5 동시 금지 · gain 0.0~2.0. */
    private void validateAudio(RecipeDocument.Audio audio) {
        if (audio == null || audio.tracks() == null || audio.tracks().isEmpty()) {
            throw invalid("audio");
        }
        Set<Integer> ids = new HashSet<>();
        for (Track track : audio.tracks()) {
            if (track == null || track.trackId() == null || track.trackId() < 0 || track.trackId() > MAX_TRACK_ID
                    || !ids.add(track.trackId())) {
                throw invalid("audio");
            }
            if (track.gain() == null || Double.isNaN(track.gain()) || track.gain() < 0 || track.gain() > MAX_GAIN) {
                throw invalid("audio");
            }
        }
        // 0은 1~5의 믹스라 같이 넣으면 이중 산입이다.
        if (ids.contains(0) && ids.size() > 1) {
            throw invalid("audio");
        }
    }

    /**
     * 계약6 1·2절 subtitles — {@code null}은 자막 없음. 있으면 mode 3종·segments 배열(빈 배열 허용 — srt 0개)·
     * 각 구간 {@code startAtMs < endAtMs}·오름차순·겹침 금지. <b>컷 밖 구간은 거부하지 않는다</b> — 컷을
     * 옮기면 자연히 돌아오는 것이 논디스트럭티브의 뜻이다.
     */
    private void validateSubtitles(RecipeDocument.Subtitles subtitles) {
        if (subtitles == null) {
            return;
        }
        if (subtitles.mode() == null || !SUBTITLE_MODES.contains(subtitles.mode()) || subtitles.segments() == null) {
            throw invalid("subtitles");
        }
        long previousEnd = Long.MIN_VALUE;
        for (Segment segment : subtitles.segments()) {
            if (segment == null || segment.startAtMs() == null || segment.endAtMs() == null || segment.text() == null
                    || segment.startAtMs() < 0 || segment.startAtMs() >= segment.endAtMs()) {
                throw invalid("subtitles");
            }
            // 앞 구간의 끝과 같은 시각에서 시작하는 것은 겹침이 아니다 — 구간은 [start, end)다.
            if (segment.startAtMs() < previousEnd) {
                throw invalid("subtitles");
            }
            previousEnd = segment.endAtMs();
        }
    }

    private static RecipeErrors.InvalidRecipeException invalid(String field) {
        return new RecipeErrors.InvalidRecipeException(field);
    }
}
