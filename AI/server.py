import uvicorn
from fastapi import FastAPI
from contextlib import asynccontextmanager

from models import (
    SeparateRequest, SeparateResponse,
    TranscribeRequest, TranscribeResponse,
    PipelineRequest, PipelineResponse,
)
from demucs_service import separate
from whisper_service import transcribe

# -- Lifespan: warm up both models at startup --
# Without this, the first real request would trigger model loading
# and time out. We load them eagerly so they're ready immediately.
@asynccontextmanager
async def lifespan(app: FastAPI):
    print("[Server] Warming up models...")
    from demucs_service import _get_model as demucs_warmup
    from whisper_service import _get_model as whisper_warmup
    demucs_warmup()
    whisper_warmup()
    print("[Server] All models ready. Listening on http://localhost:8000")
    yield  # server runs here
    print("[Server] Shutting down.")

app = FastAPI(
    title="Lyrify AI Server",
    description="Local REST API for Demucs voice separation and Whisper transcription.",
    version="1.0.0",
    lifespan=lifespan,
)

# -- Health check --
# Java pings this before sending real requests to confirm the server is up
@app.get("/health")
def health():
    return {"status": "ok"}

# -- Endpoint 1: voice separation --
@app.post("/separate", response_model=SeparateResponse)
def separate_endpoint(req: SeparateRequest) -> SeparateResponse:
    # Takes an audio file path, runs Demucs, returns path to the vocal stem
    return separate(req)

# -- Endpoint 2: transcription --
@app.post("/transcribe", response_model=TranscribeResponse)
def transcribe_endpoint(req: TranscribeRequest) -> TranscribeResponse:
    # Takes an audio file path (usually the vocal stem), runs Whisper,
    # returns the transcribed lyric text + per-segment timestamps
    return transcribe(req)

# -- Endpoint 3: full pipeline (separate + transcribe in one call) --
# Convenience endpoint so Java can do the whole AI fallback in a single request
# instead of two sequential ones
@app.post("/pipeline", response_model=PipelineResponse)
def pipeline_endpoint(req: PipelineRequest) -> PipelineResponse:
    # Step 1: separate vocals
    sep_result = separate(SeparateRequest(
        audio_path=req.audio_path,
        output_dir=req.output_dir
    ))

    if not sep_result.success:
        return PipelineResponse(success=False, error=f"Separation failed: {sep_result.error}")

    # Step 2: transcribe the vocal stem
    trans_result = transcribe(TranscribeRequest(
        audio_path=sep_result.vocal_path,
        language=req.language
    ))

    if not trans_result.success:
        return PipelineResponse(
            success=False,
            vocal_path=sep_result.vocal_path,
            duration_seconds=sep_result.duration_seconds,
            error=f"Transcription failed: {trans_result.error}"
        )

    return PipelineResponse(
        success=True,
        vocal_path=sep_result.vocal_path,
        text=trans_result.text,
        language=trans_result.language,
        segments=trans_result.segments,
        duration_seconds=sep_result.duration_seconds
    )

if __name__ == "__main__":
    uvicorn.run(
        "server:app",
        host="127.0.0.1",  # localhost only — not exposed to the network
        port=8000,
        reload=False,       # reload=True is handy during dev but slows startup
    )