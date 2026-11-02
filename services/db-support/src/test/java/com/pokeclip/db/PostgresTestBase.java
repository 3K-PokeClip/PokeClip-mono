package com.pokeclip.db;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 컨테이너를 static 블록에서 한 번만 띄운다(서버 넷의 IntegrationTestSupport와 같은 이유).
 * 서버와 같은 {@code postgres:17}이다.
 */
abstract class PostgresTestBase {

    // Testcontainers 2.x에서 이 클래스는 비제네릭이다.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }
}
