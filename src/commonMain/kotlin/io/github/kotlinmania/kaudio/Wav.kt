// port-lint: source src/wav.rs
package io.github.kotlinmania.kaudio

/**
 * Audio sample convertible to a 16-bit signed PCM integer.
 */
public interface Sample {
    /** Convert this sample to a 16-bit signed integer. */
    public fun toI16(): Short
}

/**
 * 32-bit floating-point [Sample] wrapper.
 */
public data class FloatSample(
    public val value: Float,
) : Sample {
    override fun toI16(): Short = (value.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
}

/**
 * 64-bit floating-point [Sample] wrapper.
 */
public data class DoubleSample(
    public val value: Double,
) : Sample {
    override fun toI16(): Short = (value.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
}

/**
 * 16-bit signed integer [Sample] wrapper.
 */
public data class ShortSample(
    public val value: Short,
) : Sample {
    override fun toI16(): Short = value
}

/** Convert a 32-bit float in `[-1.0, 1.0]` to a 16-bit signed PCM sample. */
public fun Float.toI16Sample(): Short = (this.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()

/** Convert a 64-bit float in `[-1.0, 1.0]` to a 16-bit signed PCM sample. */
public fun Double.toI16Sample(): Short = (this.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()

/** Identity conversion for a 16-bit signed PCM sample. */
public fun Short.toI16Sample(): Short = this

/**
 * Byte sink for writing WAV output.
 */
public interface WavWrite {
    /** Write all bytes in [buf] to the underlying sink. */
    public fun writeAll(buf: ByteArray)
}

/**
 * In-memory [WavWrite] implementation backed by a growable byte buffer.
 */
public class ByteBufferWavWriter : WavWrite {
    private var buf: ByteArray = ByteArray(256)
    private var size: Int = 0

    override fun writeAll(buf: ByteArray) {
        ensureCapacity(size + buf.size)
        buf.copyInto(this.buf, destinationOffset = size)
        size += buf.size
    }

    /** Return a copy of all written bytes. */
    public fun toByteArray(): ByteArray = buf.copyOfRange(0, size)

    /** Return the number of bytes written so far. */
    public fun len(): Int = size

    /** Clear all written bytes. */
    public fun clear() {
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buf.size) return
        var newCap = if (buf.isEmpty()) 256 else buf.size
        while (newCap < required) {
            newCap *= 2
        }
        buf = buf.copyOf(newCap)
    }
}

private fun u16ToLeBytes(v: UShort): ByteArray =
    byteArrayOf(
        (v.toInt() and 0xFF).toByte(),
        ((v.toInt() ushr 8) and 0xFF).toByte(),
    )

private fun i16ToLeBytes(v: Short): ByteArray = u16ToLeBytes(v.toUShort())

private fun u32ToLeBytes(v: UInt): ByteArray =
    byteArrayOf(
        (v and 0xFFu).toByte(),
        ((v shr 8) and 0xFFu).toByte(),
        ((v shr 16) and 0xFFu).toByte(),
        ((v shr 24) and 0xFFu).toByte(),
    )

private fun writeWavHeaderAndDataPrefix(
    w: WavWrite,
    sampleCount: Int,
    sampleRate: UInt,
    nChannels: UInt,
) {
    var len = 12u // header
    len += 24u // fmt
    len += sampleCount.toUInt() * 2u + 8u // data
    val bytesPerSecond = sampleRate * 2u * nChannels
    w.writeAll("RIFF".encodeToByteArray())
    w.writeAll(u32ToLeBytes(len - 8u))
    w.writeAll("WAVE".encodeToByteArray())

    // Format block
    w.writeAll("fmt ".encodeToByteArray())
    w.writeAll(u32ToLeBytes(16u))
    w.writeAll(u16ToLeBytes(1u))
    w.writeAll(u16ToLeBytes(nChannels.toUShort()))
    w.writeAll(u32ToLeBytes(sampleRate))
    w.writeAll(u32ToLeBytes(bytesPerSecond))
    w.writeAll(u16ToLeBytes(2u))
    w.writeAll(u16ToLeBytes(16u))

    // Data block
    w.writeAll("data".encodeToByteArray())
    w.writeAll(u32ToLeBytes(sampleCount.toUInt() * 2u))
}

/**
 * Write PCM [samples] as a 16-bit integer RIFF WAVE stream into [w].
 */
public fun writePcmAsWav(
    w: WavWrite,
    samples: List<Sample>,
    sampleRate: UInt,
    nChannels: UInt,
) {
    writeWavHeaderAndDataPrefix(w, samples.size, sampleRate, nChannels)
    for (sample in samples) {
        w.writeAll(i16ToLeBytes(sample.toI16()))
    }
}

/**
 * Write 32-bit float PCM [samples] as a 16-bit integer RIFF WAVE stream into [w].
 */
public fun writePcmF32AsWav(
    w: WavWrite,
    samples: FloatArray,
    sampleRate: UInt,
    nChannels: UInt,
) {
    writeWavHeaderAndDataPrefix(w, samples.size, sampleRate, nChannels)
    for (sample in samples) {
        w.writeAll(i16ToLeBytes(sample.toI16Sample()))
    }
}

/**
 * Write 64-bit float PCM [samples] as a 16-bit integer RIFF WAVE stream into [w].
 */
public fun writePcmF64AsWav(
    w: WavWrite,
    samples: DoubleArray,
    sampleRate: UInt,
    nChannels: UInt,
) {
    writeWavHeaderAndDataPrefix(w, samples.size, sampleRate, nChannels)
    for (sample in samples) {
        w.writeAll(i16ToLeBytes(sample.toI16Sample()))
    }
}

/**
 * Write 16-bit signed integer PCM [samples] as a 16-bit integer RIFF WAVE stream into [w].
 */
public fun writePcmI16AsWav(
    w: WavWrite,
    samples: ShortArray,
    sampleRate: UInt,
    nChannels: UInt,
) {
    writeWavHeaderAndDataPrefix(w, samples.size, sampleRate, nChannels)
    for (sample in samples) {
        w.writeAll(i16ToLeBytes(sample))
    }
}
