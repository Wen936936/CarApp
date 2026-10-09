package com.example.carapp

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
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

/**
 * 小车控制主界面
 * 通过 OkHttp 向后端发送 GET 请求控制小车前进、后退、停止、左转、右转
 * 同时通过 WebSocket 接收后端推送的摄像头图像并实时显示
 */
class MainActivity : AppCompatActivity() {

    // 后端服务器基础地址，需根据实际局域网 IP 修改
    private val baseUrl = "http://10.0.2.2:8080"

    // 摄像头图像推送地址，模拟器中 10.0.2.2 指向宿主机
    private val cameraWsUrl = "ws://10.0.2.2:8080/ws/camera"

    // OkHttp 客户端，全局复用一个实例，避免重复创建连接池
    private val client = OkHttpClient()

    // 摄像头 WebSocket 连接实例，便于在页面销毁时关闭
    private var cameraWebSocket: WebSocket? = null

    private lateinit var tvStatus: TextView
    private lateinit var ivCamera: ImageView

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

        // 页面创建时连接摄像头 WebSocket，开始接收图像推送
        connectCameraWebSocket()

        // 给摄像头画面绑定触摸监听，点击画面可控制云台转向
        ivCamera.setOnTouchListener { view, event ->
            // 只在手指抬起（ACTION_UP）时触发，避免按下瞬间就发请求
            if (event.action == MotionEvent.ACTION_UP) {
                // 将触摸点坐标换算成 ImageView 上的百分比（0~100），并限制在合法范围内
                val xPercent = (event.x / view.width * 100f).coerceIn(0f, 100f)
                val yPercent = (100f - event.y / view.height * 100f).coerceIn(0f, 100f)
                sendPtzCommand(xPercent, yPercent)
            }
            true
        }
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
     * 向后端发送云台转向指令
     * x、y 为点击位置在图像上的百分比坐标（0~100），通过 URL 查询参数传递（后端用 @RequestParam 接收）
     */
    private fun sendPtzCommand(x: Float, y: Float) {
        val xInt = x.toInt()
        val yInt = y.toInt()
        val url = "$baseUrl/car/ptz?x=$xInt&y=$yInt"

        // POST 请求体为空，参数全部拼在 URL 上
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
                        tvStatus.text = "云台指令发送失败：${e.message}"
                    }
                }

                // 收到后端响应的回调
                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        runOnUiThread {
                            tvStatus.text = if (res.isSuccessful) {
                                "云台指令发送成功（x=$xInt, y=$yInt）"
                            } else {
                                "云台指令发送失败，状态码：${res.code}"
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            tvStatus.text = "云台指令发送异常：${e.message}"
        }
    }
}
