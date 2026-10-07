package com.d35p4c1t0.piffbackup

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d35p4c1t0.piffbackup.data.*
import com.d35p4c1t0.piffbackup.rsync.*
import com.d35p4c1t0.piffbackup.settings.ConfigurationTransfer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImprovementInstrumentedTest {
    @Test fun versionOneDatabaseMigratesWithoutLosingProfilesOrCheckpoints() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.d35p4c1t0.piffbackup.data.PiffBackupDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) sql.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            sql.execSQL("INSERT INTO storage_box_profiles VALUES ('primary','u123','u123.your-storagebox.de',23,'Backups',NULL,NULL,0,7,100,100)")
            sql.execSQL("INSERT INTO media_checkpoints VALUES ('primary','external_primary','v1',21,7,100)")
            sql.version = 1
        }
        val database = PiffBackupDatabase.open(context,name)
        try {
            val profile = requireNotNull(database.dao().profile("primary"))
            assertEquals("HETZNER",profile.provider)
            assertEquals(7L,profile.configurationRevision)
            assertEquals(21L,database.dao().checkpoint("primary","external_primary")?.successfulGeneration)
            assertTrue(database.dao().activeOperations().isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun checksumRepairsSameSizeSameTimestampAndVersionsPreserveOldBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"content-${System.nanoTime()}").apply { mkdirs() }
        val source = File(root,"source").apply { mkdir() }
        val destination = File(root,"destination").apply { mkdir() }
        val original = File(destination,"file.txt").apply { writeText("old"); setLastModified(1700000000000) }
        File(source,"file.txt").apply { writeText("new"); setLastModified(original.lastModified()) }
        File(destination,"remote-only.txt").writeText("keep")
        try {
            val command = listOf(NativeToolLocator(context).require(NativeTool.RSYNC).path,
                "-rt", "--checksum", "--no-whole-file", "--backup", "--backup-dir=.piffbackup-versions/test", "--",source.path+"/",destination.path+"/")
            val result = NativeProcessRunner().start(command,root).await(10000)
            assertEquals(result.stderr,0,result.exitCode)
            assertEquals("new",original.readText())
            assertEquals("old",destination.resolve(".piffbackup-versions/test/file.txt").readText())
            assertEquals("keep",destination.resolve("remote-only.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationTerminatesNativeChildProcessAndDrainsPipes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val child = java.util.concurrent.atomic.AtomicInteger()
        val ready = java.util.concurrent.CountDownLatch(1)
        val process = NativeProcessRunner().start(listOf("/system/bin/sh", "-c",
            "sleep 30 & child=\$!; printf '%s\\n' \"\$child\"; wait"), context.cacheDir,
            onStdoutChunk = { text -> text.trim().toIntOrNull()?.let { child.set(it); ready.countDown() } })
        assertTrue(ready.await(3,java.util.concurrent.TimeUnit.SECONDS))
        process.cancel()
        val result = process.await()
        assertTrue(result.cancelled)
        assertTrue("${result.durationMillis} ms",result.durationMillis < 5000)
        var exists = true
        repeat(30) {
            exists = runCatching { android.system.Os.kill(child.get(),0) }.isSuccess
            if (exists) Thread.sleep(50)
        }
        assertFalse("Native child survived cancellation",exists)
    }

    @Test fun splitUtf8CodePointSurvivesStreamReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val observed = StringBuilder()
        val result = NativeProcessRunner().start(listOf("/system/bin/sh", "-c",
            "printf '\\360\\237'; sleep 0.1; printf '\\230\\204'"),context.cacheDir,
            onStdoutChunk = { synchronized(observed) { observed.append(it) } }).await(3000)
        assertEquals(0,result.exitCode)
        assertEquals("😄", observed.toString())
    }

    @Test fun welcomeAndConnectionScreenSurviveActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runBlocking { (instrumentation.targetContext.applicationContext as PiffBackupApp).awaitReady() }
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.start_setup_button))
                .perform(androidx.test.espresso.action.ViewActions.click())
            scenario.recreate()
            instrumentation.waitForIdleSync()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.provider_button))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        }
    }

    @Test fun documentProviderSnapshotAuthenticatesAndRestoresWithoutNativePaths() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PiffBackupApp
        app.awaitReady()
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("com.d35p4c1t0.piffbackup.test.documents","root")
        app.encryptedArchives.installKey(ByteArray(32) { it.toByte() })
        val id = "operation-${java.util.UUID.randomUUID()}"
        val destination = File(app.cacheDir,"archive-restored-${System.nanoTime()}").apply { mkdir() }
        try {
            val file = app.encryptedArchives.createSaf(id,tree)
            assertTrue(file.isFile)
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("SAF receipt"))
            app.encryptedArchives.restore(id,destination)
            assertEquals("SAF receipt 😄",destination.resolve("Documents/receipt 😄.txt").readText())
        } finally { app.encryptedArchives.staged(id).delete(); destination.deleteRecursively() }
    }

    @Test fun nativeLauncherPreservesNonzeroExitStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = NativeProcessRunner().start(listOf("/system/bin/sh","-c","exit 7"),context.cacheDir).await(3000)
        assertEquals(7,result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test fun homeToolsAndPreferencesRenderWithHonestCoverage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PiffBackupApp
        app.awaitReady()
        val folder = File(android.os.Environment.getExternalStorageDirectory(),"Documents/PiffBackup-test-ui").apply { mkdirs() }
        app.configurationStore.saveProfile(StorageBoxProfileInput("primary","u123","u123.your-storagebox.de","Backups",
            encryptedCredentialRef="test-only",pinnedHostKey="ssh-ed25519 AQID",setupCompleted=true))
        app.configurationStore.replaceMappings("primary",listOf(FolderMappingInput("ui-test","Documents",
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FPiffBackup-test-ui",folder.path,
            "Documents/PiffBackup-test-ui/","Backups/Documents","ALL_FILES",true)))
        val profile = requireNotNull(app.configurationStore.profile("primary"))
        app.durableBackupStore.completeInitialAdoption("ui-test-run","primary",profile.configurationRevision,
            com.d35p4c1t0.piffbackup.media.MediaStoreCheckpoint("external_primary","test",0),System.currentTimeMillis(),0,0,0)
        try {
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun view(id: Int) = androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(id))
                view(R.id.home_status_title).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.withText(R.string.everything_backed_up)))
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(app.cacheDir,"home-review.png").outputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
                }; bitmap.recycle() }
                view(R.id.home_tools_button).perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.encrypted_archives))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                view(android.R.id.button2).perform(androidx.test.espresso.action.ViewActions.click())
                view(R.id.home_settings_button).perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click())
                app.durableBackupStore.recordCheck("primary")
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.settings_group).visibility)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(app.cacheDir,"settings-review.png").outputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
                }; bitmap.recycle() }
                view(R.id.backup_constraints_button).perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.daily_backup))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(app.cacheDir,"preferences-review.png").outputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
                }; bitmap.recycle() }
                view(android.R.id.button2).perform(androidx.test.espresso.action.ViewActions.click())
                view(R.id.main_scroll).perform(androidx.test.espresso.action.ViewActions.swipeUp())
                view(R.id.settings_change_connection_button).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            }
        } finally { app.database.dao().deleteProfile("primary"); folder.delete() }
    }

    @Test fun configurationExportContainsNoSecretsAndImportRequiresEnrollment() {
        val profile = StorageBoxProfileEntity("primary","user","nas.example",22,"Backups",
            "PRIVATE-REFERENCE","PUBLIC-PIN",true,12,0,0,"SSH_RSYNC")
        val encoded = ConfigurationTransfer.encode(profile,emptyList())
        assertFalse(encoded.contains("PRIVATE-REFERENCE"))
        val imported = ConfigurationTransfer.decode(encoded).first
        assertFalse(imported.setupCompleted)
        assertNull(imported.encryptedCredentialRef)
        assertEquals("SSH_RSYNC",imported.provider)
        assertEquals("PUBLIC-PIN",imported.pinnedHostKey)
    }
}
