package com.pokeclip.clip.recipe;

import com.pokeclip.clip.recipe.RecipeErrors.InvalidRecipeException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
 * <p>컨트롤러가 {@code @RequestBody String}으로 받아 여기로 넘기는 이유도 같다 — 스프링의 본문 변환이
 * 실패하면 {@code HttpMessageNotReadableException}이 나가고 그것을 400 봉투로 바꾸려면 <b>전역</b> 조언에
 * 갈래를 더해야 해서 다른 문의 400 모양이 같이 바뀐다. 문자열로 받으면 파싱 실패도 이 문 안에서 끝난다.
 */
@Component
public class RecipeParser {

    /**
     * 본문 상한. 자막 수백 줄이 들어와도 수십 KB다 — 이보다 크면 레시피가 아니라 저장 공간을 노리는 것이다.
     * 톰캣은 JSON 본문 크기를 기본으로 안 막는다({@code max-http-post-size}는 폼 전용).
     */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final ObjectMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 1.5를 trackId 1로, "1"을 1로 접지 않는다 — 계약이 조용히 두 갈래가 된다(ChatEventsRequest와 같은 자세).
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    /**
     * @throws InvalidRecipeException JSON이 아니거나, 모르는 칸이 있거나, 칸의 형이 다르다.
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
        try {
            return strict.readValue(body, RecipeDocument.class);
        } catch (JacksonException e) {
            throw new InvalidRecipeException(pathOf(e));
        }
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
