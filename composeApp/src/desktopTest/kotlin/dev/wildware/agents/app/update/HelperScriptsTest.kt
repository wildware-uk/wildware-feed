package dev.wildware.agents.app.update

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scripts that finish an update, word for word. They run after the app has gone, where
 * nothing can catch a mistake, so any change to them should be a deliberate one.
 */
class HelperScriptsTest {
    private val wait = """
        |pid=4242
        |i=0
        |while kill -0 "${'$'}pid" 2>/dev/null; do
        |  i=${'$'}((i + 1))
        |  if [ "${'$'}i" -ge 120 ]; then
        |    kill -9 "${'$'}pid" 2>/dev/null
        |    sleep 1
        |    break
        |  fi
        |  sleep 1
        |done
    """.trimMargin()

    @Test
    fun mac() {
        val script = HelperScripts.mac(
            pid = 4242,
            app = "/Applications/Agents.app",
            newApp = "/Users/alex/Library/Application Support/Agents/updates/1.0.2/mount/Wildware Feed.app",
            mountPoint = "/Users/alex/Library/Application Support/Agents/updates/1.0.2/mount",
            log = "/Users/alex/Library/Application Support/Agents/updates/1.0.2/update.log",
        )
        assertEquals(
            """
            |#!/bin/sh
            |# Wildware Feed updater: swaps in the new app once the old one has quit, then opens it again.
            |exec >>'/Users/alex/Library/Application Support/Agents/updates/1.0.2/update.log' 2>&1
            |echo "update started ${'$'}(date)"
            |$wait
            |app='/Applications/Agents.app'
            |new='/Users/alex/Library/Application Support/Agents/updates/1.0.2/mount/Wildware Feed.app'
            |mount='/Users/alex/Library/Application Support/Agents/updates/1.0.2/mount'
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
            """.trimMargin(),
            script,
        )
    }

    @Test
    fun windowsMsi() {
        val dir = "C:\\Users\\alex\\AppData\\Roaming\\Agents\\updates\\1.0.2"
        val script = HelperScripts.windowsMsi(
            pid = 4242,
            msi = "$dir\\Agents-1.0.2-windows-x64.msi",
            exes = listOf(
                "C:\\Users\\alex\\AppData\\Local\\Wildware Feed\\Wildware Feed.exe",
                "C:\\Users\\alex\\AppData\\Local\\Agents\\Agents.exe",
            ),
            log = "$dir\\update.log",
            msiLog = "$dir\\msiexec.log",
        )
        assertEquals(
            """
            |# Wildware Feed updater: installs the new version once the app has quit, then opens it again.
            |Start-Transcript -Path 'C:\Users\alex\AppData\Roaming\Agents\updates\1.0.2\update.log' -Append | Out-Null
            |Wait-Process -Id 4242 -Timeout 120 -ErrorAction SilentlyContinue
            |Stop-Process -Id 4242 -Force -ErrorAction SilentlyContinue
            |${'$'}install = Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i "C:\Users\alex\AppData\Roaming\Agents\updates\1.0.2\Agents-1.0.2-windows-x64.msi" /passive /norestart /l*v "C:\Users\alex\AppData\Roaming\Agents\updates\1.0.2\msiexec.log"' -Wait -PassThru
            |"msiexec finished with ${'$'}(${'$'}install.ExitCode)"
            |${'$'}exe = @('C:\Users\alex\AppData\Local\Wildware Feed\Wildware Feed.exe', 'C:\Users\alex\AppData\Local\Agents\Agents.exe') | Where-Object { Test-Path -LiteralPath ${'$'}_ } | Select-Object -First 1
            |if (${'$'}exe) { Start-Process -FilePath ${'$'}exe }
            |Stop-Transcript | Out-Null
            |
            """.trimMargin(),
            script,
        )
    }

    @Test
    fun windowsPortable() {
        val script = HelperScripts.windowsPortable(
            pid = 4242,
            folder = "D:\\Alex's Tools\\Agents",
            staged = "D:\\Alex's Tools\\Agents.updating",
            exe = "D:\\Alex's Tools\\Agents\\Agents.exe",
            log = "C:\\Temp\\update.log",
        )
        assertEquals(
            """
            |# Wildware Feed updater: swaps in the new folder once the app has quit, then opens it again.
            |Start-Transcript -Path 'C:\Temp\update.log' -Append | Out-Null
            |Wait-Process -Id 4242 -Timeout 120 -ErrorAction SilentlyContinue
            |Stop-Process -Id 4242 -Force -ErrorAction SilentlyContinue
            |${'$'}app = 'D:\Alex''s Tools\Agents'
            |${'$'}new = 'D:\Alex''s Tools\Agents.updating'
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
            |Start-Process -FilePath 'D:\Alex''s Tools\Agents\Agents.exe'
            |Stop-Transcript | Out-Null
            |
            """.trimMargin(),
            script,
        )
    }

    @Test
    fun linux() {
        val script = HelperScripts.linuxRelaunch(4242, "/home/alex/Apps/It's Agents.AppImage", "/home/alex/.config/agents/updates/1.0.2/update.log")
        assertEquals(
            """
            |#!/bin/sh
            |# Wildware Feed updater: opens the new version once the old one has quit.
            |exec >>'/home/alex/.config/agents/updates/1.0.2/update.log' 2>&1
            |echo "relaunch started ${'$'}(date)"
            |$wait
            |exec '/home/alex/Apps/It'\''s Agents.AppImage' </dev/null
            |
            """.trimMargin(),
            script,
        )
    }
}

/** The shell scripts, run for real where this machine can. */
class HelperScriptsRunTest {
    private fun shAvailable() = java.io.File("/bin/sh").canExecute()

    @Test
    fun theShellScriptsParse() {
        if (!shAvailable()) return
        val dir = java.nio.file.Files.createTempDirectory("agents-scripts").toFile()
        try {
            val scripts = mapOf(
                "mac.sh" to HelperScripts.mac(1, "/Applications/Agents.app", "/tmp/m/Agents.app", "/tmp/m", "/tmp/m.log"),
                "linux.sh" to HelperScripts.linuxRelaunch(1, "/opt/agents/bin/Agents", "/tmp/l.log"),
            )
            scripts.forEach { (name, text) ->
                val file = java.io.File(dir, name).apply { writeText(text) }
                val process = ProcessBuilder("/bin/sh", "-n", file.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), "$name: $output")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theLinuxHelperWaitsForTheAppThenOpensTheNewOne() {
        if (!shAvailable() || java.io.File.separatorChar != '/') return
        val dir = java.nio.file.Files.createTempDirectory("agents-relaunch").toFile()
        try {
            val opened = java.io.File(dir, "opened")
            val launcher = java.io.File(dir, "Agents").apply {
                writeText("#!/bin/sh\necho yes > '${opened.absolutePath}'\n")
                setExecutable(true)
            }
            val app = ProcessBuilder("sleep", "1").start()
            val script = java.io.File(dir, "relaunch.sh").apply {
                writeText(HelperScripts.linuxRelaunch(app.pid(), launcher.absolutePath, java.io.File(dir, "update.log").absolutePath))
            }
            val helper = ProcessBuilder("/bin/sh", script.absolutePath).start()
            Thread.sleep(300)
            kotlin.test.assertFalse(opened.exists(), "not while the app still runs")
            kotlin.test.assertTrue(helper.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
            kotlin.test.assertFalse(app.isAlive)
            assertEquals("yes", opened.readText().trim())
        } finally {
            dir.deleteRecursively()
        }
    }
}
