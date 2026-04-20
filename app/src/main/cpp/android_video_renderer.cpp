// Phase 2: video decoding lives on the Java side (com.tarmac.media.VideoPipeline,
// MediaCodec → SurfaceView). The native bridge in jni_bridge.cpp forwards the
// raw NALU bytes via a direct ByteBuffer; this file is intentionally empty
// (kept as a translation unit so the CMake target list stays stable for
// future native-side work like SEI/HDR-metadata parsing).
