package io.github.kotlinmania.kaudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KaudioTest {
    @Test
    fun testWavWriteAndPcmDecodeRoundTrip() {
        val writer = ByteBufferWavWriter()
        val inputSamples = floatArrayOf(0.0f, 0.25f, -0.5f, 0.75f, -1.0f, 1.0f)
        writePcmF32AsWav(writer, inputSamples, sampleRate = 24_000u, nChannels = 1u)
        val wavBytes = writer.toByteArray()
        assertEquals(44 + inputSamples.size * 2, wavBytes.size)
        assertEquals("RIFF", wavBytes.decodeToString(0, 4))
        assertEquals("WAVE", wavBytes.decodeToString(8, 12))

        val decoded = pcmDecode(wavBytes)
        assertEquals(24_000u, decoded.sampleRate)
        assertEquals(inputSamples.size, decoded.pcmData.size)
        for (i in inputSamples.indices) {
            assertTrue(abs(inputSamples[i] - decoded.pcmData[i]) < 0.001f)
        }
    }

    @Test
    fun testWavSampleConversions() {
        val writer = ByteBufferWavWriter()
        val samples =
            listOf(
                FloatSample(0.5f),
                DoubleSample(-0.5),
                ShortSample(1234),
            )
        writePcmAsWav(writer, samples, sampleRate = 16_000u, nChannels = 1u)
        assertEquals(44 + 6, writer.len())

        writer.clear()
        assertEquals(0, writer.len())
        writePcmF64AsWav(writer, doubleArrayOf(0.0, 0.5), sampleRate = 16_000u, nChannels = 1u)
        assertEquals(44 + 4, writer.len())

        writer.clear()
        writePcmI16AsWav(writer, shortArrayOf(100, -100), sampleRate = 16_000u, nChannels = 1u)
        assertEquals(44 + 4, writer.len())
    }

    @Test
    fun testOggPagerAndPacketReader() {
        val payload1 = ByteArray(300) { (it and 0xFF).toByte() }
        val payload2 = ByteArray(120) { ((it * 3) and 0xFF).toByte() }

        val page1 =
            buildOggPage(
                packet = payload1,
                serial = 42u,
                sequenceNum = 0u,
                granulePosition = 0uL,
                firstPage = true,
            )

        val page2 =
            buildOggPage(
                packet = payload2,
                serial = 42u,
                sequenceNum = 1u,
                granulePosition = 960uL,
                firstPage = false,
            )

        val packetReader = PacketReader.new()
        packetReader.appendBytes(page1)
        packetReader.appendBytes(page2)

        assertContentEquals(payload1, packetReader.next())
        assertContentEquals(payload2, packetReader.next())
        assertNull(packetReader.next())
    }

    @Test
    fun testOggOpusHeadValidation() {
        val headerPacket = writeOpusHeader()
        val parsed = OpusHead.fromSlice(headerPacket)
        assertEquals(1u.toUByte(), parsed.version)
        assertEquals(1u.toUByte(), parsed.channelCount)
        assertEquals(3840u.toUShort(), parsed.preSkip)
        assertEquals(48_000u, parsed.sampleRate)
        assertEquals(0.toShort(), parsed.outputGain)
        assertEquals(0u.toUByte(), parsed.mappingFamily)
    }

    @Test
    fun testOggOpusEncoderAndDecoderRoundTrip() {
        val encoder = Encoder.new(sampleRate = 24_000)
        val headerBytes = encoder.headerData()
        assertTrue(headerBytes.isNotEmpty())

        val frameSamples =
            FloatArray(OPUS_ENCODER_FRAME_SIZE) { i ->
                (0.25 * sin(2.0 * PI * i.toDouble() / 48.0)).toFloat()
            }
        val encodedFrame = encoder.encodePage(frameSamples)

        val streamBytes = headerBytes + encodedFrame
        val decoder = Decoder.new(sampleRate = 24_000, flushEveryNSamples = OPUS_ENCODER_FRAME_SIZE)
        val decodedFrame = decoder.decode(streamBytes)
        assertNotNull(decodedFrame)
        assertEquals(OPUS_ENCODER_FRAME_SIZE, decodedFrame.size)
        for (i in frameSamples.indices) {
            assertTrue(abs(frameSamples[i] - decodedFrame[i]) < 1e-4f)
        }
        assertNull(decoder.decode(ByteArray(0)))
    }

    @Test
    fun testAsyncDecoderStreaming() {
        val encoder = Encoder.new(sampleRate = 24_000)
        val pair = AsyncDecoder.new(sampleRate = 24_000, flushEveryNSamples = OPUS_ENCODER_FRAME_SIZE)
        val sender = pair.sender
        val asyncDecoder = pair.decoder

        sender.send(encoder.headerData())
        assertNull(asyncDecoder.read())

        val frameSamples =
            FloatArray(OPUS_ENCODER_FRAME_SIZE) { i ->
                (i.toFloat() / OPUS_ENCODER_FRAME_SIZE.toFloat())
            }
        sender.send(encoder.encodePage(frameSamples))

        val decoded = asyncDecoder.read()
        assertNotNull(decoded)
        assertEquals(OPUS_ENCODER_FRAME_SIZE, decoded.size)
    }

    @Test
    fun testAudioOutputDataAndResample() {
        val outputData = AudioOutputData.new(inputSampleRate = 24_000, outputSampleRate = 48_000)
        assertTrue(outputData.isEmpty())
        outputData.pushSub("hello")
        val inputChunk = FloatArray(1024) { i -> sin(2.0 * PI * i.toDouble() / 64.0).toFloat() }
        outputData.pushSamples(inputChunk)
        assertEquals(1024, outputData.totalSamples())
        assertFalse(outputData.isEmpty())
        assertTrue(outputData.samplesInBuffer() > 1900)
        assertEquals(1, outputData.subtitles().size)
        assertEquals("hello", outputData.subtitles()[0].text)
        assertTrue(outputData.db10() > -10.0f)

        val drained = outputData.takeAll()
        assertTrue(drained.size > 1900)
        assertTrue(outputData.isEmpty())

        outputData.reset()
        assertEquals(0, outputData.totalSamples())

        val resampled = resample(inputChunk, srIn = 24_000, srOut = 12_000)
        assertEquals(512, resampled.size)
    }

    @Test
    fun testErrorBailAndContext() {
        val err =
            assertFailsWith<KaudioError> {
                bail("custom failure")
            }
        val withCtx = err.context("additional context").withPath("/audio/test.wav")
        assertTrue(withCtx.toString().contains("custom failure"))
        assertTrue(withCtx.toString().contains("additional context"))
        assertTrue(withCtx.toString().contains("/audio/test.wav"))
    }
}
