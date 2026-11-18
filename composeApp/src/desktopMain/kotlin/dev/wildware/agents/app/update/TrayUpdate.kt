package dev.wildware.agents.app.update

/** The tray menu's update item: what it says, and what a click on it does. */
sealed interface TrayUpdate {
    val label: String
    val enabled: Boolean get() = true

    /** Nothing newer known: a click checks, without opening the window. */
    data object Check : TrayUpdate {
        override val label = "Check for updates…"
    }

    data object Checking : TrayUpdate {
        override val label = "Checking…"
        override val enabled = false
    }

    /** A click installs [version] and restarts, as the dialog's button does. */
    data class Install(val version: String) : TrayUpdate {
        override val label get() = "Install update $version"
    }

    /** A newer [version] this copy can't install by itself: a click opens the dialog, which says why. */
    data class Open(val version: String) : TrayUpdate {
        override val label get() = "Update to $version…"
    }

    data object Installing : TrayUpdate {
        override val label = "Installing update…"
        override val enabled = false
    }

    companion object {
        fun of(checking: Boolean, available: Release?, install: InstallState, canInstall: Boolean): TrayUpdate = when {
            install is InstallState.Downloading || install == InstallState.Verifying ||
                install == InstallState.Installing || install == InstallState.Restarting -> Installing
            checking -> Checking
            available == null -> Check
            canInstall && install !is InstallState.Manual -> Install(available.version)
            else -> Open(available.version)
        }
    }
}
