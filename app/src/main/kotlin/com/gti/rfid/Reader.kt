// Reader.kt
package com.gti.rfid

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gg.reader.api.dal.HandlerOnKeyEvent
import com.gg.reader.api.dal.HandlerTagEpcLog
import com.gg.reader.api.dal.HandlerTagEpcOver
import com.gg.reader.api.protocol.gx.EnumG
import com.gg.reader.api.protocol.gx.LogBaseEpcInfo
import com.gg.reader.api.protocol.gx.LogBaseEpcOver
import com.gg.reader.api.protocol.gx.MsgBaseInventoryEpc
import com.gg.reader.api.protocol.gx.MsgBaseSetTagLog as GxMsgBaseSetTagLog
import com.gg.reader.api.protocol.gx.MsgBaseStop
import com.gg.reader.api.protocol.gx.MsgHDSystemSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

private enum class InvMode { Single, Loop }

private data class TagRow(
  val epc9: String,
  var count: Long = 1,
  var last: Long = System.currentTimeMillis(),
)

private fun normalizeEpc000_9(s: String?): String? =
s?.takeIf { it.length >= 12 && it.startsWith("000") }?.substring(3, 12)

private fun csv(v: Any?): String {
  val s = v?.toString().orEmpty()
  val q = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
  return if (q) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

private fun buildCsv(rows: List<TagRow>, df: SimpleDateFormat): String = buildString(1024) {
  append("EPC,Count,Last\n")
  for (t in rows) {
    append(csv(t.epc9)).append(',')
    append(csv(t.count)).append(',')
    append(csv(df.format(Date(t.last)))).append('\n')
  }
}

@Composable
fun Reader(onBack: () -> Unit) {
  val client = Rfid.client
  val scope = rememberCoroutineScope()
  val main = remember { Handler(Looper.getMainLooper()) }
  val ctx = LocalContext.current

  var invMode by remember { mutableStateOf(InvMode.Single) }
  var isReading by remember { mutableStateOf(false) }
  var buzzerOn by remember { mutableStateOf(true) }

  val tags = remember { LinkedHashMap<String, TagRow>() }
  var uiTick by remember { mutableStateOf(0) }
  var menuOpen by remember { mutableStateOf(false) }

  val df = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

  fun clear() { tags.clear(); uiTick++ }

  fun exportCsvToDownloads() {
    val snap = tags.values.toList()
    if (snap.isEmpty()) return

    val ts = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
    val fileName = "obn_$ts.csv"
    val bytes = buildCsv(snap, df).toByteArray(StandardCharsets.UTF_8)

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val cv = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, fileName)
          put(MediaStore.Downloads.MIME_TYPE, "text/csv")
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri: Uri = ctx.contentResolver
        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        ?: error("MediaStore insert failed")

        ctx.contentResolver.openOutputStream(uri).use { os ->
          requireNotNull(os) { "openOutputStream failed" }
          os.write(bytes)
        }
      } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create Downloads dir")
        val out = File(dir, fileName)
        FileOutputStream(out).use { it.write(bytes) }
      }
    } catch (e: Exception) {
      Log.e("RFID", "export failed", e)
    }
  }

  suspend fun startWith(mode: InvMode) = withContext(Dispatchers.IO) {
    val c = client ?: return@withContext

    if (mode == InvMode.Loop) {
      c.sendSynMsg(GxMsgBaseSetTagLog().apply {
        setRepeatedTime(0)
        setRssiTV(0)
      })
    }

    val msg = MsgBaseInventoryEpc().apply {
      setAntennaEnable(EnumG.AntennaNo_1)
      setInventoryMode(
        if (mode == InvMode.Single) EnumG.InventoryMode_Single
        else EnumG.InventoryMode_Inventory
      )
    }

    c.sendSynMsg(msg)

    withContext(Dispatchers.Main) {
      invMode = mode
      isReading = (msg.getRtCode().toInt() and 0xFF) == 0
    }
  }

  suspend fun stopNow() = withContext(Dispatchers.IO) {
    val c = client ?: return@withContext
    c.sendSynMsg(MsgBaseStop())
    withContext(Dispatchers.Main) { isReading = false }
  }

  suspend fun setBuzzer(on: Boolean) = withContext(Dispatchers.IO) {
    val c = client ?: return@withContext
    val msg = MsgHDSystemSet().apply { setBuzzerSwitch(if (on) 1 else 0) }
    c.sendSynMsg(msg)
    if ((msg.getRtCode().toInt() and 0xFF) == 0) withContext(Dispatchers.Main) { buzzerOn = on }
  }

  DisposableEffect(client) {
    val c = client ?: return@DisposableEffect onDispose { }

    val prevLog = c.onTagEpcLog
    val prevOver = c.onTagEpcOver
    val prevKey = c.onKeyEvent

    c.onTagEpcLog = HandlerTagEpcLog { _, info: LogBaseEpcInfo? ->
      info ?: return@HandlerTagEpcLog
      val epc9 = normalizeEpc000_9(info.epc) ?: return@HandlerTagEpcLog
      val key = (info.tid ?: "") + epc9

      tags[key]?.let {
        it.count += 1
        it.last = System.currentTimeMillis()
      } ?: run {
        tags[key] = TagRow(epc9 = epc9, count = 1, last = System.currentTimeMillis())
      }
      main.post { uiTick++ }
    }

    c.onTagEpcOver = HandlerTagEpcOver { _, _: LogBaseEpcOver? ->
      main.post { isReading = false }
    }

    c.onKeyEvent = HandlerOnKeyEvent { keycode, _ ->
      if (keycode == 132 || keycode == 131) main.post { isReading = !isReading }
    }

    onDispose {
      c.onTagEpcLog = prevLog
      c.onTagEpcOver = prevOver
      c.onKeyEvent = prevKey
    }
  }

  LaunchedEffect(isReading) {
    if (client == null) { isReading = false; return@LaunchedEffect }
    if (isReading) startWith(invMode) else stopNow()
  }

  LaunchedEffect(Unit) { if (client != null) setBuzzer(true) }
  LaunchedEffect(buzzerOn) { if (client != null) setBuzzer(buzzerOn) }

  BackHandler { isReading = false; onBack() }

  val rows = remember(uiTick) { tags.values.toList() }
  val totalReads = remember(uiTick) { rows.fold(0L) { a, t -> a + t.count } }
  val unique = rows.size
  val modeText = if (invMode == InvMode.Single) "Single" else "Inventory"

  Scaffold(
    topBar = {
      Surface(
        shadowElevation = 2.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
      ) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          TextButton(onClick = { isReading = false; onBack() }) { Text("Back") }
          Text("Reader", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))

          Box {
            TextButton(onClick = { menuOpen = true }) { Text("Menu") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              DropdownMenuItem(
                text = { Text("Export CSV") },
                onClick = { menuOpen = false; exportCsvToDownloads() }
              )
              DropdownMenuItem(
                text = { Text("Clear") },
                onClick = { menuOpen = false; clear() }
              )
            }
          }
        }
      }
    }
  ) { pad ->
    Column(
      Modifier.padding(pad).fillMaxSize().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxSize()) {
          val hs = rememberScrollState()

          Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().horizontalScroll(hs)) {
              TableHeader()
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.epc9 + it.last }) { t ->
                  TableRow(
                    epc = t.epc9,
                    count = t.count,
                    time = df.format(Date(t.last)),
                  )
                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
              }
            }

            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
              modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
              Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text("Mode: $modeText", style = MaterialTheme.typography.bodyMedium)
                Text("Unique: $unique", style = MaterialTheme.typography.bodyMedium)
                Text("Reads: $totalReads", style = MaterialTheme.typography.bodyMedium)
              }
            }
          }
        }
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipBtn(
          text = if (buzzerOn) "Buzzer On" else "Buzzer Off",
          selected = buzzerOn,
          enabled = client != null,
          modifier = Modifier.weight(1f)
        ) { buzzerOn = !buzzerOn }

        ChipBtn(
          text = if (invMode == InvMode.Single) "Single" else "Inventory",
          selected = invMode == InvMode.Loop,
          enabled = client != null,
          modifier = Modifier.weight(1f)
        ) {
          val next = if (invMode == InvMode.Single) InvMode.Loop else InvMode.Single
          scope.launch {
            if (isReading) { stopNow(); startWith(next) } else invMode = next
          }
        }
      }

      ChipBtn(
        text = if (isReading) "Stop" else "Scan",
        selected = isReading,
        enabled = client != null,
        modifier = Modifier.fillMaxWidth()
      ) { isReading = !isReading }
    }
  }
}

@Composable
private fun ChipBtn(
  text: String,
  selected: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  SuggestionChip(
    onClick = { if (enabled) onClick() },
    label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    enabled = enabled,
    modifier = modifier.heightIn(min = 44.dp),
    colors = SuggestionChipDefaults.suggestionChipColors(
      containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
      labelColor = MaterialTheme.colorScheme.onSurface,
      disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
      disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  )
}

@Composable
private fun TableHeader() {
  Row(
    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
    .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Cell("EPC(9)", 120.dp, true)
    Cell("Count", 80.dp, true)
    Cell("Last", 90.dp, true)
  }
}

@Composable
private fun TableRow(epc: String, count: Long, time: String) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Cell(epc, 120.dp)
    Cell(count.toString(), 80.dp)
    Cell(time, 100.dp)
  }
}

@Composable
private fun Cell(text: String, w: Dp, bold: Boolean = false) {
  Text(
    text,
    modifier = Modifier.width(w),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
    color = if (bold) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
  )
}

