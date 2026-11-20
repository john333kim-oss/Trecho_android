# RunKKRun (안드로이드 러닝 앱, 구 Trecho) (안드로이드 프로토타입)

휴대폰 GPS와 걸음 센서로 러닝을 직접 측정하는 앱입니다. 갤럭시 S24+(Android 14 이상) 기준으로 만들었고 Android 10 이상에서 동작합니다. 개인 테스트용 비공개 프로토타입이며 결제 기능은 없습니다.

## 기능
- 러닝 시작·일시정지·끝내기. 화면이 꺼져도 "러닝 기록 중" 알림과 함께 계속 기록 (포그라운드 서비스)
- 실시간: 거리, 시간, 현재 페이스(최근 30초), 평균 페이스, 케이던스, GPS 상태, 직전 1km 구간 페이스
- 측정 보정: 정확도 25m 초과 점과 순간이동(12m/s 초과) 제거, 3m 미만 흔들림 무시, GPS가 8초 이상 끊기면 걸음수×학습한 보폭으로 거리 보충
- 기록 상세: Google 지도(키 등록 시, 없으면 OpenStreetMap)에 경로, 1km 구간별 페이스 막대, 상승 고도, 걸음, 보폭, 이번 러닝이 세운 최고기록
- 하단 탭: 홈(마지막 러닝 지도+거리·페이스·시간, 이번 주, 최고기록, 기록 목록) · 러닝(시작) · 계정(주별 거리 그래프, 월 달력·예전 달 보기, 전체 기록, 설정)
- 설정: 한국어/English 전환, 이름, 서버 주소
- 설정한 Trecho 서버(Claude 연결 MCP 서버)로 GPX 자동 전송 → Claude에서 "이번 주 러닝 어땠어?" 질문 가능

## 설치 (GitHub 자동 빌드)
1. 이 폴더를 비공개 GitHub 저장소 `main` 브랜치에 올리면 Actions가 테스트 후 APK를 만듭니다.
2. 저장소 → Actions → 최신 "Build APK" 실행 → Artifacts의 `trecho-apk` 를 휴대폰에서 내려받아 압축을 풉니다.
3. `app-debug.apk` 를 열고, "출처를 알 수 없는 앱 설치"를 이 한 번만 허용합니다.
4. 처음 "러닝 시작"을 누르면 위치(정확한 위치)·신체 활동·알림 권한을 허용합니다.
5. **꼭**: 설정 → 애플리케이션 → RunKKRun → 배터리 → "제한 없음". 갤럭시 절전 기능이 화면 꺼진 앱을 멈추는 것을 막습니다.

## Garmin 워치 기록 가져오기
1. Garmin Connect 앱 → 설정 → Health Connect(또는 '앱 연결/데이터 공유')에서 Health Connect 공유를 켭니다.
2. KR Run 홈 맨 위 "Garmin 연결 안 됨 · 연결"을 눌러 Health Connect 읽기 권한(운동·거리·걸음)을 허용합니다.
3. 앱을 열 때마다 최근 30일 Garmin 러닝을 가져옵니다. Garmin은 GPS 경로를 Health Connect에 쓰지 않아 가져온 기록에는 지도가 없습니다.

## 인스타그램 공유
홈 '최근 러닝' 오른쪽 공유 버튼(또는 기록 상세의 공유 버튼) → 사진 고르기 → 기록을 얹은 4:5 이미지 미리보기 → "Instagram 게시물".

## Google 지도 키 연결
1. Google Cloud 콘솔에서 프로젝트를 만들고 결제 계정을 연결한 뒤 "Maps SDK for Android"를 사용 설정합니다.
2. 사용자 인증 정보 → API 키 만들기 → 키 제한: "Android 앱", 패키지 `app.trecho.run`, SHA-1 `CF:90:92:91:AA:9E:78:39:72:2A:51:CE:79:CA:B2:EA:21:E8:FD:9F` (저장소의 debug.keystore). API 제한: Maps SDK for Android만.
3. GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret: 이름 `MAPS_API_KEY`, 값에 키를 붙여넣습니다.
4. Actions에서 "Build APK"를 다시 실행(Run workflow)하면 Google 지도가 들어간 APK가 나옵니다. 키가 없으면 OpenStreetMap으로 빌드됩니다.

## 백업
- 러닝 기록(`files/runs`, gzip 압축)과 설정은 Android 자동 백업으로 Google 드라이브에 하루 1번쯤(충전 중·Wi-Fi·대기 상태일 때) 백업됩니다. 휴대폰 설정 → 계정 및 백업 → Google 드라이브 백업이 켜져 있어야 합니다.
- 앱당 25MB 한도. 압축 저장으로 1시간 러닝 1개가 대략 수십 KB라 수백 개까지 들어갑니다(추정).
- 사진·동영상은 이 백업과 무관합니다(Google 포토·Samsung Cloud 별도).

## 구조
- `core/RunTracker.kt`: 측정 로직(안드로이드 의존성 없음, 단위 테스트 `RunTrackerTest`)
- `core/RunMath.kt`: 최고기록, 포맷, GPX 변환
- `RunService.kt`: 위치(Fused Location, 1초)·걸음 센서 수신, 알림
- `RunSession.kt`: 진행 중 기록 상태 공유
- `RunRepository.kt`: 폰 내부 JSON 저장, 서버 업로드, 설정
- `MainActivity.kt`: 화면(Compose) · `ui/RouteMap.kt`: 지도 선택(`GoogleRouteMap` / `OsmRouteMap`)

## 서비스화 전에 바꿀 것
- 키 없이 쓰는 OSM 기본 타일 서버는 개인·소량 사용만 허용 → 서비스 시 Google 지도로 통일
- 디버그 키 서명 → 정식 서명 키(비공개 보관)
- 앱 이름 확정 후 applicationId·아이콘 교체 (현재 `app.trecho.run`)
- 위치정보법(위치기반서비스사업 신고), 개인정보 처리방침, 회사 겸업 규정 확인
