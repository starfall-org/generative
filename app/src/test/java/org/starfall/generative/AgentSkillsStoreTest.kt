package org.starfall.generative

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.files.AgentSkillsStore
import org.starfall.generative.shared.model.AgentSkill

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentSkillsStoreTest {
    @Test
    fun contextStorageUsesAndroidFilesDirAndRecoversIncorrectlyNestedSkills() = runBlocking {
        val baseDirectory = Files.createTempDirectory("skill-appdata-").toFile()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = baseDirectory
        }
        val legacySkill = baseDirectory.resolve("appdata/agent-skills/legacy-skill")
        val existingSkill = baseDirectory.resolve("agent-skills/existing-skill")
        val duplicateLegacy = baseDirectory.resolve("appdata/agent-skills/existing-skill")
        try {
            legacySkill.mkdirs()
            existingSkill.mkdirs()
            duplicateLegacy.mkdirs()
            legacySkill.resolve("SKILL.md").writeText("""---
name: legacy-skill
description: Old skill
---

# Old instructions
""".trimIndent())
            legacySkill.resolve("scripts").mkdirs()
            legacySkill.resolve("scripts/run.sh").writeText("echo legacy")
            existingSkill.resolve("SKILL.md").writeText("""---
name: existing-skill
description: Current skill
---

# Current instructions
""".trimIndent())
            duplicateLegacy.resolve("SKILL.md").writeText("""---
name: existing-skill
description: Obsolete skill
---

# Obsolete instructions
""".trimIndent())

            val store = AgentSkillsStore(context)
            assertEquals(setOf("legacy-skill", "existing-skill"), store.readBlocking().map { it.name }.toSet())
            assertFalse(legacySkill.exists())
            assertEquals("echo legacy", baseDirectory.resolve("agent-skills/legacy-skill/scripts/run.sh").readText())
            assertEquals("# Current instructions", store.readBlocking().first { it.name == "existing-skill" }.content)
            assertTrue("Conflicting old data should remain intact", duplicateLegacy.resolve("SKILL.md").isFile)
            assertFalse("No extra appdata folder should be used for new files",
                baseDirectory.resolve("appdata/agent-skills/new-skill").exists())

            val nextSkill = AgentSkill("new-skill", "new-skill", "## Instructions\nDo new work", "A new skill")
            store.replaceAll(listOf(nextSkill))
            assertTrue(baseDirectory.resolve("agent-skills/new-skill/SKILL.md").isFile)
            assertFalse(baseDirectory.resolve("appdata/agent-skills/new-skill").exists())
            assertFalse(baseDirectory.resolve("agent-skills/legacy-skill").exists())
        } finally {
            baseDirectory.deleteRecursively()
        }
    }

    @Test
    fun newInstallationWritesDirectlyToFilesDirWithoutCreatingAppdata() = runBlocking {
        val baseDirectory = Files.createTempDirectory("skill-filesdir-").toFile()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = baseDirectory
        }
        try {
            val store = AgentSkillsStore(context)
            store.replaceAll(listOf(AgentSkill("writing", "writing", "Write clearly", "Writing assistant")))
            assertTrue(baseDirectory.resolve("agent-skills/writing/SKILL.md").isFile)
            assertFalse(baseDirectory.resolve("appdata").exists())
        } finally {
            baseDirectory.deleteRecursively()
        }
    }

    @Test
    fun listsAndReadsReferencedResourcesButRejectsTraversalAndSymlinks() = runBlocking {
        val folder = Files.createTempDirectory("skill-resources-").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val store = AgentSkillsStore(folder)
            store.replaceAll(listOf(AgentSkill("research", "research", "Read references/guide.md", "Research work")))
            val guide = folder.resolve("research/references/guide.md")
            requireNotNull(guide.parentFile).mkdirs()
            guide.writeText("Reference instructions")
            assertEquals(listOf("references/guide.md"), store.listResources(context, "research"))
            assertEquals("Reference instructions", store.readResource(context, "research", "references/guide.md"))
            listOf("../private.txt", "/etc/passwd", "references/../../private.txt", "C:/private.txt").forEach { path ->
                try {
                    store.readResource(context, "research", path)
                    org.junit.Assert.fail("Unsafe path was accepted: $path")
                } catch (_: IllegalArgumentException) { }
            }
            val outside = folder.resolve("private.txt").apply { writeText("private") }
            val link = folder.resolve("research/references/escape.txt")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFalse(store.listResources(context, "research").contains("references/escape.txt"))
            try {
                store.readResource(context, "research", "references/escape.txt")
                org.junit.Assert.fail("Symlink escaped skill directory")
            } catch (_: IllegalArgumentException) { }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun updatingSkillPreservesOptionalFrontmatterAndResourceFiles() = runBlocking {
        val folder = Files.createTempDirectory("skill-metadata-").toFile()
        try {
            val skillFile = folder.resolve("review/SKILL.md")
            requireNotNull(skillFile.parentFile).mkdirs()
            skillFile.writeText("""---
name: review
description: Review code
license: MIT
compatibility: Requires git
metadata:
  author: example
  version: "1.0"
allowed-tools: Read
---

# Review
""".trimIndent())
            val resource = folder.resolve("review/references/notes.md")
            requireNotNull(resource.parentFile).mkdirs()
            resource.writeText("Notes remain")
            val store = AgentSkillsStore(folder)
            store.replaceAll(listOf(AgentSkill("review", "review", "Updated instructions", "Review code changes")))
            val text = skillFile.readText()
            assertTrue(text.contains("license: MIT"))
            assertTrue(text.contains("compatibility: Requires git"))
            assertTrue(text.contains("  author: example"))
            assertTrue(text.contains("allowed-tools: Read"))
            assertEquals("Notes remain", resource.readText())
            assertEquals("Updated instructions", store.readBlocking().single().content)
            assertTrue(store.exportFiles().keys.contains("review/references/notes.md"))
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun optionalMetadataCanBeEditedAndRemovedInOneSkillMd() = runBlocking {
        val folder = Files.createTempDirectory("skill-paste-").toFile()
        try {
            val store = AgentSkillsStore(folder)
            val original = AgentSkill("writer", "writer", "# Write", "Write prose", extraFrontmatter = "license: MIT")
            store.replaceAll(listOf(original))
            assertTrue(folder.resolve("writer/SKILL.md").readText().contains("license: MIT"))
            val changed = original.copy(content = "# New instructions", extraFrontmatter = "metadata:\n  author: test")
            store.replaceAll(listOf(changed))
            val file = folder.resolve("writer/SKILL.md").readText()
            assertTrue(file.contains("  author: test"))
            assertFalse(file.contains("license: MIT"))
            assertEquals(changed, store.readBlocking().single())
            store.replaceAll(listOf(changed.copy(extraFrontmatter = "")))
            assertFalse(folder.resolve("writer/SKILL.md").readText().contains("metadata:"))
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun malformedSkillDirectoryIsPreservedWhenSavingOtherSkills() = runBlocking {
        val folder = Files.createTempDirectory("skill-invalid-").toFile()
        try {
            val invalid = folder.resolve("broken/SKILL.md")
            requireNotNull(invalid.parentFile).mkdirs()
            invalid.writeText("not YAML frontmatter")
            AgentSkillsStore(folder).replaceAll(listOf(
                AgentSkill("valid", "valid", "## Instructions", "A valid skill")))
            assertTrue(invalid.isFile)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun reorderedSkillsSurviveStoreRecreationAndKeepFolderData() = runBlocking {
        val root = Files.createTempDirectory("ordered-skills-").toFile()
        try {
            val writer = AgentSkill("writer", "writer", "## Write", "Writing skill")
            val analyst = AgentSkill("analyst", "analyst", "## Analyze", "Analysis skill")
            val helper = AgentSkill("helper", "helper", "## Help", "Helper skill")
            val store = AgentSkillsStore(root)
            store.replaceAll(listOf(writer, analyst, helper))
            assertEquals(listOf("writer", "analyst", "helper"),
                AgentSkillsStore(root).readBlocking().map { it.name })
            store.replaceAll(listOf(helper, writer, analyst))
            assertEquals(listOf("helper", "writer", "analyst"),
                AgentSkillsStore(root).readBlocking().map { it.name })
            assertTrue(root.resolve("writer/SKILL.md").isFile)
            assertFalse(store.exportFiles().keys.any { it.contains(".order") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun writesAndReadsStandardSkillDirectory() = runBlocking {
        val directory = Files.createTempDirectory("agent-skills-").toFile()
        try {
            val store = AgentSkillsStore(directory)
            val skill = AgentSkill(
                id = "pdf-processing",
                name = "pdf-processing",
                content = "## Instructions\nExtract the text.",
                description = "Extract PDF text when the user asks to process a PDF."
            )

            store.replaceAll(listOf(skill))

            val file = directory.resolve("pdf-processing/SKILL.md")
            assertTrue(file.isFile)
            assertTrue(file.readText().startsWith("---\nname: pdf-processing\ndescription:"))
            assertEquals(skill, store.readBlocking().single())
        } finally {
            directory.deleteRecursively()
        }
    }
}
