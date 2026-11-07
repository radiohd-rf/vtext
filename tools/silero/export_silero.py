"""
Конвертация Silero v5 (ru) в формат PyTorch Lite для приложения vtext.

Что делает скрипт:
  1. скачивает `v5_6_ru.pt` (~147 МБ) с models.silero.ai, если файла нет;
  2. достаёт из torch.package четыре нейросети (длительности, питч, Tacotron, HiFiGAN),
     модель ударений (accentor) и метаданные символов;
  3. сохраняет их как `.ptl` для Lite Interpreter и собирает `silero-5-ru.zip`
     (его импортирует приложение: Настройки → Silero v5 → Импорт).

Требования: Python 3.10–3.12, `pip install torch` (CPU-сборки достаточно).
Запуск:  python tools/silero/export_silero.py [--out DIR]

Лицензия модели Silero v5 — CC BY-NC 4.0 (только некоммерческое использование).
"""

import argparse
import json
import os
import urllib.request
import zipfile

import torch
import torch.package

MODEL_URL = "https://models.silero.ai/models/tts/ru/v5_6_ru.pt"


class Dur(torch.nn.Module):
    def __init__(self, module):
        super().__init__()
        self.m = module

    def forward(self, text, speaker_ids, orig_mask, type_ids, focus_mask):
        return self.m(text, speaker_ids, orig_mask, 1.0, None, type_ids, focus_mask, None)


class Pitch(torch.nn.Module):
    def __init__(self, module):
        super().__init__()
        self.m = module

    def forward(self, text, speaker_ids, orig_mask, type_ids, focus_mask):
        return self.m(text, speaker_ids, orig_mask, type_ids, focus_mask, None)


class Tacotron(torch.nn.Module):
    def __init__(self, module):
        super().__init__()
        self.m = module

    def forward(self, text, speaker_ids, orig_mask, dur_hat, pitch_hat):
        return self.m(text, speaker_ids, orig_mask, dur_hat, pitch_hat, None)


class Vocoder(torch.nn.Module):
    def __init__(self, module, sample_rate: int):
        super().__init__()
        self.m = module
        self.sample_rate = sample_rate

    def forward(self, mel):
        return self.m(mel, self.sample_rate, 0.0, True)


def download(path: str) -> None:
    if os.path.isfile(path) and os.path.getsize(path) > 100_000_000:
        return
    print(f"Скачиваем {MODEL_URL}")
    urllib.request.urlretrieve(MODEL_URL, path)


def save_lite(module, path: str) -> None:
    torch.jit.script(module).eval()._save_for_lite_interpreter(path)
    print(f"  {os.path.basename(path)}: {os.path.getsize(path) // 1048576} МБ")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="build/silero", help="куда сложить результат")
    parser.add_argument("--weights", default=None, help="путь к уже скачанному v5_6_ru.pt")
    args = parser.parse_args()

    torch.set_grad_enabled(False)
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    weights = args.weights or os.path.join(out, "v5_6_ru.pt")
    download(weights)

    importer = torch.package.PackageImporter(weights)
    model = importer.load_pickle("tts_models", "model")
    package = model.packages[0]
    tts = package.models[0]

    print("Сохраняем модели для Lite Interpreter")
    save_lite(Dur(tts.dur_predictor), os.path.join(out, "dur.ptl"))
    save_lite(Pitch(tts.pitch_predictor), os.path.join(out, "pitch.ptl"))
    save_lite(Tacotron(tts.tacotron), os.path.join(out, "tacotron.ptl"))
    save_lite(Vocoder(tts.vocoder, 48000), os.path.join(out, "vocoder48.ptl"))
    # accentor уже TorchScript: сохраняем как есть.
    package.accentor.accentor.model._save_for_lite_interpreter(os.path.join(out, "accentor.ptl"))
    print(f"  accentor.ptl: {os.path.getsize(os.path.join(out, 'accentor.ptl')) // 1048576} МБ")

    meta = {
        "symbols": package.symbols,
        "symbol_to_id": package.symbol_to_id,
        "sos": package.sos_token,
        "eos": package.eos_token,
        "speakers": package.speaker_to_ids,
        "punkt_symbols": [int(x) for x in tts.punkt_symbols],
        "type2id": {"st": 0, "wh_q": 1, "general_q": 2, "alternative_q": 3, "tag_q": 4, "exclam": 5},
    }
    with open(os.path.join(out, "meta.json"), "w", encoding="utf-8") as handle:
        json.dump(meta, handle, ensure_ascii=False, indent=1)

    archive = os.path.join(out, "silero-5-ru.zip")
    files = ["accentor.ptl", "dur.ptl", "pitch.ptl", "tacotron.ptl", "vocoder48.ptl", "meta.json"]
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED) as bundle:
        for name in files:
            bundle.write(os.path.join(out, name), name)
    print(f"Готово: {archive} ({os.path.getsize(archive) // 1048576} МБ)")
    print("Импортируйте архив в приложении: Настройки → Silero v5 → Импорт.")


if __name__ == "__main__":
    main()
