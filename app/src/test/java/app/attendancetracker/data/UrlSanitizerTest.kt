package app.attendancetracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun sanitize_stripsTokensAndSignatures() {
        val raw = "https://parents.nie.ac.in/index.php?option=com_studentdashboard&controller=studentdashboard&task=dashboard&ksign=secret123&token=abc456&auth=xyz"
        val sanitized = UrlSanitizer.sanitize(raw)

        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("ksign"))
        assertFalse(sanitized.contains("token"))
        assertFalse(sanitized.contains("auth"))
        assertEquals("https://parents.nie.ac.in/index.php?option=com_studentdashboard&controller=studentdashboard&task=dashboard", sanitized)
    }

    @Test
    fun sanitize_noQuery_returnsOriginal() {
        val raw = "https://parents.nie.ac.in/index.php"
        assertEquals(raw, UrlSanitizer.sanitize(raw))
    }

    @Test
    fun sanitize_allSensitive_returnsBaseOnly() {
        val raw = "https://parents.nie.ac.in/?ksign=1&password=2&usn=3"
        assertEquals("https://parents.nie.ac.in/", UrlSanitizer.sanitize(raw))
    }
}
