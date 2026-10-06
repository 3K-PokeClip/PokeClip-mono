package com.pokeclip.auth.streamkey.secret;

/**
 * 프로세스를 끝내는 자리. 이행 실행기만 쓴다. 기본은 {@link System#exit}이고, 부팅 시험이 이 빈을 넣어 종료 코드를
 * 받는다. 시험 JVM을 끝낼 수는 없어서다.
 */
@FunctionalInterface
public interface ProcessExit {

    void exit(int code);
}
