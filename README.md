# Overworld Travel

오버월드 약탈 서버용 이동 플러그인입니다. TPA 요청, 스폰 이동, 마지막 사망 지점 복귀를 제공합니다. Passport를 함께 설치하면 실명으로 상대를 찾고 선택할 수 있습니다.

## 실행 환경과 설치

- **Minecraft Java / Paper 26.2, Java 25** ([Paper 설치 안내](https://docs.papermc.io/paper/getting-started/))
- 선택 사항: 공개 `PassportIdentityService`를 제공하는 [Passport Paper](https://github.com/underconnor/passport-paper)
- 이동 대상은 같은 Paper 서버에 접속한 플레이어입니다.

1. [Releases](https://github.com/underconnor/overworld_travel/releases)에서 `overworld_travel-<버전>.jar`를 받습니다.
2. Paper 서버를 정상 종료하고 JAR를 해당 서버의 `plugins/`에 넣습니다. 프록시를 사용한다면 실제 월드가 실행되는 Paper 백엔드에 설치합니다.
3. 서버를 시작합니다. 생성된 `plugins/OverworldTravel/config.yml`을 수정했다면 서버를 재시작합니다.

다른 플러그인이 같은 명령어를 등록했다면 `/overworldtravel:tpa`, `/overworldtravel:home`, `/overworldtravel:back`처럼 사용할 수 있습니다. 수락·거절 명령어도 같은 접두사를 지원합니다.

## 명령어

| 명령어 | 기능 | 쿨타임 / 이동 대기 |
| --- | --- | --- |
| `/tpa [닉네임 또는 실명]` | 상대에게 이동을 요청합니다. 인수가 없으면 플레이어 선택 화면을 엽니다. | 120초 / 5초 |
| `/tpaccept [닉네임 또는 실명]` | 받은 요청을 수락합니다. | 요청한 플레이어에게 TPA 설정 적용 |
| `/tpadeny [닉네임 또는 실명]` | 받은 요청을 거절합니다. | 없음 |
| `/tpacancel` | 보낸 요청이나 진행 중인 이동 대기를 취소합니다. | 없음 |
| `/home` | 개인 리스폰 지점으로 이동합니다. 사용할 수 없으면 기본 월드 스폰으로 이동합니다. | 300초 / 5초 |
| `/back` | 이 플러그인을 설치한 뒤 기록된 마지막 사망 지점으로 이동합니다. | 300초 / 10초 |

### TPA와 실명

- `/tpa` 선택 화면에는 Passport 실명과 Minecraft 닉네임이 함께 표시됩니다. 플레이어가 많으면 페이지를 넘길 수 있습니다.
- `/tpa ExampleMiner`처럼 닉네임을 입력하거나, Passport에 등록된 실명을 입력할 수 있습니다. 닉네임은 대소문자를 구분하지 않습니다.
- 실명은 앞뒤 공백을 제거하고 유니코드 NFC 정규화 후 정확히 비교합니다. 일부 글자만 입력해 요청을 보내지는 않습니다.
- 실명이 같은 사람이 여럿이면 닉네임으로 대상을 지정해야 합니다.
- 요청을 받은 사람은 채팅의 **수락 / 거절**을 클릭하거나 명령어를 입력할 수 있습니다.
- 요청은 **60초** 후 만료됩니다. 한 사람은 한 번에 요청 하나를 보낼 수 있고, 새 요청 사이에는 **3초** 간격이 필요합니다.
- 실명은 Passport의 유효한 신원 정보에서 매번 조회합니다. Passport가 없거나 신원 정보가 만료되면 닉네임을 사용합니다. 표시 이름에서 실명을 추측하지 않습니다.
- 자신에게 보이지 않는 플레이어는 검색·선택 대상에서 제외됩니다.

### 이동 규칙

- 쿨타임은 **이동 성공 후** 시작하며 명령어별로 따로 적용됩니다. 서버를 재시작해도 남은 쿨타임과 마지막 사망 지점은 유지됩니다.
- 기본 설정에서는 대기 중 출발점에서 **0.1블록을 초과해 움직이거나**, 피해를 받거나, 다른 대상을 공격하면 이동을 취소합니다. 시점을 돌리는 것은 허용합니다.
- 목적지의 발·머리 공간, 바닥, 위험 블록, 월드 경계를 확인합니다. 안전하지 않은 목적지나 실패한 이동에는 쿨타임을 부과하지 않습니다.
- `/home`은 기본적으로 유효한 침대·리스폰 정박기를 사용하고, 없으면 지정 월드의 스폰을 사용합니다. 항상 월드 스폰으로 보내려면 `home.destination: world-spawn`으로 설정합니다.
- `/back`은 마지막 사망 좌표를 사용합니다. 사망 이전 이동 기록을 복구하는 명령어는 아닙니다. 사망 지점이 위험하면 이동이 취소될 수 있습니다.
- OP도 같은 대기 시간과 쿨타임을 적용받습니다. 우회 권한은 없습니다.
- 대기 중 피해·공격 취소 기능은 포함되어 있습니다. 전투 후 일정 시간 이동을 막는 별도 전투 태그 기능은 제공하지 않습니다.

## 설정

기본 `config.yml`입니다. 시간 단위는 초이며, 변경 후 서버를 재시작합니다.

```yaml
tpa:
  cooldown-seconds: 120
  warmup-seconds: 5
  request-expiry-seconds: 60
  request-throttle-seconds: 3
home:
  cooldown-seconds: 300
  warmup-seconds: 5
  destination: respawn
  spawn-world: ''
back:
  cooldown-seconds: 300
  warmup-seconds: 10
safety:
  movement-tolerance: 0.1
  cancel-on-damage: true
  cancel-on-attack: true
  check-destination: true
  chunk-load-timeout-seconds: 15
```

`home.spawn-world`가 비어 있으면 서버의 기본 월드를 사용합니다. `cancel-on-damage`, `cancel-on-attack`, `check-destination`은 각각 피해 취소, 공격 취소, 목적지 안전 검사를 설정합니다.

모든 플레이어에게 기본으로 허용되는 권한은 다음과 같습니다.

| 권한 | 명령어 |
| --- | --- |
| `overworld.travel.tpa` | `/tpa`, `/tpaccept`, `/tpadeny`, `/tpacancel` |
| `overworld.travel.home` | `/home` |
| `overworld.travel.back` | `/back` |

## 빌드와 배포

Java 25에서 실행합니다. Gradle Wrapper가 포함되어 있으며 Paper API는 `26.2.build.129-stable`로 고정되어 있습니다. Passport 저장소를 별도로 빌드할 필요는 없습니다.

```bash
./gradlew build
```

JAR는 `build/libs/overworld_travel-<버전>.jar`에 생성됩니다. Windows에서는 `gradlew.bat build`를 사용합니다.

GitHub Actions는 `main` 푸시와 PR에서 테스트·빌드를 실행하고 JAR 아티팩트를 보관합니다. `build.gradle.kts` 버전과 일치하는 `v<버전>` 태그를 푸시하면 릴리스에 JAR, SHA-256 체크섬, 빌드 메타데이터를 올립니다. 같은 태그의 릴리스가 이미 있으면 첨부 파일을 갱신합니다. 각 릴리스의 검증 범위는 릴리스 설명을 확인하세요.

## v1.0.0 검증 범위

- 자동 테스트 86개: 요청·권한·만료, 실명·동명이인, 쿨타임·사망 기록 저장, 설정·안전 검사, 명령어·이벤트 처리.
- 로컬 Paper 26.2 build 129: 플러그인 활성화, 명령어 등록, 정상 종료·재시작 후 상태 복원, 실제 블록 충돌 검사.
- 명령어 흐름 테스트는 플레이어 API를 모킹합니다. 실제 클라이언트 두 명과 운영 Passport를 연결한 플레이 검증은 별도로 진행해야 합니다.

## 라이선스

Copyright 2026 underconnor. [Apache License 2.0](LICENSE).

Passport 연동은 [passport-paper의 공개 신원 서비스](https://github.com/underconnor/passport-paper)를 참조합니다. Passport의 클래스나 회원 데이터는 이 JAR에 포함하지 않습니다.
