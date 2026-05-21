from pydantic import BaseModel
from typing import Optional

# -- Demucs (voice separation) --

class SeparateRequest(BaseModel):
    audio_path: str        # absolute path to the input audio file
    output_dir: Optional[str] = None  # where to write separated stems, defaults to a temp dir

class SeparateResponse(BaseModel):
    success: bool
    vocal_path: Optional[str] = None   # path to the isolated vocal stem
    duration_seconds: Optional[float] = None
    error: Optional[str] = None

# -- Whisper (transcription) --

class TranscribeRequest(BaseModel):
    audio_path: str        # path to audio to transcribe (usually the vocal stem from Demucs)
    language: Optional[str] = None  # hint e.g. "en" — None means Whisper auto-detects

class TranscribeResponse(BaseModel):
    success: bool
    text: Optional[str] = None      # full transcribed lyric text
    language: Optional[str] = None  # language Whisper detected
    segments: Optional[list] = None # per-segment timestamps, useful for LRC generation
    error: Optional[str] = None

# -- Combined pipeline (runs both in one call) --

class PipelineRequest(BaseModel):
    audio_path: str
    language: Optional[str] = None
    output_dir: Optional[str] = None

class PipelineResponse(BaseModel):
    success: bool
    vocal_path: Optional[str] = None
    text: Optional[str] = None
    language: Optional[str] = None
    segments: Optional[list] = None
    duration_seconds: Optional[float] = None
    error: Optional[str] = None