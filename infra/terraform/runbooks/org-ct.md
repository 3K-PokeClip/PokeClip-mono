# 조직 · CT 도입 런북

새 관리 계정으로 AWS Organizations 조직을 만들고, 기존 계정을 초대한 뒤
Control Tower(CT) 랜딩 존을 설정하는 절차입니다. 설계서 8절의 2단계(조직)와
3단계(CT), 계획의 운영 단계 O2 · O3 에 해당합니다.

용어:

*   **관리 계정**: 조직을 만든 계정입니다. 조직 · CT 를 다스리며 워크로드를
    두지 않습니다. SCP 는 관리 계정에 적용되지 않습니다.
*   **랜딩 존(LZ)**: CT 가 Organizations · IAM Identity Center(IdC) ·
    CloudTrail · Config 를 표준 구성으로 설치한 결과입니다.
*   **통합 계정**: LZ 가 로그 보관(Logging) · 보안 역할(SecurityRoles) ·
    Config 집계를 맡기는 계정입니다. LZ 최초 설정 때에만 지정할 수 있습니다.

## 누가 무엇을 하나

| 주체 | 하는 일 | 하지 않는 일 |
|---|---|---|
| kty(콘솔) | 계정 생성 · 조직 생성 · 초대와 수락 · 역할 수동 생성 · Config · CloudTrail 정리 · CT 콘솔 작업 전부 | — |
| 오케스트레이터 | kty 가 승인한 읽기 확인(아래 「완료 확인」의 읽기 명령)과 결과 기록 | 쓰기 명령 · 콘솔 작업 |
| 에이전트 | 없음 | **계정 생성 · 초대 · CT 콘솔 작업은 에이전트가 하지 않습니다** |

*   모든 단계가 승인 지점입니다(설계서 8절 「각 단계」 · 「각 등록」). 한 단계의
    완료 확인이 끝나기 전에 다음 단계로 가지 않습니다.
*   이 런북의 결과물(조직 · OU · 계정 · CT 가 만든 자원)은 Terraform 이
    소유하지 않습니다. 계정 ID 는 4단계부터 Terraform 입력 변수로 넘깁니다
    (설계서 2-2).
*   계정 ID · 이메일 · 계정 이름은 이 문서와 저장소에 적지 않습니다. 아래의
    `<ACCOUNT_ID_MGMT>` 같은 자리표시자로만 씁니다.

## 정해진 것(kty 결정)

| 항목 | 값 | 출처 |
|---|---|---|
| 관리 계정 | **새로 만듭니다**(워크로드 0) | kty 3차 · 볼트 Knowledge `aws-multi-account-landing-zone` 2절 |
| 기존 계정 | NonProd OU 멤버로 초대 → CT 등록 | kty 3차 |
| prod 계정 | 새로 만듭니다(Account Factory) | kty 3차 |
| 계정 수 | 다섯 개 — 관리 · Log Archive · Audit · prod · 기존(NonProd) | 설계서 2-1 |
| CT 도입 시점 | 지금(강의안 방식) | kty 3차 |
| CT Config 통합 | **켬**(LZ 전체 리전 거부 · Account Factory · CT 관리 IdC) | kty 4차 D5 |
| 홈 리전 | 서울(`ap-northeast-2`) — **바꿀 수 없습니다** | 설계서 2-1 |
| 거버넌스 리전 | 서울 + `us-east-1` | kty 3차 |
| LZ 판 | 4.0 | 설계서 8절 3단계 |

목표 구조(설계서 2-1):

```
Root
├── (관리 계정 — 새로 만듦, 워크로드 0)
├── Security OU
│   ├── Log Archive 계정   (Logging 통합)                    ← LZ 최초 설정 때 새로 만듦
│   └── Audit 계정         (SecurityRoles · Config 통합 겸용) ← LZ 최초 설정 때 새로 만듦
└── Workloads OU
    ├── Prod OU            → prod 계정(Account Factory · CT VPC 생성 끔)
    └── NonProd OU         → 기존 계정(초대 → 등록)
```

*   서비스 통합 계정은 모두 같은 부모 OU(Security OU) 아래에 둡니다.
*   기존 계정은 dev 워크로드가 있는 NonProd 라 통합 계정으로 쓰지 않습니다.

## 사전 조건(설계서 2-3)

여섯 개 모두 2단계 안의 한 걸음으로 처리합니다.

| 번호 | 사전 조건 | 처리하는 걸음 |
|---|---|---|
| 1 | 기존 계정의 현재 조직 소속 확인(읽기 전용) | 2-1 |
| 2 | 비용 이력을 CUR 로 내보내기 | 2-2 |
| 3 | 기존 계정에 Config 자원 0 · CloudTrail 트레일 정리 | 2-7 |
| 4 | 기존 계정에 `AWSControlTowerExecution` 수동 생성 | 2-6 |
| 5 | 서울 · `us-east-1` 밖 자원 읽기 전용 조사 | 2-8 |
| 6 | Account Factory 설정에서 VPC 생성 끄기 | 3-3 |

선행: 1단계 ⓔ(nonprod/bootstrap · dev · dns-legacy import)가 끝나야 합니다.

## 2단계 — 조직(O2)

### 2-1. 기존 계정의 조직 소속 확인

*   **누가**: kty(콘솔). 오케스트레이터는 kty 가 알려 준 결과를 기록합니다.
*   **왜**: `.github/workflows/services-deploy.yml` 11행 주석에 이 계정이 다른
    조직의 멤버였다는 기록이 있고, 지금 소속은 **미확인**입니다. 다른 조직에
    속해 있으면 먼저 탈퇴해야 새 조직의 초대를 받을 수 있습니다.
*   **지금 아는 것**: O1 읽기 조사(2026-10-08)에서 IAM 사용자로
    `aws organizations describe-organization` 을 부르면 `AccessDenied` 가
    났습니다.
*   **이 오류만으로는 소속을 판단할 수 없습니다.** 거부가 「호출한 IAM 사용자의
    정책에 이 권한이 없어서」인지, 「조직의 SCP 가 막아서」인지 오류만으로는
    가릴 수 없습니다. 조직에 속하지 않은 계정도 호출 주체에 권한이 없으면 같은
    거부를 받을 수 있습니다.
*   **대신 보는 방법**(둘 중 하나):
    1.  루트 사용자 또는 관리자 권한 주체로 기존 계정 콘솔에 들어가
        AWS Organizations 화면을 엽니다. 조직의 멤버면 소속 조직과 관리
        계정 정보가 보이고, 어디에도 속하지 않았으면 조직을 새로 만드는
        화면이 나옵니다.
    1.  관리자 권한 주체(IAM 정책 거부가 없는 주체)로 같은 읽기 명령을
        부릅니다.

        ```
        aws organizations describe-organization
        ```

        조직 정보가 나오면 멤버입니다. 조직에 속하지 않았으면
        `AWSOrganizationsNotInUseException` 이 납니다. 관리자 권한으로도
        `AccessDenied` 면 SCP 가 막는 것이므로 멤버로 봅니다.
*   **완료 확인**: 「멤버 아님」 또는 「멤버(조직 ID 는 `<ORG_ID_OLD>` 로만
    기록)」 중 하나로 판정이 기록됨.
*   **승인 지점**: 멤버이면 그 조직에서 탈퇴할지 kty 가 정합니다. 탈퇴 조건과
    탈퇴 뒤 남는 것은 **미확인**입니다(설계서 13절) — 탈퇴 전에 따로
    확인합니다.
*   **되돌리기**: 읽기만 하므로 없습니다.

### 2-2. 비용 이력을 CUR 로 내보내기

*   **누가**: kty(기존 계정 Billing 콘솔).
*   **왜**: 계정이 조직에 들어가면 그 전 비용 이력에 접근할 수 없게 됩니다
    (AWS 전환 가이드 — 볼트 Knowledge 6절).
*   **무엇을**: 기존 계정의 비용 · 사용 보고서(CUR)를 내보내 보관합니다.
*   **완료 확인**: 내보낸 파일이 기존 계정 밖(kty 보관 위치)에도 있음을 kty 가
    확인.
*   **승인 지점**: 다음 걸음(초대)으로 넘어가도 되는지.
*   **되돌리기**: 필요 없습니다.

### 2-3. 새 관리 계정 만들기

*   **누가**: kty(콘솔). 새 이메일 `<EMAIL_MGMT>` 로 새 계정을 만듭니다.
*   **왜**: 기존 계정으로 조직을 만들지 않습니다 — AWS 전환 가이드는 새 계정으로
    시작하라고 하고, CT 는 워크로드 없는 계정만 관리 계정으로 허용합니다
    (볼트 Knowledge 2절).
*   **완료 확인**: 새 계정 로그인 · 워크로드 0.
*   **승인 지점**: 계정 생성.
*   **되돌리기**: 계정 해지. 해지 조건은 이 런북에서 확인하지 않았습니다.

### 2-4. 조직 만들기(콘솔)

*   **누가**: kty(새 관리 계정 콘솔).
*   **왜 콘솔인가**: 2026-07-10 이후 **콘솔로** 만든 조직에만 기본 SCP
    (`DenyLeaveAndCloseAccount`)가 자동으로 붙습니다. CLI · SDK 로 만들면
    손으로 붙여야 합니다(볼트 Knowledge 2절).
*   **완료 확인**: 관리 계정의 Organizations 화면에 조직 ID 와 관리 계정
    `<ACCOUNT_ID_MGMT>` 가 보이고, 정책 화면에 기본 SCP 가 붙어 있음. 읽기
    명령(관리 계정에서):

    ```
    aws organizations describe-organization
    aws organizations list-policies --filter SERVICE_CONTROL_POLICY
    ```
*   **승인 지점**: 조직 생성.
*   **되돌리기**: 설계서 9-1 「조직 생성 · 초대」 — CT 등록 해제 → 탈퇴.
    조건 · 남는 자원은 **미확인**입니다.

### 2-5. 기존 계정 초대와 수락

*   **누가**: kty — 관리 계정에서 초대, 기존 계정에서 수락.
*   **선행**: 2-1 판정이 「멤버 아님」(또는 탈퇴 완료)이고 2-2 가 끝남.
*   **완료 확인**: 관리 계정의 계정 목록에 `<ACCOUNT_ID_NONPROD>` 가 활성으로
    보임. 읽기 명령(관리 계정에서):

    ```
    aws organizations list-accounts
    ```
*   **승인 지점**: 초대 발송 · 수락.
*   **되돌리기**: 기존 계정의 조직 탈퇴(설계서 9-1). 조건은 **미확인**.

### 2-6. 기존 계정에 역할 두 개를 수동으로 만들기

*   **누가**: kty(기존 계정 IAM 콘솔).
*   **무엇을**:
    *   `OrganizationAccountAccessRole` — 초대한 계정에는 자동으로 생기지
        않습니다(Organizations 초대 문서, 설계서 2-2 RC-35).
    *   `AWSControlTowerExecution` — 초대한 계정을 CT 에 등록하려면
        필요합니다. CT 문서의 예시 템플릿을 씁니다(설계서 2-3 의 4).
    *   둘 다 관리 계정 `<ACCOUNT_ID_MGMT>` 를 신뢰합니다.
*   **완료 확인**: 기존 계정 IAM 역할 화면에 두 역할이 있음. 읽기 명령(기존
    계정에서):

    ```
    aws iam get-role --role-name OrganizationAccountAccessRole
    aws iam get-role --role-name AWSControlTowerExecution
    ```
*   **승인 지점**: 역할 생성.
*   **되돌리기**: 역할 삭제. `AWSControlTowerExecution` 은 등록 뒤 CT 가
    관리하므로, 등록 뒤에는 지우지 않습니다. 두 역할 모두 Terraform 이
    소유하지 않습니다(설계서 2-2).

### 2-7. 기존 계정의 Config · CloudTrail 정리

*   **누가**: kty(기존 계정 콘솔). 확인은 오케스트레이터 읽기.
*   **왜**: CT 등록 대상 계정에는 기존 Config 자원이 없어야 하고, 기존
    CloudTrail 트레일은 CT 트레일과 중복 과금됩니다(설계서 2-3 의 3).
*   **완료 확인**: 거버넌스 리전(서울 · `us-east-1`)마다 Config 레코더 ·
    전달 채널 0건, 트레일 정리 결과를 기록. 읽기 명령(기존 계정에서, 리전마다):

    ```
    aws configservice describe-configuration-recorders --region <REGION>
    aws configservice describe-delivery-channels --region <REGION>
    aws cloudtrail describe-trails --region <REGION>
    ```
*   **승인 지점**: 지우기 전에 목록을 보고 kty 가 정합니다.
*   **되돌리기**: 지운 설정을 다시 만듭니다. 지운 동안의 기록 공백은 되돌릴
    수 없습니다.

### 2-8. 서울 · us-east-1 밖 자원 조사

*   **누가**: 오케스트레이터 읽기(kty 승인 뒤) 또는 kty.
*   **왜**: CT 리전 거부를 켜면 거버넌스 리전 밖의 기존 자원은 접근을
    잃습니다(볼트 Knowledge 3절).
*   **무엇을**: 기존 계정에서 두 리전 밖에 자원이 있는지 읽기 전용으로
    조사합니다. 조사 수단은 설계서에 정해져 있지 않습니다 — 착수 때 정하고,
    수단이 놓치는 자원 종류를 함께 기록합니다.
*   **완료 확인**: 리전별 결과 기록. 자원이 있으면 옮길지 지울지 kty 가
    정한 뒤 3단계로 갑니다.
*   **승인 지점**: 읽기 실행 · 발견 자원 처분.
*   **되돌리기**: 읽기만 하므로 없습니다.

## 3단계 — CT(O3)

선행: 2단계 전부.

### 3-1. 랜딩 존 설정

*   **누가**: kty(관리 계정 CT 콘솔).
*   **무엇을**:
    *   LZ 판 4.0 · 홈 리전 서울 · 거버넌스 리전 서울 + `us-east-1`.
    *   통합: Logging · SecurityRoles · Config · IdC 를 켭니다. IdC 통합은
        Config · SecurityRoles 통합에 의존합니다.
    *   Log Archive · Audit 계정을 **새로** 만듭니다(이메일
        `<EMAIL_LOG_ARCHIVE>` · `<EMAIL_AUDIT>`). Audit 계정이 SecurityRoles ·
        Config 통합을 겸합니다. 두 계정은 Security OU 아래에 둡니다.
*   **되돌릴 수 없는 것**: 홈 리전, 통합 계정 지정(LZ 최초 설정 때만 가능),
    관리 계정 선택.
*   **완료 확인**: CT 대시보드의 LZ 상태가 사용 가능. 읽기 명령(관리 계정에서):

    ```
    aws controltower list-landing-zones
    aws controltower get-landing-zone --landing-zone-identifier <LZ_ARN>
    ```
*   **승인 지점**: LZ 설정(설계서 8절 3단계).
*   **되돌리기**: LZ 해제(설계서 9-1). 통합 계정 · 로그 자원은 남습니다.
    관리 계정은 되돌릴 수 없는 선택입니다.

### 3-2. OU 만들기 · 등록 · Config 베이스라인

*   **누가**: kty(CT 콘솔).
*   **무엇을**: Workloads OU 와 그 아래 Prod OU · NonProd OU 를 만들어 CT 에
    등록합니다. LZ 수준 Config 통합은 통합 계정에만 Config 를 깔므로, 멤버
    계정이 기록되려면 **OU 마다 Config 베이스라인을 켭니다**.
*   **완료 확인**: CT 조직 화면에서 각 OU 가 등록됨. 읽기 명령(관리 계정에서):

    ```
    aws organizations list-organizational-units-for-parent --parent-id <ROOT_ID>
    ```
*   **승인 지점**: OU 등록마다.
*   **되돌리기**: OU 등록 해제. 남는 자원은 **미확인**.

### 3-3. Account Factory 의 VPC 생성 끄기

*   **누가**: kty(CT 콘솔 Account Factory 설정).
*   **왜**: Account Factory 로 만든 계정은 기본 VPC 를 지우고 CT VPC 를
    만듭니다. prod 는 Terraform(`prod/network`)의 VPC 를 쓰므로 끕니다
    (설계서 2-3 의 6).
*   **완료 확인**: Account Factory 설정 화면에서 VPC 생성 꺼짐.
*   **승인 지점**: 설정 변경. **3-4 전에 끝나야 합니다.**
*   **되돌리기**: 설정을 다시 켭니다.

### 3-4. prod 계정 만들기(Account Factory)

*   **누가**: kty(CT 콘솔).
*   **무엇을**: 새 이메일 `<EMAIL_PROD>` 로 prod 계정을 Prod OU 에 만듭니다.
*   **완료 확인**: CT 계정 화면에 `<ACCOUNT_ID_PROD>` 가 등록됨 · prod 계정에
    CT VPC 없음.
*   **승인 지점**: 계정 생성(등록).
*   **되돌리기**: 계정 관리 해제 · 해지. 조건은 **미확인**.

### 3-5. 기존 계정을 NonProd OU 에 등록

*   **누가**: kty(CT 콘솔).
*   **선행**: 2-6(`AWSControlTowerExecution`) · 2-7(Config 0) · 3-2(NonProd OU
    등록 · 베이스라인).
*   **알 것**: 등록하면 CT 가 StackSet 을 배포하고 OU 의 SCP 를 적용하며
    Config 로 모든 자원을 기록합니다. 기존 계정의 VPC 는 만들거나 지우지
    않습니다.
*   **완료 확인**: CT 계정 화면에 `<ACCOUNT_ID_NONPROD>` 가 등록됨. 등록 뒤에도
    dev 상자 · 옛 존이 그대로인지 확인합니다.
*   **승인 지점**: 등록.
*   **되돌리기**: CT 등록 해제(설계서 9-1). 남는 자원은 **미확인**.

## 다음 단계

*   4단계(O4): 관리 계정에 `mgmt/bootstrap` → `mgmt/org-extras` apply(계획
    M3 c2 · c3 · c4). 3단계에서 기록한 계정 ID 를 입력 변수로 씁니다.
*   Terraform 실행 주체는 지금 IAM 사용자이고, IdC 로의 전환은 3단계 뒤에
    합니다(kty 27차 O1 결정 ②).

## 되돌리기 요약(설계서 9-1)

| 대상 | 방법 | 한계 |
|---|---|---|
| 조직 생성 · 초대 | CT 등록 해제 → 탈퇴 | 탈퇴 조건 · 남는 자원 **미확인** |
| CT | LZ 해제 | 통합 계정 · 로그 자원이 남음. 관리 계정은 되돌릴 수 없는 선택 |

## 확인하지 못한 것

*   기존 계정의 현재 조직 소속(2-1 에서 확인 — 설계서 13절).
*   조직 탈퇴 조건 · CT 해제 뒤 남는 자원(설계서 13절).
*   계정 해지 조건 · OU 등록 해제 뒤 남는 자원.
*   2-1 의 「조직에 속하지 않으면 `AWSOrganizationsNotInUseException`」과
    콘솔 화면 문구는 이 런북을 쓸 때 AWS 문서 원문과 다시 대조하지 않았습니다.
    2-1 착수 때 확인합니다.
*   2-8 의 조사 수단.

## 근거

*   설계서 「HTTPS · 엣지 인프라 설계서」 2-1 · 2-2 · 2-3 · 8절 · 9-1 · 13절.
*   계획서 M3 c1 · 5절 대응표(2 · 3단계 = O2 · O3).
*   볼트 Knowledge `aws-multi-account-landing-zone`(2026-10-06 취득 AWS 1차
    문서 정리) 2 · 3 · 6절.
*   [services-deploy.yml](../../../.github/workflows/services-deploy.yml) 11행
    주석(다른 조직 멤버 기록).
*   [infra/terraform README](../README.md).
