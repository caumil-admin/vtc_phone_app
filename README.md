# VTC-ASSURE 폰 앱 (Rokid Glasses 표시)

```
마이크 / BODA 루프백
      ↓
rokid_vtc_server.py   M2 음질향상 + ASR  →  M3 복창 종속검증
      ↓ WebSocket ws://<PC>:8765
이 앱 (폰)            CXR-M 1.2.2
      ↓ Bluetooth
Rokid Glasses HUD     480x400, 단색 녹색 Micro-LED
```

adb도, 5핀 개발용 케이블도, 안경 APK 설치도 필요 없습니다.

## 빌드 (처음부터)

### 1. Android Studio 설치
https://developer.android.com/studio 에서 Windows 버전을 받습니다 (약 1GB).
설치 마법사는 전부 기본값으로 둡니다. 첫 실행 시 **Standard** 설정을 고르면
Android SDK 와 JDK 17 을 알아서 받습니다 (추가로 약 3GB, 10~20분).
JDK 를 따로 설치할 필요는 없습니다 — Android Studio 가 번들 JDK 를 씁니다.

### 2. 프로젝트 열기
`File > Open` 에서 `C:\VTC\rokid\vtc_phone_app` 폴더를 고릅니다.
**`Import Project` 가 아니라 `Open` 입니다.** Import 를 쓰면 gradle 설정을 덮어씁니다.

열면 하단 상태바에서 Gradle Sync 가 자동으로 돕니다 (첫 회 5~10분).
- "Gradle wrapper 를 만들까요" 류의 제안 → 수락
- "Android Gradle Plugin 을 업그레이드하겠습니까" → **거절** (Upgrade 누르지 말 것)
- Sync 가 끝나면 좌측 트리에 `app` 모듈이 보입니다

### 3. 폰 준비
- 설정 > 휴대전화 정보 > 소프트웨어 정보 > **빌드번호 7번 탭** → 개발자 옵션 활성화
- 설정 > 개발자 옵션 > **USB 디버깅** 켜기
- **Hi Rokid 앱에서 안경이 연결된 상태로 둘 것** (블루투스 페어링이 살아 있어야 함)

### 4. 실행
폰을 USB 로 PC 에 연결하면 폰에 "USB 디버깅을 허용하시겠습니까" 가 뜹니다 → 허용.
Android Studio 상단 기기 선택 칸에 폰 모델명이 나타나면 **▶ Run** 을 누릅니다.
첫 빌드는 3~5분, 이후는 수십 초입니다.

### 막힐 때
| 증상 | 조치 |
|---|---|
| Sync 중 `Could not resolve com.rokid.cxr:client-m` | 사내망/방화벽이 maven.rokid.com 을 막는지 확인. 폰 핫스팟으로 우회 가능 |
| 기기 목록에 폰이 안 뜸 | 제조사 USB 드라이버 설치 (삼성이면 Samsung USB Driver). USB 케이블이 충전 전용이 아닌지 확인 |
| `Unsupported Java` | File > Settings > Build Tools > Gradle > Gradle JDK 를 번들 JBR 17 로 |
| 빌드는 되는데 안경 연결 실패 | Hi Rokid 앱에서 안경이 연결돼 있는지 먼저 확인. 앱 로그창에 원인이 찍힘 |

## 사용

1. Hi Rokid 앱에서 안경이 폰과 연결된 상태로 둡니다.
2. 이 앱 실행 → **안경 연결** (페어링 목록 우선, 없으면 BLE 스캔 15초).
3. 서버 IP / 포트 확인 후 **서버 연결**.
4. PC 에서 서버 실행:
   ```
   python rokid_vtc_server.py --source mic --model medium --judge local
   ```
5. **표시 테스트** 버튼으로 안경 표시를 먼저 확인한 뒤 회의를 시작합니다.

## 표시 경로 (CXR-M 1.2.2 실측 API)

| 용도 | API | 비고 |
|---|---|---|
| 자막 영역 설정 | `configWordTipsText(textSize, lineSpace, mode, x, y, w, h)` | 연결 직후 1회 |
| M2 자막 | `sendWordTipsAsrContent(text)` | |
| M3 경고 (시각) | `sendGlobalToastContent(type, text, isShow)` | ⚠ 기호 부착 |
| M3 경고 (음성) | `sendGlobalTtsContent(text)` | |
| 복창 정상 | `sendGlobalToastContent(...)` | ✓ 기호 |

HUD 가 단색 녹색이라 색으로 경고를 구분할 수 없습니다. 기호로 구분합니다.

## 미확인 사항 (실기기에서 확인 필요)

- `configWordTipsText` 의 `mode` 인자 문자열. 현재 `"multi"` 로 두었습니다 (`GlassesBridge.TIPS_MODE`).
- `sendGlobalToastContent` 의 `type` 정수 의미. 현재 `0`.
- 표시 영역 좌표 `AREA_X/Y/W/H` 는 480x400 기준 추정치입니다.

세 값 모두 `GlassesBridge.kt` 상단 companion object 에 모아 두었으니 실기기를 보면서 조정하십시오.

## 대안 경로 (CXR-L)

`app/build.gradle` 의 `client-l` 주석을 풀면 `customViewOpen/Update(json)` 와
`appUploadAndInstall(apk)` 를 쓸 수 있습니다. CXR-L 은 폰의 Hi Rokid 앱
(`com.rokid.sprite.aiapp`) 에 AIDL 로 바인딩하는 방식이라 그 앱이 반드시 설치·연결되어
있어야 합니다. `appUploadAndInstall` 은 `glasses_app/` 안경 네이티브 앱을 무선으로
설치하는 경로이기도 합니다 (케이블 불필요).

단, `client-l` 의 POM 이 `cxr-service-bridge:1.0-SNAPSHOT` 을 끌어와 해석이 실패할 수
있습니다. 기본 경로(CXR-M)가 동작하면 건드리지 마십시오.
