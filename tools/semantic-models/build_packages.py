#!/usr/bin/env python3
"""Convert the approved TinyCLIP checkpoints into UGallery LiteRT packages."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

import litert_torch
import torch
from transformers import CLIPModel, CLIPTokenizer


MODELS = {
    "tinyclip-balanced": ("wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M", "a2a8c6eaa2549ad66eb7c31b85022bf58273a26c"),
    "tinyclip-quality": ("wkcn/TinyCLIP-ViT-39M-16-Text-19M-YFCC15M", "07a4b0bc751cb64fecd2b661c048c1dd98d69444"),
}
TOKENIZER = ("openai/clip-vit-base-patch32", "3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268")


class ImageEncoder(torch.nn.Module):
    def __init__(self, clip: CLIPModel):
        super().__init__()
        self.vision = clip.vision_model
        self.projection = clip.visual_projection

    def forward(self, pixels: torch.Tensor) -> torch.Tensor:
        pooled = self.vision(pixel_values=pixels.permute(0, 3, 1, 2), return_dict=False)[1]
        embedding = self.projection(pooled)
        return embedding / torch.linalg.vector_norm(embedding, dim=-1, keepdim=True)


class TextEncoder(torch.nn.Module):
    def __init__(self, clip: CLIPModel):
        super().__init__()
        self.text = clip.text_model
        self.projection = clip.text_projection

    def forward(self, tokens: torch.Tensor) -> torch.Tensor:
        pooled = self.text(input_ids=tokens, return_dict=False)[1]
        embedding = self.projection(pooled)
        return embedding / torch.linalg.vector_norm(embedding, dim=-1, keepdim=True)


def convert(module: torch.nn.Module, sample: torch.Tensor, destination: Path) -> None:
    module.eval()
    with torch.inference_mode():
        result = litert_torch.convert(module, (sample,), strict_export=False)
    result.export(str(destination))


def build(model_id: str, checkpoint: str, revision: str, output: Path, private_key: Path) -> dict[str, object]:
    work = output / model_id
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    clip = CLIPModel.from_pretrained(checkpoint, revision=revision).eval()
    require_dimensions = clip.config.projection_dim
    if require_dimensions != 512:
        raise ValueError(f"{checkpoint} projection is {require_dimensions}, expected 512")
    convert(ImageEncoder(clip), torch.zeros(1, 224, 224, 3), work / "image.tflite")
    convert(TextEncoder(clip), torch.zeros(1, 77, dtype=torch.int32), work / "text.tflite")
    # TinyCLIP checkpoints use the canonical OpenAI CLIP BPE vocabulary but do not
    # duplicate those tokenizer assets in every checkpoint repository.
    tokenizer = CLIPTokenizer.from_pretrained(TOKENIZER[0], revision=TOKENIZER[1])
    tokenizer.save_pretrained(work)
    for extra in work.glob("tokenizer*"):
        extra.unlink()
    (work / "special_tokens_map.json").unlink(missing_ok=True)
    (work / "NOTICE.txt").write_text(
        f"Checkpoint: {checkpoint}@{revision}\nLicense: MIT (TinyCLIP project/model card).\n"
        "Converted to LiteRT for on-device use by UGallery.\n",
        encoding="utf-8",
    )
    archive = output / f"{model_id}-1.0.0.ugmodel"
    archive.unlink(missing_ok=True)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as package:
        for name in ("image.tflite", "text.tflite", "vocab.json", "merges.txt", "NOTICE.txt"):
            package.write(work / name, name)
    signature = output / f"{archive.name}.sig"
    subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(private_key), "-out", str(signature), str(archive)],
        check=True,
    )
    return {
        "id": model_id,
        "checkpoint": checkpoint,
        "revision": revision,
        "file": archive.name,
        "bytes": archive.stat().st_size,
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "signatureBase64": __import__("base64").b64encode(signature.read_bytes()).decode("ascii"),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("build/semantic-models"))
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--model", choices=[*MODELS, "all"], default="all")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    selected = MODELS.items() if args.model == "all" else [(args.model, MODELS[args.model])]
    catalog = [
        build(model_id, checkpoint, revision, args.output, args.private_key)
        for model_id, (checkpoint, revision) in selected
    ]
    (args.output / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
