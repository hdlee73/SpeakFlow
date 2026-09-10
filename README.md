# SpeakFlow

Excel 문장 데이터로 영어 듣기·말하기를 연습하는 Android 앱입니다. Galaxy Z Fold의 커버 화면과 펼친 화면에 맞춰 크기가 자연스럽게 바뀌는 Jetpack Compose UI를 사용합니다.

## 주요 기능

- `.xlsx` 또는 `.csv` 데이터셋 불러오기
- 영어를 듣고 따라 말하기 / 한국어를 듣고 영어로 말하기
- Android TTS 예문 재생과 음성 인식 기반 발음 유사도 채점
- 말하는 동안 인식된 단어를 실시간 파란색으로 표시
- 발음 후 다시 발음하거나 다음 문장으로 직접 이동
- 여러 데이터셋을 앱에 저장하고 목록에서 즉시 전환
- 정답·오답·시간 초과 결과를 확인한 뒤 사용자가 직접 다음 문장으로 이동
- 긴 문장의 글자 크기 자동 조절 및 카드 스크롤 지원
- 순차 또는 무작위 재생
- 제한 시간(5~30초)과 통과 점수(55~95점) 설정
- 선택한 데이터셋과 설정을 기기에 보존

## 데이터셋 형식

기본 형식은 첫 번째 열(A열)에 한국어, 두 번째 열(B열)에 대응하는 영어를 넣는 방식입니다.

| A (한국어) | B (English) |
|---|---|
| 안녕하세요 | Hello |
| 기다려 볼까요? | What if we wait? |
| 좋은 생각이에요 | That's a good idea |

`A열=한국어`, `B열=영어` 형식입니다. 첫 번째 시트만 읽으며, 제목 행과 빈 셀 쌍은 건너뜁니다.

## Android Studio에서 실행

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle 동기화를 완료합니다.
3. Galaxy Z Fold 또는 Android 8.0(API 26) 이상 기기를 연결합니다.
4. `app` 실행 구성을 실행하고, 첫 음성 인식 때 마이크 권한을 허용합니다.

음성 합성과 인식은 기기에 설치된 Android 음성 서비스에 의존합니다. 인식 언어는 `en-US`, 한국어 안내 음성은 `ko-KR`로 설정됩니다.

## 구조

- `DatasetParser`: 외부 라이브러리 없이 XLSX/CSV의 문장 쌍을 읽음
- `SpeechEngine`: TextToSpeech와 SpeechRecognizer 연결
- `LearningViewModel`: 진행 순서, 타이머, 채점, 설정과 상태 관리
- `SpeakFlowApp`: 카드 중심 반응형 Compose 화면
