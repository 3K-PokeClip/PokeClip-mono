package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 프로파일 test가 없으면 @NotBlank가 빈 토큰에 걸려 컨텍스트가 통째로 죽는다.
@SpringBootTest
@ActiveProfiles("test")
class CollectorApplicationTests extends IntegrationTestSupport {

    @Test
    void 컨텍스트가_뜬다() {
    }
}
