import os
import tempfile
import soundfile as sf
from pathlib import Path
from demucs.apply import apply_model
from demucs.pretrained import get_model
from demucs.audio import AudioFile, save_audio
import torch

from models import SeparateRequest, SeparateResponse

# Load the model once at startup — keeps it in memory so every
# subsequent request doesn't pay the cost of loading from disk again.
# htdemucs is the default 4-stem model (drums, bass, other, vocals).
# We only use the vocals stem, but the model separates all four.
_MODEL_NAME = "mdx_extra"
_model = None

def _get_model():
    # Lazy-load: only initialise on first use
    global _model
    if _model is None:
        print(f"[Demucs] Loading model '{_MODEL_NAME}'...")
        _model = get_model(_MODEL_NAME)
        _model.eval()  # put model in inference mode (disables dropout etc.)
        print("[Demucs] Model ready.")
    return _model

def separate(req: SeparateRequest) -> SeparateResponse:
    audio_path = Path(req.audio_path)

    # Basic validation before we do any heavy work
    if not audio_path.exists():
        return SeparateResponse(success=False, error=f"File not found: {audio_path}")
    if not audio_path.is_file():
        return SeparateResponse(success=False, error=f"Not a file: {audio_path}")

    # Decide where to write the output
    # If the caller didn't specify, use a temp directory
    out_dir = Path(req.output_dir) if req.output_dir else Path(tempfile.mkdtemp(prefix="lyrify_demucs_"))
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        model = _get_model()

        # Use GPU if available — Demucs is much faster on CUDA
        device = "cuda" if torch.cuda.is_available() else "cpu"
        model = model.to(device)

        # Load and decode the audio file into a tensor
        # AudioFile handles mp3/flac/m4a etc. — returns shape (channels, samples)
        wav = AudioFile(audio_path).read(
            streams=0,                    # stream 0 = the main audio track
            samplerate=model.samplerate,  # resample to whatever the model expects (usually 44100)
            channels=model.audio_channels # stereo (2) by default
        )

        # Demucs expects shape (batch, channels, samples) — we add the batch dim
        wav = wav.unsqueeze(0).to(device)

        # Run separation — returns tensor of shape (batch, stems, channels, samples)
        # Stems order for htdemucs: drums, bass, other, vocals
        with torch.no_grad():  # no_grad saves memory during inference
            sources = apply_model(model, wav, device=device)

        # Extract just the vocals stem (index 3 for htdemucs)
        stem_names = model.sources  # e.g. ["drums", "bass", "other", "vocals"]
        vocal_index = stem_names.index("vocals")
        vocals = sources[0, vocal_index]  # shape: (channels, samples)

        # Write the vocal stem to disk as a wav file
        vocal_path = out_dir / f"{audio_path.stem}_vocals.wav"
        # Convert to numpy and write with soundfile (no TorchCodec needed)
        vocals_np = vocals.cpu().numpy().T  # shape: (samples, channels)
        sf.write(str(vocal_path), vocals_np, model.samplerate)

        # Calculate duration from the sample count
        num_samples = vocals.shape[-1]
        duration = num_samples / model.samplerate

        return SeparateResponse(
            success=True,
            vocal_path=str(vocal_path),
            duration_seconds=round(duration, 3)
        )

    except Exception as e:
        return SeparateResponse(success=False, error=str(e))