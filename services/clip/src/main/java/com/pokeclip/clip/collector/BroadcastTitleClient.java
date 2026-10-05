package com.pokeclip.clip.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 방송 목록 한 장의 치지직 제목·카테고리를 수집기에 <b>한 번에</b> 묻는다(POK-259). 수집기는 걷고 있는 방송마다 1분에 한 번 제목을
 * 관측해 쌓고(POK-234 PR-C), {@code GET /internal/broadcast-info/latest?streamIds=}가 방송마다 제목이 있는 마지막 관측을 준다.
 *
 * <p><b>표를 직접 읽지 않는다.</b> {@code broadcast_info}는 같은 DB에 있지만 수집기 표다. 서버끼리 서로의 표를 읽지 않는 것이
 * {@code services/README.md}의 규율이다(조각 장부는 계약으로 연 예외다).
 *
 * <p><b>예외를 안 던진다.</b> 제목은 부가 칸이라 수집기가 꺼졌거나(주소 없음), 죽었거나, 모르는 모양을 주면 빈 맵을 돌려주고
 * 목록은 그대로 나간다. 화면은 그때 방송 번호를 보인다. 대가: 수집기가 응답 없이 매달리면 목록이 시한(접속 2초 + 읽기 3초)만큼
 * 늦어진다.
 *
 * <p>clip은 이 응답을 <b>해석한다</b>({@link CollectorClient}는 채팅 창구 본문을 그대로 넘긴다). 목록 응답의 칸 둘로 옮겨 실어야
 * 해서다.
 */
@Component
public class BroadcastTitleClient {

    private static final Logger log = LoggerFactory.getLogger(BroadcastTitleClient.class);

    private static final String PATH = "/internal/broadcast-info/latest";

    private final CollectorClient collector;
    private final ObjectMapper mapper;

    BroadcastTitleClient(CollectorClient collector, ObjectMapper mapper) {
        this.collector = collector;
        this.mapper = mapper;
    }

    /**
     * @param streamIds 방송 번호. 수집기 상한(100)은 방송 목록 한 장의 상한과 같다
     * @return 방송 번호 → 제목. 제목이 없는 방송과, 묻지 못한 경우 전부는 맵에 없다
     */
    public Map<String, BroadcastTitle> titlesOf(List<String> streamIds) {
        Map<String, BroadcastTitle> titles = new HashMap<>();
        if (streamIds.isEmpty() || !collector.enabled()) {
            // 꺼짐은 로그를 안 남긴다. 매 목록 요청마다 참이라 줄만 쌓인다(CollectorClient.get과 같은 이유)
            return titles;
        }
        try {
            CollectorResponse response = collector.get(PATH, Map.of("streamIds", String.join(",", streamIds)));
            if (response.status() != 200) {
                log.warn("clip.broadcast_title.unavailable cause=status={}", response.status());
                return titles;
            }
            // 🔴 본문이 비면(null) readTree가 잭슨 예외가 아니라 IllegalArgumentException을 던지고, 공백뿐이면 null을 돌려준다.
            // 둘 다 그대로 두면 목록 전체가 500이다(로컬 리뷰 · PR #214 codex). 「읽을 것이 없다」를 한 자리에서 못 읽은 것으로 접는다
            JsonNode root = response.body() == null ? null : mapper.readTree(response.body());
            if (root == null || !root.isObject()) {
                log.warn("clip.broadcast_title.unreadable cause=not_object");
                return titles;
            }
            JsonNode byStream = root.path("titles");
            for (String streamId : streamIds) {
                JsonNode one = byStream.path(streamId);
                String title = text(one.path("title"));
                if (title != null && !title.isBlank()) {
                    titles.put(streamId, new BroadcastTitle(title, text(one.path("category"))));
                }
            }
            return titles;
        } catch (CollectorErrors.CollectorUnavailableException e) {
            // CollectorClient가 원인을 이미 남겼다
            return titles;
        } catch (JacksonException e) {
            log.warn("clip.broadcast_title.unreadable cause={}", e.getClass().getSimpleName());
            return titles;
        }
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
