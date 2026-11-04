
package com.example.mikudownloader.mikudownloadvideo.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

// =============================================
// MIKU DOWNLOADER - ENGINE 1.1
// =============================================
//
// - Motor propio MikuEngine
// - Descargas MP3 y MP4
// - Guardado en Download/MikuDownloads
// - Historial local
// - Reproducción multimedia
// - Enlaces compartidos
// - Wallpaper y emblema de Hatsune Miku
//
// =============================================

// COLORES

private val Fondo = Color(0xFF06121E)
private val Turquesa = Color(0xFF39E6DB)
private val Texto = Color(0xFFF0FFFF)
private val Secundario = Color(0xFFBDD7E0)
private val Borde = Color(0xFF387D8B)
private val Panel = Color(0xDD081D29)

private val Forma = RoundedCornerShape(18.dp)

// =============================================
// ACTIVIDAD PRINCIPAL
// =============================================

class MainActivity : ComponentActivity() {

    // Inicialización explícita.
    // Evita el error relacionado con by lazy.
    private lateinit var engine: MikuEngine

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )

    var busy by mutableStateOf(false)
        private set

    var updating by mutableStateOf(false)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var status by mutableStateOf(
        "Miku Engine listo"
    )
        private set

    var engineVersion by mutableStateOf(
        "Miku Engine 1.1"
    )
        private set

    var historyRevision by mutableIntStateOf(0)
        private set

    var sharedUrl by mutableStateOf("")
        private set

    var sharedRevision by mutableIntStateOf(0)
        private set

    // =========================================
    // INICIAR APLICACIÓN
    // =========================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        // Crear el motor cuando la actividad existe.
        engine = MikuEngine(applicationContext)

        enableEdgeToEdge()

        readSharedIntent(intent)

        setContent {

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Turquesa,
                    background = Fondo,
                    surface = Panel,
                    onSurface = Texto
                )
            ) {

                MikuScreen(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)
        readSharedIntent(intent)
    }

    // =========================================
    // RECIBIR ENLACES COMPARTIDOS
    // =========================================

    private fun readSharedIntent(intent: Intent?) {

        if (intent?.action != Intent.ACTION_SEND) {
            return
        }

        val text = intent.getStringExtra(
            Intent.EXTRA_TEXT
        ).orEmpty()

        val url = Regex("https?://\\S+")
            .find(text)
            ?.value
            ?.trimEnd('.', ',', ')', ']')
            .orEmpty()

        if (url.isNotBlank()) {
            sharedUrl = url
            sharedRevision++
        }
    }

    fun showStatus(message: String) {
        status = message
    }

    // =========================================
    // DESCARGAS MEDIANTE MIKU ENGINE
    // =========================================

    fun startDownload(
        url: String,
        format: String
    ) {

        if (busy) {
            return
        }

        if (url.isBlank()) {
            status = "Pega primero un enlace."
            return
        }

        if (format != "MP3" && format != "MP4") {
            status = "Formato no compatible."
            return
        }

        busy = true
        updating = false
        progress = 0f

        status = "Iniciando Miku Engine..."

        scope.launch {

            try {

                val media = engine.download(
                    originalUrl = url,
                    format = format,
                    onProgress = { value, message ->

                        runOnUiThread {

                            progress = value.coerceIn(
                                0f,
                                1f
                            )

                            status = message
                        }
                    }
                )

                saveHistory(media)

                progress = 1f

                status =
                    "Descarga completada:\n${media.name}"

            } catch (e: Exception) {

                val message = e.message.orEmpty()

                status = when {

                    message.contains(
                        "cancelada",
                        ignoreCase = true
                    ) -> {
                        "Descarga cancelada."
                    }

                    message.contains(
                        "Sign in to confirm your age",
                        ignoreCase = true
                    ) -> {
                        "YouTube exige verificar la edad " +
                                "para acceder a este vídeo."
                    }

                    message.contains(
                        "No address associated with hostname",
                        ignoreCase = true
                    ) -> {
                        "Error DNS. Comprueba Internet."
                    }

                    message.contains(
                        "HTTP 403",
                        ignoreCase = true
                    ) -> {
                        "El servidor rechazó la descarga."
                    }

                    message.contains(
                        "Unsupported URL",
                        ignoreCase = true
                    ) -> {
                        "El enlace no es compatible " +
                                "con el extractor disponible."
                    }

                    else -> {
                        "Error:\n" +
                                message.takeLast(650).ifBlank {
                                    "No fue posible completar " +
                                            "la descarga."
                                }
                    }
                }

            } finally {

                busy = false
            }
        }
    }

    // =========================================
    // CANCELAR DESCARGA
    // =========================================

    fun cancelDownload() {

        if (!busy || updating) {
            return
        }

        status = "Cancelando descarga..."

        engine.cancel()
    }

    // =========================================
    // ACTUALIZAR EXTRACTOR
    // =========================================

    fun updateExtractor() {

        if (busy) {
            return
        }

        busy = true
        updating = true

        status = "Actualizando yt-dlp..."

        scope.launch {

            try {

                val result = withContext(
                    Dispatchers.IO
                ) {

                    YoutubeDL.init(applicationContext)
                    FFmpeg.init(applicationContext)

                    val update =
                        YoutubeDL.updateYoutubeDL(
                            applicationContext,
                            YoutubeDL.UpdateChannel.STABLE
                        )

                    val version =
                        YoutubeDL.versionName(
                            applicationContext
                        ) ?: "No determinada"

                    Pair(update, version)
                }

                engineVersion =
                    "Miku Engine 1.1 · yt-dlp ${result.second}"

                status = when (result.first) {

                    YoutubeDL.UpdateStatus.DONE -> {
                        "Motor actualizado.\n" +
                                engineVersion
                    }

                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                        "El actualizador informa " +
                                "que yt-dlp está al día.\n" +
                                engineVersion
                    }

                    else -> {
                        "Comprobación terminada.\n" +
                                engineVersion
                    }
                }

            } catch (e: Exception) {

                status =
                    "Error al actualizar:\n" +
                            e.message.orEmpty()
                                .takeLast(500)

            } finally {

                updating = false
                busy = false
            }
        }
    }

    // =========================================
    // GUARDAR HISTORIAL
    // =========================================

    private fun saveHistory(media: MikuMedia) {

        val prefs = getSharedPreferences(
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

        } catch (_: Exception) {
            JSONArray()
        }

        val updated = JSONArray()

        updated.put(
            JSONObject().apply {

                put(
                    "name",
                    media.name
                )

                put(
                    "uri",
                    media.uri
                )

                put(
                    "mime",
                    media.mime
                )
            }
        )

        // Guardar máximo 30 elementos recientes.
        for (i in 0 until minOf(29, previous.length())) {
            updated.put(previous.get(i))
        }

        prefs.edit()
            .putString(
                "archivos",
                updated.toString()
            )
            .apply()

        historyRevision++
    }

    // =========================================
    // LEER HISTORIAL
    // =========================================

    fun history(): List<MikuMedia> {

        val prefs = getSharedPreferences(
            "miku_historial",
            MODE_PRIVATE
        )

        val array = try {

            JSONArray(
                prefs.getString(
                    "archivos",
                    "[]"
                )
            )

        } catch (_: Exception) {
            JSONArray()
        }

        val result = mutableListOf<MikuMedia>()

        for (i in 0 until array.length()) {

            val item = array.optJSONObject(i)

            if (item != null) {

                result.add(
                    MikuMedia(
                        name = item.optString("name"),
                        uri = item.optString("uri"),
                        mime = item.optString(
                            "mime",
                            "video/mp4"
                        )
                    )
                )

            } else {

                // Compatibilidad con versiones previas
                // que guardaban solamente el nombre.
                val oldName = array.optString(i)

                if (oldName.isNotBlank()) {

                    val mime = if (
                        oldName.endsWith(".mp3", true)
                    ) {
                        "audio/mpeg"
                    } else {
                        "video/mp4"
                    }

                    result.add(
                        MikuMedia(
                            name = oldName,
                            uri = "",
                            mime = mime
                        )
                    )
                }
            }
        }

        return result
    }

    // =========================================
    // REPRODUCIR MP3 O MP4
    // =========================================

    fun openMedia(media: MikuMedia) {

        val uri = if (media.uri.isNotBlank()) {

            Uri.parse(media.uri)

        } else {

            findOldMedia(media.name)
        }

        if (uri == null) {

            Toast.makeText(
                this,
                "No se encontró el archivo.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        try {

            val viewIntent = Intent(
                Intent.ACTION_VIEW
            ).apply {

                setDataAndType(
                    uri,
                    media.mime
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            startActivity(
                Intent.createChooser(
                    viewIntent,
                    "Reproducir con"
                )
            )

        } catch (_: ActivityNotFoundException) {

            Toast.makeText(
                this,
                "No hay un reproductor compatible.",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "No se pudo abrir: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =========================================
    // BUSCAR ARCHIVOS DE VERSIONES ANTIGUAS
    // =========================================

    private fun findOldMedia(
        name: String
    ): Uri? {

        if (Build.VERSION.SDK_INT < 29) {
            return null
        }

        val collection =
            MediaStore.Downloads.EXTERNAL_CONTENT_URI

        return try {

            contentResolver.query(

                collection,

                arrayOf(
                    BaseColumns._ID
                ),

                "${MediaStore.MediaColumns.DISPLAY_NAME}=? " +
                        "AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",

                arrayOf(
                    name,
                    "${Environment.DIRECTORY_DOWNLOADS}/MikuDownloads/"
                ),

                "${MediaStore.MediaColumns.DATE_ADDED} DESC"

            )?.use { cursor ->

                if (cursor.moveToFirst()) {

                    ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(0)
                    )

                } else {
                    null
                }
            }

        } catch (_: Exception) {
            null
        }
    }

    // =========================================
    // CERRAR ACTIVIDAD
    // =========================================

    override fun onDestroy() {

        if (
            this::engine.isInitialized &&
            busy &&
            !updating
        ) {
            engine.cancel()
        }

        scope.cancel()

        super.onDestroy()
    }
}

// =============================================
// PANTALLA PRINCIPAL
// =============================================

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

    // Recibir enlaces compartidos.
    LaunchedEffect(activity.sharedRevision) {

        if (activity.sharedUrl.isNotBlank()) {
            link = activity.sharedUrl
        }
    }

    // Permiso para Android 8 y 9.
    val legacyPermission =
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

    // =========================================
    // ESTRUCTURA VISUAL
    // =========================================

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
            // CABECERA MIKU
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
                        Turquesa.copy(alpha = 0.7f),
                        RoundedCornerShape(25.dp)
                    )
                    .padding(17.dp),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Text(
                    text = "01  /  MIKU",
                    color = Turquesa,
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
                        "Emblema de Hatsune Miku",

                    modifier = Modifier.size(115.dp),

                    contentScale = ContentScale.Fit
                )

                Spacer(
                    Modifier.height(9.dp)
                )

                Text(
                    text = "Miku Downloader",
                    color = Texto,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(
                    Modifier.height(5.dp)
                )

                Text(
                    text = "Sin anuncios  •  Sin cuenta",
                    color = Secundario,
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
            // CAMPO DE ENLACE
            // =================================

            MikuTitle(
                icon = "🔗",
                title = "Pega o comparte el enlace"
            )

            Spacer(
                Modifier.height(10.dp)
            )

            OutlinedTextField(
                value = link,

                onValueChange = {
                    link = it
                },

                modifier = Modifier.fillMaxWidth(),

                placeholder = {

                    Text(
                        "https://...",
                        color = Secundario
                    )
                },

                singleLine = true,

                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri
                ),

                shape = Forma,

                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Turquesa,
                    unfocusedBorderColor = Borde,
                    focusedTextColor = Texto,
                    unfocusedTextColor = Texto,
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel,
                    cursorColor = Turquesa
                ),

                trailingIcon = {

                    if (link.isNotEmpty()) {

                        Text(
                            text = "✕",
                            color = Turquesa,

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
                icon = "♫",
                title = "Formato"
            )

            Spacer(
                Modifier.height(11.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                FormatButton(
                    name = "MP3",
                    icon = "♫",
                    selected = format == "MP3",
                    modifier = Modifier.weight(1f)
                ) {
                    format = "MP3"
                }

                FormatButton(
                    name = "MP4",
                    icon = "▶",
                    selected = format == "MP4",
                    modifier = Modifier.weight(1f)
                ) {
                    format = "MP4"
                }
            }

            Spacer(
                Modifier.height(23.dp)
            )

            // =================================
            // BOTÓN DE DESCARGA
            // =================================

            Button(
                onClick = {

                    if (
                        Build.VERSION.SDK_INT <= 28 &&
                        ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {

                        legacyPermission.launch(
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

                shape = Forma,

                colors = ButtonDefaults.buttonColors(
                    containerColor = Turquesa,
                    contentColor = Fondo
                )
            ) {

                Text(
                    text = if (activity.busy) {
                        "Procesando..."
                    } else {
                        "↓  Descargar $format"
                    },

                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(
                Modifier.height(15.dp)
            )

            // =================================
            // PROGRESO
            // =================================

            if (
                activity.busy &&
                !activity.updating
            ) {

                LinearProgressIndicator(
                    progress = {
                        activity.progress
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp),

                    color = Turquesa,
                    trackColor = Borde
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                OutlinedButton(
                    onClick = {
                        activity.cancelDownload()
                    },

                    modifier = Modifier.fillMaxWidth()
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
                        Forma
                    )
                    .padding(14.dp),

                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = activity.status,
                    color = Secundario,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(
                Modifier.height(15.dp)
            )

            // =================================
            // DIRECTORIO
            // =================================

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Panel,
                        Forma
                    )
                    .border(
                        1.dp,
                        Borde,
                        Forma
                    )
                    .padding(12.dp),

                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = "▣  Download/MikuDownloads",
                    color = Texto,
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
                icon = "▣",
                title = "Descargas recientes"
            )

            Spacer(
                Modifier.height(12.dp)
            )

            val files = remember(
                activity.historyRevision
            ) {
                activity.history()
            }

            if (files.isEmpty()) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Panel,
                            Forma
                        )
                        .border(
                            1.dp,
                            Borde,
                            Forma
                        )
                        .padding(26.dp),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "♫",
                        color = Turquesa,
                        fontSize = 32.sp
                    )

                    Spacer(
                        Modifier.height(7.dp)
                    )

                    Text(
                        text = "Todavía no hay descargas",
                        color = Texto,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(5.dp)
                    )

                    Text(
                        text = "Tus archivos aparecerán aquí.",
                        color = Secundario,
                        fontSize = 12.sp
                    )
                }

            } else {

                files.forEach { media ->

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                bottom = 9.dp
                            )
                            .background(
                                Panel,
                                Forma
                            )
                            .border(
                                1.dp,
                                Borde,
                                Forma
                            )
                            .clickable {

                                activity.openMedia(
                                    media
                                )
                            }
                            .padding(13.dp),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Text(
                            text = if (
                                media.mime.startsWith(
                                    "audio/"
                                )
                            ) {
                                "♫"
                            } else {
                                "▶"
                            },

                            fontSize = 25.sp,
                            color = Turquesa
                        )

                        Spacer(
                            Modifier.width(12.dp)
                        )

                        Column {

                            Text(
                                text = media.name,
                                color = Texto,
                                fontSize = 13.sp,
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            Spacer(
                                Modifier.height(4.dp)
                            )

                            Text(
                                text = "▶ Tocar para reproducir",
                                color = Turquesa,
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
            // ACTUALIZACIÓN
            // =================================

            OutlinedButton(
                onClick = {

                    activity.updateExtractor()
                },

                enabled = !activity.busy,

                modifier = Modifier.fillMaxWidth()
            ) {

                Text(
                    text = "Actualizar extractores",
                    color = Turquesa
                )
            }

            Spacer(
                Modifier.height(8.dp)
            )

            Text(
                text = activity.engineVersion,
                color = Secundario,
                fontSize = 11.sp
            )

            Spacer(
                Modifier.height(15.dp)
            )

            MikuLine()

            Spacer(
                Modifier.height(12.dp)
            )

            Text(
                text = "MIKU DOWNLOADER  •  ENGINE 1.1",
                color = Secundario,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            Spacer(
                Modifier.height(12.dp)
            )
        }
    }
}

// =============================================
// WALLPAPER MIKU
// =============================================

@Composable
private fun MikuWallpaper() {

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        Image(
            painter = painterResource(
                R.drawable.miku_wallpaper
            ),

            contentDescription = null,

            modifier = Modifier.fillMaxSize(),

            contentScale = ContentScale.Crop
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

// =============================================
// TÍTULOS DE SECCIONES
// =============================================

@Composable
private fun MikuTitle(
    icon: String,
    title: String
) {

    Row(
        modifier = Modifier.fillMaxWidth(),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = icon,
            color = Turquesa,
            fontSize = 18.sp
        )

        Spacer(
            Modifier.width(8.dp)
        )

        Text(
            text = title,
            color = Texto,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// =============================================
// SELECTORES DE FORMATO
// =============================================

@Composable
private fun FormatButton(
    name: String,
    icon: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    val background = if (selected) {
        Color(0xEE075B60)
    } else {
        Panel
    }

    val borderColor = if (selected) {
        Turquesa
    } else {
        Borde
    }

    Row(
        modifier = modifier
            .height(73.dp)
            .background(
                background,
                Forma
            )
            .border(
                if (selected) 2.dp else 1.dp,
                borderColor,
                Forma
            )
            .clickable(
                onClick = onClick
            )
            .padding(10.dp),

        verticalAlignment = Alignment.CenterVertically,

        horizontalArrangement = Arrangement.Center
    ) {

        Text(
            text = icon,
            color = Turquesa,
            fontSize = 21.sp
        )

        Spacer(
            Modifier.width(8.dp)
        )

        Text(
            text = name,
            color = Texto,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        if (selected) {

            Text(
                text = " ✓",
                color = Turquesa
            )
        }
    }
}

// =============================================
// LÍNEA DECORATIVA NEÓN
// =============================================

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
                        Turquesa,
                        Color.Transparent
                    )
                )
            )
}
}
