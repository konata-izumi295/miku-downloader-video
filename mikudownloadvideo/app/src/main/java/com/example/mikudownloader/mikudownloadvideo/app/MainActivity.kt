
package com.example.mikudownloader.mikudownloadvideo.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.io.IOException
import java.util.UUID

// ============================================
// MIKU DOWNLOADER - STABLE 1.1
//
// MOTOR ORIGINAL:
// - yt-dlp
// - FFmpeg
//
// FUNCIONES:
// - Descarga MP3 / MP4
// - Historial local
// - Reproducción multimedia
// - Enlaces compartidos
// - Cancelación
// - Descargas en MikuDownloads
// - Interfaz y wallpaper Hatsune Miku
//
// ACTUALIZADOR:
// MikuAutoUpdater.kt funciona por separado.
//
// CORRECCIONES DE COMPILACIÓN:
// - MikuSavedItem con visibilidad pública
// - history() accesible desde Compose
// - openMedia() accesible desde Compose
//
// No depende de MikuEngine.kt.
// ============================================

private const val MIKU_DOWNLOAD_PROCESS =
    "miku_stable_download"

// ============================================
// COLORES
// ============================================

private val MikuDark = Color(0xFF06121E)
private val MikuCyan = Color(0xFF39E6DB)
private val MikuWhite = Color(0xFFF0FFFF)
private val MikuGray = Color(0xFFBDD7E0)
private val MikuBorder = Color(0xFF387D8B)
private val MikuPanel = Color(0xDD081D29)

private val MikuShape = RoundedCornerShape(18.dp)

// ============================================
// MODELO DE HISTORIAL
//
// IMPORTANTE:
// Esta clase NO es private porque las funciones
// públicas de MainActivity la utilizan.
// ============================================

data class MikuSavedItem(
    val name: String,
    val uri: String,
    val mime: String
)

private class MikuDownloadCancelled : Exception()

// ============================================
// MAIN ACTIVITY
// ============================================

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )

    var busy by mutableStateOf(false)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var status by mutableStateOf(
        "Lista para descargar"
    )
        private set

    var engineVersion by mutableStateOf(
        "Motor yt-dlp"
    )
        private set

    var historyRevision by mutableIntStateOf(0)
        private set

    var sharedUrl by mutableStateOf("")
        private set

    var sharedRevision by mutableIntStateOf(0)
        private set

    @Volatile
    private var cancelled = false

    // ========================================
    // INICIO
    // ========================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        readSharedIntent(intent)

        setContent {

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = MikuCyan,
                    background = MikuDark,
                    surface = MikuPanel,
                    onSurface = MikuWhite
                )
            ) {

                MikuScreen(this)
            }
        }

        // Inicializar las bibliotecas originales.
        scope.launch {

            try {

                val version = withContext(
                    Dispatchers.IO
                ) {

                    YoutubeDL.init(
                        applicationContext
                    )

                    FFmpeg.init(
                        applicationContext
                    )

                    YoutubeDL.versionName(
                        applicationContext
                    )
                }

                engineVersion = if (
                    version.isNullOrBlank()
                ) {
                    "yt-dlp listo"
                } else {
                    "yt-dlp · versión registrada: $version"
                }

            } catch (error: Exception) {

                engineVersion =
                    "Motor no inicializado"

                if (!busy) {

                    status =
                        "Error al iniciar motor:\n" +
                                error.message.orEmpty()
                                    .takeLast(400)
                }
            }
        }
    }

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(intent)

        setIntent(intent)

        readSharedIntent(intent)
    }

    // ========================================
    // RECIBIR ENLACES COMPARTIDOS
    // ========================================

    private fun readSharedIntent(
        intent: Intent?
    ) {

        if (
            intent?.action != Intent.ACTION_SEND
        ) {
            return
        }

        val text = intent.getStringExtra(
            Intent.EXTRA_TEXT
        ).orEmpty()

        val link = Regex(
            "https?://\\S+"
        )
            .find(text)
            ?.value
            ?.trimEnd(
                '.', ',', ')', ']'
            )
            .orEmpty()

        if (link.isNotBlank()) {

            sharedUrl = link

            sharedRevision++
        }
    }

    fun showStatus(
        message: String
    ) {
        status = message
    }

    // ========================================
    // VALIDACIÓN DE ENLACES
    // ========================================

    private fun normalizeUrl(
        text: String
    ): String {

        val clean = text.trim()

        return if (
            clean.startsWith(
                "www.",
                ignoreCase = true
            )
        ) {

            "https://$clean"

        } else {

            clean
        }
    }

    private fun validateUrl(
        value: String
    ): Boolean {

        return try {

            val uri = Uri.parse(value)

            (
                    uri.scheme?.lowercase()
                            in listOf("http", "https")
                    ) && !uri.host.isNullOrBlank()

        } catch (_: Exception) {

            false
        }
    }

    private fun isYouTube(
        value: String
    ): Boolean {

        val host = Uri.parse(value)
            .host
            ?.lowercase()
            .orEmpty()

        return host == "youtube.com" ||
                host.endsWith(
                    ".youtube.com"
                ) ||
                host == "youtu.be"
    }

    // ========================================
    // INICIAR DESCARGA
    // ========================================

    fun startDownload(
        originalUrl: String,
        format: String
    ) {

        if (busy) {
            return
        }

        val url = normalizeUrl(
            originalUrl
        )

        if (!validateUrl(url)) {

            status =
                "Introduce un enlace válido."

            return
        }

        if (
            format != "MP3" &&
            format != "MP4"
        ) {

            status =
                "Formato no compatible."

            return
        }

        busy = true
        cancelled = false
        progress = 0f

        status =
            "Preparando motor de descarga..."

        scope.launch {

            try {

                val saved = withContext(
                    Dispatchers.IO
                ) {

                    downloadAndSave(
                        url,
                        format
                    )
                }

                if (cancelled) {

                    status =
                        "Descarga cancelada."

                } else {

                    addHistory(saved)

                    progress = 1f

                    status =
                        "Descarga completada:\n" +
                                saved.name
                }

            } catch (
                _: MikuDownloadCancelled
            ) {

                status =
                    "Descarga cancelada."

            } catch (
                _: YoutubeDL.CanceledException
            ) {

                status =
                    "Descarga cancelada."

            } catch (
                error: Exception
            ) {

                status = if (cancelled) {

                    "Descarga cancelada."

                } else {

                    "Error:\n${readableError(error)}"
                }

            } finally {

                busy = false
            }
        }
    }

    // ========================================
    // CONTROL DE CANCELACIÓN
    // ========================================

    private fun checkCancelled() {

        if (cancelled) {

            throw MikuDownloadCancelled()
        }
    }

    fun cancelDownload() {

        if (!busy) {
            return
        }

        cancelled = true

        status =
            "Cancelando descarga..."

        YoutubeDL.destroyProcessById(
            MIKU_DOWNLOAD_PROCESS
        )
    }

    // ========================================
    // MOTOR ORIGINAL YT-DLP + FFMPEG
    // ========================================

    private fun downloadAndSave(
        url: String,
        format: String
    ): MikuSavedItem {

        YoutubeDL.init(
            applicationContext
        )

        FFmpeg.init(
            applicationContext
        )

        checkCancelled()

        val base = getExternalFilesDir(null)
            ?: cacheDir

        val folder = File(
            base,
            "miku_temp/${UUID.randomUUID()}"
        )

        if (
            !folder.mkdirs() &&
            !folder.isDirectory
        ) {

            throw IOException(
                "No se pudo crear " +
                        "la carpeta temporal."
            )
        }

        try {

            val request = YoutubeDLRequest(
                url
            )

            request.addOption(
                "--ignore-config"
            )

            request.addOption(
                "--no-playlist"
            )

            request.addOption(
                "--no-mtime"
            )

            request.addOption(
                "--newline"
            )

            request.addOption(
                "--retries",
                "3"
            )

            request.addOption(
                "--fragment-retries",
                "3"
            )

            request.addOption(
                "-o",
                File(
                    folder,
                    "%(title).110B-%(id)s.%(ext)s"
                ).absolutePath
            )

            // EJS para YouTube.
            if (isYouTube(url)) {

                request.addOption(
                    "--remote-components",
                    "ejs:github"
                )
            }

            // =================================
            // FORMATO MP3
            // =================================

            if (format == "MP3") {

                request.addOption(
                    "-f",
                    "bestaudio/best"
                )

                request.addOption(
                    "-x"
                )

                request.addOption(
                    "--audio-format",
                    "mp3"
                )

                request.addOption(
                    "--audio-quality",
                    "0"
                )

            } else {

                // =================================
                // FORMATO MP4
                // =================================

                request.addOption(
                    "-f",
                    "bv*[ext=mp4]+ba[ext=m4a]/" +
                            "b[ext=mp4]/bv*+ba/b"
                )

                request.addOption(
                    "--merge-output-format",
                    "mp4"
                )

                request.addOption(
                    "--recode-video",
                    "mp4"
                )
            }

            checkCancelled()

            runOnUiThread {

                status =
                    "Descargando $format..."
            }

            // =================================
            // EJECUTAR EXTRACTOR
            // =================================

            YoutubeDL.execute(
                request = request,
                processId = MIKU_DOWNLOAD_PROCESS,
                redirectErrorStream = false,

                callback = { percent, _, line ->

                    runOnUiThread {

                        if (!cancelled) {

                            if (percent >= 0f) {

                                progress = (
                                        percent / 100f
                                        ).coerceIn(
                                        0f,
                                        1f
                                    )
                            }

                            val processing =
                                line.contains(
                                    "Merger",
                                    true
                                ) ||
                                        line.contains(
                                            "ExtractAudio",
                                            true
                                        ) ||
                                        line.contains(
                                            "VideoConvertor",
                                            true
                                        )

                            if (processing) {

                                status =
                                    "Procesando $format..."
                            }
                        }
                    }
                }
            )

            checkCancelled()

            // =================================
            // ENCONTRAR ARCHIVO FINAL
            // =================================

            val file = folder
                .listFiles()
                ?.filter {

                    it.isFile &&
                            it.extension.equals(
                                format.lowercase(),
                                ignoreCase = true
                            ) &&
                            it.length() > 1024
                }
                ?.maxByOrNull {

                    it.lastModified()
                }
                ?: throw IOException(
                    "No se encontró un archivo " +
                            "$format completo."
                )

            verifyMedia(
                file,
                format
            )

            checkCancelled()

            runOnUiThread {

                status =
                    "Guardando en MikuDownloads..."
            }

            return saveToDownloads(
                file,
                format
            )

        } finally {

            folder.deleteRecursively()
        }
    }

    // ========================================
    // VERIFICAR MULTIMEDIA
    // ========================================

    private fun verifyMedia(
        file: File,
        format: String
    ) {

        if (
            !file.isFile ||
            file.length() < 1024
        ) {

            throw IOException(
                "El archivo está vacío " +
                        "o incompleto."
            )
        }

        val reader = MediaMetadataRetriever()

        try {

            reader.setDataSource(
                file.absolutePath
            )

            val key = if (
                format == "MP3"
            ) {

                MediaMetadataRetriever
                    .METADATA_KEY_HAS_AUDIO

            } else {

                MediaMetadataRetriever
                    .METADATA_KEY_HAS_VIDEO
            }

            if (
                reader.extractMetadata(key)
                != "yes"
            ) {

                throw IOException(
                    "El archivo no contiene " +
                            "multimedia válida."
                )
            }

        } finally {

            reader.release()
        }
    }

    // ========================================
    // GUARDAR EN MIKUDOWNLOADS
    // ========================================

    private fun saveToDownloads(
        source: File,
        format: String
    ): MikuSavedItem {

        val mime = if (
            format == "MP3"
        ) {

            "audio/mpeg"

        } else {

            "video/mp4"
        }

        val name = source.name
            .replace(
                Regex(
                    "[\\\\/:*?\"<>|]"
                ),
                "_"
            )
            .take(170)

        // =====================================
        // ANDROID 10+
        // =====================================

        if (
            Build.VERSION.SDK_INT >= 29
        ) {

            val values =
                ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        name
                    )

                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        mime
                    )

                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/MikuDownloads/"
                    )

                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        1
                    )
                }

            val resolver =
                contentResolver

            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IOException(
                "No se pudo crear el archivo."
            )

            try {

                resolver.openOutputStream(
                    uri,
                    "w"
                )?.use { output ->

                    source.inputStream().use { input ->

                        val buffer =
                            ByteArray(65536)

                        while (true) {

                            checkCancelled()

                            val count =
                                input.read(buffer)

                            if (count == -1) {
                                break
                            }

                            output.write(
                                buffer,
                                0,
                                count
                            )
                        }
                    }

                } ?: throw IOException(
                    "No se pudo escribir el archivo."
                )

                checkCancelled()

                val finished =
                    ContentValues().apply {

                        put(
                            MediaStore.MediaColumns.IS_PENDING,
                            0
                        )
                    }

                if (
                    resolver.update(
                        uri,
                        finished,
                        null,
                        null
                    ) < 1
                ) {

                    throw IOException(
                        "No se pudo finalizar " +
                                "el archivo."
                    )
                }

                val actualName =
                    resolver.query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.DISPLAY_NAME
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->

                        if (
                            cursor.moveToFirst()
                        ) {

                            cursor.getString(0)

                        } else {

                            name
                        }

                    } ?: name

                return MikuSavedItem(
                    name = actualName,
                    uri = uri.toString(),
                    mime = mime
                )

            } catch (
                error: Exception
            ) {

                resolver.delete(
                    uri,
                    null,
                    null
                )

                throw error
            }
        }

        // =====================================
        // ANDROID 9 Y ANTERIORES
        // =====================================

        val folder = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ),
            "MikuDownloads"
        )

        if (
            !folder.exists() &&
            !folder.mkdirs()
        ) {

            throw IOException(
                "No se pudo crear MikuDownloads."
            )
        }

        var target = File(
            folder,
            name
        )

        var number = 1

        while (
            target.exists()
        ) {

            target = File(
                folder,
                "${source.nameWithoutExtension}_$number.${format.lowercase()}"
            )

            number++
        }

        try {

            source.inputStream().use { input ->

                target.outputStream().use { output ->

                    val buffer =
                        ByteArray(65536)

                    while (true) {

                        checkCancelled()

                        val count =
                            input.read(buffer)

                        if (
                            count == -1
                        ) {
                            break
                        }

                        output.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }

            MediaScannerConnection.scanFile(
                this,
                arrayOf(
                    target.absolutePath
                ),
                arrayOf(
                    mime
                ),
                null
            )

            return MikuSavedItem(
                name = target.name,
                uri = "",
                mime = mime
            )

        } catch (
            error: Exception
        ) {

            target.delete()

            throw error
        }
    }

    // ========================================
    // GUARDAR HISTORIAL
    // ========================================

    private fun addHistory(
        item: MikuSavedItem
    ) {

        val prefs =
            getSharedPreferences(
                "miku_historial",
                MODE_PRIVATE
            )

        val previous = try {

            JSONArray(
                prefs.getString(
                    "archivos",
                    "[]"
                )
            )

        } catch (
            _: Exception
        ) {

            JSONArray()
        }

        val updated = JSONArray()

        updated.put(
            JSONObject().apply {

                put(
                    "name",
                    item.name
                )

                put(
                    "uri",
                    item.uri
                )

                put(
                    "mime",
                    item.mime
                )
            }
        )

        for (
        i in 0 until minOf(
            29,
            previous.length()
        )
        ) {

            updated.put(
                previous.get(i)
            )
        }

        prefs.edit()
            .putString(
                "archivos",
                updated.toString()
            )
            .apply()

        historyRevision++
    }

    // ========================================
    // LEER HISTORIAL
    //
    // CORREGIDO:
    // Es PUBLIC para que MikuScreen()
    // pueda utilizarlo.
    // ========================================

    fun history(): List<MikuSavedItem> {

        val prefs =
            getSharedPreferences(
                "miku_historial",
                MODE_PRIVATE
            )

        val data = try {

            JSONArray(
                prefs.getString(
                    "archivos",
                    "[]"
                )
            )

        } catch (
            _: Exception
        ) {

            JSONArray()
        }

        val result =
            mutableListOf<MikuSavedItem>()

        for (
        i in 0 until data.length()
        ) {

            val obj =
                data.optJSONObject(i)

            if (obj != null) {

                val name =
                    obj.optString("name")

                if (
                    name.isNotBlank()
                ) {

                    result.add(
                        MikuSavedItem(
                            name = name,
                            uri = obj.optString(
                                "uri"
                            ),
                            mime = obj.optString(
                                "mime",
                                if (
                                    name.endsWith(
                                        ".mp3",
                                        true
                                    )
                                ) {
                                    "audio/mpeg"
                                } else {
                                    "video/mp4"
                                }
                            )
                        )
                    )
                }

            } else {

                // Historial de versiones antiguas.
                val name =
                    data.optString(i)

                if (
                    name.isNotBlank()
                ) {

                    result.add(
                        MikuSavedItem(
                            name = name,
                            uri = "",
                            mime = if (
                                name.endsWith(
                                    ".mp3",
                                    true
                                )
                            ) {

                                "audio/mpeg"

                            } else {

                                "video/mp4"
                            }
                        )
                    )
                }
            }
        }

        return result
    }

    // ========================================
    // ABRIR MULTIMEDIA
    //
    // CORREGIDO:
    // Es PUBLIC para que MikuScreen()
    // pueda llamarlo.
    // ========================================

    fun openMedia(
        item: MikuSavedItem
    ) {

        val uri = if (
            item.uri.isNotBlank()
        ) {

            Uri.parse(
                item.uri
            )

        } else {

            findSavedMedia(
                item.name
            )
        }

        if (
            uri == null
        ) {

            Toast.makeText(
                this,
                "No se encontró el archivo.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        try {

            val intent = Intent(
                Intent.ACTION_VIEW
            ).apply {

                setDataAndType(
                    uri,
                    item.mime
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            startActivity(
                Intent.createChooser(
                    intent,
                    "Reproducir con"
                )
            )

        } catch (
            error: Exception
        ) {

            Toast.makeText(
                this,
                "No se pudo reproducir: " +
                        error.message.orEmpty(),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ========================================
    // BUSCAR ARCHIVOS DEL HISTORIAL
    // ========================================

    private fun findSavedMedia(
        name: String
    ): Uri? {

        val collection: Uri
        val selection: String
        val arguments: Array<String>

        if (
            Build.VERSION.SDK_INT >= 29
        ) {

            collection =
                MediaStore.Downloads.EXTERNAL_CONTENT_URI

            selection =
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? " +
                        "AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"

            arguments = arrayOf(
                name,
                "${Environment.DIRECTORY_DOWNLOADS}/MikuDownloads/"
            )

        } else {

            collection =
                MediaStore.Files.getContentUri(
                    "external"
                )

            selection =
                "${MediaStore.MediaColumns.DATA}=?"

            val file = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ),
                "MikuDownloads/$name"
            )

            arguments = arrayOf(
                file.absolutePath
            )
        }

        return try {

            contentResolver.query(
                collection,
                arrayOf(
                    BaseColumns._ID
                ),
                selection,
                arguments,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->

                if (
                    cursor.moveToFirst()
                ) {

                    ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(0)
                    )

                } else {

                    null
                }
            }

        } catch (
            _: Exception
        ) {

            null
        }
    }

    // ========================================
    // MENSAJES DE ERROR
    // ========================================

    private fun readableError(
        error: Exception
    ): String {

        val text =
            error.message.orEmpty()

        return when {

            text.contains(
                "No address associated with hostname",
                true
            ) -> {

                "Error DNS. Revisa tu conexión."
            }

            text.contains(
                "Sign in to confirm your age",
                true
            ) -> {

                "YouTube requiere verificación " +
                        "de edad para este vídeo."
            }

            text.contains(
                "HTTP Error 403",
                true
            ) -> {

                "El servidor rechazó " +
                        "la descarga (HTTP 403)."
            }

            text.contains(
                "Unsupported URL",
                true
            ) -> {

                "El enlace no es compatible " +
                        "con el extractor actual."
            }

            else -> {

                text.takeLast(800)
                    .ifBlank {

                        error.javaClass.simpleName
                    }
            }
        }
    }

    // ========================================
    // CIERRE
    // ========================================

    override fun onDestroy() {

        if (busy) {

            cancelDownload()
        }

        scope.cancel()

        super.onDestroy()
    }
}

// ============================================
// INTERFAZ HATSUNE MIKU
// ============================================

@Composable
private fun MikuScreen(
    activity: MainActivity
) {

    var link by rememberSaveable {
        mutableStateOf("")
    }

    var format by rememberSaveable {
        mutableStateOf("MP4")
    }

    // ========================================
    // ACTUALIZAR ENLACES COMPARTIDOS
    // ========================================

    LaunchedEffect(
        activity.sharedRevision
    ) {

        if (
            activity.sharedUrl.isNotBlank()
        ) {

            link =
                activity.sharedUrl
        }
    }

    // ========================================
    // PERMISO PARA ANDROID ANTIGUO
    // ========================================

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                activity.startDownload(
                    link,
                    format
                )

            } else {

                activity.showStatus(
                    "Permiso de almacenamiento denegado."
                )
            }
        }

    // ========================================
    // CONTENIDO
    // ========================================

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        MikuWallpaper()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(16.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            // =================================
            // CABECERA
            // =================================

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xDB062330),
                        RoundedCornerShape(25.dp)
                    )
                    .border(
                        1.dp,
                        MikuCyan.copy(
                            alpha = 0.7f
                        ),
                        RoundedCornerShape(25.dp)
                    )
                    .padding(17.dp),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Text(
                    "01  /  MIKU",
                    color = MikuCyan,
                    fontSize = 12.sp,
                    letterSpacing = 4.sp
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                Image(
                    painter = painterResource(
                        R.drawable.miku_emblem
                    ),

                    contentDescription =
                        "Emblema de Miku",

                    modifier =
                        Modifier.size(115.dp),

                    contentScale =
                        ContentScale.Fit
                )

                Spacer(
                    Modifier.height(9.dp)
                )

                Text(
                    "Miku Downloader",
                    color = MikuWhite,
                    fontSize = 27.sp,
                    fontWeight =
                        FontWeight.ExtraBold,
                    textAlign =
                        TextAlign.Center
                )

                Spacer(
                    Modifier.height(5.dp)
                )

                Text(
                    "Sin anuncios  •  Sin cuenta",
                    color = MikuGray,
                    fontSize = 13.sp
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                MikuLine()
            }

            Spacer(
                Modifier.height(22.dp)
            )

            // =================================
            // ENLACE
            // =================================

            MikuTitle(
                "🔗",
                "Pega o comparte el enlace"
            )

            Spacer(
                Modifier.height(10.dp)
            )

            OutlinedTextField(
                value = link,

                onValueChange = {

                    link = it
                },

                modifier =
                    Modifier.fillMaxWidth(),

                placeholder = {

                    Text(
                        "https://...",
                        color = MikuGray
                    )
                },

                singleLine = true,

                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            KeyboardType.Uri
                    ),

                shape = MikuShape,

                colors =
                    OutlinedTextFieldDefaults.colors(

                        focusedBorderColor =
                            MikuCyan,

                        unfocusedBorderColor =
                            MikuBorder,

                        focusedTextColor =
                            MikuWhite,

                        unfocusedTextColor =
                            MikuWhite,

                        focusedContainerColor =
                            MikuPanel,

                        unfocusedContainerColor =
                            MikuPanel,

                        cursorColor =
                            MikuCyan
                    ),

                trailingIcon = {

                    if (
                        link.isNotEmpty()
                    ) {

                        Text(
                            "✕",
                            color = MikuCyan,

                            modifier = Modifier
                                .clickable {

                                    link = ""
                                }
                                .padding(9.dp)
                        )
                    }
                }
            )

            Spacer(
                Modifier.height(24.dp)
            )

            // =================================
            // FORMATO
            // =================================

            MikuTitle(
                "♫",
                "Formato"
            )

            Spacer(
                Modifier.height(11.dp)
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                MikuFormatButton(
                    name = "MP3",
                    icon = "♫",
                    selected =
                        format == "MP3",
                    modifier =
                        Modifier.weight(1f)
                ) {

                    format = "MP3"
                }

                MikuFormatButton(
                    name = "MP4",
                    icon = "▶",
                    selected =
                        format == "MP4",
                    modifier =
                        Modifier.weight(1f)
                ) {

                    format = "MP4"
                }
            }

            Spacer(
                Modifier.height(23.dp)
            )

            // =================================
            // DESCARGA
            // =================================

            Button(
                onClick = {

                    if (
                        Build.VERSION.SDK_INT <= 28 &&
                        ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {

                        permissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )

                    } else {

                        activity.startDownload(
                            link,
                            format
                        )
                    }
                },

                enabled = !activity.busy,

                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),

                shape = MikuShape,

                colors =
                    ButtonDefaults.buttonColors(

                        containerColor =
                            MikuCyan,

                        contentColor =
                            MikuDark
                    )
            ) {

                Text(
                    if (
                        activity.busy
                    ) {

                        "Procesando..."

                    } else {

                        "↓  Descargar $format"
                    },

                    fontSize = 19.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                Modifier.height(15.dp)
            )

            // =================================
            // PROGRESO
            // =================================

            if (
                activity.busy
            ) {

                LinearProgressIndicator(
                    progress = {

                        activity.progress
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp),

                    color = MikuCyan,

                    trackColor =
                        MikuBorder
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                OutlinedButton(
                    onClick = {

                        activity.cancelDownload()
                    },

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        "Cancelar descarga"
                    )
                }

                Spacer(
                    Modifier.height(12.dp)
                )
            }

            // =================================
            // ESTADO
            // =================================

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xDB071824),
                        MikuShape
                    )
                    .padding(14.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    activity.status,
                    color = MikuGray,
                    fontSize = 12.sp,
                    textAlign =
                        TextAlign.Center
                )
            }

            Spacer(
                Modifier.height(15.dp)
            )

            // =================================
            // CARPETA
            // =================================

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MikuPanel,
                        MikuShape
                    )
                    .border(
                        1.dp,
                        MikuBorder,
                        MikuShape
                    )
                    .padding(12.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    "▣  Download/MikuDownloads",
                    color = MikuWhite,
                    fontSize = 13.sp
                )
            }

            Spacer(
                Modifier.height(24.dp)
            )

            // =================================
            // HISTORIAL
            // =================================

            MikuTitle(
                "▣",
                "Descargas recientes"
            )

            Spacer(
                Modifier.height(12.dp)
            )

            val files = remember(
                activity.historyRevision
            ) {

                // history() ahora es pública.
                activity.history()
            }

            if (
                files.isEmpty()
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MikuPanel,
                            MikuShape
                        )
                        .border(
                            1.dp,
                            MikuBorder,
                            MikuShape
                        )
                        .padding(26.dp),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        "♫",
                        color = MikuCyan,
                        fontSize = 32.sp
                    )

                    Spacer(
                        Modifier.height(7.dp)
                    )

                    Text(
                        "Todavía no hay descargas",
                        color = MikuWhite,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(5.dp)
                    )

                    Text(
                        "Tus archivos aparecerán aquí.",
                        color = MikuGray,
                        fontSize = 12.sp
                    )
                }

            } else {

                files.forEach { item ->

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                bottom = 9.dp
                            )
                            .background(
                                MikuPanel,
                                MikuShape
                            )
                            .border(
                                1.dp,
                                MikuBorder,
                                MikuShape
                            )
                            .clickable {

                                // openMedia() ahora
                                // también es pública.
                                activity.openMedia(
                                    item
                                )
                            }
                            .padding(13.dp),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Text(
                            if (
                                item.mime.startsWith(
                                    "audio/"
                                )
                            ) {

                                "♫"

                            } else {

                                "▶"
                            },

                            fontSize = 25.sp,

                            color = MikuCyan
                        )

                        Spacer(
                            Modifier.width(12.dp)
                        )

                        Column {

                            Text(
                                item.name,
                                color = MikuWhite,
                                fontSize = 13.sp,
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            Spacer(
                                Modifier.height(4.dp)
                            )

                            Text(
                                "▶ Tocar para reproducir",
                                color = MikuCyan,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(
                Modifier.height(18.dp)
            )

            // =================================
            // INFORMACIÓN DEL AUTO-UPDATER
            // =================================

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MikuPanel,
                        MikuShape
                    )
                    .border(
                        1.dp,
                        MikuBorder,
                        MikuShape
                    )
                    .padding(14.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                Column(
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        "MIKU AUTO-UPDATER",
                        color = MikuCyan,
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Spacer(
                        Modifier.height(6.dp)
                    )

                    Text(
                        "Comprobación periódica " +
                                "de actualizaciones.",
                        color = MikuGray,
                        fontSize = 12.sp,
                        textAlign =
                            TextAlign.Center
                    )

                    Spacer(
                        Modifier.height(6.dp)
                    )

                    Text(
                        activity.engineVersion,
                        color = MikuGray,
                        fontSize = 11.sp,
                        textAlign =
                            TextAlign.Center
                    )
                }
            }

            Spacer(
                Modifier.height(15.dp)
            )

            MikuLine()

            Spacer(
                Modifier.height(12.dp)
            )

            Text(
                "MIKU DOWNLOADER • STABLE 1.1",
                color = MikuGray,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            Spacer(
                Modifier.height(12.dp)
            )
        }
    }
}

// ============================================
// WALLPAPER
// ============================================

@Composable
private fun MikuWallpaper() {

    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        Image(
            painter = painterResource(
                R.drawable.miku_wallpaper
            ),

            contentDescription = null,

            modifier =
                Modifier.fillMaxSize(),

            contentScale =
                ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x66000713),
                            Color(0x44000713),
                            Color(0xAA000713)
                        )
                    )
                )
        )
    }
}

// ============================================
// TÍTULOS
// ============================================

@Composable
private fun MikuTitle(
    icon: String,
    title: String
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            icon,
            color = MikuCyan,
            fontSize = 18.sp
        )

        Spacer(
            Modifier.width(8.dp)
        )

        Text(
            title,
            color = MikuWhite,
            fontSize = 17.sp,
            fontWeight =
                FontWeight.Bold
        )
    }
}

// ============================================
// BOTONES MP3 / MP4
// ============================================

@Composable
private fun MikuFormatButton(
    name: String,
    icon: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {

    val background = if (
        selected
    ) {

        Color(0xEE075B60)

    } else {

        MikuPanel
    }

    val border = if (
        selected
    ) {

        MikuCyan

    } else {

        MikuBorder
    }

    Row(
        modifier = modifier
            .height(73.dp)
            .background(
                background,
                MikuShape
            )
            .border(
                if (
                    selected
                ) {
                    2.dp
                } else {
                    1.dp
                },
                border,
                MikuShape
            )
            .clickable(
                onClick = onClick
            )
            .padding(10.dp),

        verticalAlignment =
            Alignment.CenterVertically,

        horizontalArrangement =
            Arrangement.Center
    ) {

        Text(
            icon,
            color = MikuCyan,
            fontSize = 21.sp
        )

        Spacer(
            Modifier.width(8.dp)
        )

        Text(
            name,
            color = MikuWhite,
            fontSize = 19.sp,
            fontWeight =
                FontWeight.Bold
        )

        if (
            selected
        ) {

            Text(
                " ✓",
                color = MikuCyan
            )
        }
    }
}

// ============================================
// LÍNEA NEÓN
// ============================================

@Composable
private fun MikuLine() {

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        MikuCyan,
                        Color.Transparent
                    )
                )
            )
    )
}
