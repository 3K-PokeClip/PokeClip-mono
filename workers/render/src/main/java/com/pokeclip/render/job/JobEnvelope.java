package com.pokeclip.render.job;

import com.pokeclip.render.recipe.Recipe;

import java.util.List;
import java.util.UUID;

/**
 * 검증을 통과한 렌더 주문서(계약1 2·3절). 산출물은 {@code s3://outputBucket/outputKeyPrefix/{executionToken}/} 아래에만 쓴다.
 */
public record JobEnvelope(UUID jobId, String clipId, String streamId, int recipeVersion, String outputBucket,
                          String outputKeyPrefix, List<SourceSegment> sources, Recipe recipe) {

    /** 이번 실행의 산출물 자리. 토큰이 실행마다 달라 늦게 끝난 옛 실행이 새 실행의 파일을 덮지 못한다. */
    public String outputKey(String executionToken, String fileName) {
        return outputKeyPrefix + "/" + executionToken + "/" + fileName;
    }
}
