# 업로드 일꾼 (`workers/upload`)

**완성 영상을 스트리머의 유튜브 채널에 올리고, 고른 썸네일을 붙이는 일꾼이다(POK-220 · POK-291).** 줄(SQS `jobs-upload`)에서
주문서를 꺼내 창고의 mp4를 받아 유튜브 이어 올리기(resumable upload)로 보내고, 썸네일을 붙인 뒤 결과를 clip에 알린다.
주문을 받는 쪽은 clip(`services/README.md` 「유튜브 업로드 주문」 절)이다.

- 자바 21 · Spring Boot 4.1 · 웹 서버 없음 · 한 번에 한 주문(렌더 일꾼과 같은 도구).
- **DB에 안 붙는다**(`workers/README.md`). 상태는 clip의 일꾼 문 셋으로만 바꾼다: `/internal/uploads/{id}/start|session|result`.
- **토큰은 주문서에 없다.** 올리기 직전에 auth `POST /internal/youtube-link/resolve {userId}`에 묻고, 받은 토큰은 쓰고 버린다.
- **공개 범위는 스트리머가 고른 대로 싣는다**(`private`·`unlisted`·`public`, ADR-084). 🔴 유튜브 API 감사를 통과하기 전에는
  무엇을 골라도 유튜브가 비공개로 잠근다. 일꾼은 응답에서 영상 번호만 읽어 잠겼는지 모른다.
- 태그(`snippet.tags`, 비었으면 칸을 안 싣는다)와 아동용 여부(`status.selfDeclaredMadeForKids`)도 주문서 값 그대로. 분류는 게임(`categoryId=20`).
- 장면 썸네일을 뽑으려고 **ffmpeg·ffprobe가 필요하다**(이미지에는 apt 꾸러미로 들어 있다).

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
3. auth에 스트리머 토큰을 묻는다. 주소도 토큰도 없으면 여기서 끝낸다(영상을 안 받는다). 아니면 창고에서 mp4를 임시 파일로 받는다(크기를 알아야 시작할 수 있다).
4. 주소가 없으면 유튜브에 시작을 청해 주소를 받고 clip `session`에 적는다.
5. 주소에 「어디까지」를 묻고, 덜 받았으면 그 자리부터 8MB 조각으로 보낸다. 끊기면(5xx·시한·연결) 다시 묻고 잇는다.
6. 다 받았으면 **썸네일을 먼저 붙인다**(주문서에 있을 때만, 아래 「썸네일」).
7. clip `result UPLOADED {videoId, thumbnailOutcome, thumbnailErrorCode}`. 임시 파일(mp4·썸네일 그림)은 지운다.

영상이 끝났음을 아는 자리는 둘이다(5의 끝 · 이어 갈 수 없을 때 주소에 물어 보니 이미 다 받았을 때). 둘 다 같은 메서드
(`UploadProcessor.finish`)를 지나서 6·7을 한다.

## 결과 가르기

| 상황 | 보고 | 쪽지 |
|---|---|---|
| 다 올렸다(또는 물어 보니 이미 다 받았다) | `UPLOADED` + 영상 번호 | 지운다 |
| 시작에서 하루 한도(`quotaExceeded`·`dailyLimitExceeded`는 403, 채널 한도 `uploadLimitExceeded`는 **400**. 사유 이름으로 가른다) | `FAILED QUOTA_EXCEEDED` | 지운다 |
| 시작에서 유튜브가 거절(4xx) | `FAILED YOUTUBE_REJECTED` | 지운다 |
| 위 둘인데, 그사이 겹친 일꾼이 clip에 주소를 적어 두었다(실패 보내기 전에 다시 묻는다) | 그 주소로 잇는다 | 결과대로 |
| 연동 없음·해제·끊김(auth `NOT_LINKED`·`UNLINKED`·`BROKEN`), 주소 없음 | `FAILED YOUTUBE_{사유}` | 지운다 |
| 같은 경우인데 주소가 있다 | 주소에 물어 다 받았으면 `UPLOADED`, 아니면 `CHECKING` | 지운다(물음 자체가 끊기면 다시) |
| 바이트 거절(4xx) | 주소에 물어 위와 같이 가른다 | 지운다 |
| 주소가 사라졌다(404·410) | `CHECKING SESSION_GONE` | 지운다 |
| 연동이 끊겼는데 다시 물으니 그사이 겹친 일꾼이 주소를 적어 두었다 | 없음(실패를 보내면 그 주소로 이어 갈 길이 막힌다) | 60~120초 뒤 다시 |
| 시작에서 속도 제한(403 `rateLimitExceeded`·`userRateLimitExceeded`·`servingLimitExceeded`) | 없음 | 60~120초 뒤 다시 |
| 조각 전송·주소 물음에서 같은 속도 제한 | 없음(주소에 다시 물어 잇는다) | 재시도를 넘기면 60~120초 뒤 다시 |
| auth 즉석 갱신 실패(`REFRESH_UNAVAILABLE`)·유튜브 401·5xx·끊김이 재시도보다 길다·clip·S3 무응답 | 없음 | 60~120초 뒤 다시 |
| 영상은 올라갔고 썸네일만 못 붙였다(어떤 이유든) | `UPLOADED` + `thumbnailOutcome:FAILED` + `THUMBNAIL_*` | **지운다** |
| 썸네일을 붙인 뒤 clip 보고가 안 됐다(clip 무응답) | 없음 | 60~120초 뒤 다시: 주소가 「다 받았다」고 해 썸네일을 다시 붙이고 보고한다 |
| 그렇게 다시 온 배달(바이트를 하나도 안 보냈는데 주소가 「다 받았다」)에서 다시 붙이기가 실패했다 | `UPLOADED` + `thumbnailOutcome:FAILED` + `THUMBNAIL_UNCONFIRMED`(지난 배달에서 붙었을 수 있다. 원래 사유는 로그 `upload.thumbnail_unconfirmed`) | 지운다 |

**쿼터**: 예전에는 `videos.insert` 한 번이 1,600유닛이라 하루 약 6개라고 적었다(ADR-010). 지금 공식 문서는 올리기를 일반 쿼터와
따로 센다: 「Video Uploads」 몫에서 한 번에 1, 프로젝트 하루 100회. `thumbnails.set`은 일반 쿼터에서 약 50유닛이다.
(2026-10-08 문서 조회. 실제 몫은 GCP 콘솔에서 확인한다.) 한도는 시작 요청에서 드러나고, 그때는 주소가 없으니 영상도 없다(안전하게 실패).

## 썸네일 (POK-291)

주문서 `thumbnail`이 없거나 `{"source":"none"}`이면 아무것도 안 하고 `thumbnailOutcome:NONE`을 보고한다(옛 주문서와 같다).

- **장면**(`{"source":"scene","offsetMs":…}`): 받아 둔 mp4에서 뽑는다. ffprobe로 길이를 재 자리를 `[0, 길이−100ms]`로 **당겨
  맞추고**, ffmpeg `-ss {초} -i {mp4} -frames:v 1 -q:v 2`로 JPEG 한 장(출력 해상도 그대로). 🔴 ffmpeg는 마지막 프레임 시각을 1ms만
  넘어도 파일 없이 실패한다(렌더 일꾼 실측). 그래도 파일이 없으면 0초로 한 번 더, 그래도 없으면 `THUMBNAIL_EXTRACT_FAILED`.
  한 번에 30초를 넘기면 실패로 본다.
- **올린 그림**(`{"source":"file","bucket","s3Key","contentType"}`): 창고에서 받는다. 못 받으면 `THUMBNAIL_SOURCE_MISSING`
  (읽기 권한이 없으면 S3는 없는 키에도 403을 주므로 받기 실패는 모두 이것으로 본다).
- 붙이기: `POST {YOUTUBE_THUMBNAIL_URL}?videoId=…&uploadType=media`, 본문이 그림 바이트. 영상과 같은 토큰을 쓴다(스코프
  `youtube.upload`로 된다). 토큰이 없으면(이어 갈 수 없을 때 결론 내는 자리) auth에 한 번 더 묻고, 그래도 없으면 `THUMBNAIL_NO_TOKEN`.
  이 자리는 이 배달이 바이트를 안 보낸 자리라 clip에는 `THUMBNAIL_UNCONFIRMED`로 가고 `NO_TOKEN`은 로그에만 남는다(아래 예외).
- 크기: `thumbnails.set` 파일 상한은 50MB다(2026-09-14 개정, 전에는 2MB). clip·웹의 10MB 상한은 그 안쪽이라 일꾼은 그림을 줄이지 않고 그대로 보낸다.

**🔴 붙인 뒤에 보고한다.** 보고를 먼저 하면 clip이 끝난 주문이 되어, 붙이기 전에 멈춘 일꾼의 쪽지가 다시 와도 `proceed:false`라
영영 못 붙인다. 보고 전이면 다시 온 쪽지가 주소에 물어 「다 받았다」를 듣고 다시 붙인다(같은 그림을 두 번 붙여도 해가 없다).
🔴 예외 하나: 두 번째 붙이기가 **실패**하면 첫 번째가 붙었는지 알 수 없다. 이 배달이 바이트를 하나도 안 보냈는데 끝났으면(지난 배달이
영상을 끝냈다) 실패 사유 대신 `THUMBNAIL_UNCONFIRMED`로 보고한다. 사유를 그대로 보내면 clip에는 그것이 첫 값으로 남아(지난 배달의
`SET`은 닿은 적이 없다) 보관함이 채널에 붙은 그림을 「못 붙였어요」로 안내한다.

**🔴 썸네일 때문에 쪽지를 남기지 않는다.** 남기면 세 번 돈 뒤 실패 큐로 가고 clip 정리기가 영상이 있는데도 「확인 중」으로 닫는다.
잠깐 풀리는 실패는 일꾼 안에서 짧게(2초 · 5초 뒤, 모두 세 번) 다시 해 보고 끝낸다.

| 유튜브 응답 | 기록 | 다시 해 보나 |
|---|---|---|
| 사유 `quotaExceeded`·`dailyLimitExceeded`·`uploadLimitExceeded`(상태 코드와 상관없이) | `THUMBNAIL_QUOTA_EXCEEDED` | 아니오 |
| 429, 사유 `rateLimitExceeded`·`userRateLimitExceeded`·`servingLimitExceeded` | `THUMBNAIL_RATE_LIMITED` | 예 |
| 401 | auth에 토큰을 한 번 다시 받아 붙인다. 그래도 401이면 `THUMBNAIL_UNAUTHORIZED` | 한 번 |
| 그 밖의 403(`forbidden`: 채널 전화 인증 미완 등) | `THUMBNAIL_FORBIDDEN` | 아니오 |
| 404(`videoNotFound`, 막 올린 영상이 잠깐 안 보일 수 있다) | `THUMBNAIL_VIDEO_NOT_FOUND` | 예 |
| 5xx·끊김 | `THUMBNAIL_UNAVAILABLE` | 예 |
| 400·413·415(`invalidImage`·`mediaBodyRequired` 등) | `THUMBNAIL_INVALID_IMAGE` | 아니오 |
| 그 밖의 4xx | `THUMBNAIL_UNAVAILABLE` | 아니오 |

🔴 **사유를 상태 코드보다 먼저 본다.** 403 하나에 쿼터와 권한 없음이 같이 온다. 상태 코드로 가르면 쿼터를 「전화 인증이
필요해요」로 잘못 안내한다.

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
| `INTERNAL_API_TOKEN` | 빈 값 | clip·auth `/internal/**` 열쇠(서버 넷과 같은 값). **줄을 보는데 비면 부팅을 거부한다**(비어도 뜨면 401 → 쪽지 세 번 → 주문이 실패로 닫힌다) |
| `YOUTUBE_UPLOAD_URL` | 유튜브 실주소 | 시험만 바꾼다 |
| `YOUTUBE_THUMBNAIL_URL` | `https://www.googleapis.com/upload/youtube/v3/thumbnails/set` | 썸네일 붙이기 주소. 시험만 바꾼다 |
| `UPLOAD_WORK_DIR` | `/tmp/pokeclip-upload` | 받은 mp4와 썸네일 그림을 잠깐 두는 곳 |
| `FFMPEG_PATH` · `FFPROBE_PATH` | `ffmpeg` · `ffprobe` | 장면 썸네일을 뽑을 때 쓴다 |
| `UPLOAD_VISIBILITY_TIMEOUT` | `900s` | 큐 설정과 같아야 한다 |

AWS 권한: 줄 `pokeclip-jobs-upload`의 받기·지우기·숨김 바꾸기, 창고 `pokeclip-clips-2557`의 `s3:GetObject`(완성 영상 `clips/…`와
사용자가 올린 썸네일 `upload-thumbnails/…` 둘 다. 같은 창고라 지금 권한으로 된다).

## 배포 순서

**업로드 일꾼 먼저, clip 나중.** 옛 일꾼은 새 주문서의 태그·썸네일 칸을 버리고 아동용을 `false`로 박아 올린다(주문서 판 번호가 같아 거절하지 않는다).

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

썸네일(POK-291): 가짜 유튜브의 `/upload/thumbnails/set`이 상태·사유를 차례로 준다(`403 forbidden`·`403 quotaExceeded`·404·429·5xx).
시작 본문은 정규식이 아니라 JSON으로 읽어 태그·아동용·공개 범위를 잰다. 장면 뽑기는 진짜 ffmpeg로 「앞 1초 빨강, 뒤 1초 파랑」
영상을 만들어 끝을 넘는 자리가 파랑(끝 장면)인지 본다(ffmpeg가 없으면 건너뛴다). 결함 주입: 썸네일을 보고 뒤로 · 썸네일 실패를
쪽지 남기기로 · 당겨 맞추기 제거 · 사유보다 상태 코드 먼저 · 결론 자리에서 썸네일 빼먹기 · 그림 받기 실패를 일시 실패로 ·
401에 토큰 다시 안 받기 · 짧은 재시도 없음 · 아동용 고정 · 새 칸이 틀리면 못 읽음 · 임시 그림 안 지움 · 재배달의 썸네일
실패를 원래 사유로 보내기 · 바이트를 보냈다는 표시 빼먹기 · 장면 뽑기에 상대 경로 그대로 넘기기가 각각 빨간불이다.

## 아직 없는 것

- **「확인 중」을 푸는 문**: 사람이 채널을 보고 결과를 적는 것은 업로드 상태 화면(F7) 카드에서.
- **유튜브 자막 등록**(F5, `captions.insert` 400유닛) · 예약 공개 · 조회수.
- **썸네일만 다시 붙이는 문**: 영상은 올라갔고 썸네일만 실패했으면 지금은 결과만 남는다(채널 전화 인증 뒤 다시 붙이는 길이 없다).
- **실제 공개 범위 읽기**: 감사 전 비공개 잠금을 알리려면 완료 응답의 `status.privacyStatus`를 읽어 보고해야 한다(실측 필요).
- ECS 작업 정의·CI(렌더 일꾼과 같이 인프라 몫).
