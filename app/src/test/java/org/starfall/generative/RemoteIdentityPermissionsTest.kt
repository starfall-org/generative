package org.starfall.generative

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.generative.integrations.remote.ensureManagedIdentityPermissions
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RemoteIdentityPermissionsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun legacyWriteOnlyPrivateKeyBecomesOwnerReadableAndWritable() {
        val directory = File(context.filesDir, "remote-machines/identities").apply { mkdirs() }
        val key = File.createTempFile("private-", "", directory)
        try {
            key.writeText("key data")
            Os.chmod(key.absolutePath, 0b010_000_000) // Simulates the old restore flow (0200).
            ensureManagedIdentityPermissions(context.filesDir, key)

            assertEquals(0b110_000_000, Os.stat(key.absolutePath).st_mode and 0b111_111_111)
            assertEquals("key data", key.readText())
        } finally {
            key.delete()
        }
    }

    @Test
    fun arbitraryPrivateKeyOutsideAppIdentityDirectoryIsUntouched() {
        val key = File.createTempFile("external-", "", context.cacheDir)
        try {
            Os.chmod(key.absolutePath, 0b100_000_000)
            ensureManagedIdentityPermissions(context.filesDir, key)
            assertEquals(0b100_000_000, Os.stat(key.absolutePath).st_mode and 0b111_111_111)
        } finally {
            key.delete()
        }
    }
}
