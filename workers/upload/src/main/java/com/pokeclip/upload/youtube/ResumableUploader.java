package com.pokeclip.upload.youtube;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 유튜브 이어 올리기(resumable upload) 규약 셋: 시작 · 「어디까지 받았나」 · 조각 보내기. 그리고 다 올린 뒤의 썸네일 붙이기
 * ({@code thumbnails.set}, POK-291). 응답을 결과 종류로 나눠 돌려주고
 * 무엇을 할지는 부른 쪽({@code UploadProcessor})이 정한다.
 *
 * <p><b>유튜브는 한 주소가 바이트를 다 받았을 때만 영상을 만든다.</b> 그래서 「주소에 물어 덜 받았다(308)」는 영상이 없다는
 * 증거이고, 「다 받았다(200·201)」는 영상 번호를 준다. 이 둘이 「결과를 모르면 다시 올리지 않는다」를 지키는 도구다.
 *
 * <p>🔴 토큰과 이어 올리기 주소는 어디에도 찍지 않는다. 주소 자체가 올리기 권한이다. 리다이렉트는 끈다(토큰이 따라가지 않게).
 */
public class ResumableUploader {

    /** 하루 올리기 한도·쿼터에 걸렸다는 유튜브 사유들. 다시 해도 오늘은 안 된다. */
    static final Set<String> QUOTA_REASONS = Set.of("quotaExceeded", "uploadLimitExceeded", "dailyLimitExceeded");
    /** 시간이 지나면 풀리는 속도 제한 사유들(403으로 온다). auth {@code YoutubeOAuthClient}도 같은 사유를 일시 오류로 본다. */
    static final Set<String> RATE_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded", "servingLimitExceeded");
    private static final Pattern RANGE = Pattern.compile("bytes=0-(\\d+)");

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String startUrl;
    private final String thumbnailUrl;

    public ResumableUploader(ObjectMapper mapper, String startUrl, String thumbnailUrl) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapper = mapper;
        this.startUrl = startUrl;
        this.thumbnailUrl = thumbnailUrl;
    }

    /** 시작 결과. */
    public sealed interface Start {
        /** 이어 올리기 주소를 받았다. 아직 영상은 없다. */
        record Session(String uri) implements Start { }

        /** 하루 한도에 걸렸다. 주소가 없으니 영상도 없다. */
        record Quota(String reason) implements Start { }

        /** 유튜브가 거절했다(4xx). 주소가 없으니 영상도 없다. 다시 해도 같다. */
        record Rejected(int status, String reason) implements Start { }

        /** 토큰이 안 먹혔다(401). */
        record Unauthorized() implements Start { }

        /** 5xx·끊김. 주소를 못 받았으니 영상은 없다. 다시 해 볼 수 있다. */
        record Transient(String what) implements Start { }
    }

    /** 주소에 묻거나 조각을 보낸 결과. */
    public sealed interface Progress {
        /** 다 받았다. 영상이 생겼다. */
        record Done(String videoId) implements Progress { }

        /** 덜 받았다 = 아직 영상이 없다. {@code next}부터 보내면 된다. */
        record Incomplete(long next) implements Progress { }

        /** 주소가 없어졌다(404·410). 끝났는지 알 수 없다. */
        record Gone(int status) implements Progress { }

        /** 토큰이 안 먹혔다(401). */
        record Unauthorized() implements Progress { }

        /** 바이트를 거절했다(4xx). 끝났는지는 주소에 다시 물어야 안다. */
        record Rejected(int status, String reason) implements Progress { }

        /** 5xx·끊김. 응답을 못 받았다: 끝났는지 모른다. 주소에 다시 물어야 한다. */
        record Transient(String what) implements Progress { }
    }

    /**
     * @param privacyStatus 주문서 값(주문한 사람이 고른 것, ADR-084). 유튜브가 이 범위를 쓰는 사정(문서 둘이 어긋나고 실측은
     *                      테스트 상태 앱 하나다)은 services/README.md 업로드 절
     * @param tags          비었으면 칸을 아예 안 싣는다
     */
    public Start start(String accessToken, String title, String description, String privacyStatus, List<String> tags,
                       boolean madeForKids, long size) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode snippet = body.putObject("snippet");
        snippet.put("title", title);
        snippet.put("description", description);
        // 20 = Gaming. MVP 게임은 LoL 하나다.
        snippet.put("categoryId", "20");
        if (!tags.isEmpty()) {
            ArrayNode array = snippet.putArray("tags");
            tags.forEach(array::add);
        }
        ObjectNode status = body.putObject("status");
        status.put("privacyStatus", privacyStatus);
        status.put("selfDeclaredMadeForKids", madeForKids);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(startUrl + "?uploadType=resumable&part=snippet,status"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Upload-Content-Length", String.valueOf(size))
                    .header("X-Upload-Content-Type", "video/mp4")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200 || code == 201) {
                Optional<String> location = response.headers().firstValue("Location");
                return location.<Start>map(Start.Session::new).orElseGet(() -> new Start.Transient("no Location"));
            }
            if (code == 401) {
                return new Start.Unauthorized();
            }
            String reason = reason(response.body());
            // 사유로 가른다. 채널 업로드 한도(uploadLimitExceeded)는 403이 아니라 400으로 온다(PR #199 codex).
            if (QUOTA_REASONS.contains(reason)) {
                return new Start.Quota(reason);
            }
            // 속도 제한은 거절이 아니다. 닫으면 사람이 다시 눌러야 한다(PR #199 codex 2판).
            if (code == 429 || code / 100 == 5 || RATE_REASONS.contains(reason)) {
                return new Start.Transient("HTTP " + code + " " + reason);
            }
            return new Start.Rejected(code, reason);
        } catch (IOException e) {
            return new Start.Transient(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Start.Transient("interrupted");
        }
    }

    /** 「어디까지 받았나」. 바이트 없이 {@code Content-Range: bytes *&#47;size}를 보낸다. 토큰이 없으면 머리 없이 묻는다. */
    public Progress status(String sessionUri, String accessToken, long size) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(sessionUri))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Range", "bytes */" + size)
                .PUT(HttpRequest.BodyPublishers.noBody());
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return send(request.build());
    }

    /** {@code offset}부터 {@code length}바이트를 보낸다. */
    public Progress put(String sessionUri, String accessToken, Path file, long offset, int length, long size) {
        byte[] chunk = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(chunk);
        } catch (IOException e) {
            throw new IllegalStateException("받아 둔 파일을 못 읽었다", e);
        }
        return send(HttpRequest.newBuilder(URI.create(sessionUri))
                // 8MB를 느린 망으로 보내도 넉넉하다. 넘기면 끊김으로 다룬다(주소에 다시 묻는다).
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "video/mp4")
                .header("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + size)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build());
    }

    private Progress send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200 || code == 201) {
                // 완료 응답에서는 id만 읽는다: 실제 공개 범위(status.privacyStatus)는 안 본다(README 아직 없는 것)
                String id = mapper.readTree(response.body()).path("id").asString(null);
                return id == null ? new Progress.Transient("no id") : new Progress.Done(id);
            }
            if (code == 308) {
                // Range가 없으면 한 바이트도 안 받았다.
                return new Progress.Incomplete(response.headers().firstValue("Range").map(ResumableUploader::nextOffset)
                        .orElse(0L));
            }
            if (code == 404 || code == 410) {
                return new Progress.Gone(code);
            }
            if (code == 401) {
                return new Progress.Unauthorized();
            }
            String reason = reason(response.body());
            // 조각 전송·주소 물음의 속도 제한도 거절이 아니다. 주소에 다시 물어 잇는다(PR #199 codex 3판).
            if (code == 429 || code / 100 == 5 || RATE_REASONS.contains(reason)) {
                return new Progress.Transient("HTTP " + code + " " + reason);
            }
            return new Progress.Rejected(code, reason);
        } catch (IOException e) {
            return new Progress.Transient(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Progress.Transient("interrupted");
        } catch (RuntimeException e) {
            // 200인데 본문이 JSON이 아니다: 응답을 못 받은 것과 같게 다룬다(주소에 다시 묻는다).
            return new Progress.Transient(e.getClass().getSimpleName());
        }
    }

    /**
     * thumbnails.set 결과(POK-291). 어느 것도 영상을 바꾸지 않는다: 영상은 이미 올라갔다.
     * 코드는 clip 썸네일 오류 칸({@code [A-Z0-9_]{1,32}})에 그대로 들어간다.
     */
    public sealed interface ThumbnailSet {
        /** 붙었다. */
        record Done() implements ThumbnailSet { }

        /** 다시 해도 같다. */
        record Failed(String code) implements ThumbnailSet { }

        /** 잠깐 뒤 다시 해 볼 만하다(404·속도 제한·5xx·끊김). 짧은 재시도를 다 써도 안 되면 {@code code}로 남긴다. */
        record Retry(String code) implements ThumbnailSet { }

        /** 토큰이 안 먹혔다(401). 긴 업로드 동안 토큰이 끝났을 수 있다. */
        record Unauthorized() implements ThumbnailSet { }
    }

    /** {@code POST {thumbnailUrl}?videoId=…&uploadType=media}. 본문이 그림 바이트다. */
    public ThumbnailSet setThumbnail(String accessToken, String videoId, Path picture, String contentType) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(thumbnailUrl + "?videoId="
                                    + URLEncoder.encode(videoId, StandardCharsets.UTF_8) + "&uploadType=media"))
                            .timeout(Duration.ofSeconds(60))
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", contentType)
                            .POST(HttpRequest.BodyPublishers.ofFile(picture))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return classifyThumbnail(response.statusCode(), reason(response.body()));
        } catch (IOException e) {
            return new ThumbnailSet.Retry("THUMBNAIL_UNAVAILABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ThumbnailSet.Failed("THUMBNAIL_UNAVAILABLE");
        }
    }

    /**
     * 🔴 <b>사유를 상태 코드보다 먼저 본다.</b> 403 하나에 쿼터({@code quotaExceeded})와 권한 없음({@code forbidden}: 채널 전화 인증
     * 미완 등)이 같이 온다. 상태 코드로 가르면 쿼터를 「권한 없음」으로 잘못 안내한다(시작 요청과 같은 교훈, PR #199 codex).
     */
    static ThumbnailSet classifyThumbnail(int code, String reason) {
        if (code / 100 == 2) {
            return new ThumbnailSet.Done();
        }
        if (QUOTA_REASONS.contains(reason)) {
            return new ThumbnailSet.Failed("THUMBNAIL_QUOTA_EXCEEDED");
        }
        if (code == 429 || RATE_REASONS.contains(reason)) {
            return new ThumbnailSet.Retry("THUMBNAIL_RATE_LIMITED");
        }
        if (code == 401) {
            return new ThumbnailSet.Unauthorized();
        }
        if (code == 403) {
            return new ThumbnailSet.Failed("THUMBNAIL_FORBIDDEN");
        }
        if (code == 404) {
            // 막 올린 영상이 잠깐 안 보일 수 있다.
            return new ThumbnailSet.Retry("THUMBNAIL_VIDEO_NOT_FOUND");
        }
        if (code / 100 == 5) {
            return new ThumbnailSet.Retry("THUMBNAIL_UNAVAILABLE");
        }
        if (code == 400 || code == 413 || code == 415) {
            // invalidImage·mediaBodyRequired. 그림 크기·형식이 안 맞는 것도 여기다.
            return new ThumbnailSet.Failed("THUMBNAIL_INVALID_IMAGE");
        }
        return new ThumbnailSet.Failed("THUMBNAIL_UNAVAILABLE");
    }

    static long nextOffset(String range) {
        Matcher m = RANGE.matcher(range);
        return m.find() ? Long.parseLong(m.group(1)) + 1 : 0L;
    }

    /** 유튜브 오류 본문 {@code {"error":{"errors":[{"reason":…}]}}}의 첫 사유. 모양이 다르면 빈 문자열. */
    private String reason(String body) {
        try {
            JsonNode errors = mapper.readTree(body).path("error").path("errors");
            return errors.isArray() && !errors.isEmpty() ? errors.get(0).path("reason").asString("") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
