# VTC-ASSURE Phone App — Rokid Glasses 자막·경보 중계 앱

지휘관 화상회의의 **지시–복창 검증 결과**를 스마트글래스 시야에 띄우는 안드로이드 앱입니다.
PC 서버(`rokid_vtc_server.py`, M2 음성향상·인식 + M3 복창 검증)가 WebSocket으로 보내는 자막·지시·경보를 받아
Rokid Glasses 헤드업 디스플레이에 표시하고, 표시 지연을 계측해 서버로 되돌려 보냅니다.

```
회의 음성 (BODA 루프백 / 마이크 / 시연 음원)
      │
rokid_vtc_server.py   M2: GTCRN 잡음 억제 + Whisper 전사  →  M3: 지시 탐지 · 복창 대조 (규칙 → EXAONE-3.5 2.4B 심판자)
      │  WebSocket  ws://<PC>:8765   (status · subtitle · utterance · instruction · alert)
이 앱 (휴대단말)      MainActivity: 수신·라우팅·계측  /  GlassesBridge: CXR-L 1.0.4 CustomView
      │  AIDL → Hi Rokid(제조사 반려 앱) → Bluetooth
Rokid Glasses HUD     안당 480×400, 단색 녹색 Micro-LED  —  ⚠ 경고 / ✓ 일치 / 지시 원문 / 자막
```

adb, 별매 개발용 케이블, 안경 쪽 APK 설치가 모두 필요 없습니다. Hi Rokid 앱이 안경과 연결되어 있으면 됩니다.

## 왜 CXR-L 인가

Rokid 는 세 가지 연동 경로를 제공합니다. **CXR-M**(폰이 안경을 직접 제어)은 Console 의 기기 단위 인증 파일(`.lc`)을
요구하는데 발급 화면이 기업 계정에만 열려 개인·학술 계정으로는 `SN_CHECK_FAILED`로 막힙니다. **안경 내부 앱**은 별매
개발용 케이블로만 설치할 수 있습니다. **CXR-L**(폰 앱 → Hi Rokid → 안경)은 공식 SDK Overview 기준 *Public Access = Yes* 라
별도 계약 없이 쓸 수 있어 이 경로를 택했습니다. 실기기에서 표시까지 확인된 것은 이 경로만입니다.

## 빌드

1. Android Studio 설치 (Standard 구성, JDK 17 내장).
2. `File > Open` 으로 **이 폴더**를 연다 (`Import Project` 아님). Gradle Sync 는 자동 (첫 회 5~10분).
   - `com.rokid.cxr:client-l:1.0.4` 는 `https://maven.rokid.com/repository/maven-public/` 에서 받는다. 사내망이 막으면 폰 핫스팟.
3. 폰: 개발자 옵션 → USB 디버깅 켬. **Hi Rokid 앱에서 안경 연결 유지.**
4. USB 연결 → 디바이스 선택창에 폰 모델명이 뜨면 ▶ Run.

| 증상 | 조치 |
|---|---|
| `Class 'kotlin.Unit' was compiled with an incompatible version` | 루트 `build.gradle` 의 Kotlin 플러그인 2.1.0 확인 |
| `Unresolved reference 'DEVICE_MANAGE'` | 1.1.2 전용 상수. 1.0.4 는 `GlassPermission.CAMERA / MICROPHONE` 만 |
| 앱 로그 `SN_CHECK_FAILED(-4)` | CXR-M 경로를 쓰고 있는 것. 이 앱은 CXR-L 만 쓴다 |
| `안경: 미연결` | Hi Rokid 에서 안경 연결 후 앱의 `안경 연결` 을 다시 누른다 |
| 서버 연결 시간초과 | 폰과 PC 가 **같은 공유기**인지(같은 192.168.0.x 대역의 다른 공유기 주의), 모바일 데이터 끔, 방화벽 8765 허용 |

## 사용 순서

1. Hi Rokid 앱에서 안경 연결 유지.
2. 앱 `안경 연결` → Hi Rokid 권한 승인 → 로그 `CXR-L 연결됨`, `세션 시작 … 표시 가능`.
3. 서버 IP·포트 입력 → `서버 연결` → 로그 `서버 연결됨`, `서버 세션 …`, `시계 오차 추정 …`.
4. PC: `..\.venv-gpu\Scripts\python.exe rokid_vtc_server.py --source file --file demo\readback_demo.wav --judge local --model medium --loop --wait-client`
   (`--wait-client`: 폰이 붙는 순간부터 음원을 흘린다. 실제 회의는 `--source loopback`.)
5. `표시 테스트` 로 안경에 자막·지시·경보 세 줄이 보이는지 확인. `데모` 버튼은 설정·로그를 숨긴다.

## 코드 구성

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | WebSocket(OkHttp) 연결·재접속, 메시지 라우팅, 폰 화면(자막·지시·경보 배너), 지연 계측 보고(`display_log`·`display_ack`·`clock_probe`), 앱 로그 업링크(`clientlog`) |
| `GlassesBridge.kt` | `AuthorizationHelper` 권한 토큰 → `CXRLink.configCXRSession(CUSTOMVIEW).connect(token)` → `customViewOpen(레이아웃 JSON)` 1회, 이후 `customViewUpdate(텍스트)` 만 갱신. `onCustomViewUpdated` 콜백 짝짓기 |

### 서버 → 앱 메시지

| type | 내용 | 표시 |
|---|---|---|
| `status` | 접속 확인, `session` | 통계 초기화 · 시계 프로브 시작 |
| `subtitle` | 3초 창 자막 (`readback_alert=true` 는 경보 복제이므로 무시) | 자막 줄 |
| `utterance` | 무음 0.8초로 끊은 완결 발화 | 자막 줄 |
| `instruction` | 지시 탐지 (`slots`) | 지시 원문 줄 |
| `alert` | 복창 검증 결과 `severity=warn/ok`, `seq`, `t_send` | ⚠/✓ 경보 줄 (경고 10초, 정상 5초 후 소거) |

### HUD 표시 설계 (`GlassesBridge` companion object)

`SIZE_ALERT 22sp / SIZE_INSTRUCTION 13sp / SIZE_SUBTITLE 17sp`, `MARGIN_TOP 180dp`(위쪽 절반은 시야 확보), `GRAVITY bottom`,
`IDLE_MS 20 s`(새 내용이 없으면 화면 비움). 단색 HUD 라 색 대신 ⚠/✓ 기호로 구분한다.

### 지연 계측 (논문 E4)

앱은 경보마다 `t_recv`(수신), `t_pushed`(customViewUpdate 반환), `sdk_ms`(단조 시계), `onCustomViewUpdated` 콜백 시각을
서버에 되돌려 보내고, 접속 직후 왕복 5회로 PC-폰 시계 오차를 추정해 `clock_probe` 로 보낸다. 서버는 이것을
`logs\readback_<세션>.jsonl` 에 경보와 같은 `seq` 로 적고, `e4_latency_report.py` 가 구간별 표를 만든다.
2026-09-18 실측: 수신→표시 호출 반환 중앙값 16 ms, 갱신 콜백 47 ms, PC→폰 무선망 중앙값 142 ms(정체 시 수 초).

## 저장소 구조

```
app/src/main/java/com/vtcassure/phone/MainActivity.kt
app/src/main/java/com/vtcassure/phone/GlassesBridge.kt
app/src/main/res/layout/activity_main.xml
app/build.gradle            com.rokid.cxr:client-l:1.0.4, okhttp 4.12, compileSdk 34 / minSdk 28
build.gradle                AGP 8.5.2, Kotlin 2.1.0
settings.gradle             maven.rokid.com 저장소
```

이전 README(CXR-M 경로 기준)는 `README_old_cxrm.md` 로 남겨 두었다. 관련 논문: VTC-ASSURE (전자공학회논문지 투고 준비).
