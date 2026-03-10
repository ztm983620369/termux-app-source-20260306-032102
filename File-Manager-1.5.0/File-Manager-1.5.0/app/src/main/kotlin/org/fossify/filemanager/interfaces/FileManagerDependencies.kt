package org.fossify.filemanager.interfaces

data class FileManagerDependencies(
    val environment: FileManagerEnvironment,
    val controllerCommands: FileManagerControllerCommands,
    val resultHandler: FileManagerResultHandler
)
