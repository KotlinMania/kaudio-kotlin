// port-lint: source src/error.rs
package io.github.kotlinmania.kaudio

/**
 * Error type for audio processing, Ogg/Opus framing, and resampling operations.
 */
public sealed class KaudioError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Resampler construction error. */
    public data class RubatoC(
        public val error: String,
    ) : KaudioError(error)

    /** Resampling execution error. */
    public data class RubatoR(
        public val error: String,
    ) : KaudioError(error)

    /** Opus codec error. */
    public data class Opus(
        public val error: String,
    ) : KaudioError(error)

    /** Ogg stream read error. */
    public data class OggRead(
        public val error: String,
    ) : KaudioError(error)

    /** Unexpected 8-byte Ogg Opus signature. */
    public class OggUnexpectedSignature(
        public val signature: ByteArray,
    ) : KaudioError("unexpected ogg signature ${signature.contentToString()}") {
        override fun equals(other: Any?): Boolean =
            other is OggUnexpectedSignature && signature.contentEquals(other.signature)

        override fun hashCode(): Int = signature.contentHashCode()
    }

    /** Unexpected 4-byte Ogg page capture pattern. */
    public class OggUnexpectedCapturePattern(
        public val pattern: ByteArray,
    ) : KaudioError("unexpected ogg capture pattern ${pattern.contentToString()}") {
        override fun equals(other: Any?): Boolean =
            other is OggUnexpectedCapturePattern && pattern.contentEquals(other.pattern)

        override fun hashCode(): Int = pattern.contentHashCode()
    }

    /** Unexpected byte length for an `OpusHead` packet. */
    public data class OggUnexpectedLenForOpusHead(
        public val len: Int,
    ) : KaudioError("unexpected len for opus head $len")

    /** Unsupported Ogg framing version. */
    public data class OggUnsupportedVersion(
        public val version: UByte,
    ) : KaudioError("unsupported ogg version $version")

    /** Expected PCM samples were missing during Opus encoding. */
    public class OpusMissingPcm : KaudioError("opus pcm was not found") {
        override fun equals(other: Any?): Boolean = other is OpusMissingPcm

        override fun hashCode(): Int = 1
    }

    /** I/O error. */
    public data class Io(
        public val error: String,
    ) : KaudioError(error)

    /** Audio container/codec probe or decode error. */
    public data class Symphonia(
        public val error: String,
    ) : KaudioError(error)

    /** User-generated error message, typically created via [bail]. */
    public data class Msg(
        public val msg: String,
    ) : KaudioError(msg)

    /** Arbitrary wrapped error. */
    public data class Wrapped(
        public val error: String,
    ) : KaudioError(error)

    /** Error with additional context message. */
    public data class Context(
        public val inner: KaudioError,
        public val context: String,
    ) : KaudioError("$context\n$inner", inner)

    /** Error with associated file path information. */
    public data class WithPath(
        public val inner: KaudioError,
        public val path: String,
    ) : KaudioError("path: \"$path\" $inner", inner)

    /** Error with captured backtrace information. */
    public data class WithBacktrace(
        public val inner: KaudioError,
        public val backtrace: String,
    ) : KaudioError("$inner\n$backtrace", inner)

    /** Attach backtrace information if enabled, returning this error. */
    public fun bt(): KaudioError = this

    /** Attach a file [path] to this error. */
    public fun withPath(path: String): KaudioError = WithPath(inner = this, path = path)

    /** Attach descriptive [context] to this error. */
    public fun context(context: Any): KaudioError = Context(inner = this, context = context.toString())

    override fun toString(): String = message.orEmpty()

    public companion object {
        /** Wrap an arbitrary error object into a [KaudioError]. */
        public fun wrap(err: Any): KaudioError = Wrapped(err.toString()).bt()

        /** Construct a [KaudioError.Msg] from a displayable value. */
        public fun msg(err: Any): KaudioError = Msg(err.toString()).bt()

        /** Construct a [KaudioError.Msg] from a debug representation. */
        public fun debug(err: Any): KaudioError = Msg(err.toString()).bt()
    }
}

/**
 * Throw a [KaudioError.Msg] with the given [msg].
 */
public fun bail(msg: String): Nothing = throw KaudioError.msg(msg)
