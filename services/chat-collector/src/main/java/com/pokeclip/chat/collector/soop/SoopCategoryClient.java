package com.pokeclip.chat.collector.soop;

import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SOOP 카테고리 리스트({@code GET broad/category/list}, 방송 송출 문서 맨 아래, POK-462). 번호 → 이름 표를 받는다. 사진은 응답에 없다.
 *
 * <p>앱 아이디는 {@code client_id} 쿼리다. 🔴 문서 표는 인자 이름을 {@code access_token}이라 적었는데 예시 주소와 실서버는 {@code client_id}다
 * (2026-10-25 실측: 인자 없이 -1100 「argument is invalid」, 틀린 값 -1104 「invalid client」). 앱 아이디는 공개 값이라 주소에 실어도 된다
 * (공개 방송 목록 {@code broad/list}와 같다). 비밀 키는 안 쓴다.
 */
public class SoopCategoryClient {

    static final String PATH = "/broad/category/list";

    private final RestClient restClient;
    private final String apiBaseUri;
    private final String clientId;

    /** @param builder 시한이 걸린 빌더({@link SoopWatchConfiguration}) */
    public SoopCategoryClient(RestClient.Builder builder, String apiBaseUri, String clientId) {
        this.restClient = builder.build();
        this.apiBaseUri = apiBaseUri;
        this.clientId = clientId;
    }

    /**
     * @return 번호 → 이름(대카테고리와 자식을 같이 편다)
     * @throws RuntimeException HTTP 실패 · 결과 코드 음수 · 목록이 없거나 비면. 응답 본문은 싣지 않는다
     */
    public Map<String, String> fetch() {
        Map<?, ?> body = restClient.get()
                .uri(UriComponentsBuilder.fromUriString(apiBaseUri + PATH)
                        .queryParam("client_id", "{id}")
                        .queryParam("locale", "ko_KR")
                        .encode().buildAndExpand(Map.of("id", clientId)).toUri())
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("soop category list: empty body");
        }
        if (body.get("result") instanceof Number result && result.intValue() < 0) {
            throw new IllegalStateException("soop category list: result=" + result.intValue());
        }
        Map<String, String> names = new HashMap<>();
        if (body.get("broad_category") instanceof List<?> categories) {
            collect(categories, names);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("soop category list: no categories");
        }
        return names;
    }

    private static void collect(List<?> categories, Map<String, String> into) {
        for (Object item : categories) {
            if (!(item instanceof Map<?, ?> category)) {
                continue;
            }
            String number = SoopCategoryNames.normalize(category.get("cate_no"));
            if (number != null && category.get("cate_name") instanceof String name && !name.isBlank()) {
                into.put(number, name.strip());
            }
            if (category.get("child") instanceof List<?> children) {
                collect(children, into);
            }
        }
    }
}
