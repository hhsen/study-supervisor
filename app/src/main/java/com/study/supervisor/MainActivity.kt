package com.study.supervisor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class Question(
    val text: String,
    val options: List<String>,
    val answer: Int,
    val subject: String
)

object StudyNotifications {
    const val CHANNEL_ID = "study_check_channel"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "学习突击检查",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun show(context: Context) {
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHECK, true)
        }
        val pending = PendingIntent.getActivity(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("突击检查：请证明你正在学习")
            .setContentText("上传一张学习现场照片，并完成一道题")
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(1001, notification)
        }
    }
}

object Scheduler {
    private const val UNIQUE_WORK = "study_random_check"

    fun ensureScheduled(context: Context) {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(UNIQUE_WORK)
            .get()
        val active = infos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        if (!active) scheduleNext(context)
    }

    fun scheduleNext(context: Context) {
        val prefs = context.getSharedPreferences("study_supervisor", Context.MODE_PRIVATE)
        val base = prefs.getInt("interval", 25)
        val delay = Random.nextInt(base, base + 21).toLong()

        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        StudyNotifications.show(applicationContext)
        Scheduler.scheduleNext(applicationContext)
        return Result.success()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Scheduler.ensureScheduled(context)
        }
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_CHECK = "open_check"
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var statusText: TextView
    private var photoSelected = false
    private var photoPreview: ImageView? = null
    private var currentQuestion: Question? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            photoPreview?.setImageURI(uri)
            photoPreview?.visibility = View.VISIBLE
            photoSelected = true
        }
    }

    @Suppress("DEPRECATION")
    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val bitmap = result.data?.getParcelableExtra<Bitmap>("data")
            if (bitmap != null) {
                photoPreview?.setImageBitmap(bitmap)
                photoPreview?.visibility = View.VISIBLE
                photoSelected = true
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "提醒已开启" else "未获得通知权限",
                Toast.LENGTH_SHORT
            ).show()
            Scheduler.ensureScheduled(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("study_supervisor", MODE_PRIVATE)
        StudyNotifications.createChannel(this)
        buildUi()
        Scheduler.ensureScheduled(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHECK, false)) {
            showCheck()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHECK, false)) {
            showCheck()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFFF4F7F5.toInt())
        }

        root.addView(TextView(this).apply {
            text = "自习监督员"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "把“我在学”变成看得见的证据"
            textSize = 14f
            setTextColor(0xFF68756E.toInt())
        })

        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF176B4D.toInt())
            setPadding(0, 36, 0, 8)
        }
        root.addView(statusText)

        val subject = makeInput("学习学科 / 内容")
        subject.setText(prefs.getString("subject", ""))
        root.addView(makeLabel("学习学科 / 内容"))
        root.addView(subject)

        val keywords = makeInput("关键词，用逗号分隔")
        keywords.setText(prefs.getString("keywords", ""))
        root.addView(makeLabel("关键词"))
        root.addView(keywords)

        val book = makeInput("英语教材")
        book.setText(prefs.getString("book", "刘晓燕《英语四级你还在背单词吗》"))
        root.addView(makeLabel("英语教材"))
        root.addView(book)

        val pageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pageStart = makeInput("起始页").apply {
            setText(prefs.getInt("pageStart", 1).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val pageEnd = makeInput("结束页").apply {
            setText(prefs.getInt("pageEnd", 20).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        pageRow.addView(pageStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pageRow.addView(pageEnd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(makeLabel("英语出题页码范围"))
        root.addView(pageRow)

        val spinner = Spinner(this)
        val intervals = arrayOf("15", "25", "45", "60")
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervals.map { "每 $it–${it.toInt() + 20} 分钟" }
        )
        spinner.setSelection(intervals.indexOf(prefs.getInt("interval", 25).toString()).coerceAtLeast(0))
        root.addView(makeLabel("突击检查间隔"))
        root.addView(spinner)

        root.addView(makeButton("保存学习计划") {
            prefs.edit()
                .putString("subject", subject.text.toString().trim())
                .putString("keywords", keywords.text.toString().trim())
                .putString("book", book.text.toString().trim())
                .putInt("pageStart", pageStart.text.toString().toIntOrNull() ?: 1)
                .putInt("pageEnd", pageEnd.text.toString().toIntOrNull() ?: 20)
                .putInt("interval", intervals[spinner.selectedItemPosition].toInt())
                .apply()
            Scheduler.scheduleNext(this)
            Toast.makeText(this, "学习计划已保存", Toast.LENGTH_SHORT).show()
            updateStatus()
        })

        root.addView(makeButton("开启通知提醒") {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Scheduler.ensureScheduled(this)
                Toast.makeText(this, "提醒已开启", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(makeButton("立即突击检查") {
            showCheck()
        })

        root.addView(TextView(this).apply {
            text = "已完成检查：${prefs.getInt("done", 0)} 次    正确：${prefs.getInt("good", 0)} 次"
            textSize = 14f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 36, 0, 0)
        })

        setContentView(root)
        updateStatus()
    }

    private fun updateStatus() {
        val done = prefs.getInt("done", 0)
        val good = prefs.getInt("good", 0)
        statusText.text = when {
            done == 0 -> "准备开始今天的自习"
            done == good -> "今天的状态很稳，继续保持"
            else -> "已完成 $done 次检查，正确 $good 次"
        }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }
    }

    private fun makeInput(hint: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setPadding(24, 20, 24, 20)
        }
    }

    private fun makeButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }
    }

    private fun randomQuestion(): Question {
        val subject = prefs.getString("subject", "") ?: ""
        val keywords = (prefs.getString("keywords", "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val book = prefs.getString("book", "") ?: ""
        val start = prefs.getInt("pageStart", 1)
        val end = prefs.getInt("pageEnd", 20)

        return when {
            subject.contains("英语") || book.contains("英语四级") -> Question(
                "根据 $book 第 $start–$end 页，下列哪个词最接近“坚持不懈”的含义？",
                listOf("persist", "pretend", "provide", "predict"),
                0,
                "英语四级"
            )
            subject.contains("数学") -> Question(
                if (keywords.isNotEmpty()) "关于“${keywords.first()}”，下列说法正确的是？"
                else "函数在某点可导，是否一定能推出该点连续？",
                listOf("能", "不能", "一定不", "无法判断"),
                if (keywords.isNotEmpty()) 1 else 0,
                subject
            )
            subject.contains("物理") -> Question(
                "牛顿第二定律的表达式是？",
                listOf("F=ma", "E=mc²", "V=IR", "p=mv²"),
                0,
                subject
            )
            else -> Question(
                if (keywords.isNotEmpty()) "请用一句话解释“${keywords.first()}”的核心含义。"
                else "请简述今天学习内容中最重要的一个结论。",
                listOf("已掌握", "需要复习", "还不理解", "稍后补充"),
                0,
                subject.ifEmpty { "学习" }
            )
        }
    }

    private fun showCheck() {
        currentQuestion = randomQuestion()
        photoSelected = false

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        layout.addView(TextView(this).apply {
            text = currentQuestion?.text
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 16)
        })

        val options = RadioGroup(this)
        currentQuestion?.options?.forEachIndexed { index, option ->
            options.addView(RadioButton(this).apply {
                text = option
                id = View.generateViewId()
                tag = index
                setPadding(12, 12, 12, 12)
            })
        }
        layout.addView(options)

        val preview = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            maxHeight = 600
            setPadding(0, 16, 0, 8)
        }
        photoPreview = preview

        layout.addView(Button(this).apply {
            text = "拍照 / 选择学习证明"
            setOnClickListener {
                val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (camera.resolveActivity(packageManager) != null) {
                    takePhoto.launch(camera)
                } else {
                    pickPhoto.launch("image/*")
                }
            }
        })
        layout.addView(preview)

        val result = TextView(this).apply {
            visibility = View.GONE
            setPadding(0, 12, 0, 0)
        }
        layout.addView(result)

        val dialog = AlertDialog.Builder(this)
            .setTitle("突击检查：请证明你正在学习")
            .setView(layout)
            .setPositiveButton("提交证明", null)
            .setNegativeButton("稍后处理", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedId = options.checkedRadioButtonId
                if (!photoSelected) {
                    result.visibility = View.VISIBLE
                    result.setTextColor(0xFFB54242.toInt())
                    result.text = "请先上传一张学习现场照片。"
                    return@setOnClickListener
                }
                if (selectedId == -1) {
                    result.visibility = View.VISIBLE
                    result.setTextColor(0xFFB54242.toInt())
                    result.text = "请先选择一个答案。"
                    return@setOnClickListener
                }

                val selected = options.findViewById<RadioButton>(selectedId)
                val index = selected.tag as Int
                val correct = index == currentQuestion?.answer
                val done = prefs.getInt("done", 0) + 1
                val good = prefs.getInt("good", 0) + if (correct) 1 else 0
                prefs.edit().putInt("done", done).putInt("good", good).apply()

                result.visibility = View.VISIBLE
                result.setTextColor(if (correct) 0xFF176B4D.toInt() else 0xFFB54242.toInt())
                result.text = if (correct) "证明提交成功，回答正确！"
                else "照片已记录，这道题还需要复习。"

                updateStatus()
                window.decorView.postDelayed({ dialog.dismiss() }, 1200L)
            }
        }

        dialog.show()
    }
}
