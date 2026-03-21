# worked.md

## 1. 이번 작업의 목표

- 현재 저장소의 온디바이스 분류 파이프라인을 실제 코드 기준으로 다시 분석한다.
- `ML Kit + MediaPipe + fallback` 경로가 실제로 어떻게 연결되는지 확인한다.
- `배경 일러스트 -> 사람 / 캐릭터 중심` 오분류 원인을 코드 수준에서 특정한다.
- 학습 없이 taxonomy / threshold / fusion / frame aggregation / debug 구조를 개선한다.
- 빌드, 테스트, 문서화, git 커밋과 푸시까지 마무리한다.

## 2. 실제로 구현한 기능

- 분류 결과 debug info 구조 확장
  - `fallbackUsed`
  - `usedEngines`
  - `frameSummaries`
  - 모델별 `loaded / invoked / notes`
- `MediaPipeSemanticInferenceEngine`
  - 프레임별 classifier / embedder 결과 요약 생성
  - 프레임 집계를 단순 평균에서 robust aggregation 으로 변경
  - classifier / embedder 실제 로드 및 실행 여부를 결과에 포함
- `MlKitAuxiliaryInferenceEngine`
  - `face detection / text recognition / image labeling` 실제 실행 여부를 구조화해 남김
  - 최대 얼굴 면적 비율, 최대 중앙도 계산 추가
  - 프레임별 ML Kit 요약 생성
- `ClassificationPipeline`
  - ML Kit image labeling 결과를 낮은 가중치의 보조 prior 로 실제 반영
  - 작은 얼굴 1개만으로 사람 / 셀카를 강하게 밀지 않도록 면적/중앙도 기반 gating 추가
  - 배경 일러스트 성격이 강하면 `캐릭터 중심`보다 `배경 중심`을 우선하는 보수 규칙 추가
  - 사용 엔진 목록, 프레임별 요약, 모델별 요약을 debug info 에 저장
- `vision_taxonomy.json`
  - `배경 중심` 2차 분류 추가
  - `캐릭터 중심` taxonomy 를 일반 `person / portrait / face` 위주 매핑에서 축소
- `BasicSuggestionClassifier`
  - `사람 / 셀카 -> 캐릭터 중심` prior 를 `인물 중심`으로 교정
- 상세 화면 디버그 카드
  - 엔진별 로드/실행 여부 표시
  - 프레임별 요약 표시
  - fallback / reduced mode 표시 강화
- 단위 테스트 추가
  - 배경 일러스트
  - 캐릭터 중심 일러스트
  - outlier 프레임 집계
  - 사람 fallback prior 교정

## 3. 생성하거나 수정한 파일 목록

- `app/src/main/assets/vision_taxonomy.json`
- `app/src/main/java/com/codex/ppa/domain/ClassificationEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/Models.kt`
- `app/src/main/java/com/codex/ppa/domain/OnDeviceAiClassificationEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/VisionClassificationPipeline.kt`
- `app/src/main/java/com/codex/ppa/ui/MainApp.kt`
- `app/src/main/java/com/codex/ppa/ui/MainViewModel.kt`
- `app/src/test/java/com/codex/ppa/domain/BasicSuggestionClassificationEngineTest.kt`
- `app/src/test/java/com/codex/ppa/domain/ClassificationFusionHeuristicsTest.kt`
- `README.md`
- `worked.md`

## 4. 주요 설계 결정 사항

- 새 학습이나 파인튜닝은 하지 않는다.
  - 기존 `EfficientNet-Lite4 + ImageEmbedder + ML Kit + fallback` 구조를 유지한 채 정확도를 보정한다.
- `배경 일러스트` 문제는 모델 하나를 바꾸기보다 taxonomy / fusion / 보조 신호 해석을 보수화하는 쪽으로 해결한다.
- `캐릭터 중심`은 사람 전체가 아니라 `fictional character / cartoon / animation` 성격이 강할 때만 밀도록 조정했다.
- 얼굴은 `개수`만으로 해석하지 않고 `면적 비율 + 중앙도`가 있어야 사람 / 셀카 가중치를 크게 준다.
- 동영상 대표 프레임은 단순 평균보다 single outlier frame 을 덜 믿는 robust aggregation 을 사용한다.
- fallback 엔진은 항상 약한 prior 로 호출되지만, `fallbackUsed=true`는 실제 축소 모드에 가까운 경우에만 세운다.

## 5. 빌드/검증에 사용한 명령어

```bash
./gradlew testDebugUnitTest assembleDebug
find app/build/test-results/testDebugUnitTest -name '*.xml' -print0 | xargs -0 rg -n "tests=|failures=|errors="
git status --short
git diff -- app/src/main/java/com/codex/ppa/domain/VisionClassificationPipeline.kt \
  app/src/main/assets/vision_taxonomy.json \
  app/src/main/java/com/codex/ppa/domain/ClassificationEngine.kt \
  app/src/main/java/com/codex/ppa/domain/OnDeviceAiClassificationEngine.kt \
  app/src/main/java/com/codex/ppa/ui/MainApp.kt \
  app/src/main/java/com/codex/ppa/ui/MainViewModel.kt \
  app/src/test/java/com/codex/ppa/domain/ClassificationFusionHeuristicsTest.kt \
  app/src/test/java/com/codex/ppa/domain/BasicSuggestionClassificationEngineTest.kt \
  app/src/main/java/com/codex/ppa/domain/Models.kt
```

## 6. 빌드/검증 결과

- `./gradlew testDebugUnitTest assembleDebug`: 성공
- 최종 결과: `BUILD SUCCESSFUL`
- 단위 테스트 통과
  - `ClassificationManifestFileStoreTest`: 2건 통과
  - `ClassificationPathPolicyTest`: 3건 통과
  - `BasicSuggestionClassificationEngineTest`: 5건 통과
  - `ClassificationFusionHeuristicsTest`: 7건 통과
  - 총 17건 통과
- APK 생성 확인
  - `app/build/outputs/apk/debug/app-debug.apk`

## 7. 남은 한계점 및 TODO

- `EfficientNet-Lite4`는 generic ImageNet 분류기라 애니 / 게임 / 작품명 / 캐릭터 세부 의미를 정확히 이해하는 전용 모델은 아니다.
- `배경 중심` 규칙을 넣었지만, 복잡한 혼합 장면이나 key visual 류 이미지는 여전히 애매할 수 있다.
- `ML Kit image labeling`은 보조 prior 로만 쓰기 때문에, 장르/작품 단정력은 제한적이다.
- 현재는 UI 디버그 카드와 unit test 로 다중 엔진 실행 여부를 확인할 수 있지만, 실기기 자동화 UI 검증은 아직 없다.
- 더 나은 의미 기반 분류가 필요하면 다음 우선순위는 `MobileCLIP / SigLIP` 계열 온디바이스 추가다.

## 8. 현재 Codex 세션 정보

- 확인 가능
- `CODEX_THREAD_ID=019d0a7a-6af8-7d80-b329-ae8eaf13ffbb`
