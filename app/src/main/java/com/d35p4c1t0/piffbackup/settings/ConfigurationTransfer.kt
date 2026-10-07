package com.d35p4c1t0.piffbackup.settings

import com.d35p4c1t0.piffbackup.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Portable configuration deliberately contains no passwords, private keys, or credential references. */
object ConfigurationTransfer {
    fun encode(profile: StorageBoxProfileEntity, mappings: List<FolderMappingEntity>, policy: BackupSelectionEntity? = null): String = JSONObject()
        .put("format", "piffbackup-configuration").put("version", 1)
        .put("username", profile.username).put("hostname", profile.hostname).put("port", profile.port)
        .put("selection", policy?.let { JSONObject().put("includeHidden", it.includeHidden).put("exclusions", JSONArray(it.patterns)) })
        .put("provider", profile.provider).put("remoteBasePath", profile.remoteBasePath).put("hostKey", profile.pinnedHostKey)
        .put("mappings", JSONArray(mappings.map { mapping -> JSONObject()
            .put("id", mapping.id).put("displayName", mapping.displayName).put("treeUri", mapping.treeUri)
            .put("localPath", mapping.canonicalLocalPath).put("mediaPrefix", mapping.relativeMediaStorePrefix)
            .put("remotePath", mapping.relativeRemotePath).put("mode", mapping.mode).put("enabled", mapping.enabled)
        })).toString(2)

    fun decodeSelection(text: String): BackupSelectionEntity? {
        val selection = JSONObject(text).optJSONObject("selection") ?: return null
        val rules = selection.getJSONArray("exclusions")
        require(rules.length() <= 100)
        val patterns = (0 until rules.length()).map { rules.getString(it) }
        com.d35p4c1t0.piffbackup.backup.FileSelectionPolicy(patterns,selection.getBoolean("includeHidden"))
        return BackupSelectionEntity("primary",patterns.joinToString("\n"),selection.getBoolean("includeHidden"))
    }

    fun decode(text: String): Pair<StorageBoxProfileInput, List<FolderMappingInput>> {
        require(text.toByteArray().size <= 1024 * 1024) { "Configuration is too large" }
        val json = JSONObject(text)
        require(json.getString("format") == "piffbackup-configuration" && json.getInt("version") == 1)
        val profile = StorageBoxProfileInput("primary", json.getString("username"), json.getString("hostname"),
            json.getString("remoteBasePath"), json.getInt("port"), pinnedHostKey = json.optString("hostKey").takeIf { it.isNotBlank() && it != "null" },
            provider = json.optString("provider", "HETZNER"))
        val items = json.getJSONArray("mappings")
        require(items.length() <= 100) { "Too many mappings" }
        return profile to (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            FolderMappingInput(item.getString("id"), item.getString("displayName"), item.getString("treeUri"),
                item.getString("localPath"), item.getString("mediaPrefix"), item.getString("remotePath"),
                item.getString("mode"), item.getBoolean("enabled"))
        }
    }
}
