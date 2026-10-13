package com.example.carapp

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 录制的单个动作
 * type：动作类型，取值 "command"（小车控制）/ "led"（LED颜色）/ "arm"（机械臂姿态）
 * params：该动作的参数，例如 "forward"、"red"、"90,90,90,90,90,90"
 * timestamp：相对录制开始时刻的毫秒数，回放时按它延迟发送
 */
data class RecordedAction(
    val type: String,
    val params: String,
    val timestamp: Long
)

/**
 * 小车控制主界面
 * 通过 OkHttp 向后端发送 GET 请求控制小车前进、后退、停止、左转、右转
 * 同时通过 WebSocket 接收后端推送的摄像头图像并实时显示
 * 另外通过 WebSocket 接收 AI 回复，并用 POST 把聊天文字发送给 AI
 */
class MainActivity : AppCompatActivity() {

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

    // SLAM 地图相关控件
    private lateinit var tvMapTitle: TextView
    private lateinit var ivMap: ImageView
    private lateinit var tvMapStatus: TextView

    // 从后端拉到的地图尺寸，点击地图时用它们把像素换算成地图格子坐标
    private var mapWidth = 0
    private var mapHeight = 0

    // 最近一次点击地图换算出的格子坐标，供“添加A/B/C点”按钮直接取用
    private var lastClickedMapX: Int? = null
    private var lastClickedMapY: Int? = null

    // ============ 多点巡逻相关 ============

    // 巡逻点坐标，初始为空，需先点地图再点“添加X点”才会被赋值
    private var pointA: Pair<Int, Int>? = null
    private var pointB: Pair<Int, Int>? = null
    private var pointC: Pair<Int, Int>? = null

    private lateinit var tvPatrolLog: TextView

    // 是否正在巡逻，以及当前该前往第几个点（0=A，1=B，2=C，循环取模）
    private var isPatrolling = false
    private var patrolIndex = 0

    // 巡逻循环用的 Handler，复用“录像定时抓帧”同一套思路：到点发指令，再排下一次
    private val patrolHandler = Handler(Looper.getMainLooper())
    private val patrolTask = object : Runnable {
        override fun run() {
            if (!isPatrolling) return

            val points = listOf("A" to pointA, "B" to pointB, "C" to pointC)
            val (name, point) = points[patrolIndex % points.size]

            // 理论上开始巡逻前已校验三个点都已设置，这里仅做兜底，异常时停止巡逻
            if (point == null) {
                tvPatrolLog.text = "巡逻点 $name 未设置，巡逻已停止"
                isPatrolling = false
                return
            }

            tvPatrolLog.text = "正在前往 $name 点...(${point.first},${point.second})"
            sendNavGoal(point.first, point.second)

            patrolIndex++
            // 延迟 3 秒模拟小车走到目标点耗时，再发下一个点
            patrolHandler.postDelayed(this, 3000)
        }
    }

    // 喇叭当前是否处于响的状态，用于点击按钮时在 响/停 之间切换
    private var isBuzzerOn = false

    // 机械臂 6 个关节滑动条的引用，松手发指令时需要一次性读取这 6 个的当前进度
    private lateinit var seekBase: SeekBar
    private lateinit var seekP1: SeekBar
    private lateinit var seekP2: SeekBar
    private lateinit var seekP3: SeekBar
    private lateinit var seekP4: SeekBar
    private lateinit var seekP5: SeekBar

    // ============ 相机基本功能相关 ============

    // 拍照、录像按钮，录像按钮的文字需要在运行时切换，所以存成成员变量
    private lateinit var btnRecord: Button

    // 画面缩放矩阵，每次缩放都重新算一遍再赋给 ivCamera
    private val cameraMatrix = Matrix()

    // 当前缩放倍数，限制在 0.5 ~ 3.0 之间，对应布局里各控件的显示尺寸
    private var cameraScale = 1.0f

    // 模拟录像：用主线程 Handler 每 500ms 抓一帧，帧序列存在内存 List 里
    private val recordHandler = Handler(Looper.getMainLooper())
    private val recordFrames = ArrayList<Bitmap>()
    private var isRecording = false
    // 抓帧任务，抽成字段是为了停止录像时能 removeCallbacks 掉
    private val recordTask = object : Runnable {
        override fun run() {
            // 抓一帧当前画面存进帧序列，抓完再排下一次，形成 500ms 一次的循环
            captureCameraBitmap()?.let { recordFrames.add(it) }
            if (isRecording) {
                recordHandler.postDelayed(this, 500)
            }
        }
    }

    // ============ 动作录制相关 ============
    // 命名为 isActionRecording 而不是 isRecording，是因为上面相机录像已经占用了 isRecording 这个名字

    // 本轮录制下来的动作序列，开始录制时清空，停止录制后保留供回放使用
    private val recordedActions = ArrayList<RecordedAction>()

    // 是否正在录制动作
    private var isActionRecording = false

    // 录制开始的系统时间，用于把每个动作换算成相对毫秒数
    private var actionRecordStartTime = 0L

    private lateinit var tvRecordLog: TextView
    private lateinit var btnPlayRecord: Button

    // 是否正在回放，回放期间禁用回放按钮，避免重复点击
    private var isPlayingActions = false

    // 回放用的 Handler，按每个动作的 timestamp 依次 postDelayed
    private val playbackHandler = Handler(Looper.getMainLooper())

    // ============ 设置：后端地址读取/保存 ============

    /**
     * 从 SharedPreferences 读取后端地址，没存过时默认用模拟器可访问的 10.0.2.2:8080
     */
    private fun loadBaseUrl(): String {
        val prefs = getSharedPreferences("car_app_prefs", MODE_PRIVATE)
        return prefs.getString("base_url", "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"
    }

    /**
     * 把当前后端地址换算成 WebSocket 地址：http -> ws，https -> wss，再拼上传入的路径
     */
    private fun loadWsUrl(path: String): String {
        val wsBase = loadBaseUrl()
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        return "$wsBase$path"
    }

    /**
     * 弹出设置对话框，让用户输入后端地址（如 http://192.168.0.105:8080）并保存到 SharedPreferences
     * 因为 WebSocket 连接和各处请求地址都是运行时读取，保存后仍提示需要重启 App，避免已建立的连接行为不一致
     */
    private fun showSettingsDialog() {
        val editText = EditText(this)
        editText.setText(loadBaseUrl())
        editText.hint = "例如 http://192.168.0.105:8080"
        // 给输入框加一点内边距，避免紧贴对话框边缘
        val padding = (16 * resources.displayMetrics.density).toInt()
        editText.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(this)
            .setTitle("设置后端地址")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val input = editText.text.toString().trim()
                if (input.isEmpty()) {
                    Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                getSharedPreferences("car_app_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("base_url", input)
                    .apply()
                Toast.makeText(this, "已保存，重启 App 后生效", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

        // 机械臂 6 个关节滑动条（base、p1~p5）：拖动中只更新数值显示，松手后统一发送一次指令
        seekBase = bindArmSeekBar(R.id.seekBase, R.id.tvBaseValue)
        seekP1 = bindArmSeekBar(R.id.seekP1, R.id.tvP1Value)
        seekP2 = bindArmSeekBar(R.id.seekP2, R.id.tvP2Value)
        seekP3 = bindArmSeekBar(R.id.seekP3, R.id.tvP3Value)
        seekP4 = bindArmSeekBar(R.id.seekP4, R.id.tvP4Value)
        seekP5 = bindArmSeekBar(R.id.seekP5, R.id.tvP5Value)

        // AI 对话区域：聊天记录框允许内部滚动，输入框与发送按钮绑定点击事件
        tvChatLog = findViewById(R.id.tvChatLog)
        // TextView 默认不可滚动，设置该 MovementMethod 后超出高度的内容可上下滑动查看
        tvChatLog.movementMethod = ScrollingMovementMethod()
        etChatInput = findViewById(R.id.etChatInput)
        findViewById<Button>(R.id.btnSendChat).setOnClickListener { sendChatMessage() }
        // 拍照发AI：抓取当前摄像头画面，转成 Base64 后连同文字一起发给 AI
        findViewById<Button>(R.id.btnSnapAndChat).setOnClickListener { snapAndSendToAi() }

        // SLAM 地图区域：绑定控件，拉取地图数据，并给地图加上点击选点事件
        tvMapTitle = findViewById(R.id.tvMapTitle)
        ivMap = findViewById(R.id.ivMap)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        ivMap.setOnTouchListener { _, event -> handleMapTouch(event) }
        loadMap()

        // 多点巡逻区域：绑定控件与点击事件
        tvPatrolLog = findViewById(R.id.tvPatrolLog)
        findViewById<Button>(R.id.btnAddPointA).setOnClickListener { addPatrolPoint("A") }
        findViewById<Button>(R.id.btnAddPointB).setOnClickListener { addPatrolPoint("B") }
        findViewById<Button>(R.id.btnAddPointC).setOnClickListener { addPatrolPoint("C") }
        findViewById<Button>(R.id.btnStartPatrol).setOnClickListener { startPatrol() }
        findViewById<Button>(R.id.btnStopPatrol).setOnClickListener { stopPatrol() }

        // 动作录制区域：绑定控件与点击事件
        tvRecordLog = findViewById(R.id.tvRecordLog)
        findViewById<Button>(R.id.btnStartRecord).setOnClickListener { startActionRecord() }
        findViewById<Button>(R.id.btnStopRecord).setOnClickListener { stopActionRecord() }
        btnPlayRecord = findViewById(R.id.btnPlayRecord)
        btnPlayRecord.setOnClickListener { playActionRecord() }

        // 页面创建时连接摄像头 WebSocket，开始接收图像推送
        connectCameraWebSocket()

        // 页面创建时连接 AI 对话 WebSocket，开始接收 AI 回复
        connectChatWebSocket()

        // ============ 相机基本功能：缩放 / 拍照 / 录像 ============
        // 放大：在当前倍数上乘 1.2
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { zoomCamera(1.2f) }
        // 缩小：在当前倍数上除 1.2
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { zoomCamera(1f / 1.2f) }
        // 拍照：把当前画面存进系统相册
        findViewById<Button>(R.id.btnSnap).setOnClickListener { saveCameraBitmapToGallery() }
        // 录像：开始/停止之间切换
        btnRecord = findViewById(R.id.btnRecord)
        btnRecord.setOnClickListener { toggleRecording() }

        // 设置按钮：弹出对话框修改后端地址
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
    }

    /**
     * 缩放摄像头画面：在当前倍数上乘以 factor，并限制在 0.5 ~ 3.0 之间
     */
    private fun zoomCamera(factor: Float) {
        cameraScale = (cameraScale * factor).coerceIn(0.5f, 3.0f)
        applyCameraMatrix()
    }

    /**
     * 按当前 cameraScale 重新计算 ivCamera 的显示矩阵
     *
     * 布局里 ivCamera 的 scaleType 是 matrix，这时 ImageView 只会照搬 imageMatrix，
     * 所以“等比铺满（centerCrop）”这件事需要我们自己算：
     * 1. base 是铺满容器所需的最小倍数，取宽高两个方向中较大的那个，保证不留黑边；
     * 2. 再乘上用户的缩放倍数 cameraScale；
     * 3. 最后把缩放后的图片居中，平移量为 (容器尺寸 - 图片尺寸) / 2。
     *
     * 注意：矩阵只影响“显示”，不改变 drawable 里的原始 Bitmap，
     * 因此 WebSocket 每帧 setImageBitmap 之后重新调一次本方法即可，两者互不干扰。
     */
    private fun applyCameraMatrix() {
        val drawable = ivCamera.drawable as? BitmapDrawable ?: return
        val bitmap = drawable.bitmap ?: return
        val viewWidth = ivCamera.width
        val viewHeight = ivCamera.height
        // 控件还没测量完成，或位图尺寸异常时直接跳过，避免除零和算出 NaN
        if (viewWidth <= 0 || viewHeight <= 0 ||
            bitmap.width <= 0 || bitmap.height <= 0
        ) {
            return
        }

        // 等比铺满容器的基础倍数：取宽高方向所需倍数的较大值
        val baseScale = maxOf(
            viewWidth.toFloat() / bitmap.width,
            viewHeight.toFloat() / bitmap.height
        )
        val totalScale = baseScale * cameraScale

        cameraMatrix.reset()
        cameraMatrix.setScale(totalScale, totalScale)
        // 缩放后居中显示
        cameraMatrix.postTranslate(
            (viewWidth - bitmap.width * totalScale) / 2f,
            (viewHeight - bitmap.height * totalScale) / 2f
        )
        ivCamera.imageMatrix = cameraMatrix
    }

    /**
     * 取出 ivCamera 当前显示的 Bitmap
     * 画面是从 WebSocket 收到的帧，ImageView 里存的就是原始位图，直接取出来用即可
     * 没有画面（还没收到第一帧）时返回 null
     */
    private fun captureCameraBitmap(): Bitmap? {
        val drawable = ivCamera.drawable as? BitmapDrawable ?: return null
        return drawable.bitmap
    }

    /**
     * 拍照：把当前画面以 JPEG 写入系统相册（MediaStore）
     * JPEG 压缩和磁盘写入都比较耗时，放到子线程做，完成后回主线程弹 Toast
     */
    private fun saveCameraBitmapToGallery() {
        // 用 ?: 提前返回，把下面的代码都收进非空分支，省掉反复判空
        val bitmap = captureCameraBitmap() ?: run {
            Toast.makeText(this, "还没有画面，无法拍照", Toast.LENGTH_SHORT).show()
            return
        }

        val displayName = "carapp_${System.currentTimeMillis()}.jpg"

        // 写相册属于磁盘操作，不能在主线程做，否则可能卡顿甚至 ANR
        Thread {
            var success = false
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10 起走分区存储，指定子目录即可，不再需要写权限
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/CarApp"
                        )
                        // 标记为"写入中"，写完再置 0，避免相册扫到半截文件
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )
                if (uri == null) {
                    Log.e("Snap", "插入 MediaStore 失败，uri 为空")
                } else {
                    resolver.openOutputStream(uri)?.use { output ->
                        // 90 的质量在清晰度和体积之间比较平衡
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                        success = true
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // 写入结束，清除 pending 标记，照片才会正式出现在相册里
                        val done = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        resolver.update(uri, done, null, null)
                    }
                }
            } catch (e: Exception) {
                Log.e("Snap", "保存照片失败：${e.message}")
            }

            runOnUiThread {
                val msg = if (success) "已保存到相册" else "保存失败，请查看日志"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                Log.d("Snap", msg)
            }
        }.start()
    }

    /**
     * 录像按钮：在开始/停止之间切换
     * 当前为模拟实现——不开真正的编码器，只是每 500ms 抓一帧存进内存
     * 后续要改成真 MP4 的话，把 recordTask 换成 MediaRecorder / MediaCodec 即可
     */
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    /**
     * 开始录像：清空上一轮的帧，把按钮文字换成“停止录像”，并启动定时抓帧
     */
    private fun startRecording() {
        // 先清掉上一轮残留的帧，避免两次录像的帧混在一起
        recordFrames.clear()
        isRecording = true
        btnRecord.text = "停止录像"
        Toast.makeText(this, "开始录像", Toast.LENGTH_SHORT).show()
        // 先等一个周期再抓第一帧，避免点击瞬间就抓到和上一轮重复的画面
        recordHandler.postDelayed(recordTask, 500)
    }

    /**
     * 停止录像：停掉定时任务，报告一共抓了多少帧
     */
    private fun stopRecording() {
        isRecording = false
        // 只置标志位不够，已排队的任务还会再跑一次，必须显式移除
        recordHandler.removeCallbacks(recordTask)
        btnRecord.text = "录像"
        Toast.makeText(this, "录像结束，共 ${recordFrames.size} 帧", Toast.LENGTH_SHORT).show()
        Log.d("Record", "录像结束，共 ${recordFrames.size} 帧")
        recordFrames.clear()
    }

    /**
     * 处理地图上的触摸事件，只在手指抬起（ACTION_UP）时触发导航
     * 这样拖动滚动页面时不会误发目标点，只有真正的点击才生效
     */
    private fun handleMapTouch(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true

        // 地图未加载成功时无法换算坐标，直接忽略点击
        if (mapWidth <= 0 || mapHeight <= 0) {
            tvMapStatus.text = "地图尚未加载，无法选点"
            return true
        }

        val viewWidth = ivMap.width
        val viewHeight = ivMap.height
        if (viewWidth <= 0 || viewHeight <= 0) return true

        // 触摸点在 ImageView 内按比例换算成地图格子坐标
        // 用 coerceIn 限制在合法范围内，避免点到边缘时算出越界坐标
        val x = ((event.x / viewWidth) * mapWidth).toInt().coerceIn(0, mapWidth - 1)
        val y = ((event.y / viewHeight) * mapHeight).toInt().coerceIn(0, mapHeight - 1)

        // 记下这次点击的坐标，供“添加A/B/C点”按钮直接取用
        lastClickedMapX = x
        lastClickedMapY = y

        tvMapStatus.text = "已选目标点：x=$x, y=$y，发送中…"
        sendNavGoal(x, y)
        return true
    }

    /**
     * 从后端拉取假地图数据：GET /car/map
     * 返回 JSON 形如：{"width":8,"height":8,"data":[100,100,0,...]}
     * data 按行优先排列，0 表示空地（白块），100 表示障碍（黑块）
     */
    private fun loadMap() {
        val request = Request.Builder()
            .url("${loadBaseUrl()}/car/map")
            .get()
            .build()

        try {
            // 使用异步请求，避免阻塞主线程
            client.newCall(request).enqueue(object : Callback {

                // 网络异常、连接失败等情况的回调
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        tvMapStatus.text = "地图加载失败：${e.message}"
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        // 请求失败时不用读 body，直接在界面提示状态码
                        if (!res.isSuccessful) {
                            runOnUiThread {
                                tvMapStatus.text = "地图加载失败，状态码：${res.code}"
                            }
                            return
                        }

                        val body = res.body?.string() ?: ""
                        // 打印原始响应，方便对比后端到底返回了什么结构
                        Log.d("Map", "地图原始响应：$body")
                        // 解析和绘图比较耗时，放到子线程做，画好后再回主线程贴图
                        try {
                            val json = JSONObject(body)
                            val width = json.getInt("width")
                            val height = json.getInt("height")
                            val dataArray = json.getJSONArray("data")

                            // 统计障碍格数量，用于判断是否"解析成功但画面全白"
                            // 后端约定非 0 即障碍（历史上是 1，现在是 100），所以按 != 0 统计
                            val cells = flattenMapData(dataArray)
                            val obstacleCount = cells.count { it != 0 }
                            Log.d(
                                "Map",
                                "地图尺寸 ${width}x${height}，data 元素 ${dataArray.length()} 个，" +
                                    "拍平后 ${cells.size} 个，其中障碍 $obstacleCount 个"
                            )

                            // 解析成功但数据为空或长度对不上，属于后端返回格式不对，明确提示而不是静默画白图
                            if (cells.isEmpty()) {
                                runOnUiThread {
                                    tvMapStatus.text = "地图数据为空，请检查后端 /car/map 的 data 字段"
                                }
                                return
                            }

                            val bitmap = drawMapBitmap(width, height, dataArray)
                            mapWidth = width
                            mapHeight = height

                            runOnUiThread {
                                ivMap.setImageBitmap(bitmap)
                                tvMapStatus.text = if (obstacleCount == 0) {
                                    // 全是空地时提示一下，避免用户以为地图没画出来
                                    "地图已加载：${width} x ${height}（全部为空地），点击地图选目标点"
                                } else {
                                    "地图已加载：${width} x ${height}，障碍 $obstacleCount 个，点击地图选目标点"
                                }
                            }
                        } catch (e: Exception) {
                            // JSON 字段缺失或格式不对时提示，避免界面一直停在旧状态
                            Log.e("Map", "地图解析失败：${e.message}")
                            runOnUiThread {
                                tvMapStatus.text = "地图数据解析失败：${e.message}"
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            tvMapStatus.text = "地图请求异常：${e.message}"
        }
    }

    /**
     * 把地图格子数据画成一张 Bitmap：非 0 画黑块（障碍），0 画白块（空地）
     * width/height 为格子数，data 为按行优先排列的格子值数组
     */
    private fun drawMapBitmap(width: Int, height: Int, data: JSONArray): Bitmap {
        // 每个格子占多少像素。原来只有 8，拉伸到 ImageView 后线条会糊成一片，放大到 40 更清晰
        val cellSize = 40
        val bitmap = Bitmap.createBitmap(width * cellSize, height * cellSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 先铺一层白色底，保证 0 的位置是白块
        canvas.drawColor(Color.WHITE)

        val paint = Paint()

        // 兼容扁平数组 [0,1,...] 和嵌套数组 [[0,1],[1,0]] 两种格式，统一拍平后按行优先取值
        val cells = flattenMapData(data)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                // 数据长度不足时按空白处理，避免越界崩溃
                if (index >= cells.size) continue
                // 非 0 一律当障碍画黑块（后端目前用 100 表示障碍，0 表示空地）
                if (cells[index] != 0) {
                    paint.color = Color.BLACK
                    canvas.drawRect(
                        (x * cellSize).toFloat(),
                        (y * cellSize).toFloat(),
                        ((x + 1) * cellSize).toFloat(),
                        ((y + 1) * cellSize).toFloat(),
                        paint
                    )
                }
            }
        }

        // 画灰色网格线。这一步是关键：即使地图全是空地（全 0），也能看出 8x8 的格子结构，
        // 不会像一张白纸那样让人以为"图没加载出来"
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#999999")
        paint.strokeWidth = 1f
        for (x in 0..width) {
            val px = (x * cellSize).toFloat()
            canvas.drawLine(px, 0f, px, (height * cellSize).toFloat(), paint)
        }
        for (y in 0..height) {
            val py = (y * cellSize).toFloat()
            canvas.drawLine(0f, py, (width * cellSize).toFloat(), py, paint)
        }

        // 外边框加粗描边，让地图边界更明显
        paint.color = Color.parseColor("#333333")
        paint.strokeWidth = 3f
        canvas.drawRect(
            1.5f,
            1.5f,
            (width * cellSize) - 1.5f,
            (height * cellSize) - 1.5f,
            paint
        )

        return bitmap
    }

    /**
     * 把地图 data 统一拍平成一维 Int 列表
     * 后端可能返回扁平数组 [0,1,0,...]，也可能返回按行嵌套的数组 [[0,1],[1,0]]
     * 嵌套数组直接 optInt 会全部取到默认值 0（不报错），画出来就是一张全白图，所以这里先做兼容
     */
    private fun flattenMapData(data: JSONArray): List<Int> {
        val result = ArrayList<Int>()
        // 第一个元素仍是 JSONArray，说明是嵌套结构，逐行拍平
        if (data.length() > 0 && data.optJSONArray(0) != null) {
            for (r in 0 until data.length()) {
                val row = data.optJSONArray(r) ?: continue
                for (c in 0 until row.length()) {
                    result.add(row.optInt(c, 0))
                }
            }
        } else {
            for (i in 0 until data.length()) {
                result.add(data.optInt(i, 0))
            }
        }
        return result
    }

    /**
     * 发送导航目标点：POST /car/nav/goal?x=xxx&y=yyy
     * 当前后端为占位实现，会返回“导航功能开发中”，这里把返回内容显示到 tvMapStatus
     */
    private fun sendNavGoal(x: Int, y: Int) {
        val url = "${loadBaseUrl()}/car/nav/goal?x=$x&y=$y"
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
                        tvMapStatus.text = "导航目标点($x,$y)发送失败：${e.message}"
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val body = res.body?.string() ?: ""
                        runOnUiThread {
                            tvMapStatus.text = if (res.isSuccessful) {
                                "导航目标点($x,$y)：$body"
                            } else {
                                "导航目标点($x,$y)发送失败，状态码：${res.code}"
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            tvMapStatus.text = "导航请求异常：${e.message}"
        }
    }

    /**
     * 把地图上最近一次点击的坐标存成巡逻点 A/B/C
     * 还没点过地图时 lastClickedMapX/Y 为空，提示用户先点地图选点
     */
    private fun addPatrolPoint(name: String) {
        val x = lastClickedMapX
        val y = lastClickedMapY
        if (x == null || y == null) {
            Toast.makeText(this, "请先点击地图选择坐标，再添加 $name 点", Toast.LENGTH_SHORT).show()
            return
        }

        when (name) {
            "A" -> pointA = x to y
            "B" -> pointB = x to y
            "C" -> pointC = x to y
        }

        tvPatrolLog.text = patrolSummaryText()
    }

    /**
     * 拼出当前 A/B/C 三个巡逻点的坐标摘要，未设置的点显示“未设置”
     */
    private fun patrolSummaryText(): String {
        fun fmt(p: Pair<Int, Int>?) = p?.let { "(${it.first},${it.second})" } ?: "未设置"
        return "A点:${fmt(pointA)}  B点:${fmt(pointB)}  C点:${fmt(pointC)}"
    }

    /**
     * 开始巡逻：A/B/C 三个点必须都已设置，否则提示用户先补齐
     * 从 A 点开始，立即发第一个目标点，后续由 patrolTask 每 3 秒接力发送下一个点
     */
    private fun startPatrol() {
        if (isPatrolling) {
            Toast.makeText(this, "巡逻已在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (pointA == null || pointB == null || pointC == null) {
            Toast.makeText(this, "请先设置 A、B、C 三个巡逻点", Toast.LENGTH_SHORT).show()
            return
        }

        isPatrolling = true
        patrolIndex = 0
        patrolHandler.post(patrolTask)
    }

    /**
     * 停止巡逻：置标志位并移除已排队的巡逻任务，避免停止后还会再发一次
     */
    private fun stopPatrol() {
        isPatrolling = false
        patrolHandler.removeCallbacks(patrolTask)
        tvPatrolLog.text = "巡逻已停止"
    }

    /**
     * 开始录制动作：清空上一轮的历史记录，记下开始时间，并置位 isActionRecording
     */
    private fun startActionRecord() {
        recordedActions.clear()
        isActionRecording = true
        actionRecordStartTime = System.currentTimeMillis()
        tvRecordLog.text = "开始录制...\n"
        Toast.makeText(this, "开始录制动作", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止录制动作：只置位标志，已经录到的 recordedActions 原样保留，供“回放”使用
     */
    private fun stopActionRecord() {
        isActionRecording = false
        Toast.makeText(this, "录制结束，共 ${recordedActions.size} 个动作", Toast.LENGTH_SHORT).show()
    }

    /**
     * 记录一个动作：仅在 isActionRecording 为 true 时生效
     * 计算相对录制开始时刻的毫秒数存入 recordedActions，并把记录追加显示到 tvRecordLog
     * 这个方法会在 sendCommand、sendLedColor、sendArmPose 内部被调用，覆盖小车控制/LED/机械臂三类动作
     */
    private fun recordAction(type: String, params: String) {
        if (!isActionRecording) return
        val elapsed = System.currentTimeMillis() - actionRecordStartTime
        recordedActions.add(RecordedAction(type, params, elapsed))
        tvRecordLog.append("[${elapsed / 1000.0}s] $type: $params\n")
    }

    /**
     * 回放录制的动作：按每个动作的 timestamp 依次延迟发送，回放中禁用“回放”按钮避免重复点击
     */
    private fun playActionRecord() {
        if (isPlayingActions) return
        if (recordedActions.isEmpty()) {
            Toast.makeText(this, "还没有录制任何动作", Toast.LENGTH_SHORT).show()
            return
        }

        isPlayingActions = true
        btnPlayRecord.isEnabled = false
        btnPlayRecord.text = "回放中..."

        for (action in recordedActions) {
            // postDelayed 的延迟就是动作录制时的相对毫秒数，第 1 个动作在 0.5s 就延迟 500ms 执行
            playbackHandler.postDelayed({ executeRecordedAction(action) }, action.timestamp)
        }

        // 最后一个动作执行完之后，再恢复“回放”按钮
        val lastTimestamp = recordedActions.last().timestamp
        playbackHandler.postDelayed({
            isPlayingActions = false
            btnPlayRecord.isEnabled = true
            btnPlayRecord.text = "回放"
            Toast.makeText(this, "回放结束", Toast.LENGTH_SHORT).show()
        }, lastTimestamp + 200)
    }

    /**
     * 按动作类型把一条录制记录重新发送出去
     * command -> sendCommand，led -> sendLedColor，arm -> 把 "base,p1,p2,p3,p4,p5" 拆开发送机械臂姿态
     */
    private fun executeRecordedAction(action: RecordedAction) {
        when (action.type) {
            "command" -> sendCommand(action.params)
            "led" -> sendLedColor(action.params)
            "arm" -> {
                val values = action.params.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (values.size == 6) {
                    // 同步更新滑动条显示，让界面看起来和回放的机械臂姿态一致
                    seekBase.progress = values[0]
                    seekP1.progress = values[1]
                    seekP2.progress = values[2]
                    seekP3.progress = values[3]
                    seekP4.progress = values[4]
                    seekP5.progress = values[5]
                    sendArmPose(values[0], values[1], values[2], values[3], values[4], values[5])
                }
            }
        }
    }

    /**
     * 连接 AI 对话 WebSocket，接收后端推送的 AI 回复并追加到聊天记录
     * 消息格式约定为 JSON：{"text": "AI 回复内容"}
     */
    private fun connectChatWebSocket() {
        val request = Request.Builder()
            .url(loadWsUrl("/ws/chat"))
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

                        // action 字段后端已自动执行，App 端不需要再调用任何指令接口，
                        // 这里只是在聊天记录里把具体执行的动作附带展示出来，方便确认
                        val action = msg.optString("action")
                        val displayLine = if (action.isNotEmpty()) {
                            "AI：$aiText（动作：$action）"
                        } else {
                            "AI：$aiText"
                        }

                        runOnUiThread { appendChatLog(displayLine) }
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
     * 3. 后端接口已改为 POST + JSON body：{"text":"xxx","image":""}，AI 的回复仍由 WebSocket 推送
     */
    private fun sendChatMessage() {
        val input = etChatInput.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        appendChatLog("我：$input")
        etChatInput.setText("")

        // 纯文字发送时 image 字段传空字符串，用 JSONObject 拼出请求体，避免手动拼接字符串转义出错
        val json = JSONObject().apply {
            put("text", input)
            put("image", "")
        }.toString()
        // Content-Type 设为 application/json，RequestBody 带上这个 MediaType 后 OkHttp 会自动写入请求头
        val requestBody = json.toRequestBody("application/json".toMediaType())
        val url = "${loadBaseUrl()}/car/ai/chat"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
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
     * 拍照发AI：抓取当前摄像头画面，转成 Base64 后连同输入框文字一起发给 AI
     * 1. 没有画面（还没收到第一帧）时弹 Toast 提示，不发送
     * 2. 有画面时压缩成 JPEG 再转 Base64，输入框为空则用默认文案“看看这是什么”
     * 3. 后端接口已改为 POST + JSON body：{"text":"xxx","image":"base64字符串"}，AI 的回复仍由 WebSocket 推送
     */
    private fun snapAndSendToAi() {
        // 复用现有的抓帧方法，没有画面时直接提示并返回
        val bitmap = captureCameraBitmap() ?: run {
            Toast.makeText(this, "还没有画面，无法发送", Toast.LENGTH_SHORT).show()
            return
        }

        // 压缩成 JPEG 再转 Base64，90 的质量在清晰度和体积之间比较平衡
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        // 输入框为空时使用默认文案
        val inputText = etChatInput.text.toString().trim().ifEmpty { "看看这是什么" }

        appendChatLog("我：[图片] $inputText")
        etChatInput.setText("")

        // 用 JSONObject 拼请求体，text 和 base64 图片都放进 JSON 字段，不再需要 URL 编码
        val json = JSONObject().apply {
            put("text", inputText)
            put("image", base64Image)
        }.toString()
        // Content-Type 设为 application/json，RequestBody 带上这个 MediaType 后 OkHttp 会自动写入请求头
        val requestBody = json.toRequestBody("application/json".toMediaType())
        val url = "${loadBaseUrl()}/car/ai/chat"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            // 使用异步请求，避免阻塞主线程
            client.newCall(request).enqueue(object : Callback {

                // 网络异常、连接失败等情况的回调
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        tvStatus.text = "拍照发AI失败：${e.message}"
                    }
                }

                // 收到后端响应的回调，这里只关心是否发送成功，AI 回复走 WebSocket
                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        runOnUiThread {
                            tvStatus.text = if (res.isSuccessful) {
                                "已发送图片给 AI"
                            } else {
                                "拍照发AI失败，状态码：${res.code}"
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 捕获构建请求或发起请求过程中可能出现的异常
            tvStatus.text = "拍照发AI异常：${e.message}"
        }
    }

    /**
     * 绑定单个机械臂关节滑动条：拖动中只同步右侧的数值显示，
     * 松手（onStopTrackingTouch）时触发一次完整的姿态发送，把 6 个滑动条的值一起发给后端
     * 返回 SeekBar 引用，方便外部统一持有，在发送指令时读取所有滑动条的当前进度
     */
    private fun bindArmSeekBar(seekBarId: Int, valueTextViewId: Int): SeekBar {
        val seekBar = findViewById<SeekBar>(seekBarId)
        val valueText = findViewById<TextView>(valueTextViewId)
        // 初始化时先把当前进度显示出来，避免与布局里的默认文字不一致
        valueText.text = seekBar.progress.toString()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = progress.toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            // 松手才发送，避免拖动过程中产生大量重复请求
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sendArmPose()
            }
        })
        return seekBar
    }

    /**
     * 发送机械臂姿态指令：POST /car/arm?p1=xxx&p2=xxx&p3=xxx&p4=xxx&p5=xxx&base=xxx
     * 不传参数时默认读取 6 个滑动条当前的进度值；回放录制动作时会显式传入保存的 6 个数字
     */
    private fun sendArmPose(
        base: Int = seekBase.progress,
        p1: Int = seekP1.progress,
        p2: Int = seekP2.progress,
        p3: Int = seekP3.progress,
        p4: Int = seekP4.progress,
        p5: Int = seekP5.progress
    ) {
        // 正在录制动作时，把这次机械臂姿态记下来，供后续“回放”使用
        recordAction("arm", "$base,$p1,$p2,$p3,$p4,$p5")

        val url = "${loadBaseUrl()}/car/arm?p1=$p1&p2=$p2&p3=$p3&p4=$p4&p5=$p5&base=$base"
        sendSimplePostCommand(url, "机械臂姿态")
    }

    /**
     * 连接后端摄像头 WebSocket，接收图像推送并显示
     */
    private fun connectCameraWebSocket() {
        val request = Request.Builder()
            .url(loadWsUrl("/ws/camera"))
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
                            runOnUiThread {
                                ivCamera.setImageBitmap(bitmap)
                                // 新位图会顶掉旧的显示矩阵，这里按当前缩放倍数重算一次，
                                // 保证用户放大后画面不会因为新帧到来而跳回原始大小
                                applyCameraMatrix()
                            }
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

        // 页面被销毁时若还在"录像"，停掉定时抓帧并释放帧序列，
        // 否则 Handler 会一直持有 Activity 引用导致内存泄漏
        if (isRecording) {
            isRecording = false
            recordHandler.removeCallbacks(recordTask)
        }
        recordFrames.clear()

        // 页面销毁时若还在巡逻，同样要停掉循环任务，避免 Handler 持有 Activity 引用
        if (isPatrolling) {
            isPatrolling = false
            patrolHandler.removeCallbacks(patrolTask)
        }

        // 页面销毁时若还在回放动作，移除所有已排队的回放任务，避免 Handler 持有 Activity 引用
        if (isPlayingActions) {
            isPlayingActions = false
            playbackHandler.removeCallbacksAndMessages(null)
        }
    }

    /**
     * 向后端发送控制指令
     * action 取值：forward / backward / stop / left / right
     */
    private fun sendCommand(action: String) {
        // 正在录制动作时，把这次小车控制记下来，供后续“回放”使用
        recordAction("command", action)

        val url = "${loadBaseUrl()}/car/command?action=$action"
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
        sendSimplePostCommand("${loadBaseUrl()}/car/arm/reset", "机械臂归位")
        // 归位请求发出后，界面上的 6 个滑动条也要同步回到默认值 90，
        // 否则后端已经归位但 App 上滑动条和数字还停留在旧的位置，容易让人误以为没生效。
        // 设置 progress 会触发 bindArmSeekBar 里注册的 onProgressChanged 监听，
        // 对应的数字 TextView 也会跟着自动刷新为 "90"，不需要再手动赋值 text。
        seekBase.progress = 90
        seekP1.progress = 90
        seekP2.progress = 90
        seekP3.progress = 90
        seekP4.progress = 90
        seekP5.progress = 90
    }

    /**
     * 发送 LED 颜色控制指令：POST /car/led?color=xxx
     * color 取值：red / green / blue / white / off
     */
    private fun sendLedColor(color: String) {
        // 正在录制动作时，把这次 LED 颜色记下来，供后续“回放”使用
        recordAction("led", color)

        sendSimplePostCommand("${loadBaseUrl()}/car/led?color=$color", "LED颜色[$color]")
    }

    /**
     * 发送 LED 模式控制指令：POST /car/led/mode?mode=xxx
     * mode 取值：steady（常亮） / flow（流水灯） / blink（闪烁）
     */
    private fun sendLedMode(mode: String) {
        sendSimplePostCommand("${loadBaseUrl()}/car/led/mode?mode=$mode", "LED模式[$mode]")
    }

    /**
     * 切换喇叭的 响/停 状态，并发送对应指令：POST /car/buzzer?action=on|off
     */
    private fun toggleBuzzer() {
        val action = if (isBuzzerOn) "off" else "on"
        sendSimplePostCommand("${loadBaseUrl()}/car/buzzer?action=$action", "喇叭[$action]") { success ->
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
        sendSimplePostCommand("${loadBaseUrl()}/car/image/process?enabled=$enabled", description)
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
