package korlibs.io.lang

import korlibs.io.util.*
import korlibs.memory.*
import org.khronos.webgl.*

/**
 * This won't support streaming decoding
 */
val JS_UTF8 = object : UTC8CharsetBase("UTF-8") {
	val textDecoder: TextDecoder? = try {
		TextDecoder("utf-8")
	//} catch (e: dynamic) { // @TODO: wasm?
    } catch (e: Throwable) {
		null
	} // Do not fail if not supported!

	val textEncoder: TextEncoder? = try {
		TextEncoder("utf-8")
        //} catch (e: dynamic) { // @TODO: wasm?
    } catch (e: Throwable) {
		null
	} // Do not fail if not supported!

	override fun decode(out: StringBuilder, src: ByteArray, start: Int, end: Int): Int {
		if (textDecoder != null) {
			val srcBuffer = src.toInt8Array()
			out.append(textDecoder.decode(Int8Array(srcBuffer.buffer, start, end - start)))
		} else {
			super.decode(out, src, start, end)
		}
        return end - start
	}

	override fun encode(out: ByteArrayBuilder, src: CharSequence, start: Int, end: Int) {
		if (textEncoder != null) {
			val ba = textEncoder.encode(src.substring(start, end))
			out.append(Int8Array(ba.buffer).toByteArray())
		} else {
			super.encode(out, src, start, end)
		}
	}
}