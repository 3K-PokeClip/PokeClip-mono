package com.pokeclip.core;

import com.pokeclip.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 컨텍스트가 뜨는지만 본다. Testcontainers 설정은 {@link IntegrationTestSupport}가 쥔다 —
 * 여기서 따로 컨테이너를 띄우면 JVM에 postgres가 두 개 뜬다.
 */
class CoreApplicationTests extends IntegrationTestSupport {

    @Test
    void 컨텍스트가_뜬다() {
    }
}
