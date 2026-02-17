# 2026-01-13 Update

## Files Modified
1. `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectModels.kt`
   - Added `GO` to `IdeTemplate` enum to support Go language projects.

2. `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectTemplates.kt`
   - Added `GO` template file generation (`main.go`, `go.mod`, `.gitignore`).
   - Added `GO` project detection logic (checks for `main.go` or `go.mod`).
   - **Fixed:** Added missing `GO` branch to `writeTemplateFiles` `when` expression to resolve compilation error.

3. `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectStore.kt`
   - Added default run configuration for Go projects (`go run main.go`).

4. `ui-shell/src/main/java/com/termux/ui/TermuxUiApp.kt`
   - Completely redesigned "New Project" dialog (`addDialog`).
   - Replaced basic `AlertDialog` with a modern, full-custom `Dialog` using `Surface`.
   - Implemented card-based selection for "Environment" (Host/PRoot) and "Template".
   - Added grid layout for templates with icons and color coding.
   - Integrated Go version selection in the UI.
   - **New:** Completely redesigned the project list UI.
     - Replaced simple text list with **Material Design Cards**.
     - Added **Template Icons** (C++, Python, Node, Go, Note) with distinct colors.
     - Added **Environment Badges** (PRoot/Host) with color coding (Purple/Teal).
     - Improved typography and layout spacing.
     - Enhanced empty state with an icon and clearer instructions.
   - **Fixed:** Resolved `Unresolved reference: InsertDriveFile` compilation error by replacing usage with `Icons.Filled.Description`.
   - **Cleaned:** Removed duplicate imports (`Code`, `Surface`).
   - **Fixed:** Restored scrolling in the project list by switching to `LazyColumn` (no logic changes).
   - **UI:** Replaced project list left icons with VSCode SVG icons (same mechanism as Files page).
    - **UI:** Refined project card layout.
      - Switched to a horizontal row layout for better space utilization.
      - Reduced button size and padding for a more compact and standard look.
      - Aligned project details and badges for a cleaner appearance.
    - **UI:** Adjusted project card actions and metadata.
      - Show full project path with wrapping (no truncation).
      - Moved "打开" button to the top-right area.
      - Added bottom-left date display and bottom-right overflow menu (复制路径 / 复制一份 / 删除).

5. `ui-shell/src/main/java/com/termux/ui/panel/EnvironmentManager.kt`
   - Hardened `miseInstallScript()` to avoid `exit 127` after installer runs.
   - Switched from `curl | sh` to downloading then running the script file.
   - Added post-install existence/executable checks for `$HOME/.local/bin/mise` with dependency diagnostics.
   - Improved failure hint mapping for missing mise binary after installation attempt.

6. `ui-shell/src/main/java/com/termux/ui/panel/TermuxCommandRunner.kt`
   - Improved live log streaming to include both stdout and stderr.
   - Added heartbeat output when commands run long with no new lines.

7. `ui-shell/src/main/java/com/termux/ui/panel/ProotDistroManager.kt`
   - Added precheck for missing `proot-distro` to return clear error instead of silent `127`.

8. `ui-shell/src/main/java/com/termux/ui/selftest/FullChainSelfTest.kt`
   - Added proot-distro/distro presence checks to avoid hanging or misleading failures.
   - When proot is enabled but prerequisites are missing, selftest finishes quickly with fix commands.

9. `ui-shell/src/main/java/com/termux/ui/panel/EnvironmentManager.kt`
   - Avoided running PRoot detection when `proot-distro` is missing; return actionable messages.
