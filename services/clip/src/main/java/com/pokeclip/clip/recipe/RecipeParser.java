package com.pokeclip.clip.recipe;

import com.pokeclip.clip.recipe.RecipeErrors.InvalidRecipeException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 본문 문자열을 계약6 모양({@link RecipeDocument})으로 읽는다. <b>모르는 칸은 거부한다</b>(계약6 0절
 * fail-closed — 무시하면 편집자가 넣은 효과가 조용히 빠진 채 올라간다).
 *
 * <p><b>스프링이 주입하는 매퍼를 안 쓰고 자기 매퍼를 만든다.</b> Jackson 3 기본은 모르는 칸을
 * <b>무시</b>하고({@code FAIL_ON_UNKNOWN_PROPERTIES} 꺼짐 — 수집기 {@code LifecycleEnvelopeTest}에 실측),
 * 전역 매퍼에서 켜면 그 규칙을 원하지 않는 다른 문(채팅 이벤트 통과, 방송 편지 봉투의 관용)까지 바뀐다.
 * 이 매퍼는 이 클래스 밖으로 안 나간다.
 *
 * <p>컨트롤러가 {@code @RequestBody}를 안 쓰고 <b>요청 스트림을 여기로 넘기는</b> 이유도 같다 — 스프링의 본문
 * 변환이 실패하면(빈 본문·JSON 아님) {@code HttpMessageNotReadableException}이 나가고 그것을 400 봉투로 바꾸려면
 * <b>전역</b> 조언에 갈래를 더해야 해서 다른 문의 400 모양이 같이 바뀐다. 스트림을 직접 읽으면 파싱 실패도 이 문
 * 안에서 끝나고, <b>상한을 넘는 본문을 통째로 메모리에 올리기 전에</b> 자를 수 있다(PR #187 1판 codex P1 —
 * {@code String}으로 받으면 스프링이 먼저 전부 읽어 올린 뒤에야 길이를 잴 수 있다).
 */
@Component
public class RecipeParser {

    /**
     * 본문 상한. 자막 수백 줄이 들어와도 수십 KB다 — 이보다 크면 레시피가 아니라 저장 공간을 노리는 것이다.
     * 톰캣은 JSON 본문 크기를 기본으로 안 막는다({@code max-http-post-size}는 폼 전용).
     */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private final ObjectMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 1.5를 trackId 1로, "1"을 1로 접지 않는다 — 계약이 조용히 두 갈래가 된다(ChatEventsRequest와 같은 자세).
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // 레시피 뒤에 값이 하나 더 붙으면 거부한다. Jackson 3는 기본이 이미 켜짐이라(빼도 시험이 초록 — 주입 확인)
            // 이 줄은 기본값이 바뀌는 날을 대비한 명시다. 1판 codex는 Jackson 2 기본(꺼짐)으로 짚었다.
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 요청 본문을 <b>상한까지만</b> 읽는다. 상한을 한 바이트라도 넘으면 나머지는 안 읽고 400이다 — 큰 본문을
     * 다 받아 놓고 재는 것이 아니라 받다가 끊는다. 빈 본문도 여기서 400 {@code body}다.
     *
     * @throws InvalidRecipeException {@link #parse(String)}과 같다 + 본문이 상한보다 크다
     */
    public RecipeDocument parse(InputStream body) {
        byte[] bytes;
        try {
            bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new InvalidRecipeException("body");
        }
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidRecipeException JSON이 아니거나, 모르는 칸이 있거나, 칸의 형이 다르거나, 본문이 {@code null}
     *         한 낱말이거나, 문서 뒤에 값이 더 있다.
     *         {@code field}는 첫 문제 칸의 경로({@code outputs[0].crop.x} 모양)이고 본문이 아예 JSON이
     *         아니면 {@code body}다. 규칙 검사(범위·유일성)는 여기가 아니라 {@link RecipeValidator}다
     */
    public RecipeDocument parse(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidRecipeException("body");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new InvalidRecipeException("body");
        }
        RecipeDocument document;
        try {
            document = strict.readValue(body, RecipeDocument.class);
        } catch (JacksonException e) {
            throw new InvalidRecipeException(pathOf(e));
        }
        // JSON 낱말 null은 예외 없이 null로 돌아온다 — 그대로 두면 검증기가 500이다(1판 codex).
        if (document == null) {
            throw new InvalidRecipeException("body");
        }
        return document;
    }

    /** 경로가 이보다 길면 자른다 — 모르는 칸의 이름은 편집기가 보낸 문자열이라 길이가 우리 손에 없다. */
    private static final int MAX_FIELD_LENGTH = 96;

    /**
     * 예외의 경로를 {@code outputs[0].crop.x} 모양으로. 값은 싣지 않는다 — 자유 입력을 되돌려주는 자리가
     * 되면 안 된다. 칸 이름은 우리 record에서 온 것이라 고정 문자열인데 <b>모르는 칸 이름은 예외</b>다 —
     * 그것은 편집기가 보낸 이름이라(경로의 마지막 조각으로 들어온다) 제어 문자를 지우고 길이를 잘라
     * 로그·본문을 못 위조하게 한다.
     */
    private static String pathOf(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        if (path.isEmpty()) {
            return "body";
        }
        String cleaned = path.toString().replaceAll("\\p{Cntrl}", "");
        return cleaned.substring(0, Math.min(cleaned.length(), MAX_FIELD_LENGTH));
    }
}
