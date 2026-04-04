package org.fossify.filemanager.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.viewBinding
import org.fossify.filemanager.controllers.FileManagerController
import org.fossify.filemanager.databinding.FmActivityMainBinding
import org.fossify.filemanager.interfaces.FileManagerExternalActions

class MainActivity : SimpleActivity(), FileManagerExternalActions {
    override var isSearchBarEnabled = false

    private val binding by viewBinding(FmActivityMainBinding::inflate)
    private val fileManagerController by lazy {
        FileManagerController(
            activity = this,
            binding = binding,
            intentProvider = { intent },
            externalActions = this
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        fileManagerController.onCreate(null)
        if (savedInstanceState != null) {
            fileManagerController.onRestoreInstanceState(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        fileManagerController.onResume()
    }

    override fun onPause() {
        fileManagerController.onPause()
        super.onPause()
    }

    override fun onStop() {
        fileManagerController.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        fileManagerController.onSaveInstanceState(outState)
    }

    override fun onBackPressedCompat(): Boolean {
        return fileManagerController.onBackPressedCompat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun openInTerminal(path: String) {
        // Standalone file manager does not embed a terminal surface.
    }
}
