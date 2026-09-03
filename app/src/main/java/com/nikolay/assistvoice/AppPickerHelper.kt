package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(
    val packageName: String,
    val label: String
)

/**
 * Lists installed apps for the app-picker spinner on a LAUNCH_APP slot,
 * and resolves a package's launcher Activity class name.
 */
object AppPickerHelper {

    /**
     * Returns user-facing (non-system) installed apps, sorted by label,
     * for the app picker. Filters out apps with no launcher Activity at
     * all, since those wouldn't be meaningful choices here.
     *
     * On Android 11+ (API 30+), package visibility restrictions mean
     * getInstalledApplications() alone only returns packages this app
     * has already "interacted" with, unless a matching <queries>
     * element is declared in the manifest. This app declares a
     * <queries><intent> block matching ACTION_MAIN/CATEGORY_LAUNCHER,
     * which is what makes the full launchable-app list visible here —
     * queryIntentActivities() is used (rather than
     * getInstalledApplications()) because it's the query form that
     * directly corresponds to that manifest declaration.
     */
    fun listLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        return resolveInfos
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The launcher Activity's fully-qualified class name for a package,
     * or null if it can't be resolved (e.g. package has no launcher
     * Activity, or was uninstalled since the picker list was built).
     */
    fun getLauncherActivityClassName(context: Context, packageName: String): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return intent?.component?.className
    }
}
