
package com.example.mikudownloader.mikudownloadvideo.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.yausername.youtubedl_android.YoutubeDL

import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ==============================================
// MIKU AUTO-UPDATER 1.0
//
// - No modifica MainActivity
// - No necesita cuentas
// - No agrega telemetría propia
// - Revisa actualizaciones cada 24 horas aprox.
// - Actualiza yt-dlp con su actualizador oficial
// - Comprueba bibliotecas de descarga
// - Detecta nuevas APK publicadas
// - Envía notificaciones sin repetir avisos
// ==============================================

private object MikuUpdateConfig {

    // CONFIGURACIÓN DE PUBLICACIÓN:
    //
    // Dejar vacío mientras todavía no exista
    // un repositorio con APK publicadas.
    //
    // Cuando exista, completar con el propietario
    // y el nombre exacto del repositorio.
    //
    // No se necesitan tokens para repos públicos.

    const val APP_OWNER = ""
    const val APP_REPOSITORY = ""

    // Versión de youtubedl-android utilizada
    // en nuestro Gradle actual.
    const val ANDROID_LIBRARY_VERSION = "0.18.1"

    const val JOB_ID = 40721

    const val CHANNEL_ID = "miku_updates"
    const val PREFS = "miku_auto_updater"

    const val CHECK_HOURS = 24L
}

// ==============================================
// INICIALIZADOR AUTOMÁTICO
//
// Android crea este ContentProvider al iniciar
// el proceso de la aplicación.
//
// No es necesario modificar MainActivity.
// ==============================================

class MikuUpdaterInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {

        val app = context?.applicationContext
                as? Application ?: return false

        MikuUpdateCore.registerLifecycle(app)
        MikuUpdateCore.schedule(app)

        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(
        uri: Uri
    ): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

// ==============================================
// TRABAJO PERIÓDICO DE ANDROID
// ==============================================

class MikuUpdateJobService : JobService() {

    @Volatile
    private var stopped = false

    override fun onStartJob(
        params: JobParameters
    ): Boolean {

        stopped = false

        Thread(
            {
                try {
                    MikuUpdateCore.checkForUpdates(
                        applicationContext
                    )

                } catch (error: Exception) {

                    Log.w(
                        "MikuAutoUpdater",
                        "No se pudo comprobar actualizaciones",
                        error
                    )

                } finally {

                    if (!stopped) {
                        jobFinished(params, false)
                    }
                }
            },
            "MikuAutoUpdater"
        ).start()

        // Trabajo asíncrono.
        return true
    }

    override fun onStopJob(
        params: JobParameters
    ): Boolean {

        stopped = true

        // Android podrá volver a ejecutar
        // la comprobación cuando corresponda.
        return true
    }
}

// ==============================================
// LÓGICA DEL ACTUALIZADOR
// ==============================================

private object MikuUpdateCore {

    private val activeActivities = AtomicInteger(0)

    // ==========================================
    // PROGRAMAR COMPROBACIONES
    // ==========================================

    fun schedule(context: Context) {

        try {
            val scheduler =
                context.getSystemService(
                    Context.JOB_SCHEDULER_SERVICE
                ) as JobScheduler

            // Evitar programar trabajos duplicados.
            if (
                scheduler.getPendingJob(
                    MikuUpdateConfig.JOB_ID
                ) != null
            ) {
                return
            }

            val component = ComponentName(
                context,
                MikuUpdateJobService::class.java
            )

            val job = JobInfo.Builder(
                MikuUpdateConfig.JOB_ID,
                component
            )
                .setRequiredNetworkType(
                    JobInfo.NETWORK_TYPE_ANY
                )
                .setPeriodic(
                    TimeUnit.HOURS.toMillis(
                        MikuUpdateConfig.CHECK_HOURS
                    )
                )
                .setPersisted(true)
                .build()

            scheduler.schedule(job)

        } catch (error: Exception) {

            Log.w(
                "MikuAutoUpdater",
                "No se pudo programar la revisión",
                error
            )
        }
    }

    // ==========================================
    // DETECTAR USO DE LA APLICACIÓN
    // ==========================================

    fun registerLifecycle(
        app: Application
    ) {

        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) = Unit

                override fun onActivityStarted(
                    activity: Activity
                ) {
                    activeActivities.incrementAndGet()
                }

                override fun onActivityResumed(
                    activity: Activity
                ) {

                    // Solicitamos el permiso una vez
                    // cuando se abre MainActivity.
                    // No interrumpimos SplashActivity.
                    if (
                        activity.javaClass.simpleName ==
                        "MainActivity"
                    ) {
                        requestNotificationPermission(
                            activity
                        )
                    }
                }

                override fun onActivityPaused(
                    activity: Activity
                ) = Unit

                override fun onActivityStopped(
                    activity: Activity
                ) {
                    activeActivities.updateAndGet {
                        (it - 1).coerceAtLeast(0)
                    }
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit

                override fun onActivityDestroyed(
                    activity: Activity
                ) = Unit
            }
        )
    }

    // ==========================================
    // PERMISO DE NOTIFICACIONES
    // ==========================================

    private fun requestNotificationPermission(
        activity: Activity
    ) {

        if (Build.VERSION.SDK_INT < 33) {
            return
        }

        if (
            activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = activity.getSharedPreferences(
            MikuUpdateConfig.PREFS,
            Context.MODE_PRIVATE
        )

        if (
            prefs.getBoolean(
                "notification_permission_asked",
                false
            )
        ) {
            return
        }

        prefs.edit()
            .putBoolean(
                "notification_permission_asked",
                true
            )
            .apply()

        Handler(
            Looper.getMainLooper()
        ).postDelayed(
            {
                if (
                    !activity.isFinishing &&
                    !activity.isDestroyed
                ) {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.POST_NOTIFICATIONS
                        ),
                        4072
                    )
                }
            },
            600L
        )
    }

    // ==========================================
    // COMPROBACIÓN GENERAL
    // ==========================================

    fun checkForUpdates(context: Context) {

        // Cada tarea se aísla para que el fallo
        // de una no impida las demás.

        try {
            checkAppRelease(context)
        } catch (error: Exception) {
            Log.w(
                "MikuAutoUpdater",
                "Error comprobando APK",
                error
            )
        }

        try {
            checkAndroidLibrary(context)
        } catch (error: Exception) {
            Log.w(
                "MikuAutoUpdater",
                "Error comprobando biblioteca",
                error
            )
        }

        try {
            updateYtDlp(context)
        } catch (error: Exception) {
            Log.w(
                "MikuAutoUpdater",
                "Error actualizando yt-dlp",
                error
            )
        }
    }

    // ==========================================
    // CONSULTAR GITHUB RELEASES
    // ==========================================

    private fun fetchRelease(
        owner: String,
        repository: String
    ): JSONObject {

        val validPart = Regex(
            "[A-Za-z0-9_.-]+"
        )

        if (
            !validPart.matches(owner) ||
            !validPart.matches(repository)
        ) {
            throw IllegalArgumentException(
                "Repositorio no configurado"
            )
        }

        val endpoint =
            "https://api.github.com/repos/" +
                    "$owner/$repository/releases/latest"

        val connection = (
                URL(endpoint).openConnection()
                        as HttpURLConnection
                )

        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        connection.setRequestProperty(
            "Accept",
            "application/vnd.github+json"
        )

        connection.setRequestProperty(
            "User-Agent",
            "MikuDownloader-AutoUpdater"
        )

        try {
            val code = connection.responseCode

            if (code != 200) {
                throw IllegalStateException(
                    "GitHub respondió HTTP $code"
                )
            }

            val response = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            return JSONObject(response)

        } finally {
            connection.disconnect()
        }
    }

    // ==========================================
    // COMPARAR VERSIONES
    // ==========================================

    private fun isNewerVersion(
        latest: String,
        installed: String
    ): Boolean {

        fun parse(value: String): List<Long>? {

            val cleaned = value.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")

            if (
                !Regex(
                    "\\d+(\\.\\d+){0,3}"
                ).matches(cleaned)
            ) {
                return null
            }

            return cleaned.split(".").map {
                it.toLongOrNull() ?: return null
            }
        }

        val newer = parse(latest) ?: return false
        val older = parse(installed) ?: return false

        val size = maxOf(
            newer.size,
            older.size
        )

        for (index in 0 until size) {

            val a = newer.getOrElse(index) { 0L }
            val b = older.getOrElse(index) { 0L }

            if (a > b) return true
            if (a < b) return false
        }

        return false
    }

    // ==========================================
    // COMPROBAR NUEVA APK PROPIA
    // ==========================================

    private fun checkAppRelease(
        context: Context
    ) {

        val owner = MikuUpdateConfig.APP_OWNER
        val repo = MikuUpdateConfig.APP_REPOSITORY

        // Sin repositorio configurado todavía,
        // no se puede comprobar la APK propia.
        if (
            owner.isBlank() ||
            repo.isBlank()
        ) {
            return
        }

        val release = fetchRelease(owner, repo)

        val latest = release.optString(
            "tag_name"
        )

        val releaseUrl = release.optString(
            "html_url"
        )

        val packageInfo =
            context.packageManager.getPackageInfo(
                context.packageName,
                0
            )

        val installed = packageInfo.versionName
            ?: "0.0.0"

        if (
            isNewerVersion(
                latest,
                installed
            )
        ) {
            notifyOnce(
                context = context,
                identifier = "app_$latest",
                notificationId = 40721,
                title = "Nueva versión de Miku Downloader",
                message =
                    "Disponible $latest. " +
                            "Toca para ver la actualización.",
                link = releaseUrl
            )
        }
    }

    // ==========================================
    // COMPROBAR BIBLIOTECA ANDROID
    // ==========================================

    private fun checkAndroidLibrary(
        context: Context
    ) {

        val release = fetchRelease(
            owner = "yausername",
            repository = "youtubedl-android"
        )

        val latest = release.optString(
            "tag_name"
        )

        val releaseUrl = release.optString(
            "html_url"
        )

        val installed =
            MikuUpdateConfig.ANDROID_LIBRARY_VERSION

        if (
            isNewerVersion(
                latest,
                installed
            )
        ) {

            notifyOnce(
                context = context,
                identifier = "library_$latest",
                notificationId = 40722,
                title = "Actualización de Miku Downloader",
                message =
                    "Hay una versión nueva de la " +
                            "biblioteca Android de descargas. " +
                            "Se necesita una APK actualizada.",
                link = releaseUrl
            )
        }
    }

    // ==========================================
    // ACTUALIZACIÓN AUTOMÁTICA YT-DLP
    // ==========================================

    private fun updateYtDlp(
        context: Context
    ) {

        // No actualizar el extractor mientras
        // el usuario utiliza la aplicación.
        if (activeActivities.get() > 0) {
            return
        }

        YoutubeDL.init(context)

        val previous = YoutubeDL.versionName(
            context
        ) ?: "Desconocida"

        val result = YoutubeDL.updateYoutubeDL(
            context,
            YoutubeDL.UpdateChannel.STABLE
        )

        if (
            result == YoutubeDL.UpdateStatus.DONE
        ) {

            val current = YoutubeDL.versionName(
                context
            ) ?: "Desconocida"

            notifyOnce(
                context = context,
                identifier = "ytdlp_$current",
                notificationId = 40723,
                title = "Miku actualizó su extractor",
                message =
                    "yt-dlp: $previous → $current. " +
                            "La aplicación está lista para usar.",
                link =
                    "https://github.com/yt-dlp/yt-dlp/releases"
            )
        }
    }

    // ==========================================
    // NOTIFICACIONES
    // ==========================================

    private fun notificationsAllowed(
        context: Context
    ): Boolean {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        return manager.areNotificationsEnabled()
    }

    private fun ensureNotificationChannel(
        manager: NotificationManager
    ) {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel = NotificationChannel(
                MikuUpdateConfig.CHANNEL_ID,
                "Actualizaciones de Miku",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            channel.description =
                "Avisos de actualización de Miku Downloader"

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun notifyOnce(
        context: Context,
        identifier: String,
        notificationId: Int,
        title: String,
        message: String,
        link: String
    ) {

        val address = Uri.parse(link)

        // Solo permitir notificaciones que abran
        // páginas HTTPS oficiales de GitHub.
        if (
            address.scheme != "https" ||
            address.host != "github.com"
        ) {
            return
        }

        if (!notificationsAllowed(context)) {
            return
        }

        val prefs = context.getSharedPreferences(
            MikuUpdateConfig.PREFS,
            Context.MODE_PRIVATE
        )

        val savedKey = "notified_$notificationId"

        if (
            prefs.getString(savedKey, "") ==
            identifier
        ) {
            return
        }

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        ensureNotificationChannel(manager)

        val intent = Intent(
            Intent.ACTION_VIEW,
            address
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val builder = if (
            Build.VERSION.SDK_INT >= 26
        ) {
            Notification.Builder(
                context,
                MikuUpdateConfig.CHANNEL_ID
            )
        } else {
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(
                android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(message)
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(
                notificationId,
                notification
            )

            prefs.edit()
                .putString(
                    savedKey,
                    identifier
                )
                .apply()

        } catch (error: SecurityException) {

            Log.w(
                "MikuAutoUpdater",
                "Permiso de notificación no disponible",
                error
            )
        }
    }
}
