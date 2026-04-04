package org.fossify.filemanager.workspace

import com.termux.workspaceshell.model.WorkspaceKind
import com.termux.workspaceshell.model.WorkspaceShellState
import com.termux.workspaceshell.model.WorkspaceTabModel
import com.termux.workspaceshell.model.WorkspaceTabTone
import org.json.JSONArray
import org.json.JSONObject

data class WorkspaceTabSnapshot(
    val id: String,
    val reuseKey: String,
    val kind: WorkspaceKind,
    val tone: WorkspaceTabTone,
    val title: String,
    val rootRoute: String,
    val currentRoute: String,
    val locked: Boolean,
    val closable: Boolean,
    val badgeText: String? = null,
    val contentDescription: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("reuseKey", reuseKey)
            put("kind", kind.name)
            put("tone", tone.name)
            put("title", title)
            put("rootRoute", rootRoute)
            put("currentRoute", currentRoute)
            put("locked", locked)
            put("closable", closable)
            put("badgeText", badgeText ?: JSONObject.NULL)
            put("contentDescription", contentDescription ?: JSONObject.NULL)
        }
    }

    fun toModel(selected: Boolean): WorkspaceTabModel {
        return WorkspaceTabModel(
            id = id,
            reuseKey = reuseKey,
            kind = kind,
            tone = tone,
            title = title,
            rootRoute = rootRoute,
            currentRoute = currentRoute,
            selected = selected,
            locked = locked,
            closable = closable,
            badgeText = badgeText,
            contentDescription = contentDescription
        )
    }

    companion object {
        fun fromModel(model: WorkspaceTabModel): WorkspaceTabSnapshot {
            return WorkspaceTabSnapshot(
                id = model.id,
                reuseKey = model.reuseKey,
                kind = model.kind,
                tone = model.tone,
                title = model.title,
                rootRoute = model.rootRoute,
                currentRoute = model.currentRoute,
                locked = model.locked,
                closable = model.closable,
                badgeText = model.badgeText,
                contentDescription = model.contentDescription
            )
        }

        fun fromJson(json: JSONObject?): WorkspaceTabSnapshot? {
            if (json == null) return null
            val id = json.optString("id", "").trim()
            if (id.isEmpty()) return null

            val rootRoute = json.optString("rootRoute", "").trim()
            val currentRoute = json.optString("currentRoute", "").trim()
            if (rootRoute.isEmpty() || currentRoute.isEmpty()) return null

            return WorkspaceTabSnapshot(
                id = id,
                reuseKey = json.optString("reuseKey", rootRoute).trim().ifEmpty { rootRoute },
                kind = enumValueOrDefault(json.optString("kind"), WorkspaceKind.GENERIC),
                tone = enumValueOrDefault(json.optString("tone"), WorkspaceTabTone.NEUTRAL),
                title = json.optString("title", "").trim().ifEmpty { rootRoute.substringAfterLast('/') },
                rootRoute = rootRoute,
                currentRoute = currentRoute,
                locked = json.optBoolean("locked", false),
                closable = json.optBoolean("closable", !json.optBoolean("locked", false)),
                badgeText = json.optString("badgeText", "").trim().ifEmpty { null },
                contentDescription = json.optString("contentDescription", "").trim().ifEmpty { null }
            )
        }

        private fun <T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T {
            @Suppress("UNCHECKED_CAST")
            val enumClass = default.javaClass as Class<T>
            return runCatching { java.lang.Enum.valueOf(enumClass, raw.orEmpty().trim()) }
                .getOrDefault(default)
        }
    }
}

data class WorkspaceSessionSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val tabs: List<WorkspaceTabSnapshot> = emptyList(),
    val activeTabId: String? = null,
    val searchVisible: Boolean = false,
    val searchQueries: Map<String, String> = emptyMap(),
    val updatedAtMs: Long = 0L
) {
    fun isEmpty(): Boolean = tabs.isEmpty()

    fun toJson(): JSONObject {
        val tabsArray = JSONArray()
        tabs.forEach { tabsArray.put(it.toJson()) }

        val queriesJson = JSONObject()
        searchQueries.forEach { (tabId, query) ->
            if (query.isNotBlank()) {
                queriesJson.put(tabId, query)
            }
        }

        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("tabs", tabsArray)
            put("activeTabId", activeTabId ?: JSONObject.NULL)
            put("searchVisible", searchVisible)
            put("searchQueries", queriesJson)
            put("updatedAtMs", updatedAtMs)
        }
    }

    fun toShellState(): WorkspaceShellState? {
        if (tabs.isEmpty()) return null
        val validTabs = tabs.filter { it.id.isNotBlank() }
        if (validTabs.isEmpty()) return null

        val resolvedActiveId = validTabs.firstOrNull { it.id == activeTabId }?.id ?: validTabs.first().id
        val resolvedQueries = LinkedHashMap<String, String>()
        searchQueries.forEach { (tabId, query) ->
            if (query.isNotBlank() && validTabs.any { it.id == tabId }) {
                resolvedQueries[tabId] = query
            }
        }

        return WorkspaceShellState(
            tabs = validTabs.map { it.toModel(selected = it.id == resolvedActiveId) },
            activeTabId = resolvedActiveId,
            searchVisible = searchVisible,
            searchQueries = resolvedQueries
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun empty(): WorkspaceSessionSnapshot = WorkspaceSessionSnapshot()

        fun fromState(state: WorkspaceShellState, updatedAtMs: Long = System.currentTimeMillis()): WorkspaceSessionSnapshot {
            return WorkspaceSessionSnapshot(
                tabs = state.tabs.map(WorkspaceTabSnapshot::fromModel),
                activeTabId = state.activeTabId.takeIf { it.isNotBlank() },
                searchVisible = state.searchVisible,
                searchQueries = LinkedHashMap(state.searchQueries.filterValues { it.isNotBlank() }),
                updatedAtMs = updatedAtMs
            )
        }

        fun fromJsonString(raw: String?): WorkspaceSessionSnapshot {
            if (raw.isNullOrBlank()) return empty()
            return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(empty())
        }

        fun fromJson(json: JSONObject?): WorkspaceSessionSnapshot {
            if (json == null) return empty()

            val tabs = ArrayList<WorkspaceTabSnapshot>()
            val tabsArray = json.optJSONArray("tabs")
            if (tabsArray != null) {
                for (index in 0 until tabsArray.length()) {
                    WorkspaceTabSnapshot.fromJson(tabsArray.optJSONObject(index))?.let(tabs::add)
                }
            }

            val queries = LinkedHashMap<String, String>()
            val queriesJson = json.optJSONObject("searchQueries")
            if (queriesJson != null) {
                val keys = queriesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val query = queriesJson.optString(key, "").trim()
                    if (query.isNotEmpty()) {
                        queries[key] = query
                    }
                }
            }

            return WorkspaceSessionSnapshot(
                schemaVersion = json.optInt("schemaVersion", CURRENT_SCHEMA_VERSION),
                tabs = tabs,
                activeTabId = json.optString("activeTabId", "").trim().ifEmpty { null },
                searchVisible = json.optBoolean("searchVisible", false),
                searchQueries = queries,
                updatedAtMs = json.optLong("updatedAtMs", 0L)
            )
        }
    }
}
