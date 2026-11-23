package xyz.hidasy.pebblepinmanager.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight descriptor of an installed Android app that can be allowlisted for auto-pinning.
 */
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
)

/**
 * Abstraction for enumerating user-installed launchable apps without coupling ViewModels
 * to Android's [PackageManager].
 */
interface InstalledAppsProvider {
    suspend fun loadInstalledApps(): List<InstalledAppInfo>
}

class AndroidInstalledAppsProvider(
    private val context: Context,
) : InstalledAppsProvider {
    override suspend fun loadInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val ownPackage = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolvedActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        val appsByPackage = linkedMapOf<String, InstalledAppInfo>()
        for (resolveInfo in resolvedActivities) {
            val pkg = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isEmpty() || pkg == ownPackage || pkg in appsByPackage) continue

            val label = runCatching {
                resolveInfo.loadLabel(packageManager)?.toString()?.trim()
            }.getOrNull().orEmpty().ifEmpty { pkg }

            appsByPackage[pkg] = InstalledAppInfo(
                packageName = pkg,
                appName = label,
            )
        }

        appsByPackage.values.sortedWith(
            compareBy<InstalledAppInfo> { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() },
        )
    }
}
