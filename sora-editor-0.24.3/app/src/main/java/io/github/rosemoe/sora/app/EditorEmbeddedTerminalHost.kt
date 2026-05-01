package io.github.rosemoe.sora.app

interface EditorEmbeddedTerminalHost {
    fun showEmbeddedTerminalWorkspace(currentFilePath: String?): Boolean
    fun hideEmbeddedTerminalWorkspace(): Boolean
    fun isEmbeddedTerminalWorkspaceVisible(): Boolean
    fun sendTextToEmbeddedTerminal(text: CharSequence)
    fun sendBackspaceToEmbeddedTerminal()
}
