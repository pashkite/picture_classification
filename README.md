# Personal Media Sorter

Android 기기 내부의 사진과 동영상을 MediaStore로 읽어와, 3단계 분류를 저장하고 다시 불러오는 개인용 분류 앱이다. 서버 호출 없이 동작하며, 분류 데이터는 앱 내부 `manifest.json` 파일로 유지된다.

## 현재 구현 범위

- 사진/동영상 MediaStore 조회
- 목록 화면 썸네일, 메타데이터, 현재 분류 상태 표시
- 항목별 1차 / 2차 / 3차 분류 편집 및 저장
- Storage Access Framework 기반 분류 데이터 내보내기 / 가져오기
- WorkManager 기반 백그라운드 전체 자동분류
- 분류 결과에 따른 실제 파일 이동
- 자동분류 진행 중 이동 개수 실시간 반영
- 자동분류 종료 후 마지막 처리/이동 요약 유지
- 상세 화면의 개별 `AI 자동분류 제안 적용`
- 상세 화면의 엔진별 실행 여부, 축소 모드, 대표 프레임 근거 디버그 카드
- 설정 화면의 엔진 상태, 알림 설정, 백업 자리 표시
- `manifest.json` 기반 로컬 저장과 앱 재실행 후 재로드

## 분류 엔진 구조

현재 자동분류는 더 이상 ML Kit 단독 구조가 아니다. 다음 혼합 구조로 동작한다.

- 주 의미 분류: `MediaPipe Tasks Vision`
  - 기본 자산: `efficientnet-lite4.tflite`
  - 기본 경로: `MediaPipe ImageClassifier`
  - task 메타데이터 제약이 있으면 같은 `efficientnet-lite4.tflite`를 `TensorFlow Lite Interpreter`로 직접 실행
  - 외부 라벨맵: `efficientnet-imagenet-labels.txt`
- `ImageEmbedder` with `mobilenet_v3_small.tflite`
- 보조 신호 추출: `ML Kit`
  - Face Detection
  - Text Recognition
  - Image Labeling
- 최후 fallback: 파일명 / 경로 / MIME 기반 규칙 엔진

### 현재 실제 분류 파이프라인

1. `MediaFrameSampler`
   - 이미지: 대표 썸네일 1장
   - 동영상: 20% / 50% / 80% 대표 프레임 3장
2. `MainImageClassifier`
   - `efficientnet-lite4.tflite`
   - 우선 `MediaPipe ImageClassifier`
   - 실패 시 같은 Lite4 모델을 `TensorFlow Lite Interpreter`로 직접 실행
3. `MediaPipe ImageEmbedder`
   - `mobilenet_v3_small.tflite`
   - 로컬 프로토타입 이미지와의 유사도 계산
4. `ML Kit`
   - Face Detection
   - Text Recognition
   - Image Labeling
5. `ClassificationPipeline`
   - 프레임별 점수 집계
   - taxonomy 매핑
   - 얼굴/텍스트/UI/영수증/보조 라벨 점수 보정
   - 안전 폴백과 시리즈 후보 생성

상세 화면 디버그 카드에서 현재 항목의 `main-image-classifier`, `mediapipe-image-embedder`, `mlkit-face`, `mlkit-text`, `mlkit-label` 실제 실행 여부와 프레임별 요약을 바로 볼 수 있다.

### 역할 분담

- `EfficientNet-Lite4`
  - 사람, 풍경, 음식, 반려동물, 문서 계열의 일반 의미 분류에 사용
  - generic ImageNet 분류기이므로 애니 / 게임 / 밈 / 작품명은 낮은 확신도에서 보수적으로 해석
- `MediaPipe ImageEmbedder`
  - 로컬 프로토타입 이미지와의 유사도 비교에 사용
  - 그림 / 일러스트 / 애니 관련 / 게임 관련 / 밈 같은 스타일 계열 점수 보강에 사용
- `ML Kit`
  - 얼굴 수와 중앙 얼굴 강도 기반 셀카 / 인물 보조 판별
  - OCR 기반 문서 / 영수증 / 스크린샷 / UI 텍스트 힌트 추출
  - 이미지 라벨은 낮은 가중치의 보조 prior 로만 사용
- `BasicSuggestionClassifier`
  - 모델 로딩 실패 또는 낮은 신뢰도 상황에서만 약한 prior로 사용

## 이번 정확도 수정 핵심

특히 `배경 일러스트 -> 사람 / 캐릭터 중심` 오분류를 줄이도록 아래를 조정했다.

- `vision_taxonomy.json`에 `배경 중심` 2차 분류를 추가했다.
- `캐릭터 중심` taxonomy 가 일반 `person / portrait / face`를 곧바로 받지 않도록 `fictional character / cartoon / animation` 위주로 축소했다.
- 작은 얼굴 1개만 검출된 경우 사람 / 셀카 강가중치를 주지 않도록, 얼굴 면적 비율과 중앙도를 함께 보게 했다.
- `ML Kit Image Labeling` 결과를 실제 융합에 넣되, 주 분류를 덮지 못하도록 낮은 가중치의 보조 prior 로만 반영했다.
- 동영상 프레임 집계는 단순 평균이 아니라 단일 outlier 프레임을 덜 믿는 robust aggregation 으로 바꿨다.
- fallback 규칙의 `사람 / 셀카 -> 캐릭터 중심` prior 를 `인물 중심`으로 교정했다.

## 런타임 선택 이유

이번 작업은 학습이나 파인튜닝이 아니라, 기존 generic 분류기를 더 큰 generic 분류기로 교체하는 작업이다. `MobileCLIP` 또는 `SigLIP` 계열이 이상적이지만, 현재 저장소 구조와 Android 통합성을 해치지 않고 실제로 빌드/실행 가능한 경로를 우선했다.

- 선택한 런타임: `com.google.mediapipe:tasks-vision:0.10.29`
- 보조 런타임: `org.tensorflow:tensorflow-lite:2.14.0`
- 선택 이유:
  - Android 통합성이 높고 Compose/WorkManager 구조에 최소 침습으로 들어간다.
  - `ImageEmbedder`는 기존 MediaPipe 구조를 그대로 유지할 수 있다.
  - EfficientNet-Lite4 공식 체크포인트는 generic TFLite 모델이라 task 메타데이터가 약할 수 있어, 같은 Lite4 모델을 direct TFLite로도 실행할 수 있게 해 두는 편이 더 안전하다.
  - 모델 파일이 없거나 초기화 실패 시 축소 모드로 안전하게 떨어지기 쉽다.

즉, 이번 구현은 `EfficientNet-Lite4 generic classifier + MediaPipe embedder + ML Kit + 규칙 fallback` 조합이다. 학습이나 재학습은 하지 않았다.

## 현재 분류 단계

### 1차 분류

- 사람
- 셀카
- 풍경
- 음식
- 반려동물
- 문서
- 영수증
- 스크린샷
- 그림
- 일러스트
- 애니 관련
- 게임 관련
- 밈
- 기타

### 2차 분류

- 애니 이미지
- 게임 이미지
- 일반 일러스트
- 배경 중심
- 만화풍/웹툰풍
- 팬아트 가능성
- 캐릭터 중심
- UI 중심
- 전투 화면
- 대사/자막 중심
- 로고/타이틀 화면

### 3차 분류

- 로컬 `vision_taxonomy.json`의 작품 / 시리즈 후보 사전으로 추정
- 파일명, 상대 경로, OCR 텍스트를 함께 사용
- 확신도가 낮으면 빈 값으로 남김
- 상세 화면의 디버그 정보에서 상위 후보를 볼 수 있음

## 동영상 처리

- 동영상은 대표 프레임 3장(20%, 50%, 80%)을 추출한다.
- 각 프레임에 대해 MediaPipe 의미 분류를 돌리고 결과를 집계한다.
- ML Kit는 일부 프레임에서 OCR / 얼굴 / 보조 태그를 추출한다.
- 최종적으로 하나의 상위 분류, 세부 분류, 작품 후보를 만든다.

## 결과 융합 방식

최종 분류는 단순 덮어쓰기가 아니라 점수 기반 융합으로 만든다.

- Lite4 classifier 점수는 기본 의미 점수로 사용
- MediaPipe embedder는 로컬 프로토타입 이미지와의 유사도로 스타일 점수를 보강
- OCR 텍스트가 많으면 문서 / 영수증 / 스크린샷 가중치 상승
- 얼굴 수만으로는 부족하고, 얼굴 면적 비율과 중앙도가 충분할 때만 사람 / 셀카 가중치를 강하게 올린다.
- UI 키워드가 많으면 스크린샷 / 게임 관련 / UI 중심 가중치 상승
- ML Kit 이미지 라벨은 낮은 가중치의 보조 prior 로만 반영한다.
- 배경 일러스트 신호가 강하고 얼굴 비중이 작으면 `캐릭터 중심`보다 `배경 중심`을 우선한다.
- 파일명 / 상대 경로 기반 규칙은 약한 prior로만 사용
- 동영상 프레임은 단순 평균이 아니라 극단값을 덜 믿는 robust aggregation 으로 집계한다.
- Lite4 top score와 2위 score 차이가 작고 ML Kit / embedder 보조 신호도 약하면 `기타 / 검토 필요`로 폴백

추론 결과에는 다음이 함께 저장된다.

- 사용된 엔진 목록
- 모델별 원시 태그
- 최종 후보 점수
- 시리즈 후보 목록
- 대표 프레임별 요약
- 최종 근거 문자열
- 축소 모드 여부

## 저장 구조

분류 데이터는 앱 내부 경로에 저장된다.

```text
<app files dir>/classification-data/manifest.json
```

예시:

```json
{
  "schemaVersion": 2,
  "formatName": "manifest.json",
  "entries": [
    {
      "recordId": "sha256-based-id",
      "classification": {
        "level1": "애니 관련",
        "level2": "캐릭터 중심",
        "level3": "장송의 프리렌"
      },
      "engineInfo": {
        "engineId": "hybrid-mlkit-mediapipe",
        "engineVersion": "efficientnet-lite4-fp32 + mediapipe-0.10.29 + mlkit-bundled + rules-v3"
      },
      "debugInfo": {
        "confidence": 0.81,
        "reducedMode": false,
        "finalScores": [
          { "label": "애니 관련", "score": 1.74 }
        ],
        "seriesCandidates": [
          { "label": "장송의 프리렌", "score": 0.64 }
        ]
      }
    }
  ]
}
```

### 식별 방식

- `recordId`는 MediaStore ID, content URI, 경로, 파일명, 크기, 수정 시각을 조합한 SHA-256 기반 키다.
- manifest 안에는 원본 fingerprint를 함께 저장해 재식별 가능성을 높였다.
- 기존 schema 1 manifest를 가져와도 optional 필드 기본값으로 읽는다.

## 축소 모드

다음 상황에서는 앱이 죽지 않고 축소 모드로 동작한다.

- `efficientnet-lite4.tflite` 자산 누락
- `efficientnet-imagenet-labels.txt` 자산 파싱 실패
- `mobilenet_v3_small.tflite` 자산 누락
- Lite4 task/direct 초기화 실패

축소 모드에서는:

- ML Kit 보조 신호와 규칙 기반 분류만 사용
- 설정 화면과 상세 화면 디버그 카드에 축소 모드 여부를 표시
- 저장 manifest에도 `debugInfo.reducedMode=true`가 남는다

`fallbackUsed=true`는 대표 프레임을 읽지 못했거나, Lite4 / embedder 계열을 쓸 수 없어 사실상 규칙 기반 축소 모드로 내려간 경우에만 표시한다. 일반 normal mode 에서의 파일명 / 경로 prior 는 약한 보조 신호로만 처리한다.

## 설정 화면에서 확인 가능한 것

- 현재 엔진 이름
- 모델별 로딩 여부
- 모델 파일명
- 모델 역할
- 축소 모드 여부
- 알림 권한 / 앱 알림 상태

## 실행 방법

### 요구 사항

- JDK 17
- Android SDK
- Gradle Wrapper

### 빌드

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

설치 예시:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 현재 한계

- `EfficientNet-Lite4`는 앱 전용 분류기로 학습한 모델이 아니라 generic ImageNet 분류기다.
- 따라서 애니 / 게임 / 밈 / 특정 작품명 / 특정 캐릭터 정확도를 과장할 수 없고, 이번 버전은 낮은 확신도에서 `기타 / 검토 필요`로 보수적으로 폴백한다.
- `MobileCLIP` / `SigLIP`처럼 텍스트와 완전히 정렬된 멀티모달 임베딩은 아직 붙이지 않았다.
- 따라서 이번 버전의 작품명 추정은 이미지 자체보다 경로 / OCR / 로컬 키워드 사전 의존도가 더 높다.
- MediaPipe 프로토타입 매칭은 로컬 생성 프로토타입 이미지 기반이라 정밀도가 제한적이다.
- Lite4는 Lite0보다 무겁다. TensorFlow TPU 공식 README 기준 Pixel 4 CPU latency는 Lite0 FP32 12ms, Lite4 FP32 76ms 수준이라, 자동분류는 계속 백그라운드 worker 중심으로 돌리는 편이 안전하다.
- Samsung Now Bar 노출은 앱이 요청할 수는 있어도 OEM 정책상 보장할 수 없다.
- 에뮬레이터/실기기 UI 자동 검증은 이 세션에서 수행하지 못했다.

## 참고한 공식 경로

- MediaPipe image embedder sample model path:
  - `https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite`
- TensorFlow TPU EfficientNet-Lite4 checkpoint:
  - `https://storage.googleapis.com/cloud-tpu-checkpoints/efficientnet/lite/efficientnet-lite4.tar.gz`
- TensorFlow TPU ImageNet labels map:
  - `https://storage.googleapis.com/cloud-tpu-checkpoints/efficientnet/eval_data/labels_map.txt`
- Android runtime dependency:
  - `com.google.mediapipe:tasks-vision:0.10.29`
