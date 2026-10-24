package com.pokeclip.upload.clip;

import com.pokeclip.upload.work.InternalHttp;
import com.pokeclip.upload.work.InternalHttp.Reply;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * clip의 일꾼 문 넷({@code /internal/uploads/{id}/start|session|result|heartbeat}). 판정은 clip이 하고 여기는 옮기기만 한다.
 *
 * <p>🔴 {@link #session}이 돌려주는 주소가 <b>먼저 적힌 주소</b>다. 일꾼은 자기가 받은 주소가 아니라 이것으로 바이트를 보낸다 :
 * 그래야 일꾼 둘이 겹쳐도 영상이 하나다.
 */
public class ClipUploadApi {

    private final InternalHttp http;
    private final String baseUrl;

    public ClipUploadApi(InternalHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    /** @param sessionUri 이미 적힌 이어 올리기 주소. 없으면 null */
    public record Start(boolean found, boolean proceed, String status, String sessionUri) {
    }

    /** @throws com.pokeclip.upload.work.Unavailable clip이 답을 안 준다 */
    public Start start(long uploadId) {
        Reply reply = http.post(url(uploadId, "start"), Map.of());
        if (reply.status() == 404) {
            return new Start(false, false, null, null);
        }
        requireOk(reply, "start");
        String session = reply.body().path("sessionUri").asString(null);
        return new Start(true, reply.body().path("proceed").asBoolean(), reply.body().path("status").asString(null),
                session);
    }

    /** @return 쓸 주소(먼저 적힌 것). 이 주문이 이미 끝났으면(409) null */
    public String session(long uploadId, String sessionUri) {
        Reply reply = http.post(url(uploadId, "session"), Map.of("sessionUri", sessionUri));
        if (reply.status() == 409) {
            return null;
        }
        requireOk(reply, "session");
        return reply.body().path("sessionUri").asString();
    }

    /**
     * 아직 이 주문을 처리하고 있다는 신호(POK-333). clip은 상태를 안 바꾸고 마지막 일꾼 활동 시각만 옮긴다: 사람이 「안 올라갔다」로
     * 자리를 비우는 것은 그 시각에서 30분 뒤라, 이 일꾼이 같은 주소로 보내는 동안에는 비지 않는다.
     *
     * @return clip이 준 상태 코드. 200이 아니면(옛 clip은 모르는 문이라 404) 부른 쪽이 WARN만 남긴다
     */
    public int heartbeat(long uploadId) {
        return http.post(url(uploadId, "heartbeat"), Map.of()).status();
    }

    /**
     * 올림 보고에 싣는 썸네일 결과(POK-291 명세 §6). 납작한 칸 둘({@code thumbnailOutcome}·{@code thumbnailErrorCode})로 간다.
     *
     * @param outcome   {@code SET}·{@code FAILED}·{@code NONE}
     * @param errorCode {@code FAILED}일 때만. {@code THUMBNAIL_*}
     */
    public record ThumbnailReport(String outcome, String errorCode) {
        public static final ThumbnailReport NONE = new ThumbnailReport("NONE", null);
        public static final ThumbnailReport SET = new ThumbnailReport("SET", null);

        public static ThumbnailReport failed(String code) {
            return new ThumbnailReport("FAILED", code);
        }
    }

    /** @param channelId 영상을 올린 유튜브 채널(POK-423). 모르면 null이고 칸을 안 싣는다 */
    public void uploaded(long uploadId, String videoId, ThumbnailReport thumbnail, String channelId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("videoId", videoId);
        if (channelId != null) {
            body.put("channelId", channelId);
        }
        body.put("thumbnailOutcome", thumbnail.outcome());
        if (thumbnail.errorCode() != null) {
            body.put("thumbnailErrorCode", thumbnail.errorCode());
        }
        result(uploadId, "UPLOADED", body);
    }

    /** {@link #failIfNoSession}의 답 셋. */
    public sealed interface FailOutcome {
        /** 실패로 닫혔다(주소가 없었다). */
        record Failed() implements FailOutcome {
        }

        /** 그사이 겹친 일꾼이 주소를 적었다. 상태는 그대로이고 이 주소로 이어 갈 수 있다. */
        record SessionExists(String sessionUri) implements FailOutcome {
        }

        /** 이미 끝난 주문이다(409 그 밖 · 404 주문 없음, 또는 칸을 모르는 옛 clip이 확인 중으로 받았다). */
        record Terminal() implements FailOutcome {
        }
    }

    /**
     * 「주소가 없을 때만 실패」(POK-250). 실패를 보내는 순간 겹친 일꾼이 주소를 적었으면 clip은 상태를 안 바꾸고 그 주소를 돌려준다.
     * 실패 전에 clip에 다시 묻던 옛 방식은 그 물음과 실패 사이에 적힌 주소를 못 잡았다: 판정을 clip의 잠금 안 한 번으로 옮겼다.
     *
     * <p>🔴 칸을 모르는 옛 clip은 주소 있는 줄의 실패를 200 {@code checking}으로 받는다. 그것은 {@link FailOutcome.Terminal}로 본다:
     * 실패로 닫혔다고 읽으면 안 된다(주소가 있는 줄이다). 쪽지를 지우는 결과는 지금 결함과 같아 넓어지지 않는다(배포 순서 clip 먼저).
     *
     * @throws com.pokeclip.upload.work.Unavailable clip이 답을 안 준다
     */
    public FailOutcome failIfNoSession(long uploadId, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", "FAILED");
        body.putAll(error(code, message));
        body.put("onlyIfNoSession", true);
        Reply reply = http.post(url(uploadId, "result"), body);
        if (reply.status() == 404) {
            // 주문이 지워졌다(탈퇴 정리 등). start의 404와 같이 끝난 주문으로 본다: 다시 해도 같다.
            return new FailOutcome.Terminal();
        }
        if (reply.status() == 409) {
            String uri = reply.body() == null ? null : reply.body().path("sessionUri").asString(null);
            if (reply.body() != null && "SESSION_EXISTS".equals(reply.body().path("reason").asString(null)) && uri != null) {
                return new FailOutcome.SessionExists(uri);
            }
            return new FailOutcome.Terminal();
        }
        requireOk(reply, "result");
        return "checking".equals(reply.body().path("status").asString(null))
                ? new FailOutcome.Terminal() : new FailOutcome.Failed();
    }

    public void checking(long uploadId, String code, String message) {
        result(uploadId, "CHECKING", error(code, message));
    }

    private void result(long uploadId, String outcome, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        body.putAll(extra);
        Reply reply = http.post(url(uploadId, "result"), body);
        // 409 TERMINAL은 같은 주문을 누가 먼저 끝냈다는 뜻이다(쪽지가 두 번 왔다). 할 일이 없다.
        if (reply.status() != 409) {
            requireOk(reply, "result");
        }
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", code);
        body.put("errorMessage", message);
        return body;
    }

    private String url(long uploadId, String door) {
        return baseUrl + "/internal/uploads/" + uploadId + "/" + door;
    }

    private static void requireOk(Reply reply, String door) {
        if (reply.status() != 200 || reply.body() == null) {
            // 4xx는 우리 버그다(모양이 틀렸다). 다시 해도 같다.
            throw new IllegalStateException("clip " + door + " 문이 " + reply.status() + "을 줬다");
        }
    }
}
