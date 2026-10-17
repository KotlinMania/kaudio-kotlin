package io.github.kotlinmania.kaudio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Decoded mono 32-bit floating-point PCM buffer paired with its sample rate in Hz.
 */
public class PcmDecodeOutput(
    public val pcmData: FloatArray,
    public val sampleRate: UInt,
) {
    override fun equals(other: Any?): Boolean =
        other is PcmDecodeOutput &&
            sampleRate == other.sampleRate &&
            pcmData.contentEquals(other.pcmData)

    override fun hashCode(): Int = 31 * pcmData.contentHashCode() + sampleRate.hashCode()
}

/**
 * Callback interface for loading raw audio file bytes from a path on any platform.
 */
public fun interface AudioFileLoader {
    /** Load all bytes from the file at [path]. */
    public fun readBytes(path: String): ByteArray
}

/**
 * Decode mono 32-bit floating-point PCM audio and its sample rate from the file at [path]
 * using the supplied [loader].
 */
public fun pcmDecodeFromPath(
    path: String,
    loader: AudioFileLoader,
): PcmDecodeOutput {
    val bytes =
        try {
            loader.readBytes(path)
        } catch (err: Throwable) {
            throw KaudioError.Io(err.message ?: err.toString()).withPath(path)
        }
    return pcmDecode(bytes)
}

/**
 * Decode mono 32-bit floating-point PCM audio (channel 0) and its sample rate from
 * encoded container bytes (RIFF WAVE or Ogg Opus).
 */
public fun pcmDecode(bytes: ByteArray): PcmDecodeOutput {
    if (bytes.size >= 4 &&
        bytes[0] == 'O'.code.toByte() &&
        bytes[1] == 'g'.code.toByte() &&
        bytes[2] == 'g'.code.toByte() &&
        bytes[3] == 'S'.code.toByte()
    ) {
        return decodeOggOpusBytes(bytes)
    }
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'A'.code.toByte() &&
        bytes[10] == 'V'.code.toByte() &&
        bytes[11] == 'E'.code.toByte()
    ) {
        return decodeWavBytes(bytes)
    }
    throw KaudioError.Symphonia("unsupported audio container format")
}

private fun decodeOggOpusBytes(bytes: ByteArray): PcmDecodeOutput {
    val decoder = Decoder.new(48_000, 1)
    val pcm = decoder.decode(bytes) ?: FloatArray(0)
    return PcmDecodeOutput(pcmData = pcm, sampleRate = 48_000u)
}

private fun readU16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readU32Le(bytes: ByteArray, offset: Int): UInt =
    (bytes[offset].toUInt() and 0xFFu) or
        ((bytes[offset + 1].toUInt() and 0xFFu) shl 8) or
        ((bytes[offset + 2].toUInt() and 0xFFu) shl 16) or
        ((bytes[offset + 3].toUInt() and 0xFFu) shl 24)

private fun readI24Le(bytes: ByteArray, offset: Int): Int {
    val b0 = bytes[offset].toInt() and 0xFF
    val b1 = bytes[offset + 1].toInt() and 0xFF
    val b2 = bytes[offset + 2].toInt()
    return b0 or (b1 shl 8) or (b2 shl 16)
}

private fun readI32Le(bytes: ByteArray, offset: Int): Int = readU32Le(bytes, offset).toInt()

private fun readU64Le(bytes: ByteArray, offset: Int): ULong =
    readU32Le(bytes, offset).toULong() or (readU32Le(bytes, offset + 4).toULong() shl 32)

private fun decodeWavBytes(bytes: ByteArray): PcmDecodeOutput {
    var pos = 12
    var audioFormat = 0
    var channels = 0
    var sampleRate = 0u
    var bitsPerSample = 0
    var dataOffset = -1
    var dataLen = 0

    while (pos + 8 <= bytes.size) {
        val chunkId = bytes.decodeToString(pos, pos + 4)
        val chunkSize = readU32Le(bytes, pos + 4).toInt()
        val bodyStart = pos + 8
        if (chunkSize < 0 || bodyStart > bytes.size) {
            throw KaudioError.Symphonia("malformed WAV chunk header")
        }
        val clampedEnd = min(bodyStart + chunkSize, bytes.size)
        if (chunkId == "fmt ") {
            if (clampedEnd - bodyStart < 16) {
                throw KaudioError.Symphonia("WAV fmt chunk too short")
            }
            audioFormat = readU16Le(bytes, bodyStart)
            channels = readU16Le(bytes, bodyStart + 2)
            sampleRate = readU32Le(bytes, bodyStart + 4)
            bitsPerSample = readU16Le(bytes, bodyStart + 14)
            if (audioFormat == 0xFFFE && clampedEnd - bodyStart >= 40) {
                audioFormat = readU16Le(bytes, bodyStart + 24)
            }
        } else if (chunkId == "data") {
            dataOffset = bodyStart
            dataLen = clampedEnd - bodyStart
            break
        }
        val paddedSize = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
        pos = bodyStart + paddedSize
    }

    if (channels <= 0 || dataOffset < 0) {
        throw KaudioError.Symphonia("no supported audio tracks")
    }
    val bytesPerSample = bitsPerSample / 8
    if (bytesPerSample <= 0) {
        throw KaudioError.Symphonia("invalid WAV bits per sample: $bitsPerSample")
    }
    val frameStride = bytesPerSample * channels
    val frameCount = dataLen / frameStride
    val pcmData = FloatArray(frameCount)

    for (frame in 0 until frameCount) {
        val samplePos = dataOffset + frame * frameStride
        pcmData[frame] =
            when (audioFormat) {
                1 -> {
                    when (bitsPerSample) {
                        8 -> ((bytes[samplePos].toInt() and 0xFF) - 128).toFloat() / 128.0f
                        16 -> readU16Le(bytes, samplePos).toShort().toFloat() / 32768.0f
                        24 -> readI24Le(bytes, samplePos).toFloat() / 8388608.0f
                        32 -> readI32Le(bytes, samplePos).toFloat() / 2147483648.0f
                        else -> throw KaudioError.Symphonia("unsupported PCM bit depth: $bitsPerSample")
                    }
                }

                3 -> {
                    when (bitsPerSample) {
                        32 -> Float.fromBits(readI32Le(bytes, samplePos))
                        64 -> Double.fromBits(readU64Le(bytes, samplePos).toLong()).toFloat()
                        else -> throw KaudioError.Symphonia("unsupported float bit depth: $bitsPerSample")
                    }
                }

                else -> {
                    throw KaudioError.Symphonia("unsupported WAV format tag: $audioFormat")
                }
            }
    }

    return PcmDecodeOutput(pcmData = pcmData, sampleRate = sampleRate)
}

/**
 * Resample mono 32-bit floating-point audio [pcmIn] from [srIn] Hz to [srOut] Hz.
 */
public fun resample(
    pcmIn: FloatArray,
    srIn: Int,
    srOut: Int,
): FloatArray {
    if (srIn <= 0 || srOut <= 0) {
        throw KaudioError.RubatoC("sample rates must be positive: srIn=$srIn srOut=$srOut")
    }
    if (pcmIn.isEmpty()) {
        return FloatArray(0)
    }
    if (srIn == srOut) {
        return pcmIn.copyOf()
    }

    val ratio = srOut.toDouble() / srIn.toDouble()
    val outLen = max(1, (pcmIn.size.toDouble() * ratio).toInt())
    val pcmOut = FloatArray(outLen)
    val cutoff = min(1.0, ratio)
    val halfWindow = 16
    val step = 1.0 / ratio

    for (outIdx in 0 until outLen) {
        val center = outIdx.toDouble() * step
        val baseIdx = floor(center).toInt()
        var acc = 0.0
        var weightSum = 0.0
        val kStart = max(0, baseIdx - halfWindow + 1)
        val kEnd = min(pcmIn.size - 1, baseIdx + halfWindow)
        for (k in kStart..kEnd) {
            val delta = center - k.toDouble()
            val x = delta * cutoff
            val sinc =
                if (kotlin.math.abs(x) < 1e-9) {
                    1.0
                } else {
                    val pix = PI * x
                    sin(pix) / pix
                }
            val normPos = delta / halfWindow.toDouble()
            val window =
                if (kotlin.math.abs(normPos) >= 1.0) {
                    0.0
                } else {
                    0.5 * (1.0 + cos(PI * normPos))
                }
            val w = sinc * window * cutoff
            acc += pcmIn[k].toDouble() * w
            weightSum += w
        }
        pcmOut[outIdx] =
            if (kotlin.math.abs(weightSum) > 1e-9) {
                (acc / weightSum).toFloat()
            } else {
                pcmIn[baseIdx.coerceIn(0, pcmIn.size - 1)]
            }
    }

    return pcmOut
}
