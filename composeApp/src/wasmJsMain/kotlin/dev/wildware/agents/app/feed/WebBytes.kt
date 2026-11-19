package dev.wildware.agents.app.feed

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.w3c.files.Blob
import kotlin.js.Promise

private fun arrayBufferOf(blob: Blob): Promise<ArrayBuffer> = js("blob.arrayBuffer()")

/** The bytes as a string of one char per byte: one copy across to Kotlin, much faster than byte by byte. */
private fun binaryString(buffer: ArrayBuffer): String = js(
    """{
    const b = new Uint8Array(buffer);
    const step = 0x8000;
    let s = '';
    for (let i = 0; i < b.length; i += step) s += String.fromCharCode.apply(null, b.subarray(i, i + step));
    return s;
}""",
)

/** A file or blob's bytes. */
internal suspend fun Blob.readBytes(): ByteArray {
    val text = binaryString(arrayBufferOf(this).await())
    return ByteArray(text.length) { text[it].code.toByte() }
}
