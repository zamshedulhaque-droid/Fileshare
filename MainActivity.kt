package com.fastshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private lateinit var mgr: WifiP2pManager
    private lateinit var chan: WifiP2pManager.Channel
    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private var picked: List<Uri> = emptyList()
    private var sending = false
    private var recv: FileReceiver? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var afterPerm: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) afterPerm?.invoke() else say("পারমিশন লাগবে")
    }
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { picked = uris; say("${uris.size}টি ফাইল বাছাই হয়েছে। রিসিভার খুঁজছে..."); discover() }
    }

    private val br = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> mgr.requestPeers(chan) { list ->
                    peers.clear(); peers.addAll(list.deviceList)
                    adapter.clear(); adapter.addAll(peers.map { it.deviceName })
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_STATE_CHANGE_ACTION -> mgr.requestConnectionInfo(chan) { info ->
                    if (info.groupFormed && !info.isGroupOwner && picked.isNotEmpty() && !sending) {
                        sending = true
                        say("সংযুক্ত! পাঠানো শুরু...")
                        FileSender(this@MainActivity, info.groupOwnerAddress.hostAddress!!, picked, ::say, ::prog).start()
                    }
                }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        val row = LinearLayout(this)
        val bSend = Button(this).apply { text = "পাঠান" }
        val bRecv = Button(this).apply { text = "গ্রহণ করুন" }
        row.addView(bSend, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(bRecv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        status = TextView(this).apply { text = "শুরু করতে একটি বাটন চাপুন"; setPadding(0, 30, 0, 20) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this).apply { adapter = this@MainActivity.adapter }
        list.setOnItemClickListener { _, _, pos, _ -> connect(peers[pos]) }
        root.addView(row, MATCH_PARENT, WRAP_CONTENT)
        root.addView(status, MATCH_PARENT, WRAP_CONTENT)
        root.addView(bar, MATCH_PARENT, WRAP_CONTENT)
        root.addView(list, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)

        mgr = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        chan = mgr.initialize(this, mainLooper, null)

        bSend.setOnClickListener { withPerms { picked = emptyList(); sending = false; pickLauncher.launch(arrayOf("*/*")) } }
        bRecv.setOnClickListener { withPerms { startReceiving() } }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(br, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_STATE_CHANGE_ACTION)
        })
    }
    override fun onPause() { super.onPause(); unregisterReceiver(br) }
    override fun onDestroy() {
        super.onDestroy(); recv?.stop()
        try { mgr.removeGroup(chan, null) } catch (_: Exception) {}
        wifiLock?.takeIf { it.isHeld }?.release(); wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun withPerms(then: () -> Unit) {
        val p = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        afterPerm = then; permLauncher.launch(p)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "fastshare").apply { acquire() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fastshare:cpu").apply { acquire(60 * 60 * 1000L) }
    }

    private fun startReceiving() {
        acquireLocks()
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
        recv = FileReceiver(dir, ::say, ::prog).also { it.start() }
        val fallback = { mgr.createGroup(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { say("গ্রুপ তৈরি। সেন্ডারকে এই ফোন বেছে নিতে বলুন।\nসেভ হবে: ${dir.path}") }
            override fun onFailure(r: Int) { say("গ্রুপ তৈরি ব্যর্থ ($r)") }
        }) }
        mgr.removeGroup(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { create5g(fallback) }
            override fun onFailure(r: Int) { create5g(fallback) }
        })
    }

    private fun create5g(fallback: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-fs-" + UUID.randomUUID().toString().take(4))
                .setPassphrase(UUID.randomUUID().toString().take(10))
                .enablePersistentMode(false)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
            mgr.createGroup(chan, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { say("৫GHz গ্রুপ তৈরি। সেন্ডারকে এই ফোন বেছে নিতে বলুন।") }
                override fun onFailure(r: Int) { fallback() }
            })
        } else fallback()
    }

    private fun discover() {
        acquireLocks()
        mgr.discoverPeers(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { say("খুঁজছে... তালিকা থেকে রিসিভার বেছে নিন") }
            override fun onFailure(r: Int) { say("ডিসকভারি ব্যর্থ ($r)। Wi-Fi ও লোকেশন চালু আছে কি?") }
        })
    }

    private fun connect(d: WifiP2pDevice) {
        val cfg = WifiP2pConfig().apply { deviceAddress = d.deviceAddress; groupOwnerIntent = 0 }
        mgr.connect(chan, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { say("${d.deviceName} এর সাথে সংযোগ হচ্ছে...") }
            override fun onFailure(r: Int) { say("সংযোগ ব্যর্থ ($r)") }
        })
    }

    private fun say(s: String) = runOnUiThread { status.text = s }
    private fun prog(p: Int) = runOnUiThread { bar.progress = p }
}
