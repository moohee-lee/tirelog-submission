# tirelog

차량의 공기압·외기온도·주행거리를 기록해 공기 보충과 타이어 점검 시기를 알려 주는 Pleos Connect 차량용 앱입니다.

## 주요 기능

- 네 바퀴 공기압 추이와 기온 영향을 비교하고 보충·누설 의심을 안내합니다.
- 타이어별 주행거리, 제조일, 위치교환·수리·교체 이력을 관리합니다.
- 정차 중 점검 안내와 타이어점 길안내를 제공하며 주행 중 조작을 제한합니다.
- 시연 모드는 별도 데이터베이스를 사용합니다. 공기압 판단은 정비 진단을 대신하지 않습니다.

## 설치·실행

제출 APK: [이무희_tirelog_release.apk](apk/이무희_tirelog_release.apk) · 앱 버전 `0.1.0` (`1`)

```bash
adb install -r apk/이무희_tirelog_release.apk
adb shell am start -n com.tirelog.app/.ui.card.CardActivity
```

Pleos Connect 에뮬레이터에서 앱을 열어 타이어를 등록하고 차량 라벨의 권장 공기압을 확인합니다. 차량 데이터 연결 상태는 앱에서 확인할 수 있습니다. 권한 오류나 연결 지연이 발생하면 다시 확인할 수 있습니다.

차량 데이터 없이 확인하려면 첫 화면의 **시연 모드로 보기**를 선택합니다. 시연 패널에서 **시연 준비 → 실행**을 누른 다음 1일차부터 순서대로 진행하면 기온 영향·공기 보충·누설 의심·정비 기록·위치교환 후 점검·계절 세트 교체를 확인할 수 있습니다. 이후 설정에서 차량 데이터 모드로 전환할 수 있습니다.

## 개발·테스트 환경

| 항목 | 버전·규격 |
| --- | --- |
| Pleos Connect 에뮬레이터 | 2.1.0 |
| 운영체제·CPU | Android 14 (API 34), ARM64 (`arm64-v8a`) |
| 화면 | 가로 2560 × 1440 px, 160 dpi (앱 영역은 시스템 UI 제외) |
| Java / Gradle / Android Gradle Plugin | JDK 17 / 8.14 / 8.13.2 |
| Kotlin | 2.2.10 |
| Android SDK | compileSdk 36, targetSdk 35, minSdk 29 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1 |

Android SDK 경로는 Android Studio 또는 로컬 `local.properties`의 `sdk.dir`로 설정합니다. SDK·라이브러리 다운로드에는 인터넷이 필요하며 Pleos SDK 저장소는 빌드 설정에 포함되어 있습니다. 앱 실행에 별도 API 키나 자체 서버는 필요하지 않습니다.

SDK에 필요한 권한은 [AndroidManifest.xml](app/src/main/AndroidManifest.xml)에 선언했습니다. 공모전 에뮬레이터 개발·테스트와 앱마켓 배포 권한 심사는 별도 절차입니다. 일반 Android 에뮬레이터에는 Pleos 차량·길안내 서비스가 없으므로 시연 모드를 사용합니다.

## 빌드·검증

```bash
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest  # 실행 중인 에뮬레이터 필요
./gradlew :app:assembleRelease           # 로컬 서명 설정 필요
```

APK는 `apk/` 폴더에 자동 복사됩니다. 디버그 APK는 Git에서 제외합니다. 제출된 release APK는 서명되어 있어 별도의 빌드 없이 설치할 수 있습니다.

release 재빌드에는 개인 서명 키와 Git에서 제외된 `.signing/release.properties`가 필요합니다. 아래 필드를 자신의 키 정보로 설정합니다. `storeFile`의 상대 경로는 저장소 루트 기준입니다.

```properties
storeFile=.signing/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

다른 키로 서명한 APK는 기존 설치본 위에 업데이트할 수 없습니다. 기존 앱을 삭제하면 저장된 기록도 삭제됩니다.

## 소스 구성·자료 출처

| 경로 | 내용 |
| --- | --- |
| `app/` | Android 화면, Room 저장, Pleos 연동, 시연 모드, 단위·기기 테스트 |
| `core/` | 공기압 판단, 타이어 이력·정비·계절 안내, 단위 테스트 |
| `gradle/` | 의존성 버전과 Gradle Wrapper |
| `apk/` | 서명된 제출용 release APK |

서체는 [Asta Sans OFL](app/src/main/assets/licenses/AstaSans-OFL.txt)을 따릅니다. 안내 이미지의 제작 정보는 [출처 파일](app/src/main/assets/licenses/tire-guide-provenance.json)에, 타이어 목록의 출처는 [카탈로그](app/src/main/assets/catalog/tire-models.json)에 보존했습니다. 외부 라이브러리는 각 배포 라이선스를 따릅니다.

## 공모전 제출 정보

- 저장소: https://github.com/moohee-lee/tirelog-submission
- APK 경로: `apk/이무희_tirelog_release.apk`
- 접수 메일에는 저장소 주소와 최종 커밋 전체 해시를 기재합니다. 해시는 `git rev-parse HEAD`로 확인합니다.
- 접수 마감: **2026년 11월 15일 23:59 (KST)**. 작성·서명한 공식 접수양식은 별도로 이메일 제출해야 합니다.
- 비공개 저장소 제출 시 심사 계정 초대는 필요하지 않습니다. **11월 16일까지 공개 전환 후 11월 23일까지 유지**해야 개발 산출물 가산점이 반영됩니다.
- 공개가 어려우면 마감 전에 **소스와 APK를 함께 압축한 파일의 다운로드 주소**를 접수 메일로 제출할 수 있습니다.

2026년 11월 14일 확인: [제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission) · [비공개 저장소 안내](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000007) · [FAQ](https://appdevchallenge.auto-hmg.io/faq)
