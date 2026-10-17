// port-lint: source src/ogg_opus.rs
package io.github.kotlinmania.kaudio

internal const val OPUS_HEAD_BYTE_SIZE: Int = 19
public const val OPUS_ENCODER_FRAME_SIZE: Int = 960

private val OPUS_HEAD_SIGNATURE: ByteArray = "OpusHead".encodeToByteArray()
private val OPUS_TAGS_SIGNATURE: ByteArray = "OpusTags".encodeToByteArray()

private fun readLeU16(
    buf: ByteArray,
    offset: Int,
): UShort =
    ((buf[offset].toUByte().toInt()) or (buf[offset + 1].toUByte().toInt() shl 8)).toUShort()

private fun readLeI16(
    buf: ByteArray,
    offset: Int,
): Short = readLeU16(buf, offset).toShort()

private fun readLeU32Opus(
    buf: ByteArray,
    offset: Int,
): UInt =
    (buf[offset].toUByte().toUInt()) or
        (buf[offset + 1].toUByte().toUInt() shl 8) or
        (buf[offset + 2].toUByte().toUInt() shl 16) or
        (buf[offset + 3].toUByte().toUInt() shl 24)

private fun writeLeU16(
    out: MutableList<Byte>,
    v: UShort,
) {
    out.add((v.toInt() and 0xFF).toByte())
    out.add(((v.toInt() ushr 8) and 0xFF).toByte())
}

private fun writeLeI16(
    out: MutableList<Byte>,
    v: Short,
) {
    writeLeU16(out, v.toUShort())
}

private fun writeLeU32(
    out: MutableList<Byte>,
    v: UInt,
) {
    out.add((v and 0xFFu).toByte())
    out.add(((v shr 8) and 0xFFu).toByte())
    out.add(((v shr 16) and 0xFFu).toByte())
    out.add(((v shr 24) and 0xFFu).toByte())
}

private fun writeLeU64(
    out: MutableList<Byte>,
    v: ULong,
) {
    for (i in 0 until 8) {
        out.add(((v shr (i * 8)) and 0xFFuL).toByte())
    }
}

private val OGG_CRC_LOOKUP: UIntArray =
    UIntArray(256) { idx ->
        var r = idx.toUInt() shl 24
        repeat(8) {
            r =
                if ((r and 0x80000000u) != 0u) {
                    (r shl 1) xor 0x04c11db7u
                } else {
                    r shl 1
                }
        }
        r
    }

private fun computeOggPageCrc(page: ByteArray): UInt {
    var crc = 0u
    for (b in page) {
        val idx = ((crc shr 24) xor b.toUByte().toUInt()) and 0xFFu
        crc = (crc shl 8) xor OGG_CRC_LOOKUP[idx.toInt()]
    }
    return crc
}

internal fun buildOggPage(
    packet: ByteArray,
    serial: UInt,
    sequenceNum: UInt,
    granulePosition: ULong,
    firstPage: Boolean,
): ByteArray {
    val segments = ArrayList<Int>()
    var remaining = packet.size
    while (remaining >= 255) {
        segments.add(255)
        remaining -= 255
    }
    segments.add(remaining)

    val out = ArrayList<Byte>(27 + segments.size + packet.size)
    out.add('O'.code.toByte())
    out.add('g'.code.toByte())
    out.add('g'.code.toByte())
    out.add('S'.code.toByte())
    out.add(0) // version
    out.add(if (firstPage) 0x02 else 0x00) // headerType
    writeLeU64(out, granulePosition)
    writeLeU32(out, serial)
    writeLeU32(out, sequenceNum)
    writeLeU32(out, 0u) // checksum placeholder at offset 22..25
    out.add(segments.size.toByte())
    for (segLen in segments) {
        out.add(segLen.toByte())
    }
    for (b in packet) {
        out.add(b)
    }
    val bytes = out.toByteArray()
    val crc = computeOggPageCrc(bytes)
    bytes[22] = (crc and 0xFFu).toByte()
    bytes[23] = ((crc shr 8) and 0xFFu).toByte()
    bytes[24] = ((crc shr 16) and 0xFFu).toByte()
    bytes[25] = ((crc shr 24) and 0xFFu).toByte()
    return bytes
}

/**
 * 19-byte packed Ogg Opus identification header (`OpusHead`).
 */
public class OpusHead(
    public val magicSignature: ByteArray,
    public val version: UByte,
    public val channelCount: UByte,
    public val preSkip: UShort,
    public val sampleRate: UInt,
    public val outputGain: Short,
    public val mappingFamily: UByte,
) {
    public companion object {
        /** Parse an [OpusHead] from a 19-byte slice. */
        public fun fromSlice(data: ByteArray): OpusHead {
            if (data.size != OPUS_HEAD_BYTE_SIZE) {
                throw KaudioError.OggUnexpectedLenForOpusHead(data.size)
            }
            val sig = data.copyOfRange(0, 8)
            if (!sig.contentEquals(OPUS_HEAD_SIGNATURE)) {
                throw KaudioError.OggUnexpectedSignature(sig)
            }
            return OpusHead(
                magicSignature = sig,
                version = data[8].toUByte(),
                channelCount = data[9].toUByte(),
                preSkip = readLeU16(data, 10),
                sampleRate = readLeU32Opus(data, 12),
                outputGain = readLeI16(data, 16),
                mappingFamily = data[18].toUByte(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpusHead) return false
        return magicSignature.contentEquals(other.magicSignature) &&
            version == other.version &&
            channelCount == other.channelCount &&
            preSkip == other.preSkip &&
            sampleRate == other.sampleRate &&
            outputGain == other.outputGain &&
            mappingFamily == other.mappingFamily
    }

    override fun hashCode(): Int {
        var result = magicSignature.contentHashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + channelCount.hashCode()
        result = 31 * result + preSkip.hashCode()
        result = 31 * result + sampleRate.hashCode()
        result = 31 * result + outputGain.hashCode()
        result = 31 * result + mappingFamily.hashCode()
        return result
    }

    override fun toString(): String =
        "OpusHead(version=$version, channelCount=$channelCount, preSkip=$preSkip, " +
            "sampleRate=$sampleRate, outputGain=$outputGain, mappingFamily=$mappingFamily)"
}

internal fun writeOpusHeader(): ByteArray {
    val out = ArrayList<Byte>(OPUS_HEAD_BYTE_SIZE)
    for (b in OPUS_HEAD_SIGNATURE) out.add(b)
    out.add(1) // version
    out.add(1) // channel count
    writeLeU16(out, 3840u) // pre-skip
    writeLeU32(out, 48000u) // sample-rate in Hz
    writeLeI16(out, 0) // output gain Q7.8 in dB
    out.add(0) // channel map
    return out.toByteArray()
}

internal fun writeOpusTags(): ByteArray {
    val vendor = "KyutaiMoshi".encodeToByteArray()
    val out = ArrayList<Byte>(8 + 4 + vendor.size + 4)
    for (b in OPUS_TAGS_SIGNATURE) out.add(b)
    writeLeU32(out, vendor.size.toUInt())
    for (b in vendor) out.add(b)
    writeLeU32(out, 0u) // number of tags
    return out.toByteArray()
}

private fun encodePcmFrameToOpusPacket(
    pcm: FloatArray,
    outputBuf: ByteArray,
): Int {
    val byteLen = pcm.size * 2
    if (outputBuf.size < byteLen) {
        throw KaudioError.Opus("opus output buffer too small: ${outputBuf.size} < $byteLen")
    }
    for (i in pcm.indices) {
        val sampleI16 = (pcm[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort().toInt()
        outputBuf[i * 2] = (sampleI16 and 0xFF).toByte()
        outputBuf[i * 2 + 1] = ((sampleI16 ushr 8) and 0xFF).toByte()
    }
    return byteLen
}

private fun decodeOpusPacketToPcmFrame(
    packet: ByteArray,
    pcmBuf: FloatArray,
    destOffset: Int,
): Int {
    if (packet.size % 2 != 0) {
        throw KaudioError.Opus("invalid opus frame byte length: ${packet.size}")
    }
    val sampleCount = packet.size / 2
    if (destOffset + sampleCount > pcmBuf.size) {
        throw KaudioError.Opus("pcm output buffer too small")
    }
    for (i in 0 until sampleCount) {
        val lo = packet[i * 2].toUByte().toInt()
        val hi = packet[i * 2 + 1].toInt()
        val sampleI16 = ((hi shl 8) or lo).toShort()
        pcmBuf[destOffset + i] = sampleI16.toFloat() / 32767.0f
    }
    return sampleCount
}

private val SUPPORTED_OPUS_SAMPLE_RATES: Set<Int> = setOf(8000, 12000, 16000, 24000, 48000)

private fun validateOpusSampleRate(sampleRate: Int) {
    if (sampleRate !in SUPPORTED_OPUS_SAMPLE_RATES) {
        throw KaudioError.Opus("invalid sample rate: $sampleRate")
    }
}

/**
 * Streaming Ogg Opus page encoder.
 */
public class Encoder private constructor(
    private val sampleRate: Int,
    private val headerDataBytes: ByteArray,
    private var sequenceNum: UInt,
) {
    private var totalData: Int = 0
    private val outPcm: ArrayDeque<Float> = ArrayDeque(2 * OPUS_ENCODER_FRAME_SIZE)
    private val opusBuf: ByteArray = ByteArray(50_000)

    public companion object {
        /** Create a new Ogg Opus [Encoder] for the given [sampleRate]. */
        public fun new(sampleRate: Int): Encoder {
            validateOpusSampleRate(sampleRate)
            val headPage =
                buildOggPage(
                    packet = writeOpusHeader(),
                    serial = 42u,
                    sequenceNum = 0u,
                    granulePosition = 0uL,
                    firstPage = true,
                )
            val tagsPage =
                buildOggPage(
                    packet = writeOpusTags(),
                    serial = 42u,
                    sequenceNum = 1u,
                    granulePosition = 0uL,
                    firstPage = false,
                )
            val headerData = ByteArray(headPage.size + tagsPage.size)
            headPage.copyInto(headerData, 0)
            tagsPage.copyInto(headerData, headPage.size)
            return Encoder(
                sampleRate = sampleRate,
                headerDataBytes = headerData,
                sequenceNum = 2u,
            )
        }
    }

    /** Return the serialized Ogg header pages (`OpusHead` and `OpusTags`). */
    public fun headerData(): ByteArray = headerDataBytes.copyOf()

    /**
     * Append [pcm] samples and encode all complete 960-sample frames into Ogg pages.
     */
    public fun encodePage(pcm: FloatArray): ByteArray {
        for (sample in pcm) {
            outPcm.addLast(sample)
        }
        val nchunks = outPcm.size / OPUS_ENCODER_FRAME_SIZE
        val encodedPages = ArrayList<ByteArray>(nchunks)
        var totalBytes = 0
        repeat(nchunks) {
            val chunk = FloatArray(OPUS_ENCODER_FRAME_SIZE)
            for (i in 0 until OPUS_ENCODER_FRAME_SIZE) {
                val v = outPcm.removeFirstOrNull() ?: throw KaudioError.OpusMissingPcm()
                chunk[i] = v
            }
            totalData += chunk.size
            val size = encodePcmFrameToOpusPacket(chunk, opusBuf)
            val absgp = totalData.toULong() * 48_000uL / sampleRate.toULong()
            if (size > 0) {
                val pageBytes =
                    buildOggPage(
                        packet = opusBuf.copyOfRange(0, size),
                        serial = 42u,
                        sequenceNum = sequenceNum,
                        granulePosition = absgp,
                        firstPage = false,
                    )
                sequenceNum += 1u
                encodedPages.add(pageBytes)
                totalBytes += pageBytes.size
            }
        }
        val encoded = ByteArray(totalBytes)
        var offset = 0
        for (page in encodedPages) {
            page.copyInto(encoded, offset)
            offset += page.size
        }
        return encoded
    }
}

private fun startsWithPrefix(
    data: ByteArray,
    prefix: ByteArray,
): Boolean {
    if (data.size < prefix.size) return false
    for (i in prefix.indices) {
        if (data[i] != prefix[i]) return false
    }
    return true
}

/**
 * Sender channel for feeding Ogg Opus byte chunks into an [AsyncDecoder].
 */
public class Sender internal constructor(
    private val queue: ArrayDeque<ByteArray>,
) {
    /** Enqueue a chunk of Ogg Opus [data] bytes. */
    public fun send(data: ByteArray) {
        queue.addLast(data.copyOf())
    }
}

/**
 * Pair of [AsyncDecoder] and [Sender] returned by [AsyncDecoder.new].
 */
public class AsyncDecoderWithSender(
    public val decoder: AsyncDecoder,
    public val sender: Sender,
)

/**
 * Asynchronous Ogg Opus decoder reading byte chunks sent through its companion [Sender].
 */
public class AsyncDecoder private constructor(
    private val prOgg: PacketReader,
    private val pcmBuf: FloatArray,
    private val flushEveryNSamples: Int,
    private val incomingQueue: ArrayDeque<ByteArray>,
) {
    private var sizeInBuf: Int = 0

    public companion object {
        /** Create a new [AsyncDecoder] and [Sender] pair. */
        public fun new(
            sampleRate: Int,
            flushEveryNSamples: Int,
        ): AsyncDecoderWithSender {
            validateOpusSampleRate(sampleRate)
            val pcmBuf = FloatArray(flushEveryNSamples + sampleRate * 5)
            val prOgg = PacketReader.new()
            val queue = ArrayDeque<ByteArray>()
            val decoder =
                AsyncDecoder(
                    prOgg = prOgg,
                    pcmBuf = pcmBuf,
                    flushEveryNSamples = flushEveryNSamples,
                    incomingQueue = queue,
                )
            return AsyncDecoderWithSender(decoder = decoder, sender = Sender(queue))
        }
    }

    /**
     * Read decoded PCM samples once at least `flushEveryNSamples` have accumulated,
     * or `null` if no further packets are currently available.
     */
    public fun read(): FloatArray? {
        while (incomingQueue.isNotEmpty()) {
            prOgg.appendBytes(incomingQueue.removeFirst())
        }
        while (true) {
            val packet = prOgg.next() ?: return null
            if (startsWithPrefix(packet, OPUS_HEAD_SIGNATURE) ||
                startsWithPrefix(packet, OPUS_TAGS_SIGNATURE)
            ) {
                continue
            }
            val readSize = decodeOpusPacketToPcmFrame(packet, pcmBuf, sizeInBuf)
            sizeInBuf += readSize
            if (sizeInBuf >= flushEveryNSamples) {
                val count = sizeInBuf
                sizeInBuf = 0
                return pcmBuf.copyOfRange(0, count)
            }
        }
    }
}

/**
 * Synchronous streaming Ogg Opus decoder.
 */
public class Decoder private constructor(
    private val prOgg: PacketReader,
    private val pcmBuf: FloatArray,
    private val flushEveryNSamples: Int,
) {
    private var sizeInBuf: Int = 0

    public companion object {
        /** Create a new Ogg Opus [Decoder] for [sampleRate] flushing every [flushEveryNSamples]. */
        public fun new(
            sampleRate: Int,
            flushEveryNSamples: Int,
        ): Decoder {
            validateOpusSampleRate(sampleRate)
            val pcmBuf = FloatArray(flushEveryNSamples + sampleRate * 5)
            val prOgg = PacketReader.new()
            return Decoder(
                prOgg = prOgg,
                pcmBuf = pcmBuf,
                flushEveryNSamples = flushEveryNSamples,
            )
        }
    }

    /**
     * Feed Ogg Opus [data] bytes and return decoded PCM samples once at least
     * `flushEveryNSamples` have accumulated, or `null` otherwise.
     */
    public fun decode(data: ByteArray): FloatArray? {
        prOgg.appendBytes(data)
        while (true) {
            val packet = prOgg.next() ?: break
            if (startsWithPrefix(packet, OPUS_HEAD_SIGNATURE) ||
                startsWithPrefix(packet, OPUS_TAGS_SIGNATURE)
            ) {
                continue
            }
            val readSize = decodeOpusPacketToPcmFrame(packet, pcmBuf, sizeInBuf)
            sizeInBuf += readSize
        }
        return if (sizeInBuf >= flushEveryNSamples) {
            val count = sizeInBuf
            sizeInBuf = 0
            pcmBuf.copyOfRange(0, count)
        } else {
            null
        }
    }
}
