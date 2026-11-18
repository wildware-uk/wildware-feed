package dev.wildware.agents.app.update

/**
 * The little scripts that finish an update after the app has quit: a running app can't replace
 * itself. Each one waits for [pid] to exit (forcing it after two minutes), swaps the new version
 * in so that a failure leaves the old one where it was, and opens the app again either way.
 *
 * Pure text, so tests can pin them down exactly.
 */
object HelperScripts {
    /** How long a script waits for the app to quit before forcing it, in seconds. */
    const val WaitSeconds = 120

    /** Single-quotes [text] for a POSIX shell. */
    fun sh(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /** Single-quotes [text] for PowerShell. */
    fun ps(text: String) = "'" + text.replace("'", "''") + "'"

    private fun shWait(pid: Long) = """
        |pid=$pid
        |i=0
        |while kill -0 "${'$'}pid" 2>/dev/null; do
        |  i=${'$'}((i + 1))
        |  if [ "${'$'}i" -ge $WaitSeconds ]; then
        |    kill -9 "${'$'}pid" 2>/dev/null
        |    sleep 1
        |    break
        |  fi
        |  sleep 1
        |done
    """.trimMargin()

    /**
     * Mac: copies the new bundle from the mounted disk image beside the old one, swaps the two
     * with renames, unmounts the image, clears the download quarantine flag, and opens the app.
     */
    fun mac(pid: Long, app: String, newApp: String, mountPoint: String, log: String) = """
        |#!/bin/sh
        |# Wildware Feed updater: swaps in the new app once the old one has quit, then opens it again.
        |exec >>${sh(log)} 2>&1
        |echo "update started ${'$'}(date)"
        |${shWait(pid)}
        |app=${sh(app)}
        |new=${sh(newApp)}
        |mount=${sh(mountPoint)}
        |staged="${'$'}app.updating"
        |old="${'$'}app.old"
        |rm -rf "${'$'}staged" "${'$'}old"
        |if ditto "${'$'}new" "${'$'}staged"; then
        |  if mv "${'$'}app" "${'$'}old"; then
        |    if mv "${'$'}staged" "${'$'}app"; then
        |      rm -rf "${'$'}old"
        |      echo "updated"
        |      # Take the new version's name ("Agents.app" becomes "Wildware Feed.app"): a rename, so the Dock keeps it.
        |      renamed="${'$'}(dirname "${'$'}app")/${'$'}(basename "${'$'}new")"
        |      if [ "${'$'}renamed" != "${'$'}app" ] && [ ! -e "${'$'}renamed" ] && mv "${'$'}app" "${'$'}renamed"; then
        |        app="${'$'}renamed"
        |        echo "renamed to ${'$'}app"
        |      fi
        |    else
        |      mv "${'$'}old" "${'$'}app"
        |      echo "could not move the new app in; kept the old one"
        |    fi
        |  fi
        |else
        |  echo "could not copy the new app; kept the old one"
        |fi
        |rm -rf "${'$'}staged"
        |hdiutil detach "${'$'}mount" -quiet || hdiutil detach "${'$'}mount" -force -quiet
        |xattr -dr com.apple.quarantine "${'$'}app" 2>/dev/null
        |open "${'$'}app"
        |
    """.trimMargin()

    /**
     * Windows, installed from the .msi: runs the new one (it upgrades in place), then opens the
     * first of [exes] that is there (the program may have moved with a rename).
     */
    fun windowsMsi(pid: Long, msi: String, exes: List<String>, log: String, msiLog: String) = """
        |# Wildware Feed updater: installs the new version once the app has quit, then opens it again.
        |Start-Transcript -Path ${ps(log)} -Append | Out-Null
        |${psWait(pid)}
        |${'$'}install = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${ps("/i \"$msi\" /passive /norestart /l*v \"$msiLog\"")} -Wait -PassThru
        |"msiexec finished with ${'$'}(${'$'}install.ExitCode)"
        |${'$'}exe = @(${exes.joinToString(", ") { ps(it) }}) | Where-Object { Test-Path -LiteralPath ${'$'}_ } | Select-Object -First 1
        |if (${'$'}exe) { Start-Process -FilePath ${'$'}exe }
        |Stop-Transcript | Out-Null
        |
    """.trimMargin()

    /**
     * Windows, unzipped from the portable .zip: the new folder is already unpacked beside the old
     * one as [staged]; this swaps the two with renames and opens the app.
     */
    fun windowsPortable(pid: Long, folder: String, staged: String, exe: String, log: String) = """
        |# Wildware Feed updater: swaps in the new folder once the app has quit, then opens it again.
        |Start-Transcript -Path ${ps(log)} -Append | Out-Null
        |${psWait(pid)}
        |${'$'}app = ${ps(folder)}
        |${'$'}new = ${ps(staged)}
        |${'$'}old = "${'$'}app.old"
        |if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force }
        |${'$'}moved = ${'$'}false
        |for (${'$'}try = 0; ${'$'}try -lt 10 -and -not ${'$'}moved; ${'$'}try++) {
        |  try {
        |    Move-Item -LiteralPath ${'$'}app -Destination ${'$'}old -ErrorAction Stop
        |    ${'$'}moved = ${'$'}true
        |  } catch {
        |    Start-Sleep -Seconds 1
        |  }
        |}
        |if (${'$'}moved) {
        |  try {
        |    Move-Item -LiteralPath ${'$'}new -Destination ${'$'}app -ErrorAction Stop
        |    Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue
        |    "updated"
        |  } catch {
        |    "could not move the new folder in; kept the old one: ${'$'}_"
        |    Move-Item -LiteralPath ${'$'}old -Destination ${'$'}app
        |  }
        |} else {
        |  "the old folder stayed in use; kept it"
        |}
        |Start-Process -FilePath ${ps(exe)}
        |Stop-Transcript | Out-Null
        |
    """.trimMargin()

    private fun psWait(pid: Long) = """
        |Wait-Process -Id $pid -Timeout $WaitSeconds -ErrorAction SilentlyContinue
        |Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
    """.trimMargin()

    /**
     * Linux: the new version is already in place (the AppImage renamed over the old one, or the
     * package manager done), so this only waits for the old app to quit and opens the new one.
     */
    fun linuxRelaunch(pid: Long, launcher: String, log: String) = """
        |#!/bin/sh
        |# Wildware Feed updater: opens the new version once the old one has quit.
        |exec >>${sh(log)} 2>&1
        |echo "relaunch started ${'$'}(date)"
        |${shWait(pid)}
        |exec ${sh(launcher)} </dev/null
        |
    """.trimMargin()
}
