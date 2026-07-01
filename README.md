# 소음 증거 수집기 - Android 앱

## 빌드 방법

### 필요 환경
- Android Studio Hedgehog 이상
- JDK 17
- Android SDK API 34

### 빌드 순서

1. Android Studio에서 이 폴더를 열기
2. `etApiKey` 입력란에 Anthropic API 키 입력
   - https://console.anthropic.com 에서 발급
3. Build → Generate Signed APK 또는 그냥 Run으로 폰에 바로 설치

### 커맨드라인 빌드 (Android Studio 없이)

```bash
# ANDROID_HOME 환경변수 설정 필요
./gradlew assembleDebug
# APK 위치: app/build/outputs/apk/debug/app-debug.apk
```

## 사용법

1. 앱 실행 → API 키 입력 (최초 1회, 저장됨)
2. 저장할 발생원 선택 (오토바이만 등) — 미선택 시 전체 저장
3. 녹취 장소 입력
4. 임계값 조정 (권장: 75~80 dB부터 시작)
5. **녹음 시작** 버튼

## 파일 저장 위치

`내 파일 > Downloads > 소음증거 > ` 폴더

파일명 예시:
```
2026-06-30_14-23-05_오토바이_87dB_서울시○○구.m4a
```

## 주요 기능

- **포그라운드 서비스**: 화면 꺼져도 계속 동작
- **WakeLock**: CPU 절전 방지 (최대 24시간)
- **자동 저장**: Downloads/소음증거 폴더에 바로 저장
- **AI 분류**: 클립마다 Claude가 오토바이/승용차/트럭/버스/경적/기타 분류
- **발생원 필터**: 원하는 소음원만 저장
- **알림 상주**: 상태바에서 현재 상태 확인, 알림에서 바로 중지 가능

## 권한

- 마이크: 소음 감지 필수
- 알림: 포그라운드 서비스 상태 표시
- 인터넷: Claude API 호출
- 저장소: 클립 파일 저장 (Android 9 이하)
