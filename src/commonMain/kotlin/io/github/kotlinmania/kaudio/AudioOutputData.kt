package io.github.kotlinmania.kaudio

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Subtitle entry paired with the resampled sample index at which it should be displayed.
 */
public data class SubtitleEntry(
    public val sampleIndex: Int,
    public val text: String,
)

/**
 * Streaming resampled audio output buffer with subtitle synchronization and level metering.
 */
public class AudioOutputData private constructor(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val resampleRatio: Double,
) {
    private val resampledData: ArrayDeque<Float> = ArrayDeque(outputSampleRate * 10)
    private val inputBuffer: FloatArray = FloatArray(CHUNK_SIZE)
    private val outputBuffer: FloatArray =
        FloatArray((CHUNK_SIZE.toDouble() * max(resampleRatio, 1.0)).toInt() + 16)
    private val history: FloatArray = FloatArray(8)
    private var phase: Double = 0.0
    private var inputLen: Int = 0
    private var totalSamplesCount: Int = 0
    private val subs: ArrayDeque<SubtitleEntry> = ArrayDeque()
    private var meanSquares: Float = 0.0f

    /** Return the number of samples that have been seen so far. */
    public fun totalSamples(): Int = totalSamplesCount

    /** Return the number of resampled samples currently buffered. */
    public fun samplesInBuffer(): Int = resampledData.size

    /** Return a snapshot of buffered subtitle entries. */
    public fun subtitles(): List<SubtitleEntry> = subs.toList()

    /** Reset all internal buffers, resampler state, and counters. */
    public fun reset() {
        outputBuffer.fill(0.0f)
        inputBuffer.fill(0.0f)
        history.fill(0.0f)
        phase = 0.0
        inputLen = 0
        totalSamplesCount = 0
        resampledData.clear()
        subs.clear()
        meanSquares = 0.0f
    }

    /** Drain and return all currently buffered resampled samples in playback order. */
    public fun takeAll(): FloatArray {
        val data = FloatArray(resampledData.size)
        var idx = 0
        while (resampledData.isNotEmpty()) {
            data[idx++] = resampledData.removeLast()
        }
        return data
    }

    /** Return the current signal level in decibels (`10 + log10(meanSquares + 1e-10)`). */
    public fun db10(): Float = 10.0f + log10(meanSquares + 1e-10f)

    /** Clear buffered resampled audio and subtitles without resetting resampler state. */
    public fun clear() {
        resampledData.clear()
        subs.clear()
    }

    /** Return `true` if no resampled audio samples are buffered. */
    public fun isEmpty(): Boolean = resampledData.isEmpty()

    private fun pushInputBuffer(samples: FloatArray, offset: Int, length: Int) {
        samples.copyInto(
            destination = inputBuffer,
            destinationOffset = inputLen,
            startIndex = offset,
            endIndex = offset + length,
        )
        for (i in offset until offset + length) {
            val s = samples[i]
            meanSquares = meanSquares * 0.999f + s * s * 0.001f
        }
        inputLen += length
        totalSamplesCount += length
    }

    /** Enqueue a subtitle string associated with the current sample count. */
    public fun pushSub(sub: String) {
        subs.addLast(SubtitleEntry(sampleIndex = totalSamplesCount, text = sub))
    }

    /** Push input PCM samples, resampling full chunks into the output queue. */
    public fun pushSamples(samples: FloatArray) {
        var posIn = 0
        while (true) {
            val rem = inputBuffer.size - inputLen
            val posEnd = min(posIn + rem, samples.size)
            val copyLen = posEnd - posIn
            if (copyLen > 0) {
                pushInputBuffer(samples, posIn, copyLen)
            }
            posIn = posEnd
            if (inputLen < inputBuffer.size) {
                break
            }
            val outLen = processChunkIntoOutputBuffer()
            for (i in 0 until outLen) {
                resampledData.addFirst(outputBuffer[i])
            }
            inputLen = 0
        }
    }

    private fun sampleAt(index: Int): Float =
        if (index < 0) {
            history[history.size + index]
        } else {
            inputBuffer[min(index, inputBuffer.size - 1)]
        }

    private fun processChunkIntoOutputBuffer(): Int {
        val step = 1.0 / resampleRatio
        var outIdx = 0
        while (phase < CHUNK_SIZE.toDouble() && outIdx < outputBuffer.size) {
            val baseIdx = floor(phase).toInt()
            val t = (phase - baseIdx.toDouble()).toFloat()
            outputBuffer[outIdx++] = interpolateSeptic(baseIdx, t)
            phase += step
        }
        phase -= CHUNK_SIZE.toDouble()
        if (phase < 0.0) {
            phase = 0.0
        }
        inputBuffer.copyInto(
            destination = history,
            destinationOffset = 0,
            startIndex = CHUNK_SIZE - history.size,
            endIndex = CHUNK_SIZE,
        )
        return outIdx
    }

    private fun interpolateSeptic(baseIdx: Int, t: Float): Float {
        // 8-point Lagrange / septic polynomial interpolation across [baseIdx - 3 .. baseIdx + 4]
        var sum = 0.0f
        for (j in -3..4) {
            val y = sampleAt(baseIdx + j)
            var basis = 1.0f
            val xj = j.toFloat()
            for (m in -3..4) {
                if (m != j) {
                    val xm = m.toFloat()
                    basis *= (t - xm) / (xj - xm)
                }
            }
            sum += y * basis
        }
        return sum
    }

    public companion object {
        private const val CHUNK_SIZE: Int = 1024

        /** Create a new [AudioOutputData] resampler from [inputSampleRate] to [outputSampleRate]. */
        public fun new(inputSampleRate: Int, outputSampleRate: Int): AudioOutputData {
            if (inputSampleRate <= 0 || outputSampleRate <= 0) {
                throw KaudioError.RubatoC("sample rates must be positive: in=$inputSampleRate out=$outputSampleRate")
            }
            val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
            return AudioOutputData(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                resampleRatio = ratio,
            )
        }
    }
}
