import whisper
from pathlib import Path

from models import TranscribeRequest, TranscribeResponse

# Load the Whisper model once at startup.
# "large-v3" gives the best accuracy for lyrics — slower but worth it
# since this only runs when API matching failed.
# Swap to "medium" or "small" if you're on a low-VRAM machine.
_MODEL_SIZE = "medium"
_model = None

def _get_model():
    # Lazy-load on first use — Whisper downloads the weights on first run
    # and caches them in ~/.cache/whisper
    global _model
    if _model is None:
        print(f"[Whisper] Loading model '{_MODEL_SIZE}'...")
        _model = whisper.load_model(_MODEL_SIZE)
        print("[Whisper] Model ready.")
    return _model

def transcribe(req: TranscribeRequest) -> TranscribeResponse:
    audio_path = Path(req.audio_path)

    if not audio_path.exists():
        return TranscribeResponse(success=False, error=f"File not found: {audio_path}")
    if not audio_path.is_file():
        return TranscribeResponse(success=False, error=f"Not a file: {audio_path}")

    try:
        model = _get_model()

        # Transcription options
        options = {
            # word_timestamps gives us per-word timing — useful for LRC generation
            "word_timestamps": True,
            # verbose=False suppresses Whisper's own progress output
            "verbose": False,
            "beam_size": 5,        # default is 5, increase to 10 for better accuracy at cost of speed
            "best_of": 5,          # number of candidates to consider
            "temperature": 0.0,    # 0 = greedy decoding, most deterministic/accurate
            "condition_on_previous_text": True,  # use previous segments as context
        }

        # Pass language hint if provided — speeds up transcription and
        # improves accuracy when we already know the language
        if req.language:
            options["language"] = req.language

        result = model.transcribe(str(audio_path), **options)

        # result["text"] is the full transcript as a single string
        # result["segments"] is a list of dicts, each with:
        #   {id, start, end, text, words: [{word, start, end, probability}]}
        # We pass segments through so Java can use them for LRC timestamp generation

        # Simplify segments to only the fields we actually use downstream
        simplified_segments = [
            {
                "start": seg["start"],   # seconds from audio start
                "end":   seg["end"],
                "text":  seg["text"].strip(),
            }
            for seg in result.get("segments", [])
        ]

        return TranscribeResponse(
            success=True,
            text=result["text"].strip(),
            language=result.get("language"),
            segments=simplified_segments
        )

    except Exception as e:
        return TranscribeResponse(success=False, error=str(e))