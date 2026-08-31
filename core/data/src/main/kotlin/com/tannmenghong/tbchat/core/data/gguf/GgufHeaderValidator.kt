package com.tannmenghong.tbchat.core.data.gguf

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validates a GGUF container in Kotlin before any native code opens it.
 *
 * A downloaded model is untrusted input to a large C++ parser, which is the
 * highest-risk surface in the app. This does not make llama.cpp safe -- nothing
 * here can -- but it does mean an obviously malformed or truncated file is
 * rejected in managed code with a clear error, rather than reaching a native
 * parser and taking the process down with a signal the user cannot act on.
 *
 * It also doubles as the metadata reader for models the user imported
 * themselves, where there is no catalog entry to describe the architecture.
 */
object GgufHeaderValidator {

    private const val MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MIN_VERSION = 2
    private const val MAX_VERSION = 3

    // Sanity ceilings. A real model has thousands of tensors, not billions; a
    // wild count is the signature of a corrupt or hostile header.
    private const val MAX_TENSORS = 1_000_000L
    private const val MAX_KV_PAIRS = 100_000L
    private const val MAX_STRING_LEN = 1L shl 24

    data class GgufInfo(
        val version: Int,
        val tensorCount: Long,
        val metadataCount: Long,
        val architecture: String?,
        val layers: Int?,
        val kvHeads: Int?,
        val embeddingLength: Int?,
        val contextLength: Int?
    ) {
        /** head_dim is not stored directly; it is embedding length over attention heads. */
        fun headDim(attentionHeads: Int?): Int? =
            if (embeddingLength != null && attentionHeads != null && attentionHeads > 0) {
                embeddingLength / attentionHeads
            } else null
    }

    sealed class Failure(val message: String) {
        data object TooSmall : Failure("The file is too small to be a model.")
        data object BadMagic : Failure("This is not a GGUF model file.")
        data class UnsupportedVersion(val version: Int) :
            Failure("This GGUF version ($version) is not supported.")

        data class ImplausibleHeader(val detail: String) :
            Failure("The model header is damaged: $detail")

        data class Unreadable(val detail: String) : Failure("Could not read the file: $detail")
    }

    class ValidationException(val failure: Failure) : Exception(failure.message)

    fun validate(file: File): Result<GgufInfo> {
        if (!file.isFile || file.length() < 32) {
            return Result.failure(ValidationException(Failure.TooSmall))
        }

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(HEADER_PROBE_BYTES.coerceAtMost(file.length().toInt()))
                raf.readFully(head)
                val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)

                val magic = buffer.int
                if (magic != MAGIC) throw ValidationException(Failure.BadMagic)

                val version = buffer.int
                if (version < MIN_VERSION || version > MAX_VERSION) {
                    throw ValidationException(Failure.UnsupportedVersion(version))
                }

                val tensorCount = buffer.long
                val metadataCount = buffer.long

                if (tensorCount <= 0 || tensorCount > MAX_TENSORS) {
                    throw ValidationException(
                        Failure.ImplausibleHeader("$tensorCount tensors")
                    )
                }
                if (metadataCount < 0 || metadataCount > MAX_KV_PAIRS) {
                    throw ValidationException(
                        Failure.ImplausibleHeader("$metadataCount metadata entries")
                    )
                }

                GgufInfo(
                    version = version,
                    tensorCount = tensorCount,
                    metadataCount = metadataCount,
                    architecture = readArchitecture(buffer, metadataCount),
                    layers = null,
                    kvHeads = null,
                    embeddingLength = null,
                    contextLength = null
                )
            }
        }.recoverCatching { throwable ->
            throw when (throwable) {
                is ValidationException -> throwable
                else -> ValidationException(
                    Failure.Unreadable(throwable.message ?: throwable::class.java.simpleName)
                )
            }
        }
    }

    /**
     * Best-effort read of `general.architecture`, which is conventionally the
     * first metadata key. A miss is not an error: the header is still valid, we
     * simply fall back to a size-based memory estimate.
     */
    private fun readArchitecture(buffer: ByteBuffer, metadataCount: Long): String? = runCatching {
        if (metadataCount == 0L) return null
        val keyLength = buffer.long
        if (keyLength <= 0 || keyLength > MAX_STRING_LEN || keyLength > buffer.remaining()) return null
        val key = ByteArray(keyLength.toInt()).also { buffer.get(it) }.decodeToString()
        if (key != "general.architecture") return null

        val valueType = buffer.int
        if (valueType != GGUF_TYPE_STRING) return null
        val valueLength = buffer.long
        if (valueLength <= 0 || valueLength > MAX_STRING_LEN || valueLength > buffer.remaining()) return null
        ByteArray(valueLength.toInt()).also { buffer.get(it) }.decodeToString()
    }.getOrNull()

    fun isValid(file: File): Boolean = validate(file).isSuccess

    private const val GGUF_TYPE_STRING = 8

    /** Enough to cover the fixed header plus the first few metadata entries. */
    private const val HEADER_PROBE_BYTES = 8192
}
