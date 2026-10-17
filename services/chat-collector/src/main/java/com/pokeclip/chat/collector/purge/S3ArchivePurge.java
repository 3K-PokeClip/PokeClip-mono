package com.pokeclip.chat.collector.purge;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 채널 접두사 아래에서 정해진 분 전 파일을 1,000개씩 지운다. 키 모양은 {@code ArchiveKey}가 정한다({@code chat/{channelId}/날짜/시/…}).
 * 끝의 {@code /} 덕분에 번호가 이어지는 이웃 채널({@code abc}와 {@code abcd})은 안 걸린다.
 *
 * <p>🔴 묶음 지우기는 일부만 실패해도 200이다. 응답의 {@code errors}를 직접 봐야 한다.
 */
class S3ArchivePurge implements ArchivePurge {

    private final S3Client s3;
    private final String bucket;

    S3ArchivePurge(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void deleteChannel(String channelId, Instant before) {
        String prefix = "chat/" + channelId + "/";
        List<ObjectIdentifier> chunk = new ArrayList<>();
        for (S3Object object : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents()) {
            Instant minute = minuteOf(object.key().substring(prefix.length()));
            // 시각을 못 읽는 키는 건드리지 않는다(우리가 만든 모양이 아니다). 창 뒤의 분은 새 연동의 것일 수 있다(PR #220 codex P1).
            if (minute == null || !minute.isBefore(before)) {
                continue;
            }
            chunk.add(ObjectIdentifier.builder().key(object.key()).build());
            if (chunk.size() == 1000) {
                delete(chunk);
                chunk = new ArrayList<>();
            }
        }
        if (!chunk.isEmpty()) {
            delete(chunk);
        }
    }

    /** {@code yyyy-MM-dd/HH/HHmm-…} → 그 분의 시작(UTC). {@code ArchiveKey}가 받은 시각 기준 1분 창으로 짓는다. */
    static Instant minuteOf(String rest) {
        String[] parts = rest.split("/");
        if (parts.length != 3 || parts[2].length() < 4) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(parts[0]);
            int hour = Integer.parseInt(parts[2].substring(0, 2));
            int minute = Integer.parseInt(parts[2].substring(2, 4));
            return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void delete(List<ObjectIdentifier> chunk) {
        DeleteObjectsResponse response = s3.deleteObjects(b -> b.bucket(bucket)
                .delete(Delete.builder().objects(chunk).quiet(true).build()));
        if (response.hasErrors() && !response.errors().isEmpty()) {
            throw new IllegalStateException("S3 묶음 지우기 일부 실패: " + response.errors().size() + "개, 첫 코드="
                    + response.errors().getFirst().code());
        }
    }
}
