package com.pokeclip.clip.render;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 완성 영상 파일의 S3 미리서명 주소를 만드는 유일한 자리(POK-247). 자격 판정은 여기 없다: {@link ClipFileAccessService}가 먼저 한다.
 *
 * <p><b>CloudFront가 아니라 S3 직결인 이유</b>: 재생 출입증(POK-122)의 CloudFront는 영상 원본(live·dvr·vod) 도메인 전용이고
 * 완성 영상 창고 앞에는 CDN이 없다. 완성 영상은 한 사람이 한두 번 받는 파일이라 캐시로 얻을 것도 없다.
 *
 * <p>서명은 네트워크를 안 탄다(키로 계산만 한다). 로그에 <b>주소를 찍지 않는다</b>: 한 줄 새면 그 영상이 수명 동안 열린다.
 */
public class ClipFileSigner {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public ClipFileSigner(S3Presigner presigner, String bucket, Duration ttl) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = ttl;
    }

    /**
     * @param outputs 일꾼이 보고한 산출물(계약1 result). 받을 때 이미 검사했다: {@code outputId}는 {@code [a-z0-9-]}뿐이고
     *                ({@code RecipeValidator}) {@code s3Key}는 이 영상 폴더 안이다({@code JobEventService.validateResult})
     */
    public ClipFileAccess sign(long clipId, JsonNode outputs, Instant now) {
        List<ClipFileAccess.File> files = new ArrayList<>(outputs.size());
        for (JsonNode output : outputs) {
            String outputId = output.path("outputId").asString();
            String kind = output.path("kind").asString();
            // 이름에 따옴표·줄바꿈이 들어갈 수 없다: 부품이 숫자와 [a-z0-9-]뿐이다(위 @param).
            String fileName = "pokeclip-" + clipId + "-" + outputId + ("video".equals(kind) ? ".mp4" : ".srt");
            String url = presigner.presignGetObject(request -> request
                    .signatureDuration(ttl)
                    .getObjectRequest(get -> get
                            .bucket(bucket)
                            .key(output.path("s3Key").asString())
                            .responseContentDisposition("attachment; filename=\"" + fileName + "\"")))
                    .url().toString();
            files.add(new ClipFileAccess.File(outputId, kind, fileName, url));
        }
        // 서명 시각은 SDK가 제 시계로 찍는다: now와 몇 ms 어긋날 수 있지만 화면이 「언제 다시 부를지」 정하는 데는 충분하다.
        return new ClipFileAccess(clipId, now.plus(ttl), List.copyOf(files));
    }
}
