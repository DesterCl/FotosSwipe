package com.fotosswipe.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(vm: PhotoViewModel) {
    val ctx = LocalContext.current
    val perms = remember {
        when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
    fun granted() = perms.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    var hasPerm by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPerm = granted() }

    LaunchedEffect(hasPerm) { if (hasPerm) vm.load() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            if (!hasPerm) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Necesito acceso a tus fotos para que puedas revisarlas.",
                        textAlign = TextAlign.Center, fontSize = 18.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { launcher.launch(perms) }) { Text("Dar permiso") }
                }
            } else {
                MainScreen(vm)
            }
        }
    }
}

@Composable
fun MainScreen(vm: PhotoViewModel) {
    val deck by vm.deck.collectAsStateWithLifecycle()
    val pendingPhotos by vm.pendingPhotos.collectAsStateWithLifecycle()
    val pendingTimes by vm.pending.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()

    var showPending by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    BackHandler(showPending) { showPending = false }

    fun say(msg: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar(msg, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Color.Black
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (showPending) {
                PendingScreen(
                    items = pendingPhotos,
                    times = pendingTimes,
                    onBack = { showPending = false },
                    onRestore = { vm.restore(listOf(it)) },
                    onRestoreAll = { vm.restoreAll() }
                )
            } else {
                if (deck.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("¡Todo revisado! 🎉", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.resetKept() }) { Text("Revisar de nuevo las conservadas") }
                    }
                } else {
                    val pagerState = rememberPagerState { deck.size }
                    VerticalPager(
                        state = pagerState,
                        key = { deck.getOrNull(it)?.id ?: it },
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val photo = deck.getOrNull(page)
                        if (photo != null) {
                            PhotoPage(
                                photo = photo,
                                locked = photo.id in locked,
                                onToggleLock = { vm.toggleLock(photo.id) },
                                onKeep = { vm.keep(photo.id) },
                                onDelete = {
                                    vm.sendToPending(photo.id)
                                    say("Foto en lista de espera (5 min)", "Deshacer") {
                                        vm.restore(listOf(photo.id))
                                    }
                                },
                                onBlocked = { say("🔒 Foto bloqueada: desbloquéala para poder eliminarla") }
                            )
                        }
                    }
                }

                // Barra superior
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color(0x88000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${deck.size} por revisar", fontWeight = FontWeight.Bold)
                        Text("← eliminar   → conservar   ↑↓ navegar", fontSize = 11.sp, color = Color.LightGray)
                    }
                    FilledTonalButton(onClick = { showPending = true }) {
                        Text("Pendientes (${pendingPhotos.size})")
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoPage(
    photo: Photo,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onBlocked: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(photo.id) { Animatable(0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val threshold = widthPx * 0.3f
        val frac = (offsetX.value / widthPx).coerceIn(-1f, 1f)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(photo.id, locked, widthPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val x = offsetX.value
                                when {
                                    x > threshold -> {
                                        offsetX.animateTo(widthPx * 1.3f, tween(180))
                                        onKeep()
                                    }
                                    x < -threshold -> {
                                        if (locked) {
                                            onBlocked()
                                            offsetX.animateTo(0f, spring())
                                        } else {
                                            offsetX.animateTo(-widthPx * 1.3f, tween(180))
                                            onDelete()
                                        }
                                    }
                                    else -> offsetX.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        }
                    )
                }
        ) {
            // Color de fondo según la dirección del gesto
            Box(
                Modifier.fillMaxSize().background(
                    when {
                        frac > 0f -> Color(0xFF2E7D32).copy(alpha = (frac * 1.5f).coerceAtMost(0.7f))
                        frac < 0f -> (if (locked) Color(0xFFF9A825) else Color(0xFFC62828))
                            .copy(alpha = (-frac * 1.5f).coerceAtMost(0.7f))
                        else -> Color.Transparent
                    }
                )
            )

            AsyncImage(
                model = photo.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offsetX.value
                        rotationZ = offsetX.value / widthPx * 12f
                    }
            )

            if (frac > 0.1f) {
                Text(
                    "CONSERVAR ✔", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.CenterStart).padding(24.dp)
                )
            } else if (frac < -0.1f) {
                Text(
                    if (locked) "🔒 BLOQUEADA" else "ELIMINAR ✖",
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
                )
            }
        }

        // Botón de bloqueo
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .clip(RoundedCornerShape(50))
                .background(if (locked) Color(0xFFFFB300) else Color(0xAA000000))
                .clickable { onToggleLock() }
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                if (locked) "🔒 Bloqueada (toca para desbloquear)" else "🔓 Bloquear esta foto",
                color = if (locked) Color.Black else Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun PendingScreen(
    items: List<Photo>,
    times: Map<Long, Long>,
    onBack: () -> Unit,
    onRestore: (Long) -> Unit,
    onRestoreAll: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) TextButton(onClick = onRestoreAll) { Text("Restaurar todas") }
        }
        Text(
            "Lista de espera",
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "Se eliminan definitivamente a los 5 minutos (Android pedirá confirmación).",
            fontSize = 12.sp, color = Color.LightGray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay fotos en espera", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(items, key = { it.id }) { p ->
                    PendingRow(p, times[p.id] ?: System.currentTimeMillis(), onRestore)
                }
            }
        }
    }
}

@Composable
fun PendingRow(p: Photo, sentAt: Long, onRestore: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sentAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val leftMs = (WAIT_MS - (now - sentAt)).coerceIn(0L, WAIT_MS)
    val leftSec = (leftMs + 999) / 1000
    val progress = leftMs.toFloat() / WAIT_MS

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = p.uri, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("⏳ Tiempo restante", fontSize = 12.sp, color = Color.LightGray)
            Text(
                "%d:%02d".format(leftSec / 60, leftSec % 60),
                fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = if (leftSec <= 60) Color(0xFFFF5252) else Color.White
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (leftSec <= 60) Color(0xFFFF5252) else Color(0xFF42A5F5)
            )
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = { onRestore(p.id) }) { Text("Restaurar") }
    }
}
