package com.gti.rfid

import android.Manifest
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gg.reader.api.dal.GClient
import com.gg.reader.api.dal.communication.HandlerRevCommand
import com.gg.reader.api.protocol.gx.*
import com.gg.reader.api.utils.FrequencyUtils
import com.gg.reader.api.utils.HexUtils
import com.peripheral.ble.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Hashtable
import java.util.UUID

private const val TAG = "RFID"
private val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

// Logging Return Code
private fun Message.rt(): Int = getRtCode().toInt() and 0xFF
private fun Message.rtStr(): String = "rtCode=${rt()} rtMsg=${getRtMsg()}"

// Optional: log every command's result
private fun <T : Message> GClient.sendDbg(m: T): T =
m.also(::sendSynMsg).also { Log.e(TAG, "${m.javaClass.simpleName}: ${it.rtStr()}") }

private const val EU_RANGE_IDX = 4
private const val ANT1 = 1

private fun ok(msg: Message) = (msg.getRtCode().toInt() and 0xFF) == 0
private fun u8(x: Any?) = when (x) {
  is Byte -> x.toInt() and 0xFF
  is Short -> x.toInt() and 0xFFFF
  is Int -> x
  is Long -> x.toInt()
  is Number -> x.toInt()
  else -> 0
}

data class FreqPower(
  val rangeIdx: Int,
  val freqs: List<String>,
  val powerAnt1: Int,
)

private fun getFreqRangeIndex(c: GClient): Int? =
MsgBaseGetFreqRange().also(c::sendSynMsg).let { if (ok(it)) u8(it.getFreqRangeIndex()) else null }

private fun getFrequency(c: GClient): MsgBaseGetFrequency? =
MsgBaseGetFrequency().also(c::sendSynMsg).let { if (ok(it)) it else null }

private fun getPowerAnt1(c: GClient): Int? =
MsgBaseGetPower().also(c::sendSynMsg).let { m ->
  if (!ok(m)) null else m.getDicPower()?.get(ANT1)?.let(::u8)
}

private suspend fun queryFreqPower(): FreqPower? = withContext(Dispatchers.IO) {
  val c = Rfid.client ?: return@withContext null

  val rangeIdx = getFreqRangeIndex(c) ?: return@withContext null
  val f = getFrequency(c) ?: return@withContext null
  val p = getPowerAnt1(c) ?: return@withContext null

  val table = runCatching { FrequencyUtils.indexGetFre(rangeIdx) }.getOrNull().orEmpty()
  val cursors = f.getListFreqCursor().orEmpty()
  val picked = cursors.mapNotNull(table::getOrNull)

  FreqPower(rangeIdx = rangeIdx, freqs = picked, powerAnt1 = p.coerceIn(0, 33))
}

private suspend fun setEuFrequencyAndPower(power: Int = 30): String = withContext(Dispatchers.IO) {
  val c = Rfid.client ?: return@withContext "no client"

  c.sendDbg(MsgBaseStop())

  c.sendDbg(MsgBaseSetFreqRange().apply { setFreqRangeIndex(EU_RANGE_IDX) })
  .also { if (!ok(it)) return@withContext "SetFreqRange failed: ${it.rtStr()}" }

  c.sendDbg(MsgBaseGetFreqRange())
  .also { if (!ok(it)) return@withContext "GetFreqRange failed: ${it.rtStr()}" }
  .also {
    val got = u8(it.getFreqRangeIndex())
    if (got != EU_RANGE_IDX) return@withContext "FreqRange verify failed: want=$EU_RANGE_IDX got=$got"
  }

  val map = Hashtable<Int, Int>().apply { put(ANT1, power.coerceIn(0, 33)) }
  c.sendDbg(MsgBaseSetPower().apply { setDicPower(map) })
  .also { if (!ok(it)) return@withContext "SetPower failed: ${it.rtStr()}" }

  "EU range + power ok (rangeIdx=$EU_RANGE_IDX power=$power)"
}

class Main : ComponentActivity() {
  private var c: BluetoothCentralManager? = null
  private var p: BluetoothPeripheral? = null
  private var d: BleDevice? = null

  private var on by mutableStateOf(false)
  private var busy by mutableStateOf(false)
  private var err by mutableStateOf<String?>(null)

  private var cont: (() -> Unit)? = null
  private val perms = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { r ->
    (cont.also { cont = null } ?: return@registerForActivityResult)
    .let { if (r.values.all { it }) it() else { busy = false; err = "permission denied" } }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    runCatching { if (Rfid.client == null) Rfid.client = GClient() }
    .onFailure { Log.e(TAG, "GClient", it); err = "GClient failed" }

    setContent {
      var screen by remember { mutableStateOf(0) } 
      val scope = rememberCoroutineScope()
      var fp by remember { mutableStateOf<FreqPower?>(null) }

      fun query() = scope.launch {
        busy = true
        err = null
        fp = queryFreqPower().also { if (it == null) err = "query failed (see logcat)" }
        busy = false
      }

      fun setEU() = scope.launch {
        busy = true
        err = null
        val res = setEuFrequencyAndPower(power = 30)
        fp = queryFreqPower()
        err = res
        busy = false
      }

      MaterialTheme {
        when (screen) {
          0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

              Button(
                onClick = { err = null; if (on) close() else ensure(::connect) },
                enabled = !busy,
                modifier = Modifier.padding(24.dp).heightIn(min = 52.dp).widthIn(min = 240.dp)
              ) { Text(if (on) "Disconnect" else "Connect") }

              if (on) {
                Button(
                  onClick = { if (!busy) query() },
                  enabled = !busy,
                  modifier = Modifier.widthIn(min = 240.dp).heightIn(min = 52.dp)
                ) { Text("Query Freq + Power") }

                Button(
                  onClick = { if (!busy) setEU() },
                  enabled = !busy,
                  modifier = Modifier.padding(top = 12.dp).widthIn(min = 240.dp).heightIn(min = 52.dp)
                ) { Text("Set EU + Power") }

                Button(
                  onClick = { screen = 1 },
                  enabled = !busy,
                  modifier = Modifier.padding(top = 12.dp).widthIn(min = 240.dp).heightIn(min = 52.dp)
                ) { Text("Tag Reader") }
              }

              err?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
              }

              fp?.let {
                Spacer(Modifier.height(12.dp))
                Text("FreqRangeIdx: ${it.rangeIdx}")
                Text("Frequency: ${if (it.freqs.isEmpty()) "—" else it.freqs.joinToString()}")
                Text("Power(ANT1): ${it.powerAnt1}")
              }
            }
          }

          1 -> Reader(onBack = { screen = 0 })
        }
      }
    }
  }

  private fun ensure(ok: () -> Unit) {
    cont = ok
    perms.launch(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
      ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    )
  }

  private fun svc(name: String) = when {
    name.contains("Handheld") -> NUS
    name.contains("RFID-reader") -> FFE0
    else -> null
  }

  private val cb = object : BluetoothCentralManagerCallback() {
    override fun onDiscoveredPeripheral(x: BluetoothPeripheral, s: ScanResult) {
      val n = x.name?.takeIf { it.isNotBlank() } ?: return
      val want = svc(n) ?: return
      val cc = c ?: return

      cc.stopScan()
      busy = true

      d = BleDevice(cc, x).apply {
        setMtu(255)
        setServiceCallback(object : BleServiceCallback() {
          override fun onServicesDiscovered(pp: BluetoothPeripheral) {
            val service = pp.services?.firstOrNull { it.uuid == want }
            ?: run { err = "svc $want"; close(); return }
            findCharacteristic(service)
            p = pp
            setMtu(255)
          }
        })
      }

      (Rfid.client ?: run { err = "no client"; close(); return }).apply {
        openBleDevice(d)
        setOnRevCommandListener(object : HandlerRevCommand {
          override fun revCommand(bytes: ByteArray) { Log.e(TAG, HexUtils.bytes2HexString(bytes)) }
        })
      }
    }

    override fun onConnectedPeripheral(x: BluetoothPeripheral) { on = true; busy = false }
    override fun onDisconnectedPeripheral(x: BluetoothPeripheral, st: HciStatus) { on = false; busy = false }
  }

  private fun connect() {
    busy = true
    c = BluetoothCentralManager(this, cb, Handler(Looper.getMainLooper()))
    .also { it.scanForPeripherals() }
  }

  private fun close() {
    busy = true
    c?.let { cc -> p?.let(cc::cancelConnection); cc.close() }
    c = null; p = null; d = null
    on = false; busy = false
  }

  override fun onDestroy() {
    super.onDestroy()
    if (!isFinishing) return
    Rfid.client?.close()
    Rfid.client = null
    close()
  }
}

