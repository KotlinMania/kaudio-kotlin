// port-lint: source src/ogg_pager.rs
package io.github.kotlinmania.kaudio

internal const val OGG_HEADER_BYTE_SIZE: Int = 27

private val OGG_CAPTURE_PATTERN: ByteArray =
    byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())

private fun readLeU32(
    buf: ByteArray,
    offset: Int,
): UInt =
    (buf[offset].toUByte().toUInt()) or
        (buf[offset + 1].toUByte().toUInt() shl 8) or
        (buf[offset + 2].toUByte().toUInt() shl 16) or
        (buf[offset + 3].toUByte().toUInt() shl 24)

private fun readLeU64(
    buf: ByteArray,
    offset: Int,
): ULong =
    (readLeU32(buf, offset).toULong()) or
        (readLeU32(buf, offset + 4).toULong() shl 32)

/**
 * 27-byte packed Ogg page header per the Xiph Ogg framing specification.
 */
public class OggHeader(
    public val capturePattern: ByteArray,
    public val version: UByte,
    public val headerType: UByte,
    public val granulePosition: ULong,
    public val bitstreamSerial: UInt,
    public val pageSequence: UInt,
    public val checksum: UInt,
    public val pageSegments: UByte,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OggHeader) return false
        return capturePattern.contentEquals(other.capturePattern) &&
            version == other.version &&
            headerType == other.headerType &&
            granulePosition == other.granulePosition &&
            bitstreamSerial == other.bitstreamSerial &&
            pageSequence == other.pageSequence &&
            checksum == other.checksum &&
            pageSegments == other.pageSegments
    }

    override fun hashCode(): Int {
        var result = capturePattern.contentHashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + headerType.hashCode()
        result = 31 * result + granulePosition.hashCode()
        result = 31 * result + bitstreamSerial.hashCode()
        result = 31 * result + pageSequence.hashCode()
        result = 31 * result + checksum.hashCode()
        result = 31 * result + pageSegments.hashCode()
        return result
    }

    override fun toString(): String =
        "OggHeader(version=$version, headerType=$headerType, granulePosition=$granulePosition, " +
            "bitstreamSerial=$bitstreamSerial, pageSequence=$pageSequence, " +
            "checksum=$checksum, pageSegments=$pageSegments)"
}

/**
 * Decoded Ogg page consisting of its [header] and payload [segments].
 */
public class Page(
    public val header: OggHeader,
    public val segments: List<ByteArray>,
)

/**
 * Streaming non-blocking Ogg page reader that extracts complete pages from buffered bytes.
 */
public class PageReader {
    private var data: ByteArray = ByteArray(0)

    public companion object {
        /** Create a new empty [PageReader]. */
        public fun new(): PageReader = PageReader()

        /** Default factory matching `Default::default()`. */
        public fun default(): PageReader = new()
    }

    /** Append incoming [data] bytes to the internal buffer. */
    public fun appendBytes(data: ByteArray) {
        if (data.isEmpty()) return
        val merged = ByteArray(this.data.size + data.size)
        this.data.copyInto(merged, 0)
        data.copyInto(merged, this.data.size)
        this.data = merged
    }

    /**
     * Attempt to read the next complete [Page] from the internal buffer,
     * returning `null` if more bytes are needed.
     */
    public fun next(): Page? {
        val hdrSize = OGG_HEADER_BYTE_SIZE
        if (data.size < hdrSize) {
            return null
        }
        val capturePattern = data.copyOfRange(0, 4)
        val version = data[4].toUByte()
        val headerType = data[5].toUByte()
        val granulePosition = readLeU64(data, 6)
        val bitstreamSerial = readLeU32(data, 14)
        val pageSequence = readLeU32(data, 18)
        val checksum = readLeU32(data, 22)
        val pageSegments = data[26].toUByte()

        val hdr =
            OggHeader(
                capturePattern = capturePattern,
                version = version,
                headerType = headerType,
                granulePosition = granulePosition,
                bitstreamSerial = bitstreamSerial,
                pageSequence = pageSequence,
                checksum = checksum,
                pageSegments = pageSegments,
            )

        if (!hdr.capturePattern.contentEquals(OGG_CAPTURE_PATTERN)) {
            throw KaudioError.OggUnexpectedCapturePattern(hdr.capturePattern)
        }
        if (hdr.version != 0u.toUByte()) {
            throw KaudioError.OggUnsupportedVersion(hdr.version)
        }
        val nsegments = hdr.pageSegments.toInt()
        if (data.size < hdrSize + nsegments) {
            return null
        }
        val segmentTable = data.copyOfRange(hdrSize, hdrSize + nsegments)
        var payloadSum = 0
        for (b in segmentTable) {
            payloadSum += b.toUByte().toInt()
        }
        val pageSize = hdrSize + nsegments + payloadSum
        if (data.size < pageSize) {
            return null
        }

        val segments = ArrayList<ByteArray>(nsegments)
        var startOffset = hdrSize + nsegments
        for (b in segmentTable) {
            val slen = b.toUByte().toInt()
            segments.add(data.copyOfRange(startOffset, startOffset + slen))
            startOffset += slen
        }
        data = data.copyOfRange(pageSize, data.size)
        return Page(header = hdr, segments = segments)
    }
}

/**
 * Streaming non-blocking Ogg packet reader that reassembles packets across page segments.
 */
public class PacketReader {
    private val pageReader: PageReader = PageReader.new()
    private val segments: MutableList<ByteArray> = ArrayList()
    private val packets: ArrayDeque<ByteArray> = ArrayDeque()

    public companion object {
        /** Create a new empty [PacketReader]. */
        public fun new(): PacketReader = PacketReader()

        /** Default factory matching `Default::default()`. */
        public fun default(): PacketReader = new()
    }

    /** Append incoming [data] bytes to the underlying [PageReader]. */
    public fun appendBytes(data: ByteArray) {
        pageReader.appendBytes(data)
    }

    /**
     * Return the next complete Ogg packet from the stream, or `null` if none is ready yet.
     */
    public fun next(): ByteArray? {
        while (true) {
            val page = pageReader.next() ?: break
            for (segment in page.segments) {
                val slen = segment.size
                segments.add(segment)
                if (slen < 255) {
                    var totalLen = 0
                    for (part in segments) {
                        totalLen += part.size
                    }
                    val packet = ByteArray(totalLen)
                    var writePos = 0
                    for (part in segments) {
                        part.copyInto(packet, writePos)
                        writePos += part.size
                    }
                    packets.addLast(packet)
                    segments.clear()
                }
            }
        }
        return packets.removeFirstOrNull()
    }
}
