package com.pokeclip.render.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 공유 시험 표 {@code ledger-timeline-cases.json}의 렌더 쪽 읽개(PDT 설계 r7 3.5 · r6 3.4 「읽는 길」). 렌더 몫 갈래는
 * {@code audioJoin}이다.
 *
 * <p>표는 services · 렌더 일꾼 · 웹 세 곳이 읽는 계약이라 읽는 쪽은 fail-closed다 — 표가 없거나, 뿌리 {@code version}이
 * 다르거나, 모르는 {@code kind}가 있거나, {@code audioJoin} 사례가 비거나 모양이 틀리면 {@link IllegalStateException}으로 시험을
 * 실패시킨다. 건너뛰면 표가 깨져도 초록이 된다.
 */
public final class SharedTimelineCases {

    /**
     * 시험 작업 폴더(workers/render) 기준 상대 경로. {@code build.gradle}의 시험 입력 선언과 같은 경로라 표가 바뀌면 시험이
     * 다시 돈다. 렌더 이미지 빌드는 시험을 빼므로({@code bootJar -x test}) 도커 문맥과 무관하다.
     */
    public static final Path PATH = Path.of("../../services/ledger-timeline/src/test/resources/ledger-timeline-cases.json");

    /** 표 뿌리 판. services 쪽 읽개({@code LedgerTimelineCases.VERSION})와 같은 값이다. */
    static final int VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 이 표가 아는 갈래 전부. 렌더 몫이 아닌 갈래도 알아야 모르는 갈래와 가를 수 있다. */
    private static final Set<String> KINDS = Set.of("F", "length", "audioJoin");

    private final List<AudioJoinCase> audioJoin;

    private SharedTimelineCases(List<AudioJoinCase> audioJoin) {
        this.audioJoin = List.copyOf(audioJoin);
    }

    public static SharedTimelineCases load() {
        return load(PATH);
    }

    static SharedTimelineCases load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("공유 시험 표가 없다: " + path.toAbsolutePath().normalize());
        }
        try {
            return parse(Files.readString(path));
        } catch (IOException e) {
            throw new IllegalStateException("공유 시험 표를 못 읽었다: " + path, e);
        }
    }

    static SharedTimelineCases parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        // 정수 칸만 판으로 본다 — asInt 는 2.9 를 2 로 자르고 글자 "2" 도 2 로 읽는다.
        JsonNode version = root.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() != VERSION) {
            throw new IllegalStateException("표 version이 " + VERSION + "이 아니다: " + root.get("version"));
        }
        // 객체도 값들로 순회되므로 배열인지 따로 본다.
        if (!root.path("cases").isArray()) {
            throw new IllegalStateException("표 cases가 배열이 아니다");
        }
        List<AudioJoinCase> audioJoin = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode c : root.get("cases")) {
            String id = c.path("id").asString("");
            if (id.isEmpty() || !ids.add(id)) {
                throw new IllegalStateException("사례 id가 비었거나 겹친다: " + c.get("id"));
            }
            String kind = c.path("kind").asString("");
            if (!KINDS.contains(kind)) {
                throw new IllegalStateException(id + ": 모르는 kind: " + c.get("kind"));
            }
            if (kind.equals("audioJoin")) {
                audioJoin.add(AudioJoinCase.of(c));
            }
        }
        if (audioJoin.isEmpty()) {
            throw new IllegalStateException("갈래 audioJoin 사례가 하나도 없다");
        }
        return new SharedTimelineCases(audioJoin);
    }

    public List<AudioJoinCase> audioJoin() {
        return audioJoin;
    }

    /**
     * {@code audioJoin} 사례 하나. 값은 모두 파일 안 tfdt(DTS) 기준 ms다(r6 3.4). 트랙 순서는 파일 순서다.
     *
     * @param timingVersion 판 표시(2 · null). 판정은 판으로 가르지 않는다 — 같은 값의 두 판 짝이 그것을 지킨다
     * @param applies       두 쪽 모두 영상이 있어 판정을 했는가. 거짓이면 무음은 모두 0이다
     */
    public record AudioJoinCase(String id, Integer timingVersion, Side prev, Side next, boolean applies,
                                List<Long> expectSilenceMs) {

        /** id · 판 표시를 뺀 값. 판 짝이 같은 값인지 견줄 때 쓴다. */
        public List<Object> withoutIdAndVersion() {
            return List.of(prev, next, applies, expectSilenceMs);
        }

        static AudioJoinCase of(JsonNode c) {
            String id = c.get("id").asString();
            if (!c.has("timingVersion") || !(c.get("timingVersion").isNull() || c.get("timingVersion").asInt(-1) == 2)) {
                throw new IllegalStateException(id + ": timingVersion은 2 또는 null이다");
            }
            Integer timingVersion = c.get("timingVersion").isNull() ? null : 2;
            Side prev = Side.of(id, c.get("prev"), true);
            Side next = Side.of(id, c.get("next"), false);
            JsonNode applies = c.get("applies");
            if (applies == null || !applies.isBoolean()) {
                throw new IllegalStateException(id + ": applies가 불리언이 아니다");
            }
            if (applies.asBoolean() != (prev.videoStartMs() != null && next.videoStartMs() != null)) {
                throw new IllegalStateException(id + ": applies는 두 쪽 모두 영상이 있을 때만 참이다");
            }
            List<Long> silence = new ArrayList<>();
            c.path("expectSilenceMs").forEach(n -> silence.add(n.asLong()));
            if (silence.size() != next.tracks().size() || silence.size() != prev.tracks().size()) {
                throw new IllegalStateException(id + ": expectSilenceMs 수가 트랙 수와 다르다");
            }
            if (!applies.asBoolean() && silence.stream().anyMatch(ms -> ms != 0)) {
                throw new IllegalStateException(id + ": applies가 거짓인데 무음이 0이 아니다");
            }
            return new AudioJoinCase(id, timingVersion, prev, next, applies.asBoolean(), List.copyOf(silence));
        }
    }

    /**
     * 이음의 한쪽 조각. 앞 조각은 영상이 있으면 끝도 있고, 트랙마다 끝 · 표본율도 있다. 뒤 조각은 시작만 있다.
     *
     * @param videoStartMs 영상이 없으면 null
     */
    public record Side(Long videoStartMs, Long videoEndMs, List<Track> tracks) {

        static Side of(String id, JsonNode node, boolean before) {
            if (node == null || !node.isObject() || !node.path("tracks").isArray() || node.get("tracks").isEmpty()) {
                throw new IllegalStateException(id + ": prev · next에 트랙이 없다");
            }
            Long start = node.has("videoStartMs") ? node.get("videoStartMs").asLong() : null;
            Long end = node.has("videoEndMs") ? node.get("videoEndMs").asLong() : null;
            if (before ? (start == null) != (end == null) : end != null) {
                throw new IllegalStateException(id + ": 영상 시작 · 끝 칸이 맞지 않는다");
            }
            List<Track> tracks = new ArrayList<>();
            for (JsonNode t : node.get("tracks")) {
                if (!t.has("startMs") || before != t.has("endMs") || before != t.has("sampleRate")) {
                    throw new IllegalStateException(id + ": 트랙 칸이 맞지 않는다");
                }
                tracks.add(new Track(t.get("startMs").asLong(), before ? t.get("endMs").asLong() : null,
                        before ? t.get("sampleRate").asInt() : null));
            }
            return new Side(start, end, List.copyOf(tracks));
        }
    }

    /** 트랙 하나. 뒤 조각은 {@code endMs} · {@code sampleRate}가 null이다. */
    public record Track(long startMs, Long endMs, Integer sampleRate) {
    }
}
