# 비콘 연동 테스트 앱 (Android)

Kotlin + Jetpack Compose 기반 BLE 비콘 스캔 테스트 앱입니다. iBeacon, AltBeacon, Eddystone UID를 감지합니다.

## 요구 사항

- JDK 17
- Android SDK (compile/target 35, min 26)
- 실제 Android 기기 (에뮬레이터에서는 BLE 비콘 수신이 불가)

## 빌드

```bash
./gradlew assembleDebug
```

Android Studio에서 이 폴더를 연 뒤 Run으로 기기에 설치할 수도 있습니다.

## 배포 (서명된 릴리스)

처음 한 번 `keystore.properties`와 `keystore/chaodi-release.jks`를 만들어 둡니다. 이 두 파일은 앱 업데이트 서명에 필요하니 백업하세요. Git에는 올리지 않습니다.

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

- 직접 설치·공유용 APK: `app/build/outputs/apk/release/app-release.apk`
- Play 스토어 등록용 AAB: `app/build/outputs/bundle/release/app-release.aab`

이미 디버그 버전을 설치한 기기는 서명이 달라서, 릴리스 APK를 올리기 전에 기존 앱을 삭제해야 합니다.

## 권한

앱 실행 후 스캔 시작 시 아래 권한을 요청합니다.

- Android 12+ : `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`
- Android 11 이하 : `ACCESS_FINE_LOCATION`

위치 서비스와 블루투스가 켜져 있어야 비콘이 보입니다.
