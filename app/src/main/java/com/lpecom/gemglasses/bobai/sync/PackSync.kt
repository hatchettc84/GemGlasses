package com.lpecom.gemglasses.bobai.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/*
 * Verified, atomic knowledge-pack installs on the phone (addendum §6).
 *
 * Publishing (deciding what is approved) happens upstream; this only installs
 * packs that arrive already marked APPROVED for this tenant, and only after
 * every file matches the manifest. Retrieval never sees a partial generation:
 * files are staged, verified, moved into place, and only then does the
 * active pointer switch. A failure at any step leaves the previous verified
 * generation active.
 *
 * Hashes catch corruption, not a malicious publisher — authenticated transport
 * (and signed manifests, if the threat model needs them) belong to the
 * source adapter.
 */

@Serializable
data class PackFileEntry(val path: String, val sha256: String, val sizeBytes: Long)

@Serializable
data class PackManifest(
    val packId: String,
    val tenantId: String,
    val version: Int,
    val schemaVersion: Int,
    val generatedAt: Long,
    val expiresAt: Long? = null,
    val documentIds: List<String>,
    /** documentId -> revision */
    val revisions: Map<String, String> = emptyMap(),
    /** documentId -> applicable equipment models */
    val equipmentApplicability: Map<String, List<String>> = emptyMap(),
    val approvalStatus: String,
    val files: List<PackFileEntry>,
    val indexVersion: String,
    val embeddingModelVersion: String? = null,
    /** Document ids removed since the previous version. */
    val deletionTombstones: List<String> = emptyList(),
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && now >= expiresAt
}

@Serializable
private data class ActivePointer(val version: Int, val previousVersion: Int? = null)

sealed interface InstallResult {
    data class Installed(val version: Int, val previousVersion: Int?) : InstallResult
    data class AlreadyCurrent(val version: Int) : InstallResult
    data class Rejected(val reason: String) : InstallResult
}

data class ActiveGeneration(val manifest: PackManifest, val dir: File, val previousVersion: Int?) {
    fun file(path: String): File = File(dir, path)
}

/** Where approved packs come from (business folder connector, server, Git…). None is connected yet. */
interface KnowledgeSourceAdapter {
    val displayName: String

    /** Latest published manifest for the pack, or null if the source has none. */
    suspend fun latestManifest(packId: String): PackManifest?

    /** Opens one file of a published version; null if it can't be fetched. */
    suspend fun open(manifest: PackManifest, entry: PackFileEntry): InputStream?
}

class AtomicPackStore(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val staging = File(root, "staging")
    private val generations = File(root, "generations")
    private val activeDir = File(root, "active")

    fun active(packId: String): ActiveGeneration? {
        val pointer = readPointer(packId) ?: return null
        val dir = generationDir(packId, pointer.version)
        val manifest = runCatching {
            json.decodeFromString(PackManifest.serializer(), File(dir, MANIFEST).readText())
        }.getOrNull() ?: return null
        return ActiveGeneration(manifest, dir, pointer.previousVersion)
    }

    /**
     * Stages, verifies and activates [manifest]. [open] supplies each file's
     * bytes (from a download or a local import). Never throws for bad input.
     */
    fun install(
        manifest: PackManifest,
        expectedTenantId: String,
        open: (PackFileEntry) -> InputStream?,
    ): InstallResult {
        validate(manifest, expectedTenantId)?.let { return InstallResult.Rejected(it) }

        val current = readPointer(manifest.packId)
        if (current != null) {
            if (manifest.version == current.version) return InstallResult.AlreadyCurrent(current.version)
            if (manifest.version < current.version) {
                return InstallResult.Rejected("Version ${manifest.version} is older than installed ${current.version}")
            }
        }

        val stage = File(staging, "${manifest.packId}-${manifest.version}-${UUID.randomUUID()}")
        try {
            if (!stage.mkdirs()) return InstallResult.Rejected("Could not create staging folder")
            for (entry in manifest.files) {
                val problem = open(entry)?.use { input -> copyVerified(input, File(stage, entry.path), entry) }
                    ?: "Missing file ${entry.path}"
                if (problem.isNotEmpty()) {
                    stage.deleteRecursively()
                    return InstallResult.Rejected(problem)
                }
            }
            File(stage, MANIFEST).writeText(json.encodeToString(PackManifest.serializer(), manifest))

            val target = generationDir(manifest.packId, manifest.version)
            target.deleteRecursively() // leftover from an earlier failed activation; never the active one
            target.parentFile?.mkdirs()
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)

            writePointer(manifest.packId, ActivePointer(manifest.version, current?.version))
            prune(manifest.packId, keep = setOfNotNull(manifest.version, current?.version))
            return InstallResult.Installed(manifest.version, current?.version)
        } catch (e: IOException) {
            stage.deleteRecursively()
            return InstallResult.Rejected("Install failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Re-activates the previous verified generation, if one is kept. */
    fun rollback(packId: String): Boolean {
        val pointer = readPointer(packId) ?: return false
        val previous = pointer.previousVersion ?: return false
        if (!File(generationDir(packId, previous), MANIFEST).isFile) return false
        writePointer(packId, ActivePointer(previous, null))
        return true
    }

    /** Re-hashes the active generation; returns problems found (empty = intact). */
    fun verifyActive(packId: String): List<String> {
        val gen = active(packId) ?: return listOf("No active generation")
        return gen.manifest.files.mapNotNull { entry ->
            val f = gen.file(entry.path)
            when {
                !f.isFile -> "Missing ${entry.path}"
                f.length() != entry.sizeBytes -> "Size mismatch ${entry.path}"
                sha256(f.inputStream()) != entry.sha256.lowercase() -> "Hash mismatch ${entry.path}"
                else -> null
            }
        }
    }

    private fun validate(m: PackManifest, expectedTenantId: String): String? = when {
        m.schemaVersion != SUPPORTED_SCHEMA -> "Unsupported manifest schema ${m.schemaVersion}"
        m.tenantId != expectedTenantId -> "Pack belongs to another tenant"
        m.approvalStatus != APPROVED -> "Pack is not approved (${m.approvalStatus})"
        !SAFE_ID.matches(m.packId) -> "Invalid pack id"
        m.files.isEmpty() -> "Manifest lists no files"
        m.files.map { it.path }.toSet().size != m.files.size -> "Manifest lists a file twice"
        m.files.any { !isSafePath(it.path) } -> "Manifest contains an unsafe file path"
        else -> null
    }

    /** Copies while hashing. Returns "" when the file matches the manifest, else the problem. */
    private fun copyVerified(input: InputStream, dest: File, entry: PackFileEntry): String {
        dest.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                if (size > entry.sizeBytes) return "File ${entry.path} is larger than the manifest says"
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        if (size != entry.sizeBytes) return "File ${entry.path} is incomplete"
        if (digest.digest().toHex() != entry.sha256.lowercase()) return "File ${entry.path} failed verification"
        return ""
    }

    private fun readPointer(packId: String): ActivePointer? = runCatching {
        json.decodeFromString(ActivePointer.serializer(), File(activeDir, "$packId.json").readText())
    }.getOrNull()

    private fun writePointer(packId: String, pointer: ActivePointer) {
        activeDir.mkdirs()
        val tmp = File(activeDir, "$packId.json.tmp")
        tmp.writeText(json.encodeToString(ActivePointer.serializer(), pointer))
        Files.move(
            tmp.toPath(),
            File(activeDir, "$packId.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun prune(packId: String, keep: Set<Int>) {
        File(generations, packId).listFiles()?.forEach { dir ->
            if (dir.name.toIntOrNull() !in keep) dir.deleteRecursively()
        }
    }

    private fun generationDir(packId: String, version: Int) = File(File(generations, packId), version.toString())

    companion object {
        const val APPROVED = "APPROVED"
        const val SUPPORTED_SCHEMA = 1
        private const val MANIFEST = "manifest.json"
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,64}")

        fun isSafePath(path: String): Boolean =
            path.isNotBlank() &&
                !path.startsWith("/") &&
                '\\' !in path &&
                path != MANIFEST &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        fun sha256(input: InputStream): String = input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            digest.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
