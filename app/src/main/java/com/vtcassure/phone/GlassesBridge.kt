package com.vtcassure.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICXRSessionCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import org.json.JSONArray
import org.json.JSONObject

/**
 * VTC-ASSURE : 폰 -> Rokid Glasses 표시 브리지 (CXR-L)
 *
 *   폰 앱 --AIDL--> Hi Rokid (com.rokid.sprite.aiapp / .global.aiapp) --BT--> 안경 HUD
 *
 * CXR-M 은 Console > Equipment Management 의 `.lc` 기기 인증 파일을 요구하는데
 * 그 화면이 기업 협력 전용이라 개인 계정으로는 통과할 수 없다(SN_CHECK_FAILED).
 * CXR-L 은 Hi Rokid 앱이 발급하는 토큰만 있으면 되고 공식 문서상 Public Access = Yes 다.
 *
 * 표시 흐름:
 *   AuthorizationHelper.requestAuthorization(activity, perms, REQ)
 *     -> onActivityResult -> parseAuthorizationResult -> AuthSuccess(token)
 *   CXRLink(ctx).configCXRSession(CUSTOMVIEW).connect(token)
 *     -> customViewOpen(layoutJson) / customViewUpdate(updateJson)
 *
 * HUD: 480x400 per eye, 단색 녹색 Micro-LED. 색 구분이 안 되므로 ⚠ / ✓ 기호로 구분한다.
 */
class GlassesBridge(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {

    companion object {
        const val AUTH_REQUEST_CODE = 8301

        // customView 노드 id — 열 때 만들고, 이후에는 이 id 로 텍스트만 갱신한다
        private const val ID_ALERT = "vtcAlert"
        private const val ID_INSTRUCTION = "vtcInstruction"
        private const val ID_SUBTITLE = "vtcSubtitle"

        private const val SYM_WARN = "⚠"   // ⚠
        private const val SYM_OK = "✓"     // ✓

        // ---- HUD 표시 조정 (480x400 per eye, 단색 녹색) ----
        // 여기 값만 바꾸면 위치와 크기가 조정된다.
        private const val SIZE_ALERT = "22sp"
        private const val SIZE_INSTRUCTION = "13sp"
        private const val SIZE_SUBTITLE = "17sp"

        /** 아래로 내리려면 MARGIN_TOP 을 키우거나 MARGIN_BOTTOM 을 줄인다 */
        private const val MARGIN_TOP = "180dp"
        private const val MARGIN_BOTTOM = "24dp"
        private const val GRAVITY = "bottom|center_horizontal"

        /** 새 내용이 이 시간 동안 없으면 HUD 를 비운다 */
        private const val IDLE_MS = 20_000L
    }

    private val main = Handler(Looper.getMainLooper())

    private var link: CXRLink? = null

    @Volatile var connected = false
        private set

    /** CXR 세션이 실제로 시작되어 화면을 그릴 수 있는 상태 */
    @Volatile private var sessionRunning = false

    /** 표시 지연 계측: 경보 수신 -> customViewUpdate 반환까지의 밀리초 */
    private val latencies = ArrayList<Long>()

    /**
     * customViewUpdate 를 부른 순서대로 (경보 seq 또는 -1, 호출 시각 epoch ms) 를 쌓아 두고,
     * onCustomViewUpdated 콜백이 올 때마다 앞에서 하나씩 짝지운다. 경보에 대한 콜백이면
     * onAlertUpdated 로 알린다. 콜백은 Hi Rokid 가 갱신을 처리했다는 신호로, 소프트웨어에서
     * 관측할 수 있는 지점 중 HUD 렌더에 가장 가깝다 (SDK 가 이 콜백을 실제로 주는지는 로그로 확인).
     */
    private val pendingUpdates = ArrayDeque<Pair<Int, Long>>()
    private var updateCallbacks = 0

    /** (경보 seq, 콜백 시각 epoch ms, customViewUpdate 호출 -> 콜백 ms) */
    var onAlertUpdated: ((Int, Long, Long) -> Unit)? = null

    /** 경보 한 건의 표시 계측값. MainActivity 가 서버로 올려보낸다. shown=false 면 안경에 못 보낸 것. */
    data class AlertTiming(val seq: Int, val tPushedMs: Long, val sdkMs: Long, val shown: Boolean)

    private var lastSubtitle = ""
    private var lastInstruction = ""
    private var lastAlert = ""

    // ---------------------------------------------------------------- 인증

    /** Hi Rokid 앱에 권한을 요청한다. Activity 가 필요하므로 MainActivity 가 호출한다. */
    fun requestAuth(activity: Activity): Boolean {
        if (!AuthorizationHelper.isRequiredRokidAppInstalled(activity) &&
            !AuthorizationHelper.isRequiredHiRokidInstalled(activity)
        ) {
            log("Rokid AI App / Hi Rokid 가 설치·로그인되어 있지 않습니다")
            return false
        }
        log("Hi Rokid 에 권한 요청")
        val immediate = runCatching {
            AuthorizationHelper.requestAuthorization(
                activity,
                // 1.0.4 의 GlassPermission 은 CAMERA / MICROPHONE 만 있다.
                // (DEVICE_MANAGE, MEDIA 는 1.1.2 에서 추가된 값)
                arrayOf(GlassPermission.CAMERA, GlassPermission.MICROPHONE),
                AUTH_REQUEST_CODE
            )
        }.getOrElse { log("권한 요청 예외: ${it.message}"); null }

        // SDK 버전에 따라 즉시 결과를 반환하기도 한다
        immediate?.let { handleAuthResult(it.first, it.second) }
        return true
    }

    fun handleAuthResult(resultCode: Int, data: Intent?) {
        when (val r = runCatching {
            AuthorizationHelper.parseAuthorizationResult(resultCode, data)
        }.getOrElse { log("권한 결과 파싱 예외: ${it.message}"); null }) {
            is AuthResult.AuthSuccess -> { log("권한 승인됨"); connect(r.token) }
            is AuthResult.AuthFail -> log("권한 거부됨")
            is AuthResult.AuthCancel -> log("권한 요청 취소됨")
            else -> log("권한 결과 불명 (resultCode=$resultCode)")
        }
    }

    // ---------------------------------------------------------------- 연결

    private fun connect(token: String) {
        disconnect()
        val l = CXRLink(ctx.applicationContext)

        l.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(isConnected: Boolean) {
                connected = isConnected
                log(if (isConnected) "CXR-L 연결됨" else "CXR-L 연결 끊김")
                main.post { onState(isConnected) }
            }
            override fun onGlassBtConnected(isConnected: Boolean) {
                log("안경 블루투스: ${if (isConnected) "연결" else "해제"}")
            }
            override fun onGlassDeviceInfo(deviceInfo: GlassInfo) {
                log("안경 정보: ${deviceInfo.deviceName} 배터리 ${deviceInfo.batteryLevel}%" +
                    " 밝기 ${deviceInfo.brightness} 착용 ${deviceInfo.wearingStatus}")
            }
            override fun onGlassWearingStatus(wearing: Boolean) = Unit
            override fun onGlassAiAssistStart() = Unit
            override fun onGlassAiAssistStop() = Unit
            override fun onGlassAiInterrupt(interruptWake: Boolean) = Unit
        })

        val ok = runCatching {
            l.configCXRSession(
                CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW),
                object : ICXRSessionCbk {
                    override fun onSessionAvailable(reason: CxrDefs.CXRSessionReason) {
                        log("세션 사용 가능 ($reason)")
                    }
                    override fun onSessionStart(reason: CxrDefs.CXRSessionReason) {
                        sessionRunning = true
                        log("세션 시작 ($reason) — 표시 가능")
                    }
                    override fun onSessionPause(reason: CxrDefs.CXRSessionReason) {
                        sessionRunning = false
                        log("세션 일시중지 ($reason)")
                    }
                    override fun onSessionUnavailable(reason: CxrDefs.CXRSessionReason) {
                        sessionRunning = false
                        log("세션 사용 불가 ($reason)")
                    }
                }
            )
        }.getOrElse { log("세션 설정 예외: ${it.message}"); false }

        if (!ok) { log("CXR 세션 설정 실패"); runCatching { l.disconnect() }; return }

        l.setCXRCustomViewCbk(object : ICustomViewCbk {
            override fun onCustomViewOpened() { log("커스텀뷰 열림") }
            override fun onCustomViewUpdated() {
                val now = System.currentTimeMillis()
                val head = synchronized(pendingUpdates) { pendingUpdates.removeFirstOrNull() }
                updateCallbacks++
                if (updateCallbacks <= 3) log("커스텀뷰 갱신 콜백 수신 (#$updateCallbacks)")
                if (head != null && head.first >= 0) {
                    val dt = now - head.second
                    log("경보 #${head.first} 갱신 콜백 +${dt}ms")
                    onAlertUpdated?.invoke(head.first, now, dt)
                }
            }
            override fun onCustomViewClosed() { log("커스텀뷰 닫힘") }
            override fun onCustomViewIconsSent() = Unit
            override fun onCustomViewError(code: Int, msg: String?) {
                log("커스텀뷰 오류 $code: $msg")
            }
        })

        link = l
        val sent = runCatching { l.connect(token) }.getOrElse {
            log("connect 예외: ${it.message}"); false
        }
        log(if (sent) "연결 요청 전송됨 — 결과 대기" else "연결 요청 실패 (Hi Rokid 로그인 확인)")
    }

    fun disconnect() {
        runCatching { link?.customViewClose() }
        runCatching { link?.disconnect() }
        link = null
        connected = false
        sessionRunning = false
        lastSubtitle = ""; lastInstruction = ""; lastAlert = ""
        main.post { onState(false) }
    }

    // ---------------------------------------------------------------- 표시

    private fun ensureOpen(): Boolean {
        val l = link ?: return false
        if (runCatching { l.customViewIsOpen() }.getOrDefault(false)) return true
        val ok = runCatching { l.customViewOpen(layoutJson()) }.getOrElse {
            log("customViewOpen 예외: ${it.message}"); false
        }
        if (!ok) log("customViewOpen 실패")
        return ok
    }

    private fun push(seq: Int = -1): Boolean {
        val l = link ?: return false
        if (!ensureOpen()) return false
        val ok = sendUpdate(l, seq)
        armIdle()
        return ok
    }

    /** customViewUpdate 를 보내고, 콜백 짝짓기용으로 (seq, 호출 시각) 을 기록한다. */
    private fun sendUpdate(l: CXRLink, seq: Int): Boolean {
        val ok = runCatching { l.customViewUpdate(updateJson()) }.getOrElse {
            log("customViewUpdate 예외: ${it.message}"); false
        }
        if (!ok) { log("customViewUpdate 실패"); return false }
        synchronized(pendingUpdates) {
            pendingUpdates.addLast(Pair(seq, System.currentTimeMillis()))
            while (pendingUpdates.size > 64) pendingUpdates.removeFirst()   // 콜백이 없는 SDK 대비
        }
        return true
    }

    /**
     * 유휴 정리.
     * 새 내용이 IDLE_MS 동안 없으면 화면을 비운다.
     * 이게 없으면 마지막 자막이 HUD 에 영구히 남아 시야를 가린다.
     */
    private fun armIdle() {
        main.removeCallbacks(idleClear)
        if (lastSubtitle.isNotEmpty() || lastInstruction.isNotEmpty() || lastAlert.isNotEmpty())
            main.postDelayed(idleClear, IDLE_MS)
    }

    private val idleClear = Runnable {
        lastSubtitle = ""; lastInstruction = ""; lastAlert = ""
        val l = link ?: return@Runnable
        sendUpdate(l, -1)
        log("유휴 — 화면 비움")
    }

    /** M2 자막 */
    fun subtitle(text: String) {
        if (text.isBlank() || text == lastSubtitle) return
        lastSubtitle = text
        push()
    }

    /** 지시 원문 (복창 대조 대상) */
    fun instruction(text: String) {
        lastInstruction = text
        push()
    }

    /**
     * M3 복창 검증 결과. 단색 HUD 이므로 색이 아니라 기호로 구분한다.
     * 이 경로의 소요시간을 재서 표시 지연 통계로 쌓는다(논문 E4).
     *   seq   : 서버가 붙인 경보 일련번호 (-1 이면 자체 시험)
     *   반환  : customViewUpdate 반환 시각(epoch ms)과 소요 ms — 단조 시계로 재므로 시계 오차 무관
     */
    fun alert(text: String, warn: Boolean, seq: Int = -1): AlertTiming {
        val t0 = android.os.SystemClock.elapsedRealtime()
        lastAlert = (if (warn) "$SYM_WARN  " else "$SYM_OK  ") + text
        val shown = push(seq)
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        val tPushed = System.currentTimeMillis()
        synchronized(latencies) {
            latencies.add(dt)
            val n = latencies.size
            val sorted = latencies.sorted()
            val med = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
            log("표시 지연 ${dt}ms  (n=$n 중앙값 ${med}ms 평균 ${latencies.average().toInt()}ms" +
                " 최소 ${sorted.first()} 최대 ${sorted.last()})")
        }
        main.removeCallbacks(clearAlert)
        main.postDelayed(clearAlert, if (warn) 10_000 else 5_000)
        return AlertTiming(seq, tPushed, dt, shown)
    }

    private val clearAlert = Runnable {
        lastAlert = ""; lastInstruction = ""
        val l = link ?: return@Runnable
        sendUpdate(l, -1)
        armIdle()
    }

    /** 지연 통계를 비운다. 측정 세션을 새로 시작할 때 쓴다. */
    fun resetLatency() {
        synchronized(latencies) { latencies.clear() }
        log("표시 지연 통계 초기화")
    }

    fun selfTest() {
        subtitle("VTC-ASSURE 표시 시험. 제1연대는 0600까지 보고할 것.")
        main.postDelayed({ instruction("제1연대는 0600까지 보고할 것") }, 1200)
        main.postDelayed({ alert("복창 확인 필요 - 시한 상이", true) }, 2400)
        main.postDelayed({ alert("복창 일치 3/3", false) }, 6000)
    }

    // ---------------------------------------------------------------- JSON

    /**
     * customViewOpen 이 받는 레이아웃 트리.
     * 안드로이드 레이아웃 속성을 그대로 JSON 으로 옮긴 형식이다.
     */
    private fun layoutJson(): String {
        val children = JSONArray()
            .put(text(ID_ALERT, lastAlert, SIZE_ALERT, "#FFFFFFFF", style = "bold"))
            .put(text(ID_INSTRUCTION, lastInstruction, SIZE_INSTRUCTION, "#FFAAAAAA", padTop = "6dp"))
            .put(text(ID_SUBTITLE, lastSubtitle, SIZE_SUBTITLE, "#FFFFFFFF", padTop = "10dp"))

        return JSONObject()
            .put("type", "LinearLayout")
            .put(
                "props", JSONObject()
                    .put("id", "vtcRoot")
                    .put("layout_width", "match_parent")
                    .put("layout_height", "match_parent")
                    .put("marginTop", MARGIN_TOP)
                    .put("marginBottom", MARGIN_BOTTOM)
                    .put("paddingStart", "24dp")
                    .put("paddingEnd", "24dp")
                    .put("backgroundColor", "#FF000000")
                    .put("orientation", "vertical")
                    .put("gravity", GRAVITY)
            )
            .put("children", children)
            .toString()
    }

    /** customViewUpdate 가 받는 갱신 배열 */
    private fun updateJson(): String = JSONArray()
        .put(update(ID_ALERT, lastAlert))
        .put(update(ID_INSTRUCTION, lastInstruction))
        .put(update(ID_SUBTITLE, lastSubtitle))
        .toString()

    private fun update(id: String, t: String) = JSONObject()
        .put("action", "update")
        .put("id", id)
        .put("props", JSONObject().put("text", t))

    private fun text(
        id: String, t: String, size: String, color: String,
        style: String? = null, padTop: String? = null
    ): JSONObject {
        val props = JSONObject()
            .put("id", id)
            .put("layout_width", "match_parent")
            .put("layout_height", "wrap_content")
            .put("text", t)
            .put("textColor", color)
            .put("textSize", size)
            .put("gravity", "center")
        style?.let { props.put("textStyle", it) }
        padTop?.let { props.put("paddingTop", it) }
        return JSONObject().put("type", "TextView").put("props", props)
    }
}
