# RUNBOOK — 임시 데모 media 서버 (만료 2026-08-24)

위에서 아래로 순서대로 친다. 설정값은 이 문서에 옮겨 적지 않는다 — 값이 궁금하면 해당 파일을 본다
(`mediamtx.demo.yml.tmpl` · `compose.yml` · `nginx.conf`). 배경과 구성도는 `README.md`.

**서버에서 실행하는 명령은 전부 `sudo` 다.** `.env` 가 `root:root 0600` 이라서, `sudo` 를 빼면
render.sh 는 `.env` 읽기 검사에서 중단되고, compose 는 변수 보간에 실패한다 — 원인 추적이 길어진다.

**이 저장소는 public 이다 — 실제 AWS 자원 ID·공인 IP 를 이 문서에 적지 않는다**(git 히스토리에 영구히 남는다).
아래 셸 변수를 세션 시작 시 채워서 쓴다. 값은 AWS 콘솔이나 팀 비공개 채널에서 확인한다.

```bash
export VPC_ID=<vpc-id>                 # 팀 dev VPC
export SUBNET_ID=<subnet-id>           # 퍼블릭 서브넷
export AMI_ID=<al2023-ami-id>          # 없으면 1-2 의 SSM 파라미터로 최신 AL2023 조회
export HOSTED_ZONE_ID=<hosted-zone-id> # pokeclip.com 존
export INSTANCE_PROFILE=pokeclip-dev-profile
```

팀 상시 dev EC2 인스턴스와 그 보안그룹은 **이 문서 어디에서도 건드리지 않는다** — 위 변수에도 담지 않는다.

---

## 0. 로컬 리허설 (EC2에 올리기 전, 노트북에서 1회)

EC2와 **같은 파일**로 전 구간을 먼저 돌린다. 여기서 걸리는 문제는 데모 당일이 아니라 오늘 걸린다.

> **리허설에서 쓴 `DEMO_PATH`·`SRT_PASSPHRASE` 값을 EC2 로 그대로 가져가지 않는다.** EC2 용은 2절에서
> `openssl rand` 로 **새로 생성**한다. 리허설 값은 노트북 셸 히스토리·`.env`·OBS 프로필에 이미 흩어져 있다.

**먼저 팀 공용 스택을 내린다.** 떠 있으면 `pokeclip-media` 가 8890/udp 와 8888/tcp 를 이미 점유해서
`up -d` 가 `port is already allocated` 로 실패한다(실측 확인). 리허설이 끝나면 `docker compose up -d` 로 되돌린다.

```bash
cd <저장소 루트>
docker compose down          # 팀 공용 스택 정지 (리허설 후 up -d 로 복구)
mkdir -p infra/dev-media/local/dvr
cp infra/dev-media/.env.example infra/dev-media/local/.env
# .env 편집: DEMO_HOME 은 "$(pwd)/infra/dev-media/local" 의 결과(절대경로), 나머지는 .env.example 의 생성 명령대로
chmod 600 infra/dev-media/local/.env

export DEMO_HOME="$(pwd)/infra/dev-media/local"
./infra/dev-media/render.sh
docker compose --env-file "$DEMO_HOME/.env" -f infra/dev-media/compose.yml config   # ↓ 두 가지를 눈으로 본다
#   ① ports 에 8890/udp 가 찍히는가   ② volumes 의 source: 가 의도한 절대경로인가 (상대경로·이름뿐이면 즉시 중단)
docker compose --env-file "$DEMO_HOME/.env" -f infra/dev-media/compose.yml up -d
```

이어서 OBS로 송출하고 아래 **3. 실측 검증** 의 1~5를 `localhost` 기준으로 그대로 수행한다.
전항 통과가 산출물 PR의 머지 조건이다.

**render.sh 음성 테스트 (머지 조건 — 6종 전부 종료코드≠0 + 산출물 미생성/삭제).** 템플릿을 깨는 ③은
저장소 원본을 건드리지 않도록 사본에서 친다.

```bash
KIT=$(mktemp -d); cp -R infra/dev-media "$KIT/"; SH="$KIT/dev-media/render.sh"
mk(){ mkdir -p "$1/dvr"; printf 'DEMO_HOME=%s\nDEMO_PATH=%s\nSRT_PASSPHRASE=%s\n' "$1" "$2" "$3" >"$1/.env"; }
GP=$(openssl rand -hex 12); GS=$(openssl rand -hex 16); B="$KIT/neg"; n=0
run(){ n=$((n+1)); DEMO_HOME="$4" "$SH" >/dev/null 2>&1 && r=0 || r=1
       [ "$r" = 1 ] && [ ! -e "$4/mediamtx.demo.yml" ] && echo "PASS $n $1" || echo "FAIL $n $1"; }
mk "$B/1" "" "$GS";           run "DEMO_PATH 빈값"        _ _ "$B/1"
mk "$B/2" "$GP" "short1234";  run "passphrase 9자"        _ _ "$B/2"
mk "$B/5" "$GP" "abcdefghijklmnop"; run "passphrase 비-hex" _ _ "$B/5"
mk "$B/6" "abcdefghijklmnopqrst" "$GS"; run "DEMO_PATH 비-hex" _ _ "$B/6"
# ③ 플레이스홀더 잔존: 사본 템플릿을 깨서 친다
printf '\n# __X__\n' >>"$KIT/dev-media/mediamtx.demo.yml.tmpl"
mk "$B/3" "$GP" "$GS";        run "플레이스홀더 잔존"      _ _ "$B/3"
# ④ DEMO_HOME 상대경로
mk "$B/4l" "$GP" "$GS"; ( cd "$B" && DEMO_HOME=./4l "$SH" >/dev/null 2>&1 ) && r=0 || r=1
[ "$r" = 1 ] && [ ! -e "$B/4l/mediamtx.demo.yml" ] && echo "PASS 6 DEMO_HOME 상대경로" || echo "FAIL 6 DEMO_HOME 상대경로"
rm -rf "$KIT"
```

리허설이 끝나면 `infra/dev-media/local/` 을 지운다(`.env` 에 passphrase 원문이 있다).

> 로컬 Docker Desktop/OrbStack은 파일 소유권을 재매핑하므로 **설정 파일 모드 함정이 재현되지 않는다**(리눅스 전용).
> 그래서 EC2에서 한 번 더 본다.

---

## 1. AWS 자원 생성

### 1-1. 보안그룹

인바운드는 정확히 2건이다 — `udp 8890`(SRT 송출) · `tcp 8888`(재생). 소스는 둘 다 팀 공인 IP/32.

```bash
MYIP=$(curl -s https://checkip.amazonaws.com)          # 현재 공인 IP 를 그때그때 산출 (상수로 적지 않는다)
SG=$(aws ec2 create-security-group --group-name pokeclip-media-demo-sg \
      --description "temp mentor demo (expires 2026-08-24)" \
      --vpc-id "$VPC_ID" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol udp --port 8890 --cidr "$MYIP/32"
aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol tcp --port 8888 --cidr "$MYIP/32"

# 검증: 규칙 2건 · 0.0.0.0/0 은 0건
aws ec2 describe-security-groups --group-ids "$SG" \
  --query 'SecurityGroups[0].IpPermissions[].{proto:IpProtocol,port:FromPort,src:IpRanges[].CidrIp}'
```

### 1-2. EC2 기동

user-data는 **도커 설치와 디렉토리 생성까지만** 한다. 컨테이너 기동은 사람이 한다(시크릿이 필요하므로).

```bash
cat > /tmp/userdata.sh <<'EOF'
#!/bin/bash
set -eux
dnf install -y docker
# AL2023 의 docker 패키지에는 compose 가 없다 — v2 플러그인을 직접 배치한다
install -d /usr/local/lib/docker/cli-plugins
curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-compose \
  https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
systemctl enable --now docker
# 소유권 모델: 상위 디렉토리·렌더물은 root 소유(그룹 10002 읽기), 세그먼트 디렉토리만 10002 쓰기.
# 컨테이너(10002)는 설정을 읽기만 하면 되므로 탈옥해도 .env·설정을 조작할 수 없다.
install -d -m 750 -o root -g 10002 /opt/pokeclip-demo
install -d -m 750 -o 10002 -g 10002 /opt/pokeclip-demo/dvr
EOF

aws ec2 run-instances --image-id "$AMI_ID" --instance-type t3.small \
  --subnet-id "$SUBNET_ID" --security-group-ids "$SG" \
  --iam-instance-profile Name="$INSTANCE_PROFILE" \
  --metadata-options 'HttpTokens=required,HttpEndpoint=enabled' \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
  --associate-public-ip-address --user-data file:///tmp/userdata.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=pokeclip-media-demo},{Key=expires,Value=2026-08-24}]'
```

- `$AMI_ID` 가 비었거나 만료됐으면 최신 AL2023으로 채운다:
  `export AMI_ID=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)`
- **EIP는 할당하지 않는다.** 대신 **인스턴스를 stop 하지 않는다** — stop 하면 퍼블릭 IP가 바뀐다.
- 검증(부팅 후): `sudo docker compose version` 이 v2.x 를 출력한다.

### 1-3. DNS A 레코드

```bash
IP=$(aws ec2 describe-instances --instance-ids <instance-id> \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
aws route53 change-resource-record-sets --hosted-zone-id "$HOSTED_ZONE_ID" \
  --change-batch "{\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{
    \"Name\":\"media-dev.pokeclip.com\",\"Type\":\"A\",\"TTL\":60,
    \"ResourceRecords\":[{\"Value\":\"$IP\"}]}}]}"

dig +short media-dev.pokeclip.com     # 위 IP 가 나와야 한다 (네임서버는 존 조회로 확인)
```

### 1-4. 접속 확인

```bash
aws ssm describe-instance-information --query "InstanceInformationList[?InstanceId=='<instance-id>'].PingStatus"  # Online
nc -z -w3 media-dev.pokeclip.com 22 && echo "22 열림 — 즉시 조사" || echo "22 닫힘(정상)"
```

---

## 2. 배포와 시크릿 주입

### 2-1. 시크릿 주입 (SSM 대화형 세션 안에서만)

`aws ssm send-command` 의 **인자로 시크릿을 넘기지 않는다** — 명령 이력이 SSM에 남는다.

```bash
# 세션 로깅이 꺼져 있는지 먼저 본다. 켜져 있으면 아래 heredoc 내용이 S3/CloudWatch 에 남는다 → 중단.
aws ssm get-document --name SSM-SessionManagerRunShell --query Content --output text
#   s3BucketName / cloudWatchLogGroupName 이 빈 값이어야 한다

aws ssm start-session --target <instance-id>
```

세션 **안에서**:

```bash
unset HISTFILE
umask 077
sudo install -d -m 750 -o root -g 10002 /opt/pokeclip-demo
# 값은 EC2 용으로 **새로 생성**한다(리허설 값 재사용 금지, 0절 참조):
#   DEMO_PATH=$(openssl rand -hex 12) · SRT_PASSPHRASE=$(openssl rand -hex 16)
cat > /tmp/.demoenv <<'EOF'
DEMO_HOME=/opt/pokeclip-demo
DEMO_PATH=...
SRT_PASSPHRASE=...
EOF
# .env 는 root:root 0600 — 컨테이너(10002)는 .env 를 읽지 않는다(--env-file 은 호스트의 docker CLI 가 root 로 읽는다).
sudo install -m 600 -o root -g root /tmp/.demoenv /opt/pokeclip-demo/.env
shred -u /tmp/.demoenv

# 형식 검사 — 리허설 값(다른 형식일 수 있음)이 섞여 들어오지 않았는지, 새 hex 값이 맞는지 본다.
sudo sh -c 'grep -qE "^DEMO_PATH=[0-9a-f]{24}$" /opt/pokeclip-demo/.env \
         && grep -qE "^SRT_PASSPHRASE=[0-9a-f]{32}$" /opt/pokeclip-demo/.env' && echo "형식 OK"

# 유출 검사 — 평문을 argv 에 노출하지 않도록 stdin 패턴으로 넣는다. 결과 0건이어야 한다.
sudo sh -c '. /opt/pokeclip-demo/.env; printf "%s" "$SRT_PASSPHRASE" | grep -rlFf - /home /root /tmp /var/log 2>/dev/null'
```

값 자체는 `.env.example` 의 생성 명령으로 노트북에서 만든다.

### 2-2. 코드 배포와 기동

```bash
sudo dnf install -y git
git clone --depth 1 --branch main https://github.com/3K-PokeClip/PokeClip-mono
cd PokeClip-mono
# 산출물 PR 머지가 늦어지면 --branch feat/demo-media-server 로 클론해 실측을 선행하고,
# 머지 후 git fetch && git checkout main 으로 갈아탄다.

sudo env DEMO_HOME=/opt/pokeclip-demo ./infra/dev-media/render.sh
sudo chown root:10002 /opt/pokeclip-demo/mediamtx.demo.yml   # root 소유·그룹 10002 읽기
sudo chmod 0640       /opt/pokeclip-demo/mediamtx.demo.yml

sudo docker compose --env-file /opt/pokeclip-demo/.env -f infra/dev-media/compose.yml config   # bind source 절대경로 눈확인
sudo docker compose --env-file /opt/pokeclip-demo/.env -f infra/dev-media/compose.yml up -d
sudo docker compose --env-file /opt/pokeclip-demo/.env -f infra/dev-media/compose.yml ps       # 두 컨테이너 Up
```

- 렌더된 설정에는 passphrase 원문이 들어 있다. **`chmod a+r` 를 쓰면 안 된다** — 소유권(root:10002)과 0640으로 그룹 읽기만 준다.
- compose bind 는 설정 파일이 `:ro`(읽기전용), `dvr` 만 쓰기다 — 위 `config` 출력에서 `read_only: true` 를 확인한다.
- 디스크: `df -h /` — 1시간 DVR은 20GB 안에 들어온다(6Mbps 기준 약 2.7GB). `record: no` 라 녹화 증가분은 0이다.

### 2-3. hls.js 배치 (필수 — 2026-08-18 실측 후 상시 단계로 승격)

재생 페이지는 hls.js 를 같은 오리진 `/hls.min.js` 에서 받는다(MediaMTX 동봉본 의존은 실측에서 실패해 폐기).
compose 가 `./hls.min.js` 를 마운트하므로 **compose.yml 과 같은 디렉토리에** 내려받는다:

```bash
sudo curl -fsSL -o <repo>/infra/dev-media/hls.min.js https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
sudo chown 10002:10002 <repo>/infra/dev-media/hls.min.js
# (마운트는 compose.yml 에 기본 포함 — 2026-08-18부터) up -d 로 재기동
```

---

## 3. 실측 검증 (순서 고정)

각 출력은 Jira 코멘트 증빙으로 붙인다. 아래 `<host>` 는 로컬 리허설이면 `localhost`, EC2면 `media-dev.pokeclip.com`.

**1) SRT 송출 성립**

OBS 설정: 서버 `srt://<host>:8890?streamid=publish:<DEMO_PATH>&passphrase=<32자>` ·
**키프레임 간격 2초** · 4~6Mbps · 로컬 녹화 ON(백업 자료).

```bash
sudo docker compose --env-file /opt/pokeclip-demo/.env -f infra/dev-media/compose.yml logs -f mediamtx
```

- `passphrase=` 문법이 안 먹으면 `&pbkeylen=32` 추가 → streamid 형식 변경 순으로 시도한다.
- 로그 `"connection is encrypted, but not passphrase is defined"` 는 **송출측이 passphrase 를 안 붙였다**는 뜻이다(표현이 반대다).
- 붙지 않을 때 의심 순서는 문법이 **마지막**이다: 보안그룹 udp 8890 → compose 포트 publish → bind 경로(설정이 실제로 읽혔는가)
  → `paths` 경로 오타 → `permissions` 의 `publish` 누락 → 그다음이 passphrase 문법.

**2) 매니페스트 확인 — 2단계다**

`index.m3u8` 은 멀티버리언트 플레이리스트라 LL-HLS 마커가 **거기 없다**. 마커는 그것이 가리키는 미디어 플레이리스트에 있다.

```bash
curl -s "http://<host>:8888/<DEMO_PATH>/index.m3u8"        # ① 200 + 변형 URI 한 줄(비주석 마지막 줄)
curl -s "http://<host>:8888/<DEMO_PATH>/<위에서 얻은 URI>"  # ② 마커 확인
```

②의 합격 조건: `EXT-X-PART` · `EXT-X-PART-INF` · `EXT-X-PRELOAD-HINT` · `CAN-BLOCK-RELOAD` 존재 + `EXTINF` 가 4.0 근처.
`PART-HOLD-BACK` 은 **값을 기록만 한다**(발행본 실측값 1.33초와 달라도 위반이 아니다 — 플레이어는 매니페스트가 주는 값을 따른다).
오타 경로로 요청하면 접근할 수 없어야 한다.

**3) GOP 실측 — 설정값을 믿지 않는다**

디스크의 세그먼트를 단독으로 프로브하지 않는다(LL-HLS 세그먼트는 init 분리형 fMP4라 `moov atom not found` 로 실패한다).
HLS 디먹서에 맡긴다.

```bash
ffprobe -select_streams v -show_frames -show_entries frame=pts_time,key_frame -of csv \
  "http://<host>:8888/<DEMO_PATH>/index.m3u8"      # 몇 초 뒤 Ctrl-C
```

`key_frame=1` 행의 `pts_time` 간격이 **2.0초**여야 한다. 어긋나면 세그먼트 4초가 GOP의 정수배가 아니게 되어
DVR·지연 예산이 전부 흔들린다. (오프라인 확인 대안: `cat init.mp4 <seg>.mp4 > /tmp/probe.mp4` 후 그 파일을 프로브)

**4) DVR 축적 (시청자 0명 상태에서)**

```bash
sudo ls /opt/pokeclip-demo/dvr/<DEMO_PATH> | wc -l      # 5분 뒤 약 75개
```

아무도 안 보고 있는데도 세그먼트가 쌓이면 `hlsAlwaysRemux` 가 동작한 것이다. 데모 전 선송출이 성립하는 근거다.

**5) DVR 되감기** — 25분 축적 후 `http://<host>:8888/player.html?p=<DEMO_PATH>` 에서
**마우스 드래그**로 20분 전, 이어서 **키보드 ←/→** 로도 이동한다. 두 방식 모두 그 시점 재생이 30초 이상 유지되고
라이브로 튕기지 않아야 한다. 인증 팝업은 뜨지 않는다.

**6) 1시간 상한 체감** — 60분 이상 송출해 세그먼트 수가 900에서 멈추고 오래된 것이 삭제되는지, 최초 로드가 느려지는지 본다.

**7) 끊김 시나리오(의도적)** — OBS를 끊었다 다시 붙이면 DVR이 0부터 시작한다. **데모 중 절대 하면 안 되는 행동**을 몸으로 익히는 것이 목적이다.

**8) 부하** — `sudo docker stats` 로 CPU/RSS. RSS가 1GB를 넘으면 `hlsDirectory` 오설정을 의심한다.

> 6·8이 나쁘면 **임의로 값을 바꾸지 않는다.** kty 판단으로 ① 인스턴스 타입 상향 ② 시연 폭 조정(되감기를 30분만 보여주되 창은 1시간 유지)
> ③ DVR 창 축소 중에서 고른다. ③은 데모 서사가 줄어드므로 최후순위다.

---

## 4. 데모 당일 (2026-08-21)

**T-60분 — 현장 공인 IP 확인, 다르면 보안그룹 갱신(왕복 2명령)**

```bash
# 세션이 1절과 다르면 $SG 가 비어 있다 — 이름으로 다시 집는다.
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=pokeclip-media-demo-sg \
      --query 'SecurityGroups[0].GroupId' --output text)
NEW=$(curl -s https://checkip.amazonaws.com)
for P in "udp 8890" "tcp 8888"; do set -- $P
  aws ec2 revoke-security-group-ingress   --group-id "$SG" --protocol $1 --port $2 --cidr "<이전IP>/32"
  aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol $1 --port $2 --cidr "$NEW/32"
done

# 사후 검증(생략 금지) — 왕복 중 한쪽만 성공하는 실패 모드를 여기서 잡는다.
aws ec2 describe-security-groups --group-ids "$SG" \
  --query 'SecurityGroups[0].IpPermissions[].{proto:IpProtocol,port:FromPort,src:IpRanges[].CidrIp}'
#   규칙 정확히 2건 · 소스가 새 IP/32 · 0.0.0.0/0 은 0건
```

- 퍼블릭 IP가 그대로인지 확인하고, 바뀌었으면 1-3의 UPSERT를 다시 친다.
- **T-30분**: 송출 시작. 이후 **OBS를 건드리지 않는다**(끊기면 축적된 DVR이 전부 사라진다). 세그먼트가 쌓이는지 확인.
- **T-5분**: 재생 페이지를 열어 라이브를 확인한다.
- 실패 시 순서: ① 페이지 새로고침 ② `sudo docker compose --env-file /opt/pokeclip-demo/.env -f infra/dev-media/compose.yml ps`(두 컨테이너 Up 확인)
  ③ 보안그룹 사후 검증 재실행 ④ 사전 녹화 영상 재생. **데모 중 서버 설정을 고치지 않는다.**

---

## 5. 철거 (데모 종료 직후 권장, 늦어도 2026-08-24 12:00)

순서를 지킨다 — 보안그룹은 인스턴스가 종료된 뒤에만 지워진다.

```bash
# 세션이 다르면 SG·인스턴스 ID 를 이름·태그로 다시 집는다.
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=pokeclip-media-demo-sg \
      --query 'SecurityGroups[0].GroupId' --output text)
IID=$(aws ec2 describe-instances --filters Name=tag:Name,Values=pokeclip-media-demo \
       Name=instance-state-name,Values=running,stopped \
       --query 'Reservations[0].Instances[0].InstanceId' --output text)
```

1. **terminate 전에 캡처**: 볼륨 ID와 `DeleteOnTermination` 값을 적어 둔다. `false` 면 먼저 `true` 로 바꾸거나 종료 후 볼륨을 직접 지운다.
   ```bash
   aws ec2 describe-instances --instance-ids <instance-id> \
     --query 'Reservations[0].Instances[0].BlockDeviceMappings[].Ebs.{id:VolumeId,del:DeleteOnTermination}'
   ```
2. `aws ec2 terminate-instances --instance-ids <instance-id>` → describe 가 `terminated`
3. `aws ec2 describe-volumes --volume-ids <vol-id>` → `InvalidVolume.NotFound`
4. `aws ec2 delete-security-group --group-id "$SG"` → describe 0건
5. A 레코드 `DELETE`(1-3의 change-batch에서 Action 만 바꾼다) → `dig` 응답 없음
6. 계정 훑기: `describe-instances` · `describe-volumes` · `describe-addresses` 에 demo 태그 잔여 0건
7. EC2 `/opt/pokeclip-demo/.env` 파기(인스턴스 종료로 자동이지만 명시한다)
8. 노트북 `infra/dev-media/local/`(.env·렌더물·DVR) 삭제 + **클립보드 비우기**
9. **OBS 프로필** — 서버 URL에 passphrase 원문이 그대로 들어 있다. 데모 프로필을 삭제하거나 URL을 비운다.
   **가장 잊기 쉬운 항목이다.**
10. `/dvr` 의 `init.mp4`·디렉토리 잔존 여부를 관측해 ADR-040에 1줄 기록(인스턴스 종료로 실효는 없다)
11. 저장소 원복 PR — `infra/dev-media/` 전체 삭제 + `.gitignore` 3줄(주석+패턴+빈줄) 원복 + `docs/dev-environment.md` 포인터 1줄 원복
12. Jira 전건 완료 전이 + 근거 코멘트(1~11의 확인 출력), ADR-040 상태를 `만료` 로 갱신 후 위키 재발행

### 중간에 멈출 때 (역순 롤백)

| 어디까지 갔나 | 되돌릴 것 | 확인 |
|---|---|---|
| 데모까지 끝 | 위 1~12 전부 | 철거 체크리스트 |
| 배포까지(2절) | A 레코드 DELETE → terminate → 볼륨 부재 → 보안그룹 삭제 | 각 describe 0건 |
| DNS까지(1-3) | A 레코드 DELETE → terminate → 보안그룹 삭제 | 위와 동일 |
| 보안그룹만(1-1) | 보안그룹 삭제 | describe 0건 |
| 산출물 PR 머지만 | 원복 PR(또는 `git revert -m 1 <merge-sha>` — merge commit 이라 `-m 1` 필수) | main 에 `infra/dev-media` 부재 |

어느 경우에도 팀 dev EC2와 공용 compose 파일은 건드리지 않으므로 팀에 미치는 영향은 없다.
