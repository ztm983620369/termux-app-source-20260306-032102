package org.fossify.filemanager.helpers

import com.termux.bridge.FileOpenRequest
import java.util.Locale

object FileOpenStateMachine {
    enum class Trigger {
        DEFAULT_OPEN,
        OPEN_WITH_CHOOSER,
        OPEN_AS
    }

    enum class State {
        RECEIVED,
        NORMALIZED,
        USER_OVERRIDE_APPLIED,
        CLASSIFIED,
        RESOLVED
    }

    enum class ContentKind {
        IMAGE,
        OTHER
    }

    data class Input(
        val path: String,
        val displayName: String? = null,
        val extension: String? = null,
        val mimeType: String? = null,
        val request: FileOpenRequest? = null,
        val trigger: Trigger = Trigger.DEFAULT_OPEN,
        val openAsType: Int = OPEN_AS_DEFAULT
    )

    sealed class Action {
        data class OpenInEditor(val request: FileOpenRequest) : Action()
        data class OpenWithSystemViewer(
            val path: String,
            val forceChooser: Boolean = false,
            val openAsType: Int = OPEN_AS_DEFAULT
        ) : Action()
    }

    data class Decision(
        val action: Action,
        val contentKind: ContentKind,
        val terminalState: State,
        val transitions: List<State>
    )

    private data class NormalizedFile(
        val path: String,
        val extension: String?,
        val mimeType: String?
    )

    fun decide(input: Input): Decision {
        val transitions = arrayListOf(State.RECEIVED)
        val normalized = normalize(input)
        transitions += State.NORMALIZED

        val userOverride = resolveUserOverride(input, normalized)
        if (userOverride != null) {
            transitions += State.USER_OVERRIDE_APPLIED
            transitions += State.RESOLVED
            return Decision(
                action = userOverride,
                contentKind = classify(normalized),
                terminalState = State.RESOLVED,
                transitions = transitions
            )
        }

        val contentKind = classify(normalized)
        transitions += State.CLASSIFIED

        val action = when (contentKind) {
            ContentKind.IMAGE -> Action.OpenWithSystemViewer(
                path = normalized.path,
                forceChooser = false,
                openAsType = OPEN_AS_IMAGE
            )

            ContentKind.OTHER -> input.request?.let(Action::OpenInEditor)
                ?: Action.OpenWithSystemViewer(normalized.path)
        }

        transitions += State.RESOLVED
        return Decision(
            action = action,
            contentKind = contentKind,
            terminalState = State.RESOLVED,
            transitions = transitions
        )
    }

    private fun resolveUserOverride(input: Input, normalized: NormalizedFile): Action? {
        return when (input.trigger) {
            Trigger.OPEN_WITH_CHOOSER -> Action.OpenWithSystemViewer(
                path = normalized.path,
                forceChooser = true,
                openAsType = OPEN_AS_DEFAULT
            )

            Trigger.OPEN_AS -> when (input.openAsType) {
                OPEN_AS_DEFAULT -> null
                OPEN_AS_TEXT -> input.request?.let(Action::OpenInEditor)
                    ?: Action.OpenWithSystemViewer(normalized.path, openAsType = OPEN_AS_TEXT)

                else -> Action.OpenWithSystemViewer(
                    path = normalized.path,
                    forceChooser = false,
                    openAsType = input.openAsType
                )
            }

            Trigger.DEFAULT_OPEN -> null
        }
    }

    private fun normalize(input: Input): NormalizedFile {
        return NormalizedFile(
            path = input.path,
            extension = normalizeExtension(input.extension, input.displayName, input.path),
            mimeType = input.mimeType
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }
        )
    }

    private fun classify(file: NormalizedFile): ContentKind {
        if (file.mimeType?.startsWith("image/") == true) {
            return ContentKind.IMAGE
        }

        if (file.extension != null && imageExtensions.contains(file.extension)) {
            return ContentKind.IMAGE
        }

        return ContentKind.OTHER
    }

    private fun normalizeExtension(explicit: String?, displayName: String?, path: String): String? {
        explicit
            ?.trim()
            ?.trimStart('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return listOf(displayName, path)
            .asSequence()
            .filterNotNull()
            .map { it.substringAfterLast('/') }
            .map { it.substringAfterLast('.', "") }
            .map { it.trim().lowercase(Locale.ROOT) }
            .firstOrNull { it.isNotEmpty() }
    }

    private val imageExtensions = setOf(
        "jpg",
        "jpeg",
        "png",
        "bmp",
        "webp",
        "heic",
        "heif",
        "apng",
        "avif",
        "jxl",
        "gif"
    )
}
