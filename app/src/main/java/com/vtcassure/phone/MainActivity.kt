package com.vtcassure.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * VTC-ASSURE 폰 앱
 *
 *   rokid_vtc_server.py  --(WebSocket)-->  이 앱  --(CXR-M / BT)-->  Rokid Glasses
 *
 * 서버 메시지 규약 (rokid_vtc_server.py / readback.py 기준):
 *   status      : 접속 확인
 *   subtitle    : M2 자막. readback_alert=true 인 것은 경보 중복이므로 무시
 *   utterance   : 무음으로 끊은 완결 발화 (M3 입력)
 *   instruction : 지시 탐지 표시자
 *   alert       : M3 복창 검증 결과. severity = warn | ok. seq / t_send / session 을 실어 온다
 *   pong        : 시계 오차 추정 응답 (t0 = 우리가 보낸 값, t1 = 서버 epoch 초)
 *
 * 앱이 올려보내는 것 (E4 표시 지연 분해 — 서버가 readback_<세션>.jsonl 에 그대로 적는다):
 *   clientlog   : 앱 로그 한 줄
 *   ping        : 접속 직후 5회. 서버-폰 시계 오차를 NTP 식으로 추정한다
 *   clock_probe : 추정된 오차(offset_ms = 서버-폰)와 최단 왕복(rtt_ms)
 *   display_log : 경보 seq 별 t_recv(수신) / t_pushed(customViewUpdate 반환) / sdk_ms / net_ms
 *   display_ack : 경보 seq 별 onCustomViewUpdated 콜백 시각
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etSn: EditText
    private lateinit var tvGlass: TextView
    private lateinit var tvServer: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvAlert: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var boxConfig: android.view.View
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    private lateinit var glasses: GlassesBridge

    private var ws: WebSocket? = null
    private var wantServer = false
    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    // ---- E4 계측 상태 ----
    /** 서버 세션 id (status 메시지). 계측행에 실어 jsonl 에서 경보와 조인한다 */
    private var session = ""
    /** 경보 seq -> 폰 수신 시각(epoch ms). 갱신 콜백 보고에 쓴다 */
    private val recvAt = HashMap<Int, Long>()
    /** 시계 오차 추정 (서버 - 폰, ms). 왕복이 가장 짧은 표본을 채택한다 */
    private var clockOffsetMs = Double.NaN
    private var clockRttMs = Long.MAX_VALUE
    private var pongsGot = 0
    private val PROBES = 5

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 생명주기

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etServerIp)
        etPort = findViewById(R.id.etServerPort)
        etSn = findViewById(R.id.etSn)
        tvGlass = findViewById(R.id.tvGlassStatus)
        tvServer = findViewById(R.id.tvServerStatus)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvAlert = findViewById(R.id.tvAlert)
        tvInstruction = findViewById(R.id.tvInstruction)
        boxConfig = findViewById(R.id.boxConfig)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        val pref = getSharedPreferences("vtc", Context.MODE_PRIVATE)
        etIp.setText(pref.getString("ip", "192.168.0.89"))
        etPort.setText(pref.getString("port", "8765"))

        glasses = GlassesBridge(applicationContext, ::log) { up ->
            tvGlass.text = if (up) "안경: 연결됨" else "안경: 미연결"
        }
        glasses.onAlertUpdated = { seq, tUpdated, sincePush -> reportAck(seq, tUpdated, sincePush) }

        findViewById<Button>(R.id.btnGlasses).setOnClickListener {
            if (glasses.connected) glasses.disconnect()
            else if (!glasses.requestAuth(this)) {
                log("Hi Rokid 앱을 설치하고 로그인한 뒤 다시 시도하십시오")
            }
        }
        findViewById<Button>(R.id.btnServer).setOnClickListener {
            if (wantServer) stopServer() else startServer()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { glasses.selfTest() }
        findViewById<Button>(R.id.btnDemo).setOnClickListener { toggleDemo() }

        // CXR-M 전용이던 조작들은 CXR-L 경로에서 쓰이지 않는다
        findViewById<Button>(R.id.btnActivate).visibility = android.view.View.GONE
        findViewById<Button>(R.id.btnReconnect).visibility = android.view.View.GONE
        findViewById<Button>(R.id.btnDiag).setOnClickListener { glasses.resetLatency() }
        findViewById<Button>(R.id.btnAuthFile).visibility = android.view.View.GONE
        etSn.visibility = android.view.View.GONE

        log("준비됨. 안경 연결 -> 서버 연결 순으로 누르십시오.")
        requestBtPermissions()
    }

    override fun onDestroy() {
        stopServer()
        glasses.disconnect()
        super.onDestroy()
    }

    /** Hi Rokid 권한 화면의 결과를 받는다. CXR-L 1.0.4 는 이 콜백을 쓴다. */
    @Deprecated("Rokid CXR-L 1.0.4 uses the activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GlassesBridge.AUTH_REQUEST_CODE) {
            glasses.handleAuthResult(resultCode, data)
        }
    }

    // ---------------------------------------------------------------- 권한

    private fun requestBtPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    // ---------------------------------------------------------------- 서버

    private fun startServer() {
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().ifEmpty { "8765" }
        if (ip.isEmpty()) { log("서버 IP를 입력하십시오"); return }
        getSharedPreferences("vtc", Context.MODE_PRIVATE).edit()
            .putString("ip", ip).putString("port", port).apply()

        wantServer = true
        val url = "ws://$ip:$port"
        log("서버 접속 시도: $url")
        ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun stopServer() {
        wantServer = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        main.post { tvServer.text = "서버: 미연결" }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(s: WebSocket, r: Response) {
            main.post { tvServer.text = "서버: 연결됨" }
            log("서버 연결됨")
        }

        override fun onMessage(s: WebSocket, text: String) {
            runCatching { handle(JSONObject(text)) }
                .onFailure { log("메시지 처리 실패: ${it.message}") }
        }

        override fun onFailure(s: WebSocket, t: Throwable, r: Response?) {
            main.post { tvServer.text = "서버: 끊김" }
            log("서버 오류: ${t.message}")
            retry()
        }

        override fun onClosed(s: WebSocket, code: Int, reason: String) {
            main.post { tvServer.text = "서버: 끊김" }
            log("서버 연결 종료 ($code)")
            retry()
        }
    }

    private fun retry() {
        if (!wantServer) return
        main.postDelayed({ if (wantServer) startServer() }, 3000)
    }

    // ---------------------------------------------------------------- 라우팅

    private fun handle(j: JSONObject) {
        val type = j.optString("type")
        val text = j.optString("text").trim()

        when (type) {
            "status" -> {
                log("서버: $text")
                val sid = j.optString("session")
                if (sid.isNotEmpty() && sid != session) {
                    // 새 서버 세션 — 지연 통계를 세션 단위로 끊는다
                    session = sid
                    glasses.resetLatency()
                    log("서버 세션 $sid")
                }
                startClockProbe()
            }

            "pong" -> onPong(j)

            "subtitle" -> {
                // 경보를 자막 채널로 복제한 구형 호환 메시지는 버린다 (중복 표시 방지)
                if (j.optBoolean("readback_alert", false)) return
                show(text); glasses.subtitle(text)
            }

            "utterance" -> { show(text); glasses.subtitle(text) }

            "instruction" -> {
                log("지시 탐지: ${text.take(40)}")
                showInstruction(text)
                glasses.instruction(text)
            }

            "alert" -> {
                val tRecv = System.currentTimeMillis()          // 수신 시각 (PC 시계와 동기화 전제)
                val seq = j.optInt("seq", -1)
                val tSend = j.optDouble("t_send", Double.NaN)   // 서버 ws.send 직전 epoch 초
                val flat = text.replace("\n", " / ")
                val warn = j.optString("severity") == "warn"
                log(if (warn) "[경보] $flat" else "[정상] $flat")
                val timing = glasses.alert(flat, warn, seq)
                showAlert(flat, warn)
                reportDisplay(seq, tSend, tRecv, timing)
            }

            else -> log("알 수 없는 메시지: $type")
        }
    }

    // ---------------------------------------------------------------- 화면

    private var demo = false

    /** 데모 모드: 설정·로그를 숨기고 자막과 경보만 크게 남긴다. */
    private fun toggleDemo() {
        demo = !demo
        val v = if (demo) android.view.View.GONE else android.view.View.VISIBLE
        boxConfig.visibility = v
        svLog.visibility = v
    }

    private fun show(text: String) {
        if (text.isBlank()) return
        main.post { tvSubtitle.text = text }
    }

    /** 지시가 탐지되면 원문을 자막 위에 고정해 둔다 (복창 대조 대상). */
    private fun showInstruction(text: String) {
        main.post {
            tvInstruction.text = "지시  ·  " + text
            tvInstruction.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * M3 경보 배너.
     * warn = 붉은 배경 + ⚠, ok = 초록 배경 + ✓.
     * 안경 HUD 는 단색이라 기호로 구분하지만, 폰 화면에서는 색까지 쓴다.
     */
    private fun showAlert(text: String, warn: Boolean) {
        main.post {
            tvAlert.text = (if (warn) "\u26A0  " else "\u2713  ") + text
            tvAlert.setBackgroundColor(if (warn) 0xFFB00020.toInt() else 0xFF1B5E20.toInt())
            tvAlert.visibility = android.view.View.VISIBLE
        }
        main.removeCallbacks(clearAlert)
        main.postDelayed(clearAlert, if (warn) 10_000 else 5_000)
    }

    private val clearAlert = Runnable {
        tvAlert.visibility = android.view.View.GONE
        tvInstruction.visibility = android.view.View.GONE
    }

    // ---------------------------------------------------------------- E4 계측 보고

    /**
     * 경보 한 건의 표시 계측행을 서버로 올린다. 서버가 readback_<세션>.jsonl 에 적어
     * 같은 seq 의 경보 행과 조인한다.
     *   t_send   : 서버 송신 직전 (epoch 초)         — 서버가 붙여 보냄
     *   t_recv   : 폰 onMessage (epoch 초)
     *   t_pushed : customViewUpdate 반환 (epoch 초)
     *   sdk_ms   : t_recv -> t_pushed 를 단조 시계로 잰 값 (시계 오차 무관)
     *   net_ms   : t_recv - t_send. PC·폰 시계가 동기화됐을 때만 의미가 있고,
     *              clock_probe 의 offset_ms 로 잔여 오차를 보정한다
     */
    private fun reportDisplay(seq: Int, tSend: Double, tRecv: Long, tm: GlassesBridge.AlertTiming) {
        if (seq < 0) return
        synchronized(recvAt) {
            recvAt[seq] = tRecv
            if (recvAt.size > 200) recvAt.keys.sorted().take(100).forEach { recvAt.remove(it) }
        }
        val row = JSONObject()
            .put("type", "display_log")
            .put("seq", seq)
            .put("session", session)
            .put("t_recv", tRecv / 1000.0)
            .put("t_pushed", tm.tPushedMs / 1000.0)
            .put("sdk_ms", tm.sdkMs)
            .put("shown", tm.shown)
        var netMs: Long? = null
        if (!tSend.isNaN()) {
            netMs = tRecv - (tSend * 1000.0).toLong()
            row.put("t_send", tSend).put("net_ms", netMs)
        }
        if (!clockOffsetMs.isNaN()) row.put("clock_offset_ms", clockOffsetMs).put("clock_rtt_ms", clockRttMs)
        runCatching { ws?.send(row.toString()) }
        if (netMs != null) log("경보 #$seq  PC→폰 ${netMs}ms  SDK ${tm.sdkMs}ms")
    }

    /** onCustomViewUpdated 콜백 시각. seq 의 수신 시각을 알 때만 보고한다. */
    private fun reportAck(seq: Int, tUpdated: Long, sincePushMs: Long) {
        val tRecv = synchronized(recvAt) { recvAt[seq] } ?: return
        val row = JSONObject()
            .put("type", "display_ack")
            .put("seq", seq)
            .put("session", session)
            .put("t_updated", tUpdated / 1000.0)
            .put("since_push_ms", sincePushMs)
            .put("since_recv_ms", tUpdated - tRecv)
        runCatching { ws?.send(row.toString()) }
    }

    /**
     * 접속 직후 PROBES 회 왕복으로 서버-폰 시계 오차를 추정한다 (NTP 식).
     * offset = t1 - (t0 + t3) / 2, 표본 중 왕복(t3 - t0)이 가장 짧은 것을 채택한다.
     * 결과는 clock_probe 행으로 서버에 남겨 net_ms 보정과 오차 한계 서술에 쓴다.
     */
    private fun startClockProbe() {
        clockRttMs = Long.MAX_VALUE
        clockOffsetMs = Double.NaN
        pongsGot = 0
        for (i in 0 until PROBES) main.postDelayed({ sendPing() }, 300L * i)
    }

    private fun sendPing() {
        val t0 = System.currentTimeMillis()
        runCatching { ws?.send(JSONObject().put("type", "ping").put("t0", t0).toString()) }
    }

    private fun onPong(j: JSONObject) {
        val t3 = System.currentTimeMillis()
        val t0 = j.optLong("t0", -1L)
        val t1 = j.optDouble("t1", Double.NaN)          // 서버 epoch 초
        if (t0 < 0 || t1.isNaN()) return
        val rtt = t3 - t0
        val offset = t1 * 1000.0 - (t0 + t3) / 2.0      // 서버 - 폰 (ms)
        if (rtt < clockRttMs) { clockRttMs = rtt; clockOffsetMs = offset }
        pongsGot++
        if (pongsGot == PROBES) {
            log("시계 오차 추정: 서버-폰 ${"%.0f".format(clockOffsetMs)}ms (왕복 ${clockRttMs}ms)")
            runCatching {
                ws?.send(JSONObject().put("type", "clock_probe").put("session", session)
                    .put("offset_ms", clockOffsetMs).put("rtt_ms", clockRttMs).toString())
            }
        }
    }

    // ---------------------------------------------------------------- 로그

    private fun log(line: String) {
        val stamped = "${stamp.format(Date())}  $line"
        main.post {
            tvLog.append(stamped + "\n")
            if (tvLog.lineCount > 400) tvLog.text = ""
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        // 서버가 연결돼 있으면 같은 줄을 PC 로 올려보낸다.
        // 서버가 logs\phone_<세션>.log 로 모아주므로 스크린샷을 찍을 필요가 없다.
        runCatching {
            ws?.send(JSONObject().put("type", "clientlog").put("text", stamped).toString())
        }
    }
}
