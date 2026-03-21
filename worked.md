# worked.md

## 1. 이번 작업의 목표

- 기존 `EfficientNet-Lite4 + ImageEmbedder + ML Kit` 구조에 추가 온디바이스 의미 모델을 붙인다.
- `MobileCLIP / SigLIP` 계열을 검토한 뒤, 현재 저장소와 Android 통합성이 가장 현실적인 경로를 실제 코드로 넣는다.
- 서버 호출 없이 기기 내부에서 동작하는 의미 기반 분류 보강을 구현한다.
- 빌드, 테스트, 문서화, git 커밋과 푸시까지 마무리한다.

## 2. 실제로 구현한 기능

- `onnxruntime-android:1.24.3` 추가
- `MobileCLIP2-S0` vision encoder 자산 추가
  - `app/src/main/assets/mobileclip2_s0_vision.onnx`
- 프롬프트 임베딩 자산 추가
  - `app/src/main/assets/mobileclip_prompt_embeddings.json`
  - 앱에는 text encoder 를 싣지 않고, 사전 계산된 프롬프트 임베딩만 싣는다.
- 개발용 생성 스크립트 추가
  - `scripts/generate_mobileclip_prompt_embeddings.py`
  - MobileCLIP text encoder 를 개발 시점에 한 번만 사용해 프롬프트 임베딩을 만든다.
- `MobileClipVisionModelProvider` 추가
  - ONNX Runtime 세션 로딩
  - prompt catalog 로딩
  - 모델 상태 노출
- `MobileClipSemanticInferenceEngine` 추가
  - 대표 프레임에서 image embedding 생성
  - 사전 계산 프롬프트 임베딩과 유사도 비교
  - 1차 / 2차 분류 점수 산출
  - 프레임별 MobileCLIP 요약 생성
- `ClassificationPipeline` 확장
  - Lite4 / MobileCLIP / ImageEmbedder / ML Kit / fallback 동시 융합
  - debug info 에 `mobileclip-vision-encoder` 결과 표시
- `OnDeviceAiClassificationEngine` 엔진 이름 / 버전 갱신
- UI 설명 문구와 README 갱신
- MobileCLIP softmax 랭킹 단위 테스트 추가

## 3. 생성하거나 수정한 파일 목록

- `app/build.gradle.kts`
- `app/src/main/assets/mobileclip2_s0_vision.onnx`
- `app/src/main/assets/mobileclip_prompt_embeddings.json`
- `app/src/main/java/com/codex/ppa/domain/MobileClipSemanticEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/OnDeviceAiClassificationEngine.kt`
- `app/src/main/java/com/codex/ppa/domain/VisionClassificationPipeline.kt`
- `app/src/main/java/com/codex/ppa/ui/MainApp.kt`
- `app/src/test/java/com/codex/ppa/domain/ClassificationFusionHeuristicsTest.kt`
- `app/src/test/java/com/codex/ppa/domain/MobileClipSemanticEngineTest.kt`
- `README.md`
- `worked.md`
- `scripts/generate_mobileclip_prompt_embeddings.py`

## 4. 주요 설계 결정 사항

- 앱에는 `MobileCLIP2-S0 vision encoder`만 넣고, text encoder 는 넣지 않는다.
  - text encoder 는 약 243MB라 앱 탑재 비용이 너무 크다.
  - 대신 개발 시점에 같은 MobileCLIP text encoder 로 라벨 프롬프트 임베딩을 미리 계산해 JSON 자산으로 고정했다.
- 새 의미 모델은 기존 구조를 깨지 않도록 `InferenceEngine` 계층으로 추가했다.
- MobileCLIP 점수는 absolute threshold 대신 softmax 랭킹으로 1차 / 2차 후보를 만든다.
- Lite4, MobileCLIP, embedder, ML Kit 중 하나가 실패해도 전체 앱은 죽지 않고 나머지 경로로 계속 동작한다.

## 5. 빌드/검증에 사용한 명령어

```bash
python3 -m venv /tmp/mobileclip-env
source /tmp/mobileclip-env/bin/activate
pip install numpy onnxruntime tokenizers pillow
curl -L --fail -o /tmp/mobileclip2-s0/text_model.onnx https://huggingface.co/plhery/mobileclip2-onnx/resolve/main/onnx/s0/text_model.onnx
curl -L --fail -o /tmp/mobileclip2-s0/tokenizer.json https://huggingface.co/plhery/mobileclip2-onnx/resolve/main/tokenizer.json
curl -L --fail -o /tmp/mobileclip2-s0/vision_model.onnx https://huggingface.co/plhery/mobileclip2-onnx/resolve/main/onnx/s0/vision_model.onnx
source /tmp/mobileclip-env/bin/activate && python scripts/generate_mobileclip_prompt_embeddings.py \
  --text-model /tmp/mobileclip2-s0/text_model.onnx \
  --tokenizer /tmp/mobileclip2-s0/tokenizer.json \
  --output app/src/main/assets/mobileclip_prompt_embeddings.json
./gradlew testDebugUnitTest assembleDebug
find app/build/test-results/testDebugUnitTest -name '*.xml' -print0 | xargs -0 rg -n "tests=|failures=|errors="
git log -1 --oneline
```

## 6. 빌드/검증 결과

- `./gradlew testDebugUnitTest assembleDebug`: 성공
- 최종 결과: `BUILD SUCCESSFUL`
- 단위 테스트 통과
  - `ClassificationManifestFileStoreTest`: 2건 통과
  - `ClassificationPathPolicyTest`: 3건 통과
  - `BasicSuggestionClassificationEngineTest`: 5건 통과
  - `ClassificationFusionHeuristicsTest`: 7건 통과
  - `MobileClipSemanticEngineTest`: 2건 통과
  - 총 19건 통과
- APK 생성 확인
  - `app/build/outputs/apk/debug/app-debug.apk`
- git 반영
  - 이 worked.md 갱신 내용은 이번 MobileCLIP 통합 커밋에 포함할 예정

## 7. 남은 한계점 및 TODO

- 앱 안에는 MobileCLIP vision encoder 와 사전 계산 프롬프트 임베딩만 있다. 즉 사용자가 임의 라벨을 입력하면 그 자리에서 text embedding 을 새로 만들 수는 없다.
- 작품명 / 시리즈명 후보는 여전히 OCR / 경로 / 로컬 키워드 사전 의존도가 높다.
- ONNX Runtime native lib 가 들어가면서 APK 크기가 더 커졌다.
- 실기기 성능과 메모리 사용량은 수동 확인이 더 필요하다.
- 다음 개선 우선순위는
  - prompt catalog 확장
  - 시리즈 후보용 CLIP prompt 분기 추가
  - 저사양 기기용 MobileCLIP 비활성화 정책

## 8. 현재 Codex 세션 정보

- 확인 가능
- `CODEX_THREAD_ID=019d0a7a-6af8-7d80-b329-ae8eaf13ffbb`
