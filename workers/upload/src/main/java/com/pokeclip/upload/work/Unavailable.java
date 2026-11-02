package com.pokeclip.upload.work;

/** clip·auth·유튜브·S3가 재시도 끝까지 답을 안 줬다. 일꾼은 쪽지를 남겨 조금 뒤 다시 받는다(일시 실패). */
public class Unavailable extends RuntimeException {
    public Unavailable(String what, Throwable cause) {
        super(what, cause);
    }
}
