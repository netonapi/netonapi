package logic.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultipartFormTest {
    private val b = "----XyZ"
    private val body = ("--$b\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n" +
        "--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"model\"\r\nContent-Type: audio/wav\r\n\r\nRIFFbinary\r\n" +
        "--$b\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nzh\r\n--$b--\r\n").encodeToByteArray()

    @Test
    fun parsesBoundaryAndFields() {
        assertEquals("----XyZ", MultipartForm.boundary("multipart/form-data; boundary=----XyZ"))
        assertEquals("a b", MultipartForm.boundary("multipart/form-data; boundary=\"a b\""))
        assertNull(MultipartForm.boundary("application/json"))
        assertEquals("whisper-1", MultipartForm.field(body, b, "model"))
        assertEquals("zh", MultipartForm.field(body, b, "language"))
        assertNull(MultipartForm.field(body, b, "nope"))
    }

    @Test
    fun replacesOnlyTheNamedFieldKeepingTheFilePart() {
        val out = MultipartForm.replaceField(body, b, "model", "gpt-4o-transcribe")
        val s = out.decodeToString()
        assertTrue(s.contains("name=\"model\"\r\n\r\ngpt-4o-transcribe\r\n"))
        assertTrue(s.contains("filename=\"model\""))
        assertTrue(s.contains("RIFFbinary"))
        assertEquals(body.size - "whisper-1".length + "gpt-4o-transcribe".length, out.size)
        assertEquals("gpt-4o-transcribe", MultipartForm.field(out, b, "model"))
        assertEquals("zh", MultipartForm.field(out, b, "language"))
    }
}
