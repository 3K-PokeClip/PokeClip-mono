package com.pokeclip.clip.recipe;

import com.pokeclip.clip.recipe.RecipeDocument.Background;
import com.pokeclip.clip.recipe.RecipeDocument.Box;
import com.pokeclip.clip.recipe.RecipeDocument.Crop;
import com.pokeclip.clip.recipe.RecipeDocument.Divider;
import com.pokeclip.clip.recipe.RecipeDocument.Frame;
import com.pokeclip.clip.recipe.RecipeDocument.Layer;
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
 * <p>규칙마다 계약6 절 번호를 적어 둔다. <b>규칙을 바꾸는 권한은 렌더 실행 쪽(스키마 승인권)이다</b> — 여기 숫자를
 * 바꾸는 것은 계약6을 바꾼 뒤다.
 *
 * <p><b>v1과 v2를 둘 다 받는다</b>(계약6 7절). v1 출력은 {@code crop} 하나, v2 출력은 층·바탕·구분선이고 자막 자리는 v2에만 있다.
 * 한 판에 다른 판의 칸이 섞이면 거절한다 — 렌더가 그 칸을 모르는 칸으로 거부해 저장은 되고 영상은 못 만드는 편집본이 된다.
 */
@Component
public class RecipeValidator {

    static final int SCHEMA_V1 = 1;
    static final int SCHEMA_V2 = 2;

    /** 계약6 2절 cut — 5초 이상 180초 이하(kty 확정 2026-08-24, Shorts 상한). */
    static final long MIN_CUT_MS = 5_000;
    static final long MAX_CUT_MS = 180_000;

    /**
     * 절대 시각 상한 — 3000-01-01 UTC. 계약6은 상한을 안 정하지만 PostgreSQL {@code to_timestamp}가 받는 범위가 {@code long}보다
     * 훨씬 좁아, 그 밖의 값은 조각 조회에서 500이 난다(PR #188 1판 codex). 컷과 자막 시각에 같이 건다.
     */
    static final long MAX_EPOCH_MS = 32_503_680_000_000L;

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

    /** 계약6 7절 — 층 1~4장 · 구분선 0~4줄 · 선 두께 ≤ 결과 폭 5% · 모서리 ≤ 10% · 흐림 세기 0~100 · 색 {@code #RRGGBB}. */
    static final int MAX_LAYERS = 4;
    static final int MAX_DIVIDERS = 4;
    static final double MAX_LINE = 0.05;
    static final double MAX_RADIUS = 0.1;
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Set<String> ANCHORS = Set.of("TOP", "MIDDLE", "BOTTOM");
    static final double BOX_SLACK = 1e-9;

    /**
     * @param streamId 경로의 방송 번호. 본문의 {@code streamId}와 같아야 한다 — 다르면 편집기가 다른 방송의
     *                 화면에서 보낸 것이고, 어느 쪽을 믿어야 할지 서버가 정하면 안 된다
     * @throws RecipeErrors.InvalidRecipeException 어느 규칙이든 어긋나면. 첫 번째 어긋난 덩어리 이름을 싣는다
     */
    public void validate(RecipeDocument recipe, String streamId) {
        Integer version = recipe.schemaVersion();
        if (version == null || (version != SCHEMA_V1 && version != SCHEMA_V2)) {
            throw invalid("schemaVersion");
        }
        boolean v2 = version == SCHEMA_V2;
        if (recipe.streamId() == null || !recipe.streamId().equals(streamId)) {
            throw invalid("streamId");
        }
        validateCut(recipe.cut());
        validateOutputs(recipe.outputs(), v2);
        validateAudio(recipe.audio());
        validateSubtitles(recipe.subtitles(), v2);
    }

    /** 계약6 2절 cut. {@code null}은 템플릿이라 통과. 있으면 두 값 다 있고 순서·길이가 맞아야 한다. */
    private void validateCut(RecipeDocument.Cut cut) {
        if (cut == null) {
            return;
        }
        if (cut.inAtMs() == null || cut.outAtMs() == null || cut.inAtMs() < 0 || cut.outAtMs() > MAX_EPOCH_MS) {
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

    /** 계약6 1·2·7절 outputs — 하나 이상 · outputId 형식·유일 · aspect enum·중복 금지 · v1은 crop 기하, v2는 층·바탕·구분선. */
    private void validateOutputs(List<Output> outputs, boolean v2) {
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
            if (v2) {
                if (output.crop() != null) {
                    throw invalid("outputs");
                }
                validateLayers(output.layers());
                validateBackground(output.background());
                validateDividers(output.dividers());
            } else {
                if (output.layers() != null || output.background() != null || output.dividers() != null) {
                    throw invalid("outputs");
                }
                validateCrop(output.crop());
            }
        }
    }

    /** 계약6 7절 layers — 1~4장 · 층마다 crop(2절 기하) · box(같은 기하) · frame(있으면). 비율 일치(±1%)는 원본 해상도가 필요해 3층이다. */
    private void validateLayers(List<Layer> layers) {
        if (layers == null || layers.isEmpty() || layers.size() > MAX_LAYERS) {
            throw invalid("outputs");
        }
        for (Layer layer : layers) {
            if (layer == null) {
                throw invalid("outputs");
            }
            validateCrop(layer.crop());
            validateBox(layer.box());
            Frame frame = layer.frame();
            if (frame != null && (!inRange(frame.width(), 0, MAX_LINE) || !inRange(frame.radius(), 0, MAX_RADIUS)
                    || !isColor(frame.color()) || frame.shadow() == null)) {
                throw invalid("outputs");
            }
        }
    }

    /**
     * 계약6 7절 box — crop과 같은 기하인데 <b>합에 1e-9 여유</b>를 둔다(렌더도 같다). 분할 70:30이면 편집기가 {@code y = 0.7},
     * {@code h = 1 − 0.7}을 싣는데 부동소수 합이 1.0000000000000002라 여유 없이 재면 멀쩡한 분할이 거절된다.
     */
    private void validateBox(Box box) {
        if (box == null || box.x() == null || box.y() == null || box.w() == null || box.h() == null) {
            throw invalid("outputs");
        }
        double x = box.x();
        double y = box.y();
        double w = box.w();
        double h = box.h();
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(w) || Double.isNaN(h)) {
            throw invalid("outputs");
        }
        if (x < 0 || x >= 1 || y < 0 || y >= 1 || w < MIN_CROP_SIDE || h < MIN_CROP_SIDE
                || x + w > 1 + BOX_SLACK || y + h > 1 + BOX_SLACK) {
            throw invalid("outputs");
        }
    }

    /** 계약6 7절 background — 없으면 검정. BLUR는 세기만, COLOR는 색만 싣는다. */
    private void validateBackground(Background background) {
        if (background == null) {
            return;
        }
        boolean ok = switch (background.kind() == null ? "" : background.kind()) {
            case "BLUR" -> background.color() == null && background.strength() != null
                    && background.strength() >= 0 && background.strength() <= 100;
            case "COLOR" -> background.strength() == null && isColor(background.color());
            default -> false;
        };
        if (!ok) {
            throw invalid("outputs");
        }
    }

    /** 계약6 7절 dividers — 0~4줄 · y ∈ (0,1) · 두께 (0, 5%] · 색. */
    private void validateDividers(List<Divider> dividers) {
        if (dividers == null) {
            return;
        }
        if (dividers.size() > MAX_DIVIDERS) {
            throw invalid("outputs");
        }
        for (Divider divider : dividers) {
            if (divider == null || divider.y() == null || divider.thickness() == null
                    || !(divider.y() > 0 && divider.y() < 1)
                    || !(divider.thickness() > 0 && divider.thickness() <= MAX_LINE) || !isColor(divider.color())) {
                throw invalid("outputs");
            }
        }
    }

    /** NaN·null은 범위 밖이다. */
    private static boolean inRange(Double value, double min, double max) {
        return value != null && value >= min && value <= max;
    }

    private static boolean isColor(String value) {
        return value != null && HEX_COLOR.matcher(value).matches();
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
    private void validateSubtitles(RecipeDocument.Subtitles subtitles, boolean v2) {
        if (subtitles == null) {
            return;
        }
        if (subtitles.mode() == null || !SUBTITLE_MODES.contains(subtitles.mode()) || subtitles.segments() == null) {
            throw invalid("subtitles");
        }
        // 자막 자리는 v2 칸이다(계약6 7절). 자리가 오면 anchor 셋 중 하나 · y ∈ [0,1]
        RecipeDocument.Position position = subtitles.position();
        if (position != null && (!v2 || position.anchor() == null || !ANCHORS.contains(position.anchor())
                || !inRange(position.y(), 0, 1))) {
            throw invalid("subtitles");
        }
        long previousEnd = Long.MIN_VALUE;
        for (Segment segment : subtitles.segments()) {
            if (segment == null || segment.startAtMs() == null || segment.endAtMs() == null || segment.text() == null
                    || segment.startAtMs() < 0 || segment.startAtMs() >= segment.endAtMs()
                    || segment.endAtMs() > MAX_EPOCH_MS) {
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
