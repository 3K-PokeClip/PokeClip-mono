# 업로드 일꾼 (`workers/upload`)

**완성 영상을 스트리머의 유튜브 채널에 비공개로 올리는 일꾼이다(POK-220).** 줄(SQS `jobs-upload`)에서 주문서를 꺼내
창고의 mp4를 받아 유튜브 이어 올리기(resumable upload)로 보내고, 결과를 clip에 알린다. 주문을 받는 쪽은
clip(`services/README.md` 「유튜브 업로드 주문」 절)이다.

- 자바 21 · Spring Boot 4.1 · 웹 서버 없음 · 한 번에 한 주문(렌더 일꾼과 같은 도구).
- **DB에 안 붙는다**(`workers/README.md`). 상태는 clip의 일꾼 문 셋으로만 바꾼다: `/internal/uploads/{id}/start|session|result`.
- **토큰은 주문서에 없다.** 올리기 직전에 auth `POST /internal/youtube-link/resolve {userId}`에 묻고, 받은 토큰은 쓰고 버린다.
- **비공개로 올린다**(ADR-010). 분류는 게임(`categoryId=20`).

## 🔴 채널에 같은 영상이 둘 생기면 안 된다

우리가 지울 수 없다. 그래서 규칙이 셋이다.

1. **바이트는 clip에 적힌 이어 올리기 주소 하나로만 보낸다.** 새 주소를 받으면 clip `session` 문에 적고, clip이 돌려준 주소
   (먼저 적힌 것)를 쓴다. 일꾼 둘이 겹쳐 각자 주소를 받아도 바이트는 한 주소로만 간다. 유튜브는 바이트를 다 받은 주소만 영상을 만든다.
2. **보내기 전에 그 주소에 「어디까지 받았나」를 먼저 묻는다**(`Content-Range: bytes */크기`). 다 받았으면 영상 번호가
   돌아온다. 쪽지가 두 번 왔거나, 마지막 응답이 사라졌거나, 일꾼이 멈췄다 다시 왔을 때 새로 올리지 않고 이것으로 끝낸다.
3. **「실패」는 주소를 받기 전에만 보낸다.** 바이트가 갈 곳이 없었으니 영상이 없다. 주소가 생긴 뒤 더 못 가면 주소에 물어 다 받았으면
   올림, 아니면 「확인 중」이다. 같은 주소로 다른 일꾼이 아직 올리는 중일 수 있어 「덜 받았다」(308)도 영상이 없다는 증거가 못 된다
   (PR #198 codex P1, clip도 주소가 적힌 줄의 실패 보고를 확인 중으로 받는다). 모르면 쪽지를 남긴다. 남긴 쪽지는 다시 와도 같은 주소로 잇고, 세 번 넘게 돌면 실패 큐로 가서 clip 정리기가
   (주소가 적혔으니) 「확인 중」으로 닫는다.

## 주문 하나가 지나가는 길

1. 쪽지를 꺼낸다(한 통씩, 숨김 900초, 남은 시간이 2분 아래면 1분마다 2분으로 늘린다).
2. clip `start`: 끝난 주문이면(`proceed:false`) 지운다. 이미 적힌 주소가 있으면 받는다.
3. auth에 스트리머 토큰을 묻는다. 창고에서 mp4를 임시 파일로 받는다(크기를 알아야 시작할 수 있다).
4. 주소가 없으면 유튜브에 시작을 청해 주소를 받고 clip `session`에 적는다.
5. 주소에 「어디까지」를 묻고, 덜 받았으면 그 자리부터 8MB 조각으로 보낸다. 끊기면(5xx·시한·연결) 다시 묻고 잇는다.
6. 다 받았으면 clip `result UPLOADED {videoId}`. 임시 파일은 지운다.

## 결과 가르기

| 상황 | 보고 | 쪽지 |
|---|---|---|
| 다 올렸다(또는 물어 보니 이미 다 받았다) | `UPLOADED` + 영상 번호 | 지운다 |
| 시작에서 하루 한도(`quotaExceeded`·`uploadLimitExceeded`·`dailyLimitExceeded`) | `FAILED QUOTA_EXCEEDED` | 지운다 |
| 시작에서 유튜브가 거절(4xx) | `FAILED YOUTUBE_REJECTED` | 지운다 |
| 연동 없음·해제·끊김(auth `NOT_LINKED`·`UNLINKED`·`BROKEN`), 주소 없음 | `FAILED YOUTUBE_{사유}` | 지운다 |
| 같은 경우인데 주소가 있다 | 주소에 물어 다 받았으면 `UPLOADED`, 아니면 `CHECKING` | 지운다(물음 자체가 끊기면 다시) |
| 바이트 거절(4xx) | 주소에 물어 위와 같이 가른다 | 지운다 |
| 주소가 사라졌다(404·410) | `CHECKING SESSION_GONE` | 지운다 |
| auth 즉석 갱신 실패(`REFRESH_UNAVAILABLE`)·유튜브 401·5xx·끊김이 재시도보다 길다·clip·S3 무응답 | 없음 | 60~120초 뒤 다시 |

**쿼터**: `videos.insert` 한 번이 1,600유닛이고 하루 기본 10,000유닛이라 하루 약 6개다(ADR-010). 한도는 시작 요청에서
드러나고, 그때는 주소가 없으니 영상도 없다(안전하게 실패).

## 새지 않게

유튜브 토큰과 이어 올리기 주소는 **로그 어디에도 안 찍는다**(시험이 로그를 잡아 확인한다). 주소 자체가 올리기 권한이다.
나가는 HTTP는 JDK 클라이언트이고 리다이렉트를 끈다(토큰·`X-Internal-Token`이 다른 출처로 따라가지 않게).

## 환경변수

| 이름 | 기본 | 뜻 |
|---|---|---|
| `UPLOAD_QUEUE_URL` | 빈 값 | 주문 줄. 비면 줄을 안 본다(부품만 띄울 때). 실물 `pokeclip-jobs-upload` |
| `UPLOAD_QUEUE_ENDPOINT` | 빈 값 | 가짜 SQS(LocalStack) 주소 |
| `S3_ENDPOINT` · `S3_PATH_STYLE` | 빈 값 · `false` | 가짜 S3 주소. LocalStack은 `true` |
| `AWS_REGION` | `ap-northeast-2` | |
| `CLIP_BASE_URL` · `AUTH_BASE_URL` | `http://localhost:8081` · `:8082` | |
| `INTERNAL_API_TOKEN` | 빈 값 | clip·auth `/internal/**` 열쇠(서버 넷과 같은 값) |
| `YOUTUBE_UPLOAD_URL` | 유튜브 실주소 | 시험만 바꾼다 |
| `UPLOAD_WORK_DIR` | `/tmp/pokeclip-upload` | 받은 mp4를 잠깐 두는 곳 |
| `UPLOAD_VISIBILITY_TIMEOUT` | `900s` | 큐 설정과 같아야 한다 |

AWS 권한: 줄 `pokeclip-jobs-upload`의 받기·지우기·숨김 바꾸기, 창고 `pokeclip-clips-2557`의 `s3:GetObject`.

## 로컬에서 돌리기

```bash
cd workers/upload && ./gradlew bootJar
```

`~/.pokeclip-local/upload.sh`가 LocalStack 줄·창고와 로컬 clip·auth를 가리키게 띄운다(시크릿은 `services/.env`). 실제 유튜브에
올리려면 스트리머 계정으로 웹 설정 화면에서 유튜브 채널을 먼저 잇는다(auth에 `YOUTUBE_*`가 있어야 한다).

## 시험

`./gradlew test`. 가짜 유튜브(`FakeYoutube`)는 이어 올리기 규칙대로 돈다. 바이트를 다 받은 주소만 영상을 만들고 그 수를 센다.
시험이 재는 것의 중심은 **그 수가 1인가**다: 응답이 사라져도 · 쪽지가 두 번 와도 · 일꾼이 멈췄다 다시 와도 · 다른 일꾼이 주소를
먼저 적어 두었어도. 결함 주입(적힌 주소 무시 · 자기 주소 쓰기 · 끊기면 바로 실패)이 각각 빨간불인 것을 확인했다.

## 아직 없는 것

- **「확인 중」을 푸는 문** — 사람이 채널을 보고 결과를 적는 것은 업로드 상태 화면(F7) 카드에서.
- **유튜브 자막 등록**(F5, `captions.insert` 400유닛) · 예약 공개 · 조회수.
- ECS 작업 정의·CI(렌더 일꾼과 같이 인프라 몫).
