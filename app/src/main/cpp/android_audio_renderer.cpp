// Phase 2: audio decoding lives on the Java side (com.tarmac.media.AudioPipeline,
// MediaCodec → AudioTrack). The native bridge in jni_bridge.cpp forwards the
// raw frames via a direct ByteBuffer + compression-type tag; this file is
// intentionally empty (kept for future native-side work like ALAC fallback or
// AudioStream low-latency callbacks).
