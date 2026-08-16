package com.pokeclip.chat.collector.archive;

/** 파일 하나를 창고에 올린다. 실물은 S3, 테스트는 가짜를 끼운다. */
public interface ArchiveUploader {

    /** 성공하면 조용히 돌아온다. 실패는 예외 하나로 — 호출자는 원인 타입만 로그에 싣는다. */
    void upload(ArchiveObject object) throws ArchiveUploadException;
}
