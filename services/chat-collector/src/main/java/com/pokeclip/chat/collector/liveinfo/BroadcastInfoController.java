package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.query.WindowRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 방송 정보 창구(POK-234 태스크 10).
 *
 * <p>{@code GET /internal/streams/{streamId}/broadcast-info?since=}
 *
 * <p>모르는 방송도 <b>200</b>이다 — {@code latest:null} · {@code series:[]}. 404로 가르면
 * 「그 방송이 없다」와 「아직 한 번도 관측을 못 했다」가 같아지는데, 후자는 <b>방송이 켜진 직후
 * 매번</b> 지나가는 정상 상태다(관측 주기가 PR-C에서 붙는다).
 *
 * <p>400 사유는 {@code QueryErrors}가 낸다 — <b>그 목록에 이 창구를 넣는 것이 이 태스크의
 * 필수 항이다</b>(계획 검증 F13). 빼면 {@code since} 오타가 400이 아니라 500으로 나가고,
 * 부르는 쪽은 자기 입력 오류를 「수집 서버 장애」로 읽는다.
 *
 * <p><b>제목·태그는 로그에 안 싣는다.</b> 이 클래스에 로거가 없는 것이 그 뜻이다.
 */
@RestController
public class BroadcastInfoController {

    /**
     * {@code since}를 안 주면 보는 구간. 되감기 화면이 한 번에 보여 주는 폭과 같다
     * ({@code pokeclip.query.window-max}가 1시간인 것과 짝이다).
     */
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(1);

    /**
     * 한 번에 주는 점 수 상한. 차트 창구의 {@code chart-max-buckets}와 <b>같은 720</b>인데
     * 설정을 공유하지 않는다 — 저쪽은 「1시간 ÷ 5초」에서 나온 값이고 여기는 「관측 주기
     * 몇 시간치」라 뿌리가 다르다. 한쪽을 바꾸려고 다른 쪽이 같이 움직이면 안 된다.
     */
    private static final int MAX_POINTS = 720;

    /** 한 번에 묻는 방송 수 상한. clip 방송 목록 한 장의 상한(100)과 같다. */
    static final int MAX_STREAMS = 100;

    private final BroadcastInfoStore store;

    public BroadcastInfoController(BroadcastInfoStore store) {
        this.store = store;
    }

    @GetMapping("/internal/streams/{streamId}/broadcast-info")
    public Response get(@PathVariable String streamId,
                        @RequestParam(required = false) String since) {
        Instant from = (since == null || since.isBlank())
                // 벽시계다. 「지금부터 한 시간 전」은 물어본 순간이 기준이라 다른 값이 없다.
                ? Instant.now().minus(DEFAULT_WINDOW)
                : WindowRequest.parseAt(since);

        List<Point> series = store.series(streamId, from, MAX_POINTS).stream()
                .map(info -> new Point(info.observedAt(), info.viewers()))
                .toList();
        Latest latest = store.latest(streamId)
                .map(info -> new Latest(info.title(), info.tags(), info.category(),
                        info.viewers(), info.observedAt()))
                .orElse(null);
        return new Response(latest, series);
    }

    /**
     * 여러 방송의 마지막 제목을 한 번에 준다(POK-259). clip 방송 목록이 한 장(최대 100개)마다 한 번 부른다. 위 창구를 방송마다 부르면
     * 한 장에 100번 왕복이다.
     *
     * <p>{@code POST /internal/broadcast-info/latest} 본문 {@code {"streamIds":["a","b"]}}. 읽기인데 POST인 이유: 방송 번호는 최대
     * 128자라 100개를 주소에 이으면 13KB쯤이 되어 톰캣 요청 줄 한도(8KB)에 걸리고, 쉼표가 든 번호는 쪼개진다(PR #214 codex).
     * 제목이 있는 관측이 없는 방송은 {@code titles}에 키가 없다. 모르는 방송도 200이다(위 창구와 같은 이유).
     * {@value #MAX_STREAMS}개를 넘으면 400 {@code too_many}다. 잘라 주면 부르는 쪽은 뒤 방송 제목이 「없다」로 보인다.
     * 본문을 못 읽으면 스프링 기본 400이다(부르는 쪽은 clip 하나이고 400을 「못 물었다」로 접는다).
     */
    @PostMapping("/internal/broadcast-info/latest")
    public ResponseEntity<?> latestTitles(@RequestBody(required = false) TitlesRequest request) {
        List<String> ids = request == null || request.streamIds() == null ? List.of()
                : request.streamIds().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.size() > MAX_STREAMS) {
            return ResponseEntity.badRequest().body(Map.of("error", "too_many"));
        }
        return ResponseEntity.ok(new Titles(store.latestTitles(ids)));
    }

    /** 제목 창구 요청 본문. */
    public record TitlesRequest(List<String> streamIds) {
    }

    /** {@code titles}: 방송 번호 → 마지막 제목. */
    public record Titles(Map<String, BroadcastInfoStore.Title> titles) {
    }

    /**
     * <b>{@code latest}는 {@code series}의 마지막 줄이 아니다.</b> {@code since}가 늦으면
     * 추이는 비어도 최신 제목은 있다 — 화면 위쪽의 제목·카테고리는 구간과 무관하게 보여야 한다.
     */
    public record Response(Latest latest, List<Point> series) {
    }

    /** 화면 위쪽. {@code observedAt}은 <b>우리 시계</b>다({@link BroadcastInfo}). */
    public record Latest(String title, List<String> tags, String category,
                         Integer viewers, Instant observedAt) {
    }

    /** 추이 한 점. <b>{@code viewers}가 {@code null}이면 그 회차에 못 찾은 것</b>이지 0이 아니다. */
    public record Point(Instant observedAt, Integer viewers) {
    }
}
