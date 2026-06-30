package com.termux.ecjbridge

object EcjBridgeContract {
    const val BRIDGE_VERSION = 1
    const val TEMPLATE_VERSION = "2026-05-20-ecj-template-01"

    const val ECJ_APP_PACKAGE = "com.example.myapplication"
    const val ACTION_RUN_PROJECT = "com.termux.ecjbridge.action.RUN_PROJECT"

    const val EXTRA_BRIDGE_VERSION = "com.termux.ecjbridge.extra.BRIDGE_VERSION"
    const val EXTRA_PROJECT_NAME = "com.termux.ecjbridge.extra.PROJECT_NAME"
    const val EXTRA_PROJECT_PATH = "com.termux.ecjbridge.extra.PROJECT_PATH"
    const val EXTRA_PROJECT_ARCHIVE_URI = "com.termux.ecjbridge.extra.PROJECT_ARCHIVE_URI"
    const val EXTRA_TEMPLATE_VERSION = "com.termux.ecjbridge.extra.TEMPLATE_VERSION"

    const val CONFIG_DIR = ".ecj"
    const val PROJECT_CONFIG = ".ecj/project.json"
    const val TERMUX_LINK_CONFIG = ".ecj/termux-link.json"

    const val HOPWEB_TITLE = ".hopweb_title"
    const val HOPWEB_TEMPLATE = ".hopweb_template"

    const val ENTRY_CLASS_NAME = "com.dynamic.RealAppScript"
    const val ENTRY_METHOD_NAME = "launch"

    const val ARCHIVE_PATH_PREFIX = "project-archive"
}
