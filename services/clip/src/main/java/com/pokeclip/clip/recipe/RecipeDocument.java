package com.pokeclip.clip.recipe;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 계약6(레시피 JSON 스키마 rev7) 본문 그대로. <b>칸 이름을 한 글자도 바꾸지 않는다</b> — 이 record가
 * 요청을 받는 모양이자 응답으로 나가는 모양이고, 표의 jsonb 칸에도 이 조각들이 그대로 들어간다.
 * 이름을 여기서 바꾸면 2번(편집기)·1번(렌더)과 세 곳이 동시에 어긋난다.
 *
 * <p><b>전부 박스형이다.</b> 빠진 칸을 {@code 0}·{@code false}로 조용히 채우지 않고 {@code null}로 받아
 * {@link RecipeValidator}가 「없다」로 거절한다. 규칙 검사는 그 클래스에 있고 여기는 모양뿐이다.
 *
 * @param cut {@code null}이면 템플릿(계약6 2절). 하나만 비는 모양은 record가 못 막고 검증이 막는다
 * @param subtitles {@code null}이면 자막 없음(번인도 srt도 없다)
 */
public record RecipeDocument(Integer schemaVersion,
                             String streamId,
                             Cut cut,
                             List<Output> outputs,
                             Audio audio,
                             Subtitles subtitles) {

    /** 방송 절대축 UTC epoch ms(계약6 0절). */
    public record Cut(Long inAtMs, Long outAtMs) {
    }

    public record Output(String outputId, String aspect, Crop crop) {
    }

    /** 정규화 좌표. 표시 평면 좌상단 원점(계약6 rev7). */
    public record Crop(Double x, Double y, Double w, Double h) {
    }

    public record Audio(List<Track> tracks) {
    }

    /** {@code trackId} 0 = 최종 믹스, 1~5 = 소스별(ADR-017). {@code gain} 1.0 = 원음. */
    public record Track(Integer trackId, Double gain) {
    }

    public record Subtitles(String mode, List<Segment> segments) {
    }

    /** 구간 의미는 {@code [startAtMs, endAtMs)}, 방송 절대축. */
    public record Segment(Long startAtMs, Long endAtMs, String text) {
    }

    /** 표의 칸을 계약6 모양으로 되돌린다 — 편집기 응답({@code RecipeService})과 렌더 주문서가 같은 복원을 쓴다. */
    public static RecipeDocument fromStored(ObjectMapper mapper, Recipe recipe) {
        Cut cut = recipe.getCutInAtMs() == null ? null : new Cut(recipe.getCutInAtMs(), recipe.getCutOutAtMs());
        List<Output> outputs = mapper.readValue(recipe.getOutputs(),
                mapper.getTypeFactory().constructCollectionType(List.class, Output.class));
        Audio audio = mapper.readValue(recipe.getAudio(), Audio.class);
        Subtitles subtitles = recipe.getSubtitles() == null ? null : mapper.readValue(recipe.getSubtitles(), Subtitles.class);
        return new RecipeDocument(recipe.getSchemaVersion(), recipe.getStreamId(), cut, outputs, audio, subtitles);
    }
}
