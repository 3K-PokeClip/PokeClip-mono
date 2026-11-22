package com.pokeclip.clip.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 발표 데모 도구를 쓸 수 있는 계정(사용자 번호, JWT {@code sub}). <b>비어 있으면 아무에게도 안 열린다</b>(기본값).
 *
 * <p>DB 칸이 아니라 설정인 이유(사용자 결정, 2026-11-22): 데모가 끝나면 값을 비우는 것으로 끄고, 켜고 끄는 데 DB를 손으로
 * 고치지 않는다.
 */
@ConfigurationProperties(prefix = "pokeclip.demo")
public record DemoProperties(List<String> userIds) {

    public DemoProperties {
        // 「4181, 4182」처럼 띄어 쓰거나 끝에 쉼표가 남아도 빈 번호가 목록에 들어가지 않게 한다
        userIds = userIds == null ? List.of()
                : userIds.stream().map(String::trim).filter(id -> !id.isEmpty()).toList();
    }

    public boolean allows(String userId) {
        return userId != null && userIds.contains(userId);
    }
}
