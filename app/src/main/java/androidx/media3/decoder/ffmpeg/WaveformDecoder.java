package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

/** Small bridge to the pinned extension's package-private decoder. The normal
 * decoder queue owns its native context and buffers; no reflected JNI calls. */
@UnstableApi
public final class WaveformDecoder {
    private WaveformDecoder() {}

    public static Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>
            create(Format format, int inputSize) throws FfmpegDecoderException {
        return new FfmpegAudioDecoder(format, 16, 16, inputSize, false);
    }
}
