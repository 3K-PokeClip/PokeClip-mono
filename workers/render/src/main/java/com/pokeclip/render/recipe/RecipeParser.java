package com.pokeclip.render.recipe;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.Crop;
import com.pokeclip.render.recipe.Recipe.Cut;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.recipe.Recipe.SubtitleMode;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import com.pokeclip.render.recipe.Recipe.Subtitles;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 계약6 3층 검증. 렌더가 <b>권위</b>다. fail-closed: 모르는 칸·모르는 schemaVersion은 거부한다(계약6 0절).
 * 모르는 칸을 무시하면 사용자가 넣은 효과가 조용히 빠진 채 영상이 나간다.
 *
 * <p>여기는 원본을 보지 않고 가를 수 있는 규칙만 본다(preflight: 계약1 4절, 무토큰). 원본 해상도가 있어야 하는
 * 픽셀 종횡비 검사는 원본을 내려받은 뒤 {@code media.CropGeometry}가 한다.
 *
 * <p>실패는 전부 {@link ErrorCode#VALIDATION}이다. 메시지는 사용자 화면까지 가므로 어느 칸이 왜인지만 적는다.
 */
public final class RecipeParser {

    static final int SCHEMA_VERSION = 1;
    static final long MIN_CUT_MS = 5_000;
    static final long MAX_CUT_MS = 180_000;
    static final double MIN_CROP_SIDE = 0.05;
    static final double MAX_GAIN = 2.0;

    private static final Pattern OUTPUT_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Set<String> ROOT = Set.of("schemaVersion", "streamId", "cut", "outputs", "audio", "subtitles");
    private static final Set<String> CUT = Set.of("inAtMs", "outAtMs");
    private static final Set<String> OUTPUT = Set.of("outputId", "aspect", "crop");
    private static final Set<String> CROP = Set.of("x", "y", "w", "h");
    private static final Set<String> AUDIO = Set.of("tracks");
    private static final Set<String> TRACK = Set.of("trackId", "gain");
    private static final Set<String> SUBTITLES = Set.of("mode", "segments");
    private static final Set<String> SEGMENT = Set.of("startAtMs", "endAtMs", "text");

    private RecipeParser() {
    }

    public static Recipe parse(JsonNode node) {
        object(node, "recipe", ROOT);
        int version = integer(node.get("schemaVersion"), "recipe.schemaVersion");
        if (version != SCHEMA_VERSION) {
            throw invalid("recipe.schemaVersion=" + version + "은 지원하지 않는다");
        }
        String streamId = text(node.get("streamId"), "recipe.streamId");
        Cut cut = cut(node.get("cut"));
        List<Output> outputs = outputs(node.get("outputs"));
        List<AudioTrack> tracks = tracks(node.get("audio"));
        JsonNode subtitlesNode = node.get("subtitles");
        Subtitles subtitles = subtitlesNode == null || subtitlesNode.isNull() ? null : subtitles(subtitlesNode);
        return new Recipe(streamId, cut, outputs, tracks, subtitles);
    }

    private static Cut cut(JsonNode node) {
        if (node == null || node.isNull()) {
            // 템플릿(cut null)은 저장은 되지만 렌더할 수 없다(계약6 2절).
            throw invalid("recipe.cut이 없다(템플릿은 렌더할 수 없다)");
        }
        object(node, "recipe.cut", CUT);
        long in = longValue(node.get("inAtMs"), "recipe.cut.inAtMs");
        long out = longValue(node.get("outAtMs"), "recipe.cut.outAtMs");
        if (in >= out) {
            throw invalid("recipe.cut: inAtMs < outAtMs 여야 한다");
        }
        long length = out - in;
        if (length < MIN_CUT_MS || length > MAX_CUT_MS) {
            throw invalid("recipe.cut: 길이는 5초 이상 180초 이하다");
        }
        return new Cut(in, out);
    }

    private static List<Output> outputs(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("recipe.outputs는 1개 이상이어야 한다");
        }
        List<Output> outputs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<Aspect> aspects = new HashSet<>();
        for (JsonNode item : node) {
            object(item, "recipe.outputs[]", OUTPUT);
            String id = text(item.get("outputId"), "recipe.outputs[].outputId");
            if (!OUTPUT_ID.matcher(id).matches()) {
                throw invalid("recipe.outputs[].outputId 형식은 [a-z0-9-]{1,32}이다");
            }
            if (!ids.add(id)) {
                throw invalid("recipe.outputs[].outputId가 겹친다: " + id);
            }
            Aspect aspect = aspect(text(item.get("aspect"), "recipe.outputs[].aspect"));
            if (!aspects.add(aspect)) {
                throw invalid("recipe.outputs[].aspect가 겹친다: " + aspect);
            }
            outputs.add(new Output(id, aspect, crop(item.get("crop"))));
        }
        return List.copyOf(outputs);
    }

    private static Aspect aspect(String value) {
        try {
            return Aspect.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalid("recipe.outputs[].aspect=" + value + "은 지원하지 않는다");
        }
    }

    private static Crop crop(JsonNode node) {
        object(node, "recipe.outputs[].crop", CROP);
        double x = number(node.get("x"), "crop.x");
        double y = number(node.get("y"), "crop.y");
        double w = number(node.get("w"), "crop.w");
        double h = number(node.get("h"), "crop.h");
        if (x < 0 || x >= 1 || y < 0 || y >= 1) {
            throw invalid("crop.x·y는 [0,1) 안이어야 한다");
        }
        if (w < MIN_CROP_SIDE || h < MIN_CROP_SIDE) {
            throw invalid("crop.w·h는 0.05 이상이어야 한다");
        }
        if (x + w > 1 + 1e-9 || y + h > 1 + 1e-9) {
            throw invalid("crop 영역이 화면 밖으로 나간다");
        }
        return new Crop(x, y, w, h);
    }

    private static List<AudioTrack> tracks(JsonNode audio) {
        object(audio, "recipe.audio", AUDIO);
        JsonNode node = audio.get("tracks");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("recipe.audio.tracks는 1개 이상이어야 한다");
        }
        List<AudioTrack> tracks = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (JsonNode item : node) {
            object(item, "recipe.audio.tracks[]", TRACK);
            int id = integer(item.get("trackId"), "audio.tracks[].trackId");
            if (id < 0 || id > 5) {
                throw invalid("audio.tracks[].trackId는 0~5다");
            }
            if (!ids.add(id)) {
                throw invalid("audio.tracks[].trackId가 겹친다: " + id);
            }
            double gain = number(item.get("gain"), "audio.tracks[].gain");
            if (gain < 0 || gain > MAX_GAIN) {
                throw invalid("audio.tracks[].gain은 0.0~2.0이다");
            }
            tracks.add(new AudioTrack(id, gain));
        }
        // 0은 1~5를 섞은 것이라 같이 넣으면 같은 소리가 두 번 들어간다(계약6 2절).
        if (ids.contains(0) && ids.size() > 1) {
            throw invalid("audio.tracks: 0번(최종 믹스)과 1~5번을 같이 넣을 수 없다");
        }
        return List.copyOf(tracks);
    }

    private static Subtitles subtitles(JsonNode node) {
        object(node, "recipe.subtitles", SUBTITLES);
        SubtitleMode mode;
        String modeText = text(node.get("mode"), "subtitles.mode");
        try {
            mode = SubtitleMode.valueOf(modeText);
        } catch (IllegalArgumentException e) {
            throw invalid("subtitles.mode=" + modeText + "은 지원하지 않는다");
        }
        JsonNode segmentsNode = node.get("segments");
        if (segmentsNode == null || !segmentsNode.isArray()) {
            throw invalid("subtitles.segments가 없다");
        }
        List<SubtitleSegment> segments = new ArrayList<>();
        long previousEnd = Long.MIN_VALUE;
        for (JsonNode item : segmentsNode) {
            object(item, "subtitles.segments[]", SEGMENT);
            long start = longValue(item.get("startAtMs"), "subtitles.segments[].startAtMs");
            long end = longValue(item.get("endAtMs"), "subtitles.segments[].endAtMs");
            // 빈 글자는 clip 저장 검증이 받는다(null만 거부). 여기서 거부하면 저장된 편집본이 영상을 못 만든다(PR #194 codex).
            // 받아 두고 렌더에서 뺀다(SrtWriter).
            JsonNode textNode = item.get("text");
            if (textNode == null || !textNode.isString()) {
                throw invalid("subtitles.segments[].text가 문자열이 아니다");
            }
            String text = textNode.asString();
            if (start >= end) {
                throw invalid("subtitles.segments[]: startAtMs < endAtMs 여야 한다");
            }
            if (start < previousEnd) {
                throw invalid("subtitles.segments[]는 시간순이고 겹치지 않아야 한다");
            }
            previousEnd = end;
            segments.add(new SubtitleSegment(start, end, text));
        }
        return new Subtitles(mode, List.copyOf(segments));
    }

    private static void object(JsonNode node, String where, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw invalid(where + "가 객체가 아니다");
        }
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid(where + "에 모르는 칸이 있다: " + name);
            }
        }
    }

    private static String text(JsonNode node, String where) {
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw invalid(where + "가 비었다");
        }
        return node.asString();
    }

    private static int integer(JsonNode node, String where) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(where + "가 정수가 아니다");
        }
        return node.asInt();
    }

    private static long longValue(JsonNode node, String where) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(where + "가 정수가 아니다");
        }
        return node.asLong();
    }

    private static double number(JsonNode node, String where) {
        if (node == null || !node.isNumber()) {
            throw invalid(where + "가 숫자가 아니다");
        }
        double value = node.asDouble();
        if (!Double.isFinite(value)) {
            throw invalid(where + "가 숫자가 아니다");
        }
        return value;
    }

    private static RenderFailure invalid(String message) {
        return RenderFailure.permanent(ErrorCode.VALIDATION, message);
    }
}
