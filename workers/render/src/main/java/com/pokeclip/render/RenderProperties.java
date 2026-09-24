package com.pokeclip.render;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 일꾼 설정 전부. 값은 {@code application.yml}이 환경변수에서 읽는다.
 *
 * @param queueUrl           주문 줄 주소. 비면 일꾼이 줄을 안 본다(로컬에서 부품만 띄울 때)
 * @param queueEndpoint      가짜 SQS(LocalStack) 주소. 비면 AWS 기본
 * @param s3Endpoint         가짜 S3 주소. 비면 AWS 기본
 * @param s3PathStyle        가짜 S3는 경로식 주소를 쓴다
 * @param region             AWS 리전
 * @param clipBaseUrl        보고를 받는 clip 주소
 * @param internalToken      보고 문의 {@code X-Internal-Token}
 * @param workDir            주문마다 임시 폴더를 여기 아래에 만든다. 끝나면 지운다
 * @param ffmpeg             ffmpeg 실행 파일
 * @param ffprobe            ffprobe 실행 파일
 * @param jobTimeout         주문 하나의 상한. 넘기면 ffmpeg를 끊고 일시 실패로 다룬다
 * @param visibilityTimeout  줄의 기본 숨김 시간(큐 설정과 같아야 한다: 계약1 1절 600초)
 * @param pollWait           롱 폴링 대기
 * @param reportRetryDelays  보고 재전송 간격(계약1 4절 5s·15s·45s). 쉼표로 적는다
 * @param fontsDir           번인 자막용 글꼴 폴더. 비면 시스템 글꼴
 */
@ConfigurationProperties(prefix = "pokeclip.render")
public record RenderProperties(String queueUrl, String queueEndpoint, String s3Endpoint, boolean s3PathStyle,
                               String region, String clipBaseUrl, String internalToken, Path workDir,
                               String ffmpeg, String ffprobe, Duration jobTimeout, Duration visibilityTimeout,
                               Duration pollWait, java.util.List<Duration> reportRetryDelays, String fontsDir) {
}
