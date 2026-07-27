package io.talkcan.mount.saf

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSelectionContractTest {

    @Test
    fun `read-write selection requests exactly persistable read and write grant flags`() {
        val spec = SafTreeSelection.intentSpec(SafRequestedAccess(read = true, write = true))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, spec.action)
        val expected = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(expected, spec.flags)
        assertEquals(0, spec.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertTrue(spec.extras.isEmpty())
    }

    @Test
    fun `read-only selection omits the write grant flag`() {
        val spec = SafTreeSelection.intentSpec(SafRequestedAccess(read = true, write = false))

        val expected = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(expected, spec.flags)
        assertEquals(0, spec.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `write-only selection omits the read grant flag`() {
        val spec = SafTreeSelection.intentSpec(SafRequestedAccess(read = false, write = true))

        val expected = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(expected, spec.flags)
        assertEquals(0, spec.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty access request is rejected`() {
        SafRequestedAccess(read = false, write = false)
    }

    @Test
    fun `null or blank picker results map to cancellation`() {
        assertEquals(SafTreePickerOutcome.Cancelled, SafTreePickerOutcome.fromActivityResult(null))
        assertEquals(SafTreePickerOutcome.Cancelled, SafTreePickerOutcome.fromActivityResult(""))
        assertEquals(SafTreePickerOutcome.Cancelled, SafTreePickerOutcome.fromActivityResult("   "))
    }

    @Test
    fun `picker result uri maps to selection verbatim after trimming`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AJournal"
        assertEquals(
            SafTreePickerOutcome.Selected(uri),
            SafTreePickerOutcome.fromActivityResult(" $uri "),
        )
    }

    @Test
    fun `picker bridge maps null activity data to cancellation`() {
        assertEquals(SafTreePickerOutcome.Cancelled, SafTreePickerBridge().outcomeFrom(null))
    }
}
