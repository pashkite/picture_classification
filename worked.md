# worked.md

## 1. 이번 작업의 목표

- 현재 온디바이스 다중 모델 분류 파이프라인을 다시 분석한다.
- `image.png`처럼 사람 없는 배경 일러스트가 `사람 / 인물 중심`으로 가는 오분류 원인을 코드 수준에서 찾는다.
- 학습 없이, 기존 `EfficientNet-Lite4 + MobileCLIP + ImageEmbedder + ML Kit` 구조를 유지한 채 정확도 보정을 넣는다.
- 빌드, 테스트, 문서화, git 커밋/푸시까지 마무리한다.

## 2. 실제로 구현한 기능

- `MobileClipSemanticEngine`에 similarity 보정 계층 추가
  - raw cosine 절대값과 top1-top2 margin 이 약하면 CLIP score 자체를 낮춘다.
  - 애매한 장면에서 `사람` / `캐릭터 중심`이 과하게 튀는 문제를 줄인다.
- `ClassificationPipeline`의 우세 규칙 보강
  - 얼굴 없음
  - 인물 텍스트 키워드 없음
  - classifier human keyword 없음
  - 풍경 / 배경 / 일러스트 신호 우세
  조건일 때 `사람` / `셀카` / `캐릭터 중심`을 억제하고 `풍경` / `일러스트` / `배경 중심`을 밀어준다.
- `shouldUseReviewFallback()` 완화
  - 얼굴 없는 `풍경 / 그림 / 일러스트` 후보는 사람/애니 계열보다 느슨한 review threshold 를 적용한다.
  - 그래서 scenic artwork가 `기타 / 검토 필요`로 과하게 떨어지는 현상을 줄였다.
- scenic/background taxonomy keyword 보강
  - `moon`, `cloud`, `star`, `night`, `wallpaper`, `scenery` 등을 풍경 / 배경 계열에 추가했다.
- 회귀 테스트 추가
  - 얼굴 없는 배경 일러스트가 사람보다 풍경/배경 중심을 우선하는지 검증
  - MobileCLIP의 애매한 similarity 가 낮은 confidence 로 보정되는지 검증
- 엔진 버전 문자열 갱신
  - `rules-v5`

## 3. 생성하거나 수정한 파일 목록

- `app/src/main/assets/vision_taxonomy.json`
- `app/src/main/java/com/codex/ppa/domain/MobileClipSemanticEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/OnDeviceAiClassificationEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/VisionClassificationPipeline.kt`
- `app/src/test/java/com/codex/ppa/domain/ClassificationFusionHeuristicsTest.kt`
- `app/src/test/java/com/codex/ppa/domain/MobileClipSemanticEngineTest.kt`
- `README.md`
- `worked.md`

## 4. 주요 설계 결정 사항

- `MobileCLIP`은 그대로 쓰되, softmax 랭킹만 믿지 않고 raw cosine 절대값과 margin 으로 confidence 를 재보정한다.
  - 이유: 후보 프롬프트 수가 적으면 close match 도 softmax 상위 1개가 과하게 커질 수 있기 때문이다.
- 사람 분류는 얼굴 또는 명시적 human evidence 가 있을 때만 강하게 인정한다.
  - 얼굴 없는 scenic illustration 은 사람으로 오탐될 가능성이 높아 보수적으로 막았다.
- `풍경`과 `배경 중심`은 path keyword 뿐 아니라 classifier keyword 로도 더 잘 잡히게 했다.
  - 이유: 실제 샘플 `image.png`는 파일명/경로 힌트가 거의 없고 시각 신호가 핵심이기 때문이다.
- 프롬프트 임베딩 JSON 자체는 이번 작업에서 다시 생성하지 않았다.
  - 저장소 안에 MobileCLIP text encoder/tokenizer 자산이 없어서, 이번 수정은 코드/규칙/threshold 중심으로 처리했다.

## 5. 빌드/검증에 사용한 명령어

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git status --short --branch
```

## 6. 빌드/검증 결과

- `./gradlew testDebugUnitTest`: 성공
- `./gradlew assembleDebug`: 성공
- 최종 결과: `BUILD SUCCESSFUL`
- 이번 변경 후 단위 테스트 통과
  - `ClassificationManifestFileStoreTest`: 2건
  - `ClassificationPathPolicyTest`: 3건
  - `BasicSuggestionClassificationEngineTest`: 5건
  - `ClassificationFusionHeuristicsTest`: 8건
  - `MobileClipSemanticEngineTest`: 4건
  - 총 22건
- 작업 시점 git 상태에서 사용자 샘플 파일 `IMAGE.png`는 untracked 상태였고 커밋에 포함하지 않았다.

## 7. 남은 한계점 및 TODO

- 이번 수정은 학습 없는 threshold / taxonomy / fusion 보정이다. 전용 애니/게임/일러스트 의미 모델을 추가 학습한 것은 아니다.
- scenic artwork 보수 규칙을 완화했기 때문에, 아주 약한 풍경 신호만 있는 이미지가 이전보다 `풍경`으로 남을 가능성은 약간 올라갔다.
- `MobileCLIP prompt embeddings` 자체를 다시 생성하지는 못했다.
  - text encoder 자산이 저장소 안에 없어서, 프롬프트 내용과 임베딩을 동시에 업데이트하는 작업은 이번 범위에서 제외했다.
- 사람 얼굴이 작거나 가려진 실제 사진은 보수 규칙 때문에 `풍경` 또는 `기타`로 갈 수 있다.
- 다음 개선 우선순위는
  - MobileCLIP prompt catalog 재생성 가능한 자산 정리
  - scenic / key visual / character / background 세분화 prompt 보강
  - 실기기 샘플셋 기준 회귀 검증 스크립트 추가

## 8. 현재 Codex 세션 정보

- 확인 가능
- `CODEX_THREAD_ID=019d0a7a-6af8-7d80-b329-ae8eaf13ffbb`
