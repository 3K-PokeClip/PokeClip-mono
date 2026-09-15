package com.pokeclip.clip.recipe;

import java.time.Instant;

/**
 * 저장·조회 문이 돌려주는 모양. 2번(편집기)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p>{@code recipe}가 계약6 JSON 그대로다 — 편집기가 보낸 것이 그 자리에 그대로 돌아온다. 그 밖의 칸은
 * 이 서버가 붙인 좌표(번호·판·시각)이고 계약6 밖이다(계약6 2절 「레시피가 아닌 것」 — {@code recipeVersion}은
 * 3번의 버저닝 좌표).
 */
public record RecipeSnapshot(long id,
                             String streamId,
                             String creatorId,
                             int recipeVersion,
                             RecipeDocument recipe,
                             Instant createdAt,
                             Instant updatedAt) {
}
