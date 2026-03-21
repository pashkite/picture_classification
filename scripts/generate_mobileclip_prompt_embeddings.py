#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


PROMPT_SETS = [
    {"level": "primary", "label": "사람", "prompts": ["a photo of a real person", "a candid photo of people"]},
    {"level": "primary", "label": "셀카", "prompts": ["a selfie portrait photo", "a front camera selfie of a person"]},
    {"level": "primary", "label": "풍경", "prompts": ["a scenic landscape photo", "a background scenery image"]},
    {"level": "primary", "label": "음식", "prompts": ["a close photo of food", "a meal or dish on a table"]},
    {"level": "primary", "label": "반려동물", "prompts": ["a pet dog or cat photo", "a cute animal pet picture"]},
    {"level": "primary", "label": "문서", "prompts": ["a document page with text", "a memo note or letter document"]},
    {"level": "primary", "label": "영수증", "prompts": ["a printed receipt with prices", "a bill or invoice receipt"]},
    {"level": "primary", "label": "스크린샷", "prompts": ["a phone screenshot of an app", "a user interface screenshot"]},
    {"level": "primary", "label": "그림", "prompts": ["a hand drawn sketch", "a doodle or line drawing"]},
    {"level": "primary", "label": "일러스트", "prompts": ["an illustrated artwork", "a digital painting or illustration"]},
    {"level": "primary", "label": "애니 관련", "prompts": ["an anime style illustration", "a japanese animation artwork"]},
    {"level": "primary", "label": "게임 관련", "prompts": ["a video game image", "game artwork or in-game scene"]},
    {"level": "primary", "label": "밈", "prompts": ["a meme image with joke text", "an internet meme picture"]},
    {"level": "primary", "label": "기타", "prompts": ["a miscellaneous everyday image", "an uncategorized media image"]},
    {"level": "secondary", "label": "애니 이미지", "prompts": ["an anime still image", "anime style scene artwork"]},
    {"level": "secondary", "label": "게임 이미지", "prompts": ["a game promotional image", "an in-game visual scene"]},
    {"level": "secondary", "label": "일반 일러스트", "prompts": ["general illustration artwork", "a non-photo digital illustration"]},
    {"level": "secondary", "label": "배경 중심", "prompts": ["background scenery artwork without a dominant character", "a landscape illustration focused on scenery"]},
    {"level": "secondary", "label": "만화풍/웹툰풍", "prompts": ["a comic or webtoon style drawing", "a manga panel style illustration"]},
    {"level": "secondary", "label": "팬아트 가능성", "prompts": ["fan art of an existing character", "illustration of a known fictional character"]},
    {"level": "secondary", "label": "캐릭터 중심", "prompts": ["artwork focused on a single character", "anime or illustration portrait of a fictional character"]},
    {"level": "secondary", "label": "UI 중심", "prompts": ["a user interface heavy screenshot", "a screen capture focused on menus and hud"]},
    {"level": "secondary", "label": "전투 화면", "prompts": ["a video game combat scene", "a battle gameplay screen"]},
    {"level": "secondary", "label": "대사/자막 중심", "prompts": ["an image dominated by subtitles or dialog text", "speech bubbles or captions are prominent"]},
    {"level": "secondary", "label": "로고/타이틀 화면", "prompts": ["a title screen or splash logo", "a start screen with a large logo"]},
    {"level": "secondary", "label": "문서 중심", "prompts": ["a document focused image", "a page or note full of text"]},
    {"level": "secondary", "label": "인물 중심", "prompts": ["a real person portrait photo", "a human subject focused photograph"]},
]


def normalize(v: np.ndarray) -> np.ndarray:
    denom = np.linalg.norm(v)
    if denom <= 1e-12:
        return v
    return v / denom


def encode_prompts(session: ort.InferenceSession, tokenizer: Tokenizer, prompts: list[str]) -> np.ndarray:
    encoded = tokenizer.encode_batch(prompts)
    input_ids = np.array([item.ids for item in encoded], dtype=np.int64)
    text_embeds = session.run(["text_embeds"], {"input_ids": input_ids})[0].astype(np.float32)
    text_embeds = np.stack([normalize(embed) for embed in text_embeds], axis=0)
    return normalize(text_embeds.mean(axis=0))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--text-model", required=True)
    parser.add_argument("--tokenizer", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    tokenizer = Tokenizer.from_file(args.tokenizer)
    tokenizer.enable_truncation(max_length=77)
    tokenizer.enable_padding(length=77, pad_id=0, pad_token="")

    session = ort.InferenceSession(args.text_model, providers=["CPUExecutionProvider"])
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    entries = []
    for item in PROMPT_SETS:
        embedding = encode_prompts(session, tokenizer, item["prompts"])
        entries.append(
            {
                "level": item["level"],
                "label": item["label"],
                "prompts": item["prompts"],
                "embedding": [round(float(value), 8) for value in embedding.tolist()],
            }
        )

    payload = {
        "schemaVersion": 1,
        "modelId": "mobileclip2-s0",
        "embeddingSize": 512,
        "entries": entries,
    }
    output_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
