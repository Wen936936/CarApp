package com.example.carapp

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 小车控制主界面
 * 通过 OkHttp 向后端发送 GET 请求控制小车前进、后退、停止、左转、右转
 * 同时通过 WebSocket 接收后端推送的摄像头图像并实时显示
 * 另外通过 WebSocket 接收 AI 回复，并用 POST 把聊天文字发送给 AI
 */
class MainActivity : AppCompatActivity() {

    // 后端服务器基础地址，需根据实际局域网 IP 修改
    private val baseUrl = "http://10.0.2.2:8080"

    // 摄像头图像推送地址，模拟器中 10.0.2.2 指向宿主机
    private val cameraWsUrl = "ws://10.0.2.2:8080/ws/camera"

    // AI 对话回复推送地址，与摄像头分成两条独立连接，互不影响
    private val chatWsUrl = "ws://10.0.2.2:8080/ws/chat"

    // OkHttp 客户端，全局复用一个实例，避免重复创建连接池
    private val client = OkHttpClient()

    // 摄像头 WebSocket 连接实例，便于在页面销毁时关闭
    private var cameraWebSocket: WebSocket? = null

    // AI 对话 WebSocket 连接实例，同样在页面销毁时关闭
    private var chatWebSocket: WebSocket? = null

    private lateinit var tvStatus: TextView
    private lateinit var ivCamera: ImageView
    private lateinit var btnBuzzer: Button
    private lateinit var tvChatLog: TextView
    private lateinit var etChatInput: EditText

    // 喇叭当前是否处于响的状态，用于点击按钮时在 响/停 之间切换
    private var isBuzzerOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        ivCamera = findViewById(R.id.ivCamera)

        val btnForward = findViewById<Button>(R.id.btnForward)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnLeft = findViewById<Button>(R.id.btnLeft)
        val btnRight = findViewById<Button>(R.id.btnRight)
        val btnBackward = findViewById<Button>(R.id.btnBackward)

        // 绑定五个方向按钮的点击事件
        btnForward.setOnClickListener { sendCommand("forward") }
        btnStop.setOnClickListener { sendCommand("stop") }
        btnLeft.setOnClickListener { sendCommand("left") }
        btnRight.setOnClickListener { sendCommand("right") }
        btnBackward.setOnClickListener { sendCommand("backward") }

        // LED 颜色按钮：红、绿、蓝、白、关
        findViewById<Button>(R.id.btnLedRed).setOnClickListener { sendLedColor("red") }
        findViewById<Button>(R.id.btnLedGreen).setOnClickListener { sendLedColor("green") }
        findViewById<Button>(R.id.btnLedBlue).setOnClickListener { sendLedColor("blue") }
        findViewById<Button>(R.id.btnLedWhite).setOnClickListener { sendLedColor("white") }
        findViewById<Button>(R.id.btnLedOff).setOnClickListener { sendLedColor("off") }

        // LED 模式按钮：常亮、流水灯、闪烁
        findViewById<Button>(R.id.btnLedModeSteady).setOnClickListener { sendLedMode("steady") }
        findViewById<Button>(R.id.btnLedModeFlow).setOnClickListener { sendLedMode("flow") }
        findViewById<Button>(R.id.btnLedModeBlink).setOnClickListener { sendLedMode("blink") }

        // 喇叭按钮：点击在 响/停 两个状态间切换
        btnBuzzer = findViewById(R.id.btnBuzzer)
        btnBuzzer.setOnClickListener { toggleBuzzer() }

        // 图像处理开关：勾选状态变化时通知后端开启/关闭 YOLO/OpenCV 处理
        findViewById<Switch>(R.id.swImageProcess).setOnCheckedChangeListener { _, isChecked ->
            sendImageProcessSwitch(isChecked)
        }

        // 机械臂归位按钮：让机械臂回到预设初始姿态
        findViewById<Button>(R.id.btnArmReset).setOnClickListener { sendArmReset() }

        // 机械臂 6 个关节滑动条（base、p1~p5），当前只更新数值显示，暂不发送指令
        bindArmSeekBar(R.id.seekBase, R.id.tvBaseValue)
        bindArmSeekBar(R.id.seekP1, R.id.tvP1Value)
        bindArmSeekBar(R.id.seekP2, R.id.tvP2Value)
        bindArmSeekBar(R.id.seekP3, R.id.tvP3Value)
        bindArmSeekBar(R.id.seekP4, R.id.tvP4Value)
        bindArmSeekBar(R.id.seekP5, R.id.tvP5Value)

        // AI 对话区域：聊天记录框允许内部滚动，输入框与发送按钮绑定点击事件
        tvChatLog = findViewById(R.id.tvChatLog)
        // TextView 默认不可滚动，设置该 MovementMethod 后超出高度的内容可上下滑动查看
        tvChatLog.movementMethod = ScrollingMovementMethod()
        etChatInput = findViewById(R.id.etChatInput)
        findViewById<Button>(R.id.btnSendChat).setOnClickListener { sendChatMessage() }

        // 页面创建时连接摄像头 WebSocket，开始接收图像推送
        connectCameraWebSocket()

        // 页面创建时连接 AI 对话 WebSocket，开始接收 AI 回复
        connectChatWebSocket()
    }

    /**
     * 连接 AI 对话 WebSocket，接收后端推送的 AI 回复并追加到聊天记录
     * 消息格式约定为 JSON：{"text": "AI 回复内容"}
     */
    private fun connectChatWebSocket() {
        val request = Request.Builder()
            .url(chatWsUrl)
            .build()

        try {
            chatWebSocket = client.newWebSocket(request, object : WebSocketListener() {

                // 连接建立成功
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "AI 对话已连接", Toast.LENGTH_SHORT).show()
                    }
                }

                // 收到文本消息，解析嵌套 JSON 里的 msg.text 并追加到聊天记录
                // 后端推送格式：{"op":"publish","topic":"/ai/reply","msg":{"text":"...","action":""}}
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)

                        // 同一条连接上可能推送多个 topic 的消息，只处理 AI 回复
                        if (json.optString("topic") != "/ai/reply") {
                            Log.d("ChatWS", "忽略非 AI 回复消息：$text")
                            return
                        }

                        // 回复内容嵌在 msg 对象里，msg 不存在（如错误消息）时直接跳过
                        val msg = json.optJSONObject("msg") ?: return
                        val aiText = msg.optString("text")
                        if (aiText.isEmpty()) return

                        runOnUiThread { appendChatLog("AI：$aiText") }
                    } catch (e: Exception) {
                        // 解析失败时记录日志，避免单条异常消息导致连接中断
                        Log.e("ChatWS", "AI 回复解析失败：${e.message}")
                    }
                }

                // 连接异常或断开
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread {
                        tvStatus.text = "AI 对话连接失败：${t.message}"
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建连接过程中可能出现的异常
            tvStatus.text = "AI 对话连接异常：${e.message}"
        }
    }

    /**
     * 把一行文字追加到聊天记录，并让记录框自动滚动到最新一行，方便看到最新回复
     */
    private fun appendChatLog(line: String) {
        tvChatLog.append("$line\n")
        // 内容更新后把滚动位置移到末尾，避免新消息被挡在可视区域之外
        val scrollAmount = tvChatLog.layout?.let {
            it.getLineTop(tvChatLog.lineCount) - tvChatLog.height
        } ?: 0
        if (scrollAmount > 0) {
            tvChatLog.scrollTo(0, scrollAmount)
        }
    }

    /**
     * 读取输入框内容发送给 AI
     * 1. 为空时不发送
     * 2. 先在聊天记录里显示“我：xxx”并清空输入框，让操作有即时反馈
     * 3. POST /car/ai/chat?text=xxx，AI 的回复由 WebSocket 推送
     */
    private fun sendChatMessage() {
        val input = etChatInput.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        appendChatLog("我：$input")
        etChatInput.setText("")

        // 中文等特殊字符不能直接拼进 URL，需要先做 URL 编码，否则后端可能收到乱码
        val encodedText = URLEncoder.encode(input, "UTF-8")
        val url = "$baseUrl/car/ai/chat?text=$encodedText"
        val emptyBody = "".toRequestBody(null)
        val request = Request.Builder()
            .url(url)
            .post(emptyBody)
            .build()

        try {
            // 使用异步请求，避免阻塞主线程
            client.newCall(request).enqueue(object : Callback {

                // 网络异常、连接失败等情况的回调
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        tvStatus.text = "发送给 AI 失败：${e.message}"
                    }
                }

                // 收到后端响应的回调，这里只关心是否发送成功，AI 回复走 WebSocket
                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        runOnUiThread {
                            tvStatus.text = if (res.isSuccessful) {
                                "已发送给 AI"
                            } else {
                                "发送给 AI 失败，状态码：${res.code}"
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            tvStatus.text = "发送给 AI 异常：${e.message}"
        }
    }

    /**
     * 绑定单个机械臂关节滑动条：拖动时只同步右侧的数值显示
     * 后续接入姿态控制接口后，可在这里追加发送请求的逻辑
     */
    private fun bindArmSeekBar(seekBarId: Int, valueTextViewId: Int) {
        val seekBar = findViewById<SeekBar>(seekBarId)
        val valueText = findViewById<TextView>(valueTextViewId)
        // 初始化时先把当前进度显示出来，避免与布局里的默认文字不一致
        valueText.text = seekBar.progress.toString()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = progress.toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    /**
     * 连接后端摄像头 WebSocket，接收图像推送并显示
     */
    private fun connectCameraWebSocket() {
        val request = Request.Builder()
            .url(cameraWsUrl)
            .build()

        try {
            cameraWebSocket = client.newWebSocket(request, object : WebSocketListener() {

                // 连接建立成功
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "摄像头已连接", Toast.LENGTH_SHORT).show()
                    }
                }

                // 收到文本消息，解析 JSON 中的 image 字段并解码显示
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val base64Image = json.getString("image")
                        val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            runOnUiThread { ivCamera.setImageBitmap(bitmap) }
                        }
                    } catch (e: Exception) {
                        // 解析或解码失败时记录日志，避免单帧异常导致连接中断
                        Log.e("CameraWS", "图像帧解析失败：${e.message}")
                    }
                }

                // 连接异常或断开
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread {
                        tvStatus.text = "摄像头连接失败：${t.message}"
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建连接过程中可能出现的异常
            tvStatus.text = "摄像头连接异常：${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 页面销毁时关闭 WebSocket，避免内存泄漏和无效连接
        cameraWebSocket?.close(1000, "页面关闭")
        chatWebSocket?.close(1000, "页面关闭")
    }

    /**
     * 向后端发送控制指令
     * action 取值：forward / backward / stop / left / right
     */
    private fun sendCommand(action: String) {
        val url = "$baseUrl/car/command?action=$action"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            // 使用异步请求，避免阻塞主线程
            client.newCall(request).enqueue(object : Callback {

                // 网络异常、连接失败等情况的回调
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        val msg = "指令[$action]发送失败：${e.message}"
                        tvStatus.text = msg
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                // 收到后端响应的回调
                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val body = res.body?.string() ?: ""
                        runOnUiThread {
                            tvStatus.text = if (res.isSuccessful) {
                                "指令[$action]发送成功：$body"
                            } else {
                                "指令[$action]发送失败，状态码：${res.code}"
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            val msg = "发送指令异常：${e.message}"
            tvStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 发送机械臂归位指令：POST /car/arm/reset
     * 无参数，请求体为空，让机械臂回到预设初始姿态
     */
    private fun sendArmReset() {
        sendSimplePostCommand("$baseUrl/car/arm/reset", "机械臂归位")
    }

    /**
     * 发送 LED 颜色控制指令：POST /car/led?color=xxx
     * color 取值：red / green / blue / white / off
     */
    private fun sendLedColor(color: String) {
        sendSimplePostCommand("$baseUrl/car/led?color=$color", "LED颜色[$color]")
    }

    /**
     * 发送 LED 模式控制指令：POST /car/led/mode?mode=xxx
     * mode 取值：steady（常亮） / flow（流水灯） / blink（闪烁）
     */
    private fun sendLedMode(mode: String) {
        sendSimplePostCommand("$baseUrl/car/led/mode?mode=$mode", "LED模式[$mode]")
    }

    /**
     * 切换喇叭的 响/停 状态，并发送对应指令：POST /car/buzzer?action=on|off
     */
    private fun toggleBuzzer() {
        val action = if (isBuzzerOn) "off" else "on"
        sendSimplePostCommand("$baseUrl/car/buzzer?action=$action", "喇叭[$action]") { success ->
            // 仅在请求成功时才切换状态和按钮文字，避免界面与实际状态不一致
            if (success) {
                isBuzzerOn = !isBuzzerOn
                btnBuzzer.text = if (isBuzzerOn) "喇叭：停" else "喇叭：响"
            }
        }
    }

    /**
     * 发送图像处理开关指令：POST /car/image/process?enabled=true|false
     * enabled 为 true 表示开启 YOLO/OpenCV 处理，false 表示关闭恢复原始画面
     */
    private fun sendImageProcessSwitch(enabled: Boolean) {
        val description = if (enabled) "图像处理开启" else "图像处理关闭"
        sendSimplePostCommand("$baseUrl/car/image/process?enabled=$enabled", description)
    }

    /**
     * 通用 POST 指令发送方法，用于 LED、喇叭等参数拼在 URL 上、请求体为空的接口
     * url 请求地址，description 用于在 tvStatus 上显示的指令描述，onResult 请求结束后的回调（是否成功）
     */
    private fun sendSimplePostCommand(url: String, description: String, onResult: ((Boolean) -> Unit)? = null) {
        val emptyBody = "".toRequestBody(null)
        val request = Request.Builder()
            .url(url)
            .post(emptyBody)
            .build()

        try {
            // 使用异步请求，避免阻塞主线程
            client.newCall(request).enqueue(object : Callback {

                // 网络异常、连接失败等情况的回调
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        tvStatus.text = "$description 发送失败：${e.message}"
                        onResult?.invoke(false)
                    }
                }

                // 收到后端响应的回调
                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        runOnUiThread {
                            tvStatus.text = if (res.isSuccessful) {
                                "$description 发送成功"
                            } else {
                                "$description 发送失败，状态码：${res.code}"
                            }
                            onResult?.invoke(res.isSuccessful)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            tvStatus.text = "$description 发送异常：${e.message}"
            onResult?.invoke(false)
        }
    }
}
