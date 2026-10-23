package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import com.pokeclip.clip.upload.UploadInfo.Thumbnail;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「영상 만들기」 본문의 {@code upload} 칸을 검사해 {@link UploadInfo}로 바꾼다(POK-291). 틀리면 400 {@code field}다.
 *
 * <p>본문을 record로 바로 받지 않고 JSON 나무로 읽는 이유: 잭슨은 {@code 12.5}를 {@code long} 칸에 조용히 12로 접고,
 * 문자열 {@code "true"}를 불리언으로 받는다. 그러면 「정수만」「불리언만」을 못 잰다.
 *
 * <p>제목·설명 규칙은 옛 업로드 문({@link UploadRequestService#title}·{@link UploadRequestService#description})을 그대로 쓴다:
 * 두 문의 규칙이 갈리면 같은 제목이 한쪽에서만 막힌다.
 */
public final class UploadInfoParser {

    /** 유튜브 태그 한도(전체 글자). 쉼표와, 공백이 든 태그의 따옴표 둘까지 센다(videos 문서). */
    static final int MAX_TAG_CHARS = 500;

    private static final Set<String> PRIVACY = Set.of("private", "unlisted", "public");

    /**
     * 사람이 고를 수 있는 유튜브 분류(videoCategories {@code assignable=true}, POK-381). 18·21·30~44는 목록에는 있어도
     * 영상에 붙일 수 없다. 웹 창의 목록(web {@code uploadInfo.ts} {@code CATEGORIES})과 같아야 한다.
     */
    static final Set<String> CATEGORIES = Set.of(
            "1", "2", "10", "15", "17", "19", "20", "22", "23", "24", "25", "26", "27", "28", "29");

    private UploadInfoParser() {
    }

    /**
     * @param upload          본문의 {@code upload} 칸(객체)
     * @param cutLengthMs     편집본 구간 길이(ms). 템플릿이면 {@code null}
     * @param filePartPresent multipart로 {@code thumbnail} 파트가 왔나
     * @return 검사를 지난 정보. 썸네일이 {@code file}이면 키는 아직 비어 있다(창고에 둔 뒤 채운다)
     */
    public static UploadInfo parse(JsonNode upload, Long cutLengthMs, boolean filePartPresent) {
        return parse(upload, cutLengthMs, filePartPresent, Instant.now());
    }

    /** @param now 예약 시각이 지났는지 재는 기준 */
    static UploadInfo parse(JsonNode upload, Long cutLengthMs, boolean filePartPresent, Instant now) {
        if (upload == null || !upload.isObject()) {
            throw new InvalidUploadRequestException("upload");
        }
        String title = UploadRequestService.title(optionalText(upload.get("title"), "title"));
        String description = UploadRequestService.description(optionalText(upload.get("description"), "description"));
        List<String> tags = tags(stringList(upload.get("tags")));
        String category = category(optionalText(upload.get("categoryId"), "categoryId"));
        String privacy = privacy(optionalText(upload.get("privacyStatus"), "privacyStatus"));
        Instant publishAt = publishAt(optionalText(upload.get("publishAt"), "publishAt"), privacy, now);
        boolean madeForKids = madeForKids(upload.get("madeForKids"));
        Thumbnail thumbnail = thumbnail(upload.get("thumbnail"), cutLengthMs, filePartPresent);
        return new UploadInfo(title, description, tags, category, privacy, publishAt, madeForKids, thumbnail);
    }

    /**
     * 태그: 앞뒤 공백을 걷고, 빈 태그·{@code <}·{@code >}·{@code ,}·제어 문자를 거절하고, 똑같은 태그는 하나로(순서 유지).
     * 합계 = Σ(코드포인트 수 + 공백이 든 태그면 2) + (개수 − 1) ≤ 500. {@code null}이면 빈 목록.
     */
    public static List<String> tags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String tag : raw) {
            String stripped = tag == null ? "" : tag.strip();
            if (stripped.isEmpty() || stripped.indexOf('<') >= 0 || stripped.indexOf('>') >= 0
                    || stripped.indexOf(',') >= 0 || UploadRequestService.hasControl(stripped, false)) {
                throw new InvalidUploadRequestException("tags");
            }
            kept.add(stripped);
        }
        int total = kept.isEmpty() ? 0 : kept.size() - 1;
        for (String tag : kept) {
            total += tag.codePointCount(0, tag.length()) + (tag.codePoints().anyMatch(Character::isWhitespace) ? 2 : 0);
        }
        if (total > MAX_TAG_CHARS) {
            throw new InvalidUploadRequestException("tags");
        }
        return List.copyOf(kept);
    }

    /** 세 값 밖이면 거절. {@code null}이면 비공개. */
    public static String privacy(String raw) {
        if (raw == null) {
            return UploadInfo.PRIVATE;
        }
        if (!PRIVACY.contains(raw)) {
            throw new InvalidUploadRequestException("privacyStatus");
        }
        return raw;
    }

    /** 고를 수 있는 분류만. {@code null}이면 게임. */
    private static String category(String raw) {
        if (raw == null) {
            return UploadInfo.GAMING;
        }
        if (!CATEGORIES.contains(raw)) {
            throw new InvalidUploadRequestException("categoryId");
        }
        return raw;
    }

    /**
     * 예약 공개 시각. 시간대가 붙은 ISO-8601만 받는다(시간대가 없으면 어느 나라 시각인지 모른다). 유튜브는 비공개 영상에만 받으므로
     * 공개 범위가 비공개가 아니면 거절하고, 이미 지난 시각도 거절한다(지난 시각이면 유튜브가 바로 공개한다).
     */
    private static Instant publishAt(String raw, String privacy, Instant now) {
        if (raw == null) {
            return null;
        }
        Instant at;
        try {
            at = OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidUploadRequestException("publishAt");
        }
        if (!UploadInfo.PRIVATE.equals(privacy) || !at.isAfter(now)) {
            throw new InvalidUploadRequestException("publishAt");
        }
        return at;
    }

    private static boolean madeForKids(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new InvalidUploadRequestException("madeForKids");
        }
        return node.booleanValue();
    }

    private static Thumbnail thumbnail(JsonNode node, Long cutLengthMs, boolean filePartPresent) {
        if (node == null || node.isNull()) {
            return none(filePartPresent);
        }
        if (!node.isObject()) {
            throw new InvalidUploadRequestException("thumbnail");
        }
        String source = optionalText(node.get("source"), "thumbnail");
        if (source == null) {
            throw new InvalidUploadRequestException("thumbnail");
        }
        return switch (source) {
            case Thumbnail.NONE -> none(filePartPresent);
            case Thumbnail.SCENE -> {
                if (filePartPresent) {
                    throw new InvalidUploadRequestException("thumbnail");
                }
                yield Thumbnail.scene(offset(node.get("offsetMs"), cutLengthMs));
            }
            case Thumbnail.FILE -> {
                // JSON 본문으로는 그림을 못 보낸다. multipart의 thumbnail 파트가 있어야 한다.
                if (!filePartPresent) {
                    throw new InvalidUploadRequestException("thumbnail");
                }
                yield Thumbnail.file(null, null);
            }
            default -> throw new InvalidUploadRequestException("thumbnail");
        };
    }

    /** 그림 파트는 {@code file}일 때만 받는다. 다른 고르기에 딸려 오면 무엇을 쓸지 모호하다. */
    private static Thumbnail none(boolean filePartPresent) {
        if (filePartPresent) {
            throw new InvalidUploadRequestException("thumbnail");
        }
        return Thumbnail.none();
    }

    /** 정수만. 0 ≤ offsetMs < 구간 길이. 구간이 없으면(템플릿) 장면을 고를 수 없다. */
    private static long offset(JsonNode node, Long cutLengthMs) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || cutLengthMs == null) {
            throw new InvalidUploadRequestException("thumbnail.offsetMs");
        }
        long offset = node.longValue();
        if (offset < 0 || offset >= cutLengthMs) {
            throw new InvalidUploadRequestException("thumbnail.offsetMs");
        }
        return offset;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidUploadRequestException(field);
        }
        return node.stringValue();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new InvalidUploadRequestException("tags");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isString()) {
                throw new InvalidUploadRequestException("tags");
            }
            values.add(item.stringValue());
        }
        return values;
    }
}
