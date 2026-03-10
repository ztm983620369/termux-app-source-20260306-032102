package org.fossify.filemanager.interfaces

interface FileManagerHost :
    FileManagerEnvironment,
    FileManagerControllerCommands,
    FileManagerResultHandler,
    FileManagerExternalActions
