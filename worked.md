# worked.md

## 1. 이번 작업의 목표

- 현재 저장소의 온디바이스 분석 파이프라인을 다시 분석한다.
- 주 분류기를 `efficientnet-lite0.tflite`에서 학습 없는 `efficientnet-lite4.tflite` 기반으로 교체한다.
- 기존 `ImageEmbedder + ML Kit` 보조 신호 구조는 유지하되, generic 분류기 한계를 반영해 더 보수적인 결과 융합과 폴백을 넣는다.
- 자동분류 진행 중 `이동` 개수가 0으로 고정돼 보이는 문제와, 완료 후 결과 요약이 사라지는 문제를 함께 수정한다.
- 코드, 문서, 빌드/검증까지 한 번에 마무리한다.

## 2. 실제로 구현한 기능

- 주 분류기 자산 경로를 `efficientnet-lite4.tflite`로 교체
- EfficientNet-Lite4 공식 FP32 checkpoint와 외부 ImageNet labels map 자산 사용
- `MainImageClassifier` 경로 추가
  - 기본 시도: `MediaPipe ImageClassifier` + Lite4
  - 예외/메타데이터 제약 시: 같은 Lite4 모델을 `TensorFlow Lite Interpreter`로 직접 실행
- Lite4 direct 경로용 전처리 추가
  - 입력 크기 `300x300`
  - 정규화 `(pixel - 127) / 128`
  - softmax 후 top-k 라벨 추출
- `ImageEmbedder + mobilenet_v3_small.tflite` 유지
- `ML Kit Face Detection / Text Recognition / Image Labeling` 유지
- 분류 융합 로직 보수화
  - low confidence + weak auxiliary signal이면 `기타 / 검토 필요`로 폴백
  - generic classifier가 애니/게임/밈을 과도하게 단정하지 않도록 수정
- `FolderNameGenerator`를 추가해 3차 후보 선택을 더 보수적으로 정리
- 대표 프레임 단위 classifier / embedding 결과에 소규모 in-memory cache 추가
- 수동 AI 제안 추론을 `Dispatchers.Default`로 옮겨 메인 스레드 차단 가능성을 줄임
- 자동분류 worker에서 `이동 개수` 변경 시 즉시 progress를 다시 발행하도록 수정
- 자동분류 종료 후에도 마지막 처리/이동 요약이 메인 화면 카드에 남도록 UI 상태 추가
- `검토 필요` 2차 기본 후보 추가
- Lite4 보수적 폴백 회귀 테스트 추가
- README / worked.md 갱신

## 3. 생성하거나 수정한 파일 목록

- `app/build.gradle.kts`
- `app/src/main/assets/efficientnet-lite4.tflite`
- `app/src/main/assets/efficientnet-imagenet-labels.txt`
- `app/src/main/assets/mobilenet_v3_small.tflite`
- `app/src/main/assets/vision_taxonomy.json`
- `app/src/main/java/com/codex/ppa/domain/Models.kt`
- `app/src/main/java/com/codex/ppa/domain/ClassificationEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/VisionClassificationPipeline.kt`
- `app/src/main/java/com/codex/ppa/domain/OnDeviceAiClassificationEngine.kt`
- `app/src/main/java/com/codex/ppa/data/ClassificationManifestFileStore.kt`
- `app/src/main/java/com/codex/ppa/data/ClassificationRepository.kt`
- `app/src/main/java/com/codex/ppa/worker/AutoClassificationWorker.kt`
- `app/src/main/java/com/codex/ppa/ui/MainViewModel.kt`
- `app/src/main/java/com/codex/ppa/ui/MainApp.kt`
- `app/src/test/java/com/codex/ppa/data/ClassificationManifestFileStoreTest.kt`
- `app/src/test/java/com/codex/ppa/domain/BasicSuggestionClassificationEngineTest.kt`
- `app/src/test/java/com/codex/ppa/domain/ClassificationFusionHeuristicsTest.kt`
- `README.md`
- `worked.md`

참고:
- 기존 `efficientnet-lite0.tflite`는 현재 코드에서 더 이상 참조하지 않으며, 최종 정리 단계에서 assets 밖(`/tmp/efficientnet-lite0.tflite.backup`)으로 이동했다.

## 4. 주요 설계 결정 사항

- 주 분류기 교체는 `학습 없는 generic classifier 교체`로 제한했다.
  - Lite4를 앱 전용으로 재학습하거나 파인튜닝하지 않는다.
  - 따라서 애니/게임/밈/작품명은 보수적으로 해석한다.
- Lite4 공식 checkpoint는 task metadata가 약할 수 있으므로, 런타임을 두 단계로 잡았다.
  - 1차: `MediaPipe ImageClassifier`
  - 2차: 같은 Lite4 자산을 `TensorFlow Lite Interpreter`로 직접 실행
  - 이는 모델 교체가 아니라 동일 모델의 실행 경로 보강이다.
- Lite4 direct 경로의 전처리는 TensorFlow TPU EfficientNet Lite 공식 코드 기준으로 맞췄다.
  - 입력 크기 `300`
  - `MEAN_RGB = 127`
  - `STDDEV_RGB = 128`
- `ImageEmbedder + ML Kit` 구조는 유지했다.
- generic classifier의 과해석을 막기 위해 low-confidence review fallback을 추가했다.
  - top score가 약하고
  - 2위와 차이가 작고
  - ML Kit / embedder 보조 신호도 약하면
  - `기타 / 검토 필요`로 폴백
- 자동분류 상태는 완료 후에도 남겨 사용자에게 마지막 이동/처리 결과를 보여주게 했다.

## 5. 빌드/검증에 사용한 명령어

```bash
./gradlew testDebugUnitTest assembleDebug
env | rg 'CODEX|THREAD|SESSION'
ls -lh app/src/main/assets
zipinfo -1 app/build/outputs/apk/debug/app-debug.apk | rg 'efficientnet|mobilenet|vision_taxonomy'
sed -n '1,220p' /tmp/tf-tpu-ppa/models/official/efficientnet/lite/README.md
rg -n "efficientnet-lite4|get_model_input_size|MEAN_RGB|STDDEV_RGB" /tmp/tf-tpu-ppa/models/official/efficientnet -g '*.py'
```

## 6. 빌드/검증 결과

- `./gradlew testDebugUnitTest assembleDebug`: 성공
- 최종 결과: `BUILD SUCCESSFUL`
- 단위 테스트 통과
  - `ClassificationManifestFileStoreTest`: 2건 통과
  - `ClassificationPathPolicyTest`: 3건 통과
  - `BasicSuggestionClassificationEngineTest`: 4건 통과
  - `ClassificationFusionHeuristicsTest`: 4건 통과
  - 총 13건 통과
- 생성된 APK
  - `app/build/outputs/apk/debug/app-debug.apk`
- 모델 자산 확인
  - `efficientnet-lite4.tflite` 약 50MB
  - `efficientnet-imagenet-labels.txt` 약 30KB
  - `mobilenet_v3_small.tflite` 약 4MB
  - `vision_taxonomy.json` 약 11KB
- APK 자산 확인
  - `assets/efficientnet-lite4.tflite`
  - `assets/efficientnet-imagenet-labels.txt`
  - `assets/mobilenet_v3_small.tflite`
  - `assets/vision_taxonomy.json`
  - `assets/efficientnet-lite0.tflite`는 APK 안에 없음
- 빌드 중 경고
  - `tensorflow-lite` native strip warning이 있었지만 빌드는 성공했고 APK 패키징도 완료됐다.

## 7. 남은 한계점 및 TODO

- Lite4는 generic ImageNet 분류기라 앱 전용 의미 분류 정확도를 보장할 수 없다.
- 애니/게임/작품명/캐릭터는 여전히 embedder + OCR/path prior 의존도가 높다.
- direct TFLite Lite4 경로는 같은 모델을 더 안전하게 돌리기 위한 것이지, 전용 의미 모델을 추가한 것은 아니다.
- Lite4는 Lite0보다 훨씬 무겁다. 저사양 기기에서는 초기화 시간과 분석 시간이 더 늘 수 있다.
- 실제 기기에서 Lite4 경로와 진행 상태 UI는 수동 확인이 더 필요하다.
- 이 시점에는 `adb devices`에 기기가 잡히지 않아 삭제 후 재설치를 수행하지 못했다.
- Samsung Now Bar 노출은 계속 OEM 정책 제약이 남아 있다.
- 필요하면 다음 단계에서 MobileCLIP 또는 SigLIP 계열을 추가해 generic classifier 한계를 보완할 수 있다.

## 8. 현재 Codex 세션 정보

- 확인 가능
- `CODEX_THREAD_ID=019d0a7a-6af8-7d80-b329-ae8eaf13ffbb`
