package com.study.supervisor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
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
                "英语学习突击检查",
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
            .setContentTitle("英语突击检查")
            .setContentText("上传学习证明，并完成一道英语题")
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
    private const val UNIQUE_WORK = "english_random_check"

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
        val min = prefs.getInt("interval_min", 25).coerceAtLeast(1)
        val max = prefs.getInt("interval_max", 45).coerceAtLeast(min)
        val delay = Random.nextInt(min, max + 1).toLong()

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

        private val MEANINGS = mapOf(
            "study" to "学习", "book" to "书", "word" to "单词", "sentence" to "句子",
            "phrase" to "短语", "knowledge" to "知识", "focus" to "专注", "review" to "复习",
            "persist" to "坚持", "allocate" to "分配", "achieve" to "实现", "effort" to "努力",
            "progress" to "进步", "practice" to "练习", "remember" to "记住", "understand" to "理解",
            "english" to "英语", "learn" to "学习", "reading" to "阅读", "writing" to "写作",
            "listen" to "听", "speak" to "说", "daily" to "每天的", "important" to "重要的",
            "difficult" to "困难的", "success" to "成功", "goal" to "目标", "plan" to "计划",
            "time" to "时间", "day" to "天", "night" to "晚上", "morning" to "早上"
        )
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var wordCountText: TextView
    private lateinit var wordPreview: TextView
    private lateinit var generatedPreview: TextView
    private var photoSelected = false
    private var photoPreview: ImageView? = null
    private var currentQuestion: Question? = null

    private val pickProof = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            photoPreview?.setImageURI(uri)
            photoPreview?.visibility = View.VISIBLE
            photoSelected = true
        }
    }

    @Suppress("DEPRECATION")
    private val takeProof = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val bitmap = result.data?.getParcelableExtra<Bitmap>("data")
            if (bitmap != null) {
                photoPreview?.setImageBitmap(bitmap)
                photoPreview?.visibility = View.VISIBLE
                photoSelected = true
            }
        }
    }

    private val pickWordPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) recognizeWords(uris)
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
        if (intent.getBooleanExtra(EXTRA_OPEN_CHECK, false)) showCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHECK, false)) showCheck()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 60)
            setBackgroundColor(0xFFF3F7F5.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "英语自习监督员"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF153F31.toInt())
        })
        root.addView(TextView(this).apply {
            text = "上传单词 · 生成短语句子 · 突击检查"
            textSize = 14f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 6, 0, 24)
        })

        val statusCard = makeCard("今日状态")
        statusText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF176B4D.toInt())
        }
        statusCard.addView(statusText)
        statusCard.addView(TextView(this).apply {
            text = "每次检查都需要照片证明 + 一道英语题"
            textSize = 13f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 8, 0, 0)
        })
        root.addView(statusCard)

        val rangeCard = makeCard("英语学习范围")
        rangeCard.addView(makeLabel("教材"))
        rangeCard.addView(makeStaticText("刘晓燕《英语四级你还在背单词吗》"))
        rangeCard.addView(makeLabel("出题页码范围"))

        val pageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pageStart = makeInput("起始页").apply {
            setText(prefs.getInt("pageStart", 1).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val pageEnd = makeInput("结束页").apply {
            setText(prefs.getInt("pageEnd", 20).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        pageRow.addView(pageStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pageRow.addView(pageEnd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rangeCard.addView(pageRow)

        rangeCard.addView(makeLabel("突击间隔（分钟）"))
        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val intervalMin = makeInput("最短").apply {
            setText(prefs.getInt("interval_min", 25).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val intervalMax = makeInput("最长").apply {
            setText(prefs.getInt("interval_max", 45).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        intervalRow.addView(intervalMin, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        intervalRow.addView(intervalMax, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rangeCard.addView(intervalRow)

        rangeCard.addView(makeButton("保存学习范围") {
            val min = intervalMin.text.toString().toIntOrNull() ?: 25
            val max = intervalMax.text.toString().toIntOrNull() ?: 45
            prefs.edit()
                .putInt("pageStart", pageStart.text.toString().toIntOrNull() ?: 1)
                .putInt("pageEnd", pageEnd.text.toString().toIntOrNull() ?: 20)
                .putInt("interval_min", min.coerceAtLeast(1))
                .putInt("interval_max", max.coerceAtLeast(min))
                .apply()
            Scheduler.scheduleNext(this)
            Toast.makeText(this, "学习范围已保存", Toast.LENGTH_SHORT).show()
            updateStatus()
        })
        root.addView(rangeCard)

        val wordCard = makeCard("上传英语单词")
        wordCard.addView(makeLabel("手动输入单词"))
        val manualWords = makeInput("用逗号分隔，例如：persist, allocate, focus")
        wordCard.addView(manualWords)
        wordCard.addView(makeButton("添加手动单词") {
            addWords(manualWords.text.toString())
            manualWords.text?.clear()
        })
        wordCard.addView(makeButton("拍照 / 选择多张单词图片") {
            pickWordPhotos.launch("image/*")
        })
        wordCard.addView(TextView(this).apply {
            text = "图片会逐张识别，避免一次处理太多导致闪退。"
            textSize = 12f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 8, 0, 8)
        })

        wordCountText = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF176B4D.toInt())
            setPadding(0, 10, 0, 6)
        }
        wordCard.addView(wordCountText)

        wordPreview = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF25352E.toInt())
            setPadding(0, 4, 0, 8)
            setBackgroundColor(0xFFF7FAF8.toInt())
        }
        wordCard.addView(wordPreview)
        root.addView(wordCard)

        val generateCard = makeCard("短语和句子")
        generateCard.addView(TextView(this).apply {
            text = "每个单词一行，附带短语、句子和中文意思。"
            textSize = 13f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 0, 0, 12)
        })
        generateCard.addView(makeButton("刷新学习内容") {
            refreshWordUi()
            Toast.makeText(this, "学习内容已刷新", Toast.LENGTH_SHORT).show()
        })
        generatedPreview = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF25352E.toInt())
            setPadding(0, 12, 0, 0)
        }
        generateCard.addView(generatedPreview)
        root.addView(generateCard)

        val actionCard = makeCard("学习检查")
        actionCard.addView(makeButton("开启通知提醒") {
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
        actionCard.addView(makeButton("立即突击检查") {
            showCheck()
        })
        actionCard.addView(makeButton("打开词库") {
            startActivity(Intent(this, VocabularyActivity::class.java))
        })
        root.addView(actionCard)

        root.addView(TextView(this).apply {
            text = historyText()
            textSize = 13f
            setTextColor(0xFF68756E.toInt())
            setPadding(8, 28, 8, 0)
        })

        refreshWordUi()
        updateStatus()
    }

    private fun historyText(): String {
        val done = prefs.getInt("done", 0)
        val good = prefs.getInt("good", 0)
        return "已完成检查：$done 次    正确：$good 次"
    }

    private fun updateStatus() {
        val done = prefs.getInt("done", 0)
        val good = prefs.getInt("good", 0)
        statusText.text = when {
            done == 0 -> "准备开始今天的英语自习"
            done == good -> "今天的状态很稳，继续保持"
            else -> "已完成 $done 次检查，正确 $good 次"
        }
    }

    private fun makeCard(title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 4f
            setPadding(32, 26, 32, 30)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 22f
            }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF153F31.toInt())
            setPadding(0, 0, 0, 14)
        })
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = 18
        card.layoutParams = params
        return card
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF52645B.toInt())
            setPadding(0, 16, 0, 6)
        }
    }

    private fun makeStaticText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF25352E.toInt())
            setPadding(0, 0, 0, 6)
        }
    }

    private fun makeInput(hint: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(0xFFF7FAF8.toInt())
                setStroke(2, 0xFFDDE8E2.toInt())
                cornerRadius = 18f
            }
        }
    }

    private fun makeButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF176B4D.toInt())
                cornerRadius = 18f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 14
            layoutParams = lp
            setOnClickListener { action() }
        }
    }

    private fun allWords(): List<String> {
        return (prefs.getStringSet("words", emptySet()) ?: emptySet())
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    private fun addWords(raw: String) {
        val newWords = raw.split(Regex("[^A-Za-z']+"))
            .map { it.trim('\'').lowercase() }
            .filter { it.length > 1 }
        if (newWords.isEmpty()) {
            Toast.makeText(this, "没有识别到英文单词", Toast.LENGTH_SHORT).show()
            return
        }
        val words = allWords().toMutableSet()
        words.addAll(newWords)
        prefs.edit().putStringSet("words", words).apply()
        refreshWordUi()
        Toast.makeText(this, "已添加 ${newWords.size} 个单词", Toast.LENGTH_SHORT).show()
    }

    private fun loadScaledBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("无法读取图片")

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600 ||
            bounds.outHeight / (sample * 2) >= 1600
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("无法解码图片")
    }

    private fun recognizeWords(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在逐张识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val queue = ArrayDeque(uris)

        fun processNext() {
            if (queue.isEmpty()) {
                recognizer.close()
                Toast.makeText(this, "图片识别完成", Toast.LENGTH_SHORT).show()
                refreshWordUi()
                return
            }

            val uri = queue.removeFirst()
            try {
                val bitmap = loadScaledBitmap(uri)
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val found = result.text
                            .split(Regex("[^A-Za-z']+"))
                            .map { it.trim('\'').lowercase() }
                            .filter { it.length > 1 }
                        addWords(found.joinToString(", "))
                    }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        processNext()
                    }
            } catch (e: Exception) {
                processNext()
            }
        }

        processNext()
    }

    private fun meaningFor(word: String): String {
        return MEANINGS[word] ?: "待补充中文意思"
    }

    private fun phraseFor(word: String): String {
        return when {
            word.endsWith("ing") -> "keep $word"
            word.endsWith("ed") -> "already $word"
            word.length > 8 -> "practice $word"
            else -> "my $word"
        }
    }

    private fun sentenceFor(word: String): String {
        return when {
            word.endsWith("ing") -> "I am $word now."
            word.endsWith("ed") -> "I $word today."
            word in setOf("book", "word", "sentence", "phrase", "knowledge", "goal", "plan") ->
                "This is my $word."
            else -> "I $word every day."
        }
    }

    private fun formattedWordList(words: List<String>): CharSequence {
        val builder = SpannableStringBuilder()
        words.forEachIndexed { index, word ->
            if (index > 0) builder.append("\n\n")
            val start = builder.length
            builder.append(word)
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                ForegroundColorSpan(0xFF176B4D.toInt()),
                start,
                builder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append("\n短语  ").append(phraseFor(word))
            builder.append("\n句子  ").append(sentenceFor(word))
            builder.append("\n中文  ").append(meaningFor(word))
        }
        return builder
    }

    private fun refreshWordUi() {
        val words = allWords()
        wordCountText.text = "已上传单词：${words.size} 个"
        wordPreview.text = if (words.isEmpty()) {
            "还没有单词，先手动输入或拍照上传。"
        } else {
            formattedWordList(words)
        }

        val generated = words.take(30).joinToString("\n") {
            "${it}  |  ${phraseFor(it)}  |  ${sentenceFor(it)}  |  ${meaningFor(it)}"
        }
        generatedPreview.text = if (words.isEmpty()) {
            "还没有生成内容，先上传单词。"
        } else {
            generated
        }
    }

    private fun randomQuestion(): Question {
        val words = allWords()
        if (words.size >= 4) {
            val answer = words.random()
            val options = (listOf(answer) + words.filter { it != answer }.shuffled().take(3)).shuffled()
            val phrase = phraseFor(answer)
            return Question(
                "选择与 “$answer” 搭配最自然的短语：\n$phrase",
                options.map { phraseFor(it) },
                options.indexOf(answer),
                "英语搭配"
            )
        }

        return Question(
            "请先在“上传英语单词”中添加至少 4 个单词。",
            listOf("返回添加单词", "稍后再试", "继续", "退出"),
            0,
            "提示"
        )
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
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF153F31.toInt())
            setPadding(0, 8, 0, 18)
        })

        val options = RadioGroup(this)
        currentQuestion?.options?.forEachIndexed { index, option ->
            options.addView(RadioButton(this).apply {
                text = option
                id = View.generateViewId()
                tag = index
                setPadding(14, 14, 14, 14)
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
                val camera = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                if (camera.resolveActivity(packageManager) != null) {
                    takeProof.launch(camera)
                } else {
                    pickProof.launch("image/*")
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
            .setTitle("英语突击检查")
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
