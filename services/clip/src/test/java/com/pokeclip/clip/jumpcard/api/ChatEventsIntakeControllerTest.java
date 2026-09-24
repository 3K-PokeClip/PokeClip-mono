package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수집기가 채팅·후원을 미는 내부 문({@code POST /internal/broadcasts/{streamId}/chat-events}). MockMvc지만
 * <b>실제 시큐리티 체인</b>을 태운다 — 내부 토큰이 없으면 컨트롤러에 닿지도 못한다({@code HighlightIntakeControllerTest}와 같다).
 *
 * <p>연결은 시험마다 <b>다른 방송·사람 번호</b>로 연다 — 서블릿 밖 {@code SseEmitter}는 완료 콜백을 안 불러
 * 명부에서 안 빠지므로, 번호가 겹치면 뒤 시험이 앞 시험의 연결을 본다.
 */
@AutoConfigureMockMvc
class ChatEventsIntakeControllerTest extends IntegrationTestSupport {

    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private static final String ONE_CHAT = """
            {"events":[{"seq":1,"seqEpoch":1757000000000,"kind":"chat","time":"2026-09-03T15:00:01Z",
                        "timeBasis":"message","nickname":"n","senderChannelId":"u","role":null,"text":"t",
                        "amount":null,"donationType":null}]}""";

    private final MockMvc mvc;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry streams;

    ChatEventsIntakeControllerTest(MockMvc mvc, BroadcastRepository broadcasts, CardStreamRegistry streams) {
        this.mvc = mvc;
        this.broadcasts = broadcasts;
        this.streams = streams;
    }

    @Test
    void 내부_토큰이_없으면_401이다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-x/chat-events").contentType(APPLICATION_JSON).content(ONE_CHAT))
                .andExpect(status().isUnauthorized());
    }

    /**
     * F11 — <b>그 방송에 연결이 없으면 DB를 안 친다.</b> 수집기는 채팅마다(묶음마다) 부르고 clip은 요청마다
     * {@code existsByStreamId}를 쳤다. 명부에 <b>없는</b> 방송으로 보내 200인 것이 「안 쳤다」의 증거다 —
     * 쳤으면 404다(아래 시험이 대조).
     */
    @Test
    void 연결이_없으면_방송을_안_찾고_200과_0이다() throws Exception {
        mvc.perform(post("/internal/broadcasts/" + unique("nobody") + "/chat-events")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON).content(ONE_CHAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.dropped").value(0));
    }

    /** 연결이 있는데 명부에 방송이 없다 — 수집기의 {@code UNKNOWN_BROADCAST} 셈이 좁아진 뜻이다. */
    @Test
    void 연결이_있는데_방송이_없으면_404다() throws Exception {
        String streamId = unique("orphan");
        streams.open(streamId, unique("u"), Duration.ofMinutes(1));

        mvc.perform(post("/internal/broadcasts/" + streamId + "/chat-events")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON).content(ONE_CHAT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
    }

    @Test
    void 연결과_방송이_있으면_받은_수와_건너뛴_수를_준다() throws Exception {
        String streamId = unique("live");
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, 1L, Instant.now(), null));
        streams.open(streamId, unique("u"), Duration.ofMinutes(1));

        mvc.perform(post("/internal/broadcasts/" + streamId + "/chat-events")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON)
                        .content("""
                                {"events":[{"seq":1,"kind":"chat","text":"a"},
                                           {"seq":2,"kind":"future-kind","title":"x"},
                                           {"seq":3,"kind":"donation","text":"b"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.dropped").value(1));
    }

    /** 400은 <b>구조 결함만</b>이다(F8). 모르는 kind는 400이 아니다 — 위 시험. */
    @Test
    void 구조가_깨지면_400_invalid_request와_칸_이름이다() throws Exception {
        expect400("{}", "events");
        expect400("{\"events\":[]}", "events");
        expect400("{\"events\":{}}", "events");
        expect400("{\"events\":[{\"kind\":\"chat\"}]}", "seq");
        expect400("{\"events\":[{\"seq\":0,\"kind\":\"chat\"}]}", "seq");
        expect400("{\"events\":[{\"seq\":\"1\",\"kind\":\"chat\"}]}", "seq");
        expect400("{\"events\":[{\"seq\":1}]}", "kind");
        expect400("{\"events\":[{\"seq\":1,\"kind\":\"  \"}]}", "kind");
        expect400("{\"events\":[3]}", "events");
    }

    private void expect400(String body, String field) throws Exception {
        mvc.perform(post("/internal/broadcasts/" + unique("bad") + "/chat-events")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value(field));
    }

    private static String unique(String prefix) {
        return "chat-in-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
