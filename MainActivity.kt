package com.example.carapp

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 小车控制主界面
 * 通过 OkHttp 向后端发送 GET 请求控制小车前进、停止、左转、右转
 */
class MainActivity : AppCompatActivity() {

    // 后端服务器基础地址，需根据实际局域网 IP 修改
    private val baseUrl = "http://192.168.0.105:8080"

    // OkHttp 客户端，全局复用一个实例，避免重复创建连接池
    private val client = OkHttpClient()

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        val btnForward = findViewById<Button>(R.id.btnForward)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnLeft = findViewById<Button>(R.id.btnLeft)
        val btnRight = findViewById<Button>(R.id.btnRight)

        // 绑定四个方向按钮的点击事件
        btnForward.setOnClickListener { sendCommand("forward") }
        btnStop.setOnClickListener { sendCommand("stop") }
        btnLeft.setOnClickListener { sendCommand("left") }
        btnRight.setOnClickListener { sendCommand("right") }
    }

    /**
     * 向后端发送控制指令
     * action 取值：forward / stop / left / right
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
}
