package org.starfall.generative

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashRecordingHandler
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashLogExporter
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReport
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReportStore
import org.starfall.generative.shared.subfeatures.crashreports.logic.coreStackTrace
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CrashReportsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun uncaughtErrorIsSavedBeforeDelegatingAndSurvivesRestartUntilDismissed() {
        val file = File(temporary.root, "crash.json")
        val store = CrashReportStore(file)
        val error = IllegalStateException("UI failure", IllegalArgumentException("Invalid position & index"))
        var delegated = false
        val handler = CrashRecordingHandler(store) { thread, thrown ->
            assertSame(Thread.currentThread(), thread)
            assertSame(error, thrown)
            assertNotNull(CrashReportStore(file).pending())
            delegated = true
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
        val restarted = CrashReportStore(file)
        val report = restarted.pending()!!
        assertEquals("java.lang.IllegalArgumentException: Invalid position & index", report.cause)
        assertTrue(report.stackTrace.contains("Caused by: java.lang.IllegalArgumentException"))
        assertEquals(report, CrashReportStore(file).pending())
        restarted.dismiss(report.id)
        assertNull(CrashReportStore(file).pending())
    }

    @Test fun dismissingOldReportDoesNotEraseNewCrash() {
        val store = CrashReportStore(File(temporary.root, "crash.json"))
        store.record(Thread.currentThread(), IllegalStateException("First"))
        val old = store.pending()!!
        store.record(Thread.currentThread(), IllegalStateException("Second"))
        store.dismiss(old.id)
        assertTrue(store.pending()!!.cause.contains("Second"))
    }

    @Test fun missingOrDamagedReportDoesNotPreventStartup() {
        val file = File(temporary.root, "crash.json")
        val store = CrashReportStore(file)
        assertNull(store.pending())
        file.writeText("broken JSON")
        assertNull(store.pending())
    }

    @Test fun savingFailureStillDelegatesOriginalError() {
        val parent = temporary.newFile("not-a-directory")
        val store = CrashReportStore(File(parent, "crash.json"))
        val error = IllegalStateException("Original error")
        var delegated: Throwable? = null
        CrashRecordingHandler(store) { _, thrown -> delegated = thrown }
            .uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated)
    }

    @Test fun issueLinkTargetsRepoAndPreservesEncodedCauseAndStackTrace() {
        val store = CrashReportStore(File(temporary.root, "crash.json"))
        store.record(Thread.currentThread(), IllegalStateException("Invalid # & ? / vị trí"))
        val report = store.pending()!!
        val uri = report.issueUri()
        assertEquals("github.com", uri.host)
        assertEquals("/starfall-org/generative/issues/new", uri.path)
        assertTrue(uri.getQueryParameter("title")!!.contains("Invalid # & ? / vị trí"))
        val body = uri.getQueryParameter("body")!!
        assertTrue(body.contains(report.cause))
        assertTrue(body.contains(report.appVersion))
        assertTrue(body.contains("at org.starfall.generative.CrashReportsTest"))
    }

    @Test fun issueLinkStaysShortAndKeepsOnlyCoreFramesForHugeTraces() {
        val noise = (1..400).joinToString("\n") { "\tat android.view.Fake$it.method(Fake.java:$it)" }
        val ownFrames = (1..30).joinToString("\n") { "\tat org.starfall.generative.Screen$it.render(Screen.kt:$it)" }
        val trace = "java.lang.IllegalStateException: boom\n$noise\n$ownFrames\n" +
            "Caused by: java.lang.IllegalArgumentException: root\n$noise\n\t... 12 more"
        val report = CrashReport("id", 0L, "1.0 (1)", "Google Pixel", "14 (API 34)", "main",
            "java.lang.IllegalArgumentException: root", trace)

        val uri = report.issueUri()
        assertTrue(uri.toString().length <= 6500)
        val body = uri.getQueryParameter("body")!!
        assertTrue(body.contains("java.lang.IllegalStateException: boom"))
        assertTrue(body.contains("Caused by: java.lang.IllegalArgumentException: root"))
        assertTrue(body.contains("at org.starfall.generative.Screen1.render"))
        assertFalse(body.contains("android.view.Fake200"))
    }

    @Test fun coreStackTraceDropsFrameworkFramesWhenAppFramesExist() {
        val core = coreStackTrace(
            "java.lang.RuntimeException: x\n\tat android.os.Foo.bar(Foo.java:1)\n" +
                "\tat org.starfall.generative.Main.run(Main.kt:2)\n\t... 5 more", 6)
        assertTrue(core.contains("org.starfall.generative.Main.run"))
        assertFalse(core.contains("android.os.Foo"))
    }

    @Test fun logFileKeepsFullStackTraceAndHasLogExtension() {
        val full = (1..400).joinToString("\n") { "\tat android.view.Fake$it.method(Fake.java:$it)" }
        val report = CrashReport("id", 0L, "1.0 (1)", "Google Pixel", "14 (API 34)", "main",
            "java.lang.IllegalStateException: boom", "java.lang.IllegalStateException: boom\n$full")
        assertTrue(report.logFileName().matches(Regex("generative-crash-\\d{8}-\\d{6}\\.log")))
        val text = report.logText()
        assertTrue(text.contains("App: 1.0 (1)"))
        assertTrue(text.contains("Cause:\njava.lang.IllegalStateException: boom"))
        assertTrue(text.contains("android.view.Fake400.method"))
    }

    @Test fun logIsWrittenToSharedDownloadsNotPrivateAppStorage() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val report = CrashReport("id", 0L, "1.0 (1)", "Google Pixel", "9 (API 28)", "main",
            "java.lang.IllegalStateException: boom", "java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)")
        val shown = CrashLogExporter.save(context, report).getOrThrow()
        assertEquals("Download/Generative/${report.logFileName()}", shown)
        @Suppress("DEPRECATION")
        val saved = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "Generative/${report.logFileName()}")
        assertTrue(saved.isFile)
        assertEquals(report.logText(), saved.readText())
        assertFalse(saved.absolutePath.startsWith(context.filesDir.absolutePath))
    }
}
