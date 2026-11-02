package com.pokeclip.db;

import com.zaxxer.hikari.HikariCredentialsProvider;
import com.zaxxer.hikari.util.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hikari가 <b>새 연결을 만들 때마다</b> 부르는 공급자다(HikariCP 7.0.2 {@code PoolBase.newConnection}).
 * 그래서 토큰이 15분 만에 만료돼도 이미 붙은 연결은 안 끊기고, 다음 연결은 새 토큰으로 붙는다.
 * 풀을 처음 띄울 때도 한 번 더 불린다({@code PoolBase.initializeDataSource}). 그 값은 드라이버
 * 기본값으로만 박히고 실제 접속에는 안 쓰여, {@code db.iam_token_issued}가 연결 수보다 하나 많다.
 *
 * <p>AWS 공식 JDBC 래퍼를 안 쓰고 이 길을 고른 이유: 래퍼가 {@code socketTimeout: 10}(초)을 밀리초로
 * 읽어 시한이 사실상 없어진다(aws-advanced-jdbc-wrapper#2157). 그 시한이 반개방 스톨을 끊는 유일한
 * 장치다({@code application.yml} 주석).
 */
public class RdsIamCredentialsProvider implements HikariCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(RdsIamCredentialsProvider.class);

    /**
     * 토큰 하나를 만드는 시한(media {@code iam.go}와 같은 10초). 서명은 로컬 계산이지만 자격 증명 조회(ECS 태스크
     * 역할 엔드포인트)는 밖으로 나가고, 그게 멈추면 Hikari의 연결 생성이 끝없이 매달려 풀이 바닥난다.
     */
    static final Duration SIGN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 시한을 넘긴 서명은 버리고 기다리지 않는다. 엔드포인트가 살아나면 다음 연결은 새 스레드에서 바로 붙는다
     * (스레드 하나로 줄 세우면 그 하나가 멈춘 순간 전부 멈춘다).
     *
     * <p>가상 스레드를 안 쓴 이유: JDK 21은 synchronized 안에서 막힌 가상 스레드가 운반 스레드를 붙든다(pinning).
     * Fargate 1 vCPU면 운반 스레드가 하나라, 매달린 조회 하나가 뒤 서명을 전부 시한까지 못 돌게 한다.
     * 데몬이라 매달린 스레드가 종료를 막지 않는다.
     */
    private static final ExecutorService SIGNING = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "rds-iam-signer");
        thread.setDaemon(true);
        return thread;
    });

    private final RdsTokenSigner signer;
    private final String host;
    private final int port;
    private final String user;
    private final Duration timeout;

    public RdsIamCredentialsProvider(RdsTokenSigner signer, String host, int port, String user) {
        this(signer, host, port, user, SIGN_TIMEOUT);
    }

    RdsIamCredentialsProvider(RdsTokenSigner signer, String host, int port, String user, Duration timeout) {
        this.signer = signer;
        this.host = host;
        this.port = port;
        this.user = user;
        this.timeout = timeout;
    }

    /**
     * 토큰 값은 어디에도 안 찍는다. 15분 유효지만 서버가 그 뒤에도 얼마간 받아 주는 재사용 가능한
     * 자격이다(media 실험 RDS 실측 약 20분).
     *
     * <p>실패의 원인 문구도 싣지 않고 종류만 싣는다. SDK 자격 증명 오류에는 프로필 이름 · 설정 파일
     * 경로가 섞일 수 있다(media {@code iam.go}와 같은 규칙).
     */
    @Override
    public Credentials getCredentials() {
        Future<String> signing = SIGNING.submit(() -> signer.sign(host, port, user));
        String token;
        try {
            token = signing.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            signing.cancel(true);
            throw failed("TimeoutException", "RDS IAM 토큰 생성이 시한(" + timeout.toMillis()
                    + "ms) 안에 안 끝났다. 실행 역할의 자격 증명 조회(ECS 태스크 역할)를 확인하라");
        } catch (ExecutionException e) {
            String causeType = e.getCause().getClass().getSimpleName();
            throw failed(causeType, "RDS IAM 토큰 생성 실패(원인 종류 " + causeType
                    + "). AWS_REGION · 실행 역할의 rds-db:connect 권한을 확인하라");
        } catch (InterruptedException e) {
            signing.cancel(true);
            Thread.currentThread().interrupt();
            throw failed("InterruptedException", "RDS IAM 토큰 생성 중 끊겼다");
        }
        log.info("db.iam_token_issued host={} user={}", host, user);
        return Credentials.of(user, token);
    }

    private IllegalStateException failed(String causeType, String message) {
        log.warn("db.iam_token_failed host={} user={} causeType={}", host, user, causeType);
        return new IllegalStateException(message);
    }
}
