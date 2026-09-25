package com.shehan.robotpet.remote

import android.graphics.Bitmap
import com.shehan.robotpet.brain.MotionCommand
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

data class RemoteServerInfo(
    val running: Boolean = false,
    val url: String = "",
    val pin: String = "",
    val manualMode: Boolean = false,
    val lastClientMs: Long = 0L
)

class RemoteControlServer(
    port: Int = 8080,
    private val statusProvider: () -> JSONObject,
    private val onCommand: (MotionCommand, Long) -> Unit,
    private val onManualModeChanged: (Boolean) -> Unit,
    private val onInfoChanged: (RemoteServerInfo) -> Unit
) : NanoHTTPD(port) {

    private val frame = AtomicReference<ByteArray?>(null)
    private val frameBusy = AtomicBoolean(false)
    private val frameExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var runningFlag = false

    @Volatile
    private var manualMode = false

    @Volatile
    private var pin = newPin()

    @Volatile
    private var lastClientMs = 0L

    @Volatile
    private var lastFrameQueuedMs = 0L

    fun startRemote() {
        if (runningFlag) return
        pin = newPin()
        start(SOCKET_READ_TIMEOUT, false)
        runningFlag = true
        publishInfo()
    }

    fun stopRemote() {
        if (!runningFlag) return
        manualMode = false
        onManualModeChanged(false)
        stop()
        runningFlag = false
        frame.set(null)
        publishInfo()
    }

    fun updateFrame(bitmap: Bitmap) {
        if (!runningFlag) return

        val now = System.currentTimeMillis()
        if (now - lastFrameQueuedMs < 220L) return
        if (!frameBusy.compareAndSet(false, true)) return
        lastFrameQueuedMs = now

        frameExecutor.execute {
            try {
                val scaled = if (bitmap.width > 640) {
                    val h = (bitmap.height * (640f / bitmap.width)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, 640, h, true)
                } else {
                    bitmap
                }

                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 58, out)
                frame.set(out.toByteArray())

                if (scaled !== bitmap) {
                    runCatching { scaled.recycle() }
                }
            } finally {
                frameBusy.set(false)
            }
        }
    }

    fun info(): RemoteServerInfo = RemoteServerInfo(
        running = runningFlag,
        url = if (runningFlag) "http://${localIpv4()}:${listeningPort}/?pin=$pin" else "",
        pin = if (runningFlag) pin else "",
        manualMode = manualMode,
        lastClientMs = lastClientMs
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val params = session.parameters
        val suppliedPin = params["pin"]?.firstOrNull().orEmpty()

        if (uri == "/") {
            if (suppliedPin != pin) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_HTML,
                    pinPage()
                )
            }
            touchClient()
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, controllerPage())
        }

        if (suppliedPin != pin) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "text/plain",
                "Wrong PIN"
            )
        }

        touchClient()

        return when (uri) {
            "/frame.jpg" -> {
                val data = frame.get()
                if (data == null) {
                    newFixedLengthResponse(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "text/plain",
                        "Camera frame not ready"
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        data.inputStream(),
                        data.size.toLong()
                    ).apply {
                        addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    }
                }
            }

            "/status" -> {
                val j = statusProvider()
                    .put("remoteManual", manualMode)
                    .put("remoteLastClientMs", lastClientMs)
                newFixedLengthResponse(Response.Status.OK, "application/json", j.toString())
            }

            "/mode" -> {
                val value = params["manual"]?.firstOrNull() == "1"
                manualMode = value
                if (!value) onCommand(MotionCommand.STOP, 0L)
                onManualModeChanged(value)
                publishInfo()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject().put("manual", manualMode).toString()
                )
            }

            "/cmd" -> {
                if (!manualMode) {
                    return newFixedLengthResponse(
                        Response.Status.CONFLICT,
                        "application/json",
                        JSONObject().put("ok", false).put("error", "MANUAL_MODE_OFF").toString()
                    )
                }

                val cmd = runCatching {
                    MotionCommand.valueOf(
                        params["c"]?.firstOrNull().orEmpty().uppercase()
                    )
                }.getOrDefault(MotionCommand.STOP)

                val requestedMs = params["ms"]?.firstOrNull()?.toLongOrNull() ?: 220L
                val duration = if (cmd == MotionCommand.STOP) 0L else requestedMs.coerceIn(80L, 700L)

                onCommand(cmd, duration)

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject()
                        .put("ok", true)
                        .put("command", cmd.name)
                        .put("durationMs", duration)
                        .toString()
                )
            }

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not found"
            )
        }
    }

    private fun touchClient() {
        lastClientMs = System.currentTimeMillis()
        publishInfo()
    }

    private fun publishInfo() {
        onInfoChanged(info())
    }

    private fun newPin(): String = Random.nextInt(100000, 999999).toString()

    private fun localIpv4(): String {
        return runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }

            candidates.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
                ?: "0.0.0.0"
        }.getOrDefault("0.0.0.0")
    }

    private fun pinPage(): String = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Robot Pet Remote</title>
<style>
body{font-family:system-ui;background:#090b10;color:white;display:grid;place-items:center;min-height:100vh;margin:0}
.card{background:#141824;padding:24px;border-radius:22px;width:min(88vw,420px)}
input,button{font-size:18px;border-radius:12px;padding:12px;width:100%;box-sizing:border-box;margin-top:10px}
button{background:#66e4ff;border:0;color:#051014;font-weight:700}
</style>
</head>
<body><div class="card">
<h2>Robot Pet Remote</h2>
<p>Enter the 6-digit PIN shown on the robot phone.</p>
<input id="p" inputmode="numeric" maxlength="6" placeholder="PIN">
<button onclick="go()">Connect</button>
<script>
function go(){location.href='/?pin='+encodeURIComponent(document.getElementById('p').value)}
</script>
</div></body></html>
""".trimIndent()

    private fun controllerPage(): String = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>Robot Pet Remote</title>
<style>
*{box-sizing:border-box;touch-action:manipulation}
body{margin:0;background:#06080d;color:white;font-family:system-ui;text-align:center}
.wrap{max-width:760px;margin:auto;padding:10px}
.cam{width:100%;max-height:48vh;object-fit:contain;background:#111;border-radius:16px}
.row{display:flex;gap:8px;justify-content:center;margin-top:8px;flex-wrap:wrap}
button{min-width:86px;min-height:56px;border:0;border-radius:16px;font-size:18px;font-weight:700;background:#252c3b;color:white}
button.primary{background:#66e4ff;color:#061014}
button.stop{background:#ff6a64;color:#1b0504}
button.active{background:#9df59f;color:#061606}
.grid{display:grid;grid-template-columns:82px 82px 82px;gap:8px;justify-content:center;margin-top:10px}
.small{font-size:13px;color:#aeb7c7;margin-top:7px}
#status{white-space:pre-wrap}
</style>
</head>
<body><div class="wrap">
<h2>Robot Pet Remote</h2>
<img id="cam" class="cam">
<div class="row">
<button id="mode" class="primary" onclick="toggleMode()">Take Manual Control</button>
<button class="stop" onclick="send('STOP',0)">STOP</button>
</div>
<div class="grid">
<div></div><button id="f">▲</button><div></div>
<button id="l">◀</button><button id="s" class="stop">■</button><button id="r">▶</button>
<div></div><button id="b">▼</button><div></div>
</div>
<div class="row">
<button id="fu">Fork ▲</button>
<button id="fd">Fork ▼</button>
</div>
<div id="status" class="small">Connecting…</div>
</div>
<script>
const qs=new URLSearchParams(location.search); const pin=qs.get('pin')||'';
let manual=false, holdTimer=null;

function api(path){return path+(path.includes('?')?'&':'?')+'pin='+encodeURIComponent(pin)}
function send(c,ms=220){fetch(api('/cmd?c='+encodeURIComponent(c)+'&ms='+ms)).catch(()=>{})}

function bindHold(id,c,ms=220){
 const e=document.getElementById(id);
 const start=(ev)=>{ev.preventDefault(); if(!manual)return; send(c,ms); clearInterval(holdTimer); holdTimer=setInterval(()=>send(c,ms),260)}
 const end=(ev)=>{if(ev)ev.preventDefault(); clearInterval(holdTimer); holdTimer=null; if(manual)send('STOP',0)}
 e.addEventListener('pointerdown',start); e.addEventListener('pointerup',end);
 e.addEventListener('pointercancel',end); e.addEventListener('pointerleave',end);
}
bindHold('f','FORWARD'); bindHold('b','BACKWARD'); bindHold('l','LEFT'); bindHold('r','RIGHT');
bindHold('fu','FORK_UP',260); bindHold('fd','FORK_DOWN',260);
document.getElementById('s').onclick=()=>send('STOP',0);

function toggleMode(){
 const next=manual?0:1;
 fetch(api('/mode?manual='+next)).then(r=>r.json()).then(j=>{manual=!!j.manual; paintMode()}).catch(()=>{});
}
function paintMode(){
 const e=document.getElementById('mode');
 e.textContent=manual?'Release to Autonomous':'Take Manual Control';
 e.className=manual?'active':'primary';
}
function refreshFrame(){document.getElementById('cam').src=api('/frame.jpg?t='+Date.now())}
setInterval(refreshFrame,240); refreshFrame();

function refreshStatus(){
 fetch(api('/status')).then(r=>r.json()).then(j=>{
   manual=!!j.remoteManual; paintMode();
   document.getElementById('status').textContent=
     'Mode: '+(manual?'REMOTE MANUAL':'AUTONOMOUS')+
     ' | Mood: '+(j.emotion||'--')+
     ' | Robot: '+(j.robotConnected?'connected':'offline')+
     ' | Safe: '+(j.safeToMove?'yes':'no')+
     '\nObject: '+(j.object||'--')+' | Battery: '+(j.battery??'--')+'%';
 }).catch(()=>{document.getElementById('status').textContent='Connection lost'});
}
setInterval(refreshStatus,700); refreshStatus();
window.addEventListener('beforeunload',()=>{if(manual)navigator.sendBeacon(api('/mode?manual=0'))});
</script>
</body></html>
""".trimIndent()

    fun shutdown() {
        stopRemote()
        frameExecutor.shutdownNow()
    }
}
