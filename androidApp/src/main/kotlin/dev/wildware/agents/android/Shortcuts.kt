package dev.wildware.agents.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.wildware.agents.ProjectSummary

/**
 * Long-press the app icon: the projects at the top of the dashboard's list, each opening its
 * feed. Kept up to date as projects come and go; cleared on sign out.
 */
object Shortcuts {
    private const val Max = 4

    fun update(context: Context, projects: List<ProjectSummary>) {
        val shortcuts = projects.take(Max).mapIndexed { rank, summary ->
            val project = summary.project
            ShortcutInfoCompat.Builder(context, "project-${project.id}")
                .setShortLabel(project.name.take(24))
                .setLongLabel(project.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(Links.open("/projects/${project.slug}")), context, MainActivity::class.java))
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
