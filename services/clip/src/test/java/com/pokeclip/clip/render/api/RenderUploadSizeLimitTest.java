package com.pokeclip.clip.render.api;

import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 썸네일 그림을 함께 보내는 「영상 만들기」(multipart, POK-291). <b>진짜 톰캣으로 잰다</b>: 크기 상한은 서블릿 층이 자르는데,
 * MockMvc는 이미 파싱된 요청을 넣어 그 층을 건너뛴다(그쪽에 쓰면 상한 설정을 지워도 초록이다. auth ProfilePhotoSizeLimitTest와 같은 이유).
 *
 * <p>「넘으면 막힌다」와 「딱 맞으면 통과한다」는 다른 단언이다: 요청 상한을 파일 상한과 같게 두면 정확히 10MB가 413이 된다.
 */
class RenderUploadSizeLimitTest extends RenderUploadTestSupport {

    private static final int LIMIT = 10 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] GIF = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final String 그림_고름 = "{\"upload\":{\"title\":\"그림\",\"thumbnail\":{\"source\":\"file\"}}}";

    private final int port;

    RenderUploadSizeLimitTest(MockMvc mvc, JdbcTemplate jdbc, @LocalServerPort int port) {
        super(mvc, jdbc);
        this.port = port;
    }

    @Test
    void 십_메가바이트를_넘는_그림은_413이고_아무것도_안_남는다() throws Exception {
        HttpResponse<String> response = 보낸다(그림_고름, 그림(PNG, LIMIT + 1024), "big.png");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("payload_too_large");
        assertThat(count("clips")).isZero();
        assertThat(count("upload_requests")).isZero();
    }

    /**
     * 정확히 상한인 PNG는 통과하고, 그림이 스트리머 접두사 아래에 <b>우리가 판정한 형식</b>으로 놓이며 의도가 그 자리를 가리킨다.
     * 보낸 쪽이 밝힌 형식(application/octet-stream)은 안 믿는다.
     */
    @Test
    void 정확히_십_메가바이트인_PNG는_통과하고_창고에_놓인다() throws Exception {
        HttpResponse<String> response = 보낸다(그림_고름, 그림(PNG, LIMIT), "exact.bin");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode 의도 = MAPPER.readTree(jdbc.queryForObject("""
                SELECT json_build_object('source', thumbnail_source, 'key', thumbnail_s3_key, 'type', thumbnail_content_type)
                  FROM upload_requests""", String.class));
        assertThat(의도.get("source").asString()).isEqualTo("file");
        assertThat(의도.get("type").asString()).isEqualTo("image/png");
        String key = 의도.get("key").asString();
        assertThat(key).startsWith("upload-thumbnails/" + TestIds.STREAMER + "/").endsWith(".png");
        HeadObjectResponse 놓인_것 = LocalStackFixture.s3().headObject(b -> b.bucket(창고).key(key));
        assertThat(놓인_것.contentLength()).isEqualTo(LIMIT);
        assertThat(놓인_것.contentType()).isEqualTo("image/png");
        assertThat(MAPPER.readTree(response.body()).at("/uploadRequest/thumbnailSource").asString()).isEqualTo("file");
    }

    @Test
    void JPEG는_jpg로_놓인다() throws Exception {
        HttpResponse<String> response = 보낸다(그림_고름, 그림(JPEG, 2048), "a.png");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT thumbnail_content_type || ' ' || thumbnail_s3_key FROM upload_requests",
                String.class)).startsWith("image/jpeg upload-thumbnails/").endsWith(".jpg");
    }

    /** 첫 바이트가 JPEG·PNG가 아니면 415. 이름(.png)과 밝힌 형식은 판정에 안 쓴다. */
    @Test
    void JPEG_PNG가_아니면_415이고_아무것도_안_남는다() throws Exception {
        HttpResponse<String> response = 보낸다(그림_고름, 그림(GIF, 2048), "fake.png");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).contains("unsupported_image");
        assertThat(count("clips")).isZero();
        assertThat(count("upload_requests")).isZero();
    }

    /**
     * 그림을 창고에 둔 뒤 갈래 처리가 거절되면(여기서는 조각이 아직 없어 409) 그 그림을 지운다(POK-291 로컬 리뷰 1라운드). 안 지우면
     * 아무 줄도 안 가리키는 그림이 남고, 그 사이 탈퇴 정리가 이미 끝났으면 영영 안 지워진다.
     */
    @Test
    void 그림을_둔_뒤_거절되면_그_그림을_지운다() throws Exception {
        jdbc.update("DELETE FROM stream_segments");
        int 전 = 놓인_그림_수();

        HttpResponse<String> response = 보낸다(그림_고름, 그림(PNG, 2048), "a.png");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains("source_not_ready");
        assertThat(놓인_그림_수()).as("거절됐는데 그림이 창고에 남았다").isEqualTo(전);
        assertThat(count("upload_requests")).isZero();
    }

    private int 놓인_그림_수() {
        return LocalStackFixture.s3().listObjectsV2(b -> b.bucket(창고).prefix("upload-thumbnails/" + TestIds.STREAMER + "/"))
                .contents().size();
    }

    /** 그림 파트는 file일 때만 받는다. request 파트가 없으면 400 field=request. */
    @Test
    void 파트가_어긋나면_400이다() throws Exception {
        HttpResponse<String> 장면인데_그림 = 보낸다("{\"upload\":{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":1}}}",
                그림(PNG, 100), "a.png");
        assertThat(장면인데_그림.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(장면인데_그림.body()).get("field").asString()).isEqualTo("thumbnail");

        HttpResponse<String> 요청_없음 = 보낸다(null, 그림(PNG, 100), "a.png");
        assertThat(요청_없음.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(요청_없음.body()).get("field").asString()).isEqualTo("request");
        assertThat(count("clips")).isZero();
    }

    private static byte[] 그림(byte[] magic, int size) {
        byte[] body = new byte[size];
        System.arraycopy(magic, 0, body, 0, magic.length);
        return body;
    }

    /** multipart 본문을 손으로 짠다: 파트 {@code request}(JSON) + {@code thumbnail}(그림, 밝힌 형식은 octet-stream). */
    private HttpResponse<String> 보낸다(String request, byte[] image, String fileName) throws Exception {
        String boundary = "pok291-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (request != null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + request + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"thumbnail\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/clip/broadcasts/" + 방송
                            + "/recipes/" + 편집본 + "/renders"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + TestTokens.access(요청자))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
