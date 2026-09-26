package com.pokeclip.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 일꾼 설정 전부. 값은 {@code application.yml}이 환경변수에서 읽는다.
 *
 * @param queueUrl          주문 줄 주소. 비면 일꾼이 줄을 안 본다(부품만 띄울 때)
 * @param internalToken     clip·auth {@code /internal/**}의 {@code X-Internal-Token}
 * @param youtubeUploadUrl  이어 올리기 시작 주소. 시험만 가짜 서버로 바꾼다
 * @param chunkSize         한 번에 보내는 조각 크기. 256KiB의 배수여야 한다(유튜브 규칙)
 * @param visibilityTimeout 줄의 기본 숨김 시간(큐 설정 900초와 같아야 한다)
 * @param retryDelays       clip 보고·auth 문의·유튜브 조각 전송이 5xx·끊김일 때의 재시도 간격
 */
@ConfigurationProperties(prefix = "pokeclip.upload")
public record UploadProperties(String queueUrl, String queueEndpoint, String s3Endpoint, boolean s3PathStyle,
                               String region, String clipBaseUrl, String authBaseUrl, String internalToken,
                               String youtubeUploadUrl, Path workDir, DataSize chunkSize, Duration visibilityTimeout,
                               Duration pollWait, List<Duration> retryDelays) {

    static final long CHUNK_UNIT = 256 * 1024;

    public UploadProperties {
        if (chunkSize == null || chunkSize.toBytes() <= 0 || chunkSize.toBytes() % CHUNK_UNIT != 0) {
            throw new IllegalStateException("pokeclip.upload.chunk-size는 256KB의 배수여야 한다(유튜브 이어 올리기 규칙): " + chunkSize);
        }
        retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
    }
}
