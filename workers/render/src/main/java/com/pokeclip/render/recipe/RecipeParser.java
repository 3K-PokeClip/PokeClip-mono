package com.pokeclip.render.recipe;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.recipe.Recipe.Anchor;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.Background;
import com.pokeclip.render.recipe.Recipe.Box;
import com.pokeclip.render.recipe.Recipe.Crop;
import com.pokeclip.render.recipe.Recipe.Cut;
import com.pokeclip.render.recipe.Recipe.Divider;
import com.pokeclip.render.recipe.Recipe.Frame;
import com.pokeclip.render.recipe.Recipe.Layer;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.recipe.Recipe.SubtitleMode;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;
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
 *
 * <p><b>v1과 v2를 둘 다 받는다.</b> v1은 출력마다 {@code crop} 하나(세로·정사각을 꽉 채움)이고, v2는 출력을 층·바탕·구분선으로
 * 적는다(분할·중앙·작은 화면, 계약6 7절). 이미 저장된 v1 편집본도 영상을 만들 수 있어야 해서 v1을 거두지 않는다.
 * 나머지 칸(cut·audio)은 두 판이 같고, 자막은 v2에만 {@code position}이 있다.
 */
public final class RecipeParser {

    static final int SCHEMA_V1 = 1;
    static final int SCHEMA_V2 = 2;
    static final long MIN_CUT_MS = 5_000;
    static final long MAX_CUT_MS = 180_000;
    static final double MIN_CROP_SIDE = 0.05;
    static final double MAX_GAIN = 2.0;

    private static final Pattern OUTPUT_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Set<String> ROOT = Set.of("schemaVersion", "streamId", "cut", "outputs", "audio", "subtitles");
    private static final Set<String> CUT = Set.of("inAtMs", "outAtMs");
    private static final Set<String> OUTPUT_V1 = Set.of("outputId", "aspect", "crop");
    private static final Set<String> OUTPUT_V2 = Set.of("outputId", "aspect", "background", "layers", "dividers");
    private static final Set<String> CROP = Set.of("x", "y", "w", "h");
    private static final Set<String> LAYER = Set.of("crop", "box", "frame");
    private static final Set<String> FRAME = Set.of("width", "color", "radius", "shadow");
    private static final Set<String> BLUR = Set.of("kind", "strength");
    private static final Set<String> COLOR = Set.of("kind", "color");
    private static final Set<String> DIVIDER = Set.of("y", "thickness", "color");
    private static final Set<String> AUDIO = Set.of("tracks");
    private static final Set<String> TRACK = Set.of("trackId", "gain");
    private static final Set<String> SUBTITLES_V1 = Set.of("mode", "segments");
    private static final Set<String> SUBTITLES_V2 = Set.of("mode", "segments", "position");
    private static final Set<String> POSITION = Set.of("anchor", "y");
    private static final Set<String> SEGMENT = Set.of("startAtMs", "endAtMs", "text");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    /** 계약6 7절 — 층은 1~4장, 구분선은 0~4줄. 화면의 레이아웃은 층 둘·구분선 하나가 최대다. */
    static final int MAX_LAYERS = 4;
    static final int MAX_DIVIDERS = 4;
    /** 테두리·구분선 두께 상한(결과 폭 비). 5%면 1080 폭에 54px — 그보다 두꺼우면 선이 아니라 칸이다. */
    static final double MAX_LINE = 0.05;
    /** 둥근 모서리 반지름 상한(결과 폭 비). */
    static final double MAX_RADIUS = 0.1;

    private RecipeParser() {
    }

    public static Recipe parse(JsonNode node) {
        object(node, "recipe", ROOT);
        int version = integer(node.get("schemaVersion"), "recipe.schemaVersion");
        if (version != SCHEMA_V1 && version != SCHEMA_V2) {
            throw invalid("recipe.schemaVersion=" + version + "은 지원하지 않는다");
        }
        boolean v2 = version == SCHEMA_V2;
        String streamId = text(node.get("streamId"), "recipe.streamId");
        Cut cut = cut(node.get("cut"));
        List<Output> outputs = outputs(node.get("outputs"), v2);
        List<AudioTrack> tracks = tracks(node.get("audio"));
        JsonNode subtitlesNode = node.get("subtitles");
        Subtitles subtitles = subtitlesNode == null || subtitlesNode.isNull() ? null : subtitles(subtitlesNode, v2);
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

    private static List<Output> outputs(JsonNode node, boolean v2) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("recipe.outputs는 1개 이상이어야 한다");
        }
        List<Output> outputs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<Aspect> aspects = new HashSet<>();
        for (JsonNode item : node) {
            object(item, "recipe.outputs[]", v2 ? OUTPUT_V2 : OUTPUT_V1);
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
            outputs.add(v2
                    ? new Output(id, aspect, background(item.get("background")), layers(item.get("layers")),
                            dividers(item.get("dividers")))
                    : Output.single(id, aspect, crop(item.get("crop"))));
        }
        return List.copyOf(outputs);
    }

    private static List<Layer> layers(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_LAYERS) {
            throw invalid("recipe.outputs[].layers는 1~" + MAX_LAYERS + "장이어야 한다");
        }
        List<Layer> layers = new ArrayList<>();
        for (JsonNode item : node) {
            object(item, "recipe.outputs[].layers[]", LAYER);
            JsonNode frame = item.get("frame");
            layers.add(new Layer(crop(item.get("crop")), box(item.get("box")),
                    frame == null || frame.isNull() ? null : frame(frame)));
        }
        return List.copyOf(layers);
    }

    /** 결과 안의 자리 — crop과 같은 기하 규칙(0.05 하한·화면 안). 결과 밖으로 나가는 층은 잘린 채 나가므로 거부한다. */
    private static Box box(JsonNode node) {
        object(node, "recipe.outputs[].layers[].box", CROP);
        double x = number(node.get("x"), "box.x");
        double y = number(node.get("y"), "box.y");
        double w = number(node.get("w"), "box.w");
        double h = number(node.get("h"), "box.h");
        if (x < 0 || x >= 1 || y < 0 || y >= 1) {
            throw invalid("box.x·y는 [0,1) 안이어야 한다");
        }
        if (w < MIN_CROP_SIDE || h < MIN_CROP_SIDE) {
            throw invalid("box.w·h는 0.05 이상이어야 한다");
        }
        if (x + w > 1 + 1e-9 || y + h > 1 + 1e-9) {
            throw invalid("box가 결과 화면 밖으로 나간다");
        }
        return new Box(x, y, w, h);
    }

    private static Frame frame(JsonNode node) {
        object(node, "recipe.outputs[].layers[].frame", FRAME);
        double width = number(node.get("width"), "frame.width");
        double radius = number(node.get("radius"), "frame.radius");
        if (width < 0 || width > MAX_LINE) {
            throw invalid("frame.width는 0~" + MAX_LINE + "이다");
        }
        if (radius < 0 || radius > MAX_RADIUS) {
            throw invalid("frame.radius는 0~" + MAX_RADIUS + "이다");
        }
        JsonNode shadow = node.get("shadow");
        if (shadow == null || !shadow.isBoolean()) {
            throw invalid("frame.shadow가 참·거짓이 아니다");
        }
        return new Frame(width, color(node.get("color"), "frame.color"), radius, shadow.asBoolean());
    }

    /** 없으면 검정(null). */
    private static Background background(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid("recipe.outputs[].background가 객체가 아니다");
        }
        String kind = text(node.get("kind"), "background.kind");
        return switch (kind) {
            case "BLUR" -> {
                object(node, "recipe.outputs[].background", BLUR);
                int strength = integer(node.get("strength"), "background.strength");
                if (strength < 0 || strength > 100) {
                    throw invalid("background.strength는 0~100이다");
                }
                yield new Background.Blur(strength);
            }
            case "COLOR" -> {
                object(node, "recipe.outputs[].background", COLOR);
                yield new Background.Color(color(node.get("color"), "background.color"));
            }
            default -> throw invalid("background.kind=" + kind + "은 지원하지 않는다");
        };
    }

    /** 없으면 빈 목록. */
    private static List<Divider> dividers(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_DIVIDERS) {
            throw invalid("recipe.outputs[].dividers는 0~" + MAX_DIVIDERS + "줄이어야 한다");
        }
        List<Divider> dividers = new ArrayList<>();
        for (JsonNode item : node) {
            object(item, "recipe.outputs[].dividers[]", DIVIDER);
            double y = number(item.get("y"), "dividers[].y");
            double thickness = number(item.get("thickness"), "dividers[].thickness");
            if (y <= 0 || y >= 1) {
                throw invalid("dividers[].y는 (0,1) 안이어야 한다");
            }
            if (thickness <= 0 || thickness > MAX_LINE) {
                throw invalid("dividers[].thickness는 0 초과 " + MAX_LINE + " 이하다");
            }
            dividers.add(new Divider(y, thickness, color(item.get("color"), "dividers[].color")));
        }
        return List.copyOf(dividers);
    }

    private static String color(JsonNode node, String where) {
        if (node == null || !node.isString() || !HEX_COLOR.matcher(node.asString()).matches()) {
            throw invalid(where + "는 #RRGGBB여야 한다");
        }
        return node.asString();
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

    private static Subtitles subtitles(JsonNode node, boolean v2) {
        object(node, "recipe.subtitles", v2 ? SUBTITLES_V2 : SUBTITLES_V1);
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
        JsonNode positionNode = node.get("position");
        SubtitlePosition position = positionNode == null || positionNode.isNull() ? null : position(positionNode);
        return new Subtitles(mode, List.copyOf(segments), position);
    }

    private static SubtitlePosition position(JsonNode node) {
        object(node, "recipe.subtitles.position", POSITION);
        String anchorText = text(node.get("anchor"), "subtitles.position.anchor");
        Anchor anchor;
        try {
            anchor = Anchor.valueOf(anchorText);
        } catch (IllegalArgumentException e) {
            throw invalid("subtitles.position.anchor=" + anchorText + "은 지원하지 않는다");
        }
        double y = number(node.get("y"), "subtitles.position.y");
        if (y < 0 || y > 1) {
            throw invalid("subtitles.position.y는 0~1이다");
        }
        return new SubtitlePosition(anchor, y);
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
