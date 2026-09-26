package com.pokeclip.upload.clip;

import com.pokeclip.upload.work.InternalHttp;
import com.pokeclip.upload.work.InternalHttp.Reply;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * clip의 일꾼 문 셋({@code /internal/uploads/{id}/start|session|result}). 판정은 clip이 하고 여기는 옮기기만 한다.
 *
 * <p>🔴 {@link #session}이 돌려주는 주소가 <b>먼저 적힌 주소</b>다. 일꾼은 자기가 받은 주소가 아니라 이것으로 바이트를 보낸다 —
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

    public void uploaded(long uploadId, String videoId) {
        result(uploadId, "UPLOADED", Map.of("videoId", videoId));
    }

    public void failed(long uploadId, String code, String message) {
        result(uploadId, "FAILED", error(code, message));
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
