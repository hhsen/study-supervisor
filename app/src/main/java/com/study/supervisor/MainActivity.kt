package com.study.supervisor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
        private val BASIC_WORDS = setOf(
            "i", "you", "he", "she", "we", "they", "it",
            "a", "the", "is", "am", "are", "can", "do",
            "and", "or", "to", "of", "in", "on", "with",
            "my", "your", "this", "that", "not", "so"
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
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF153F31.toInt())
        })
        root.addView(TextView(this).apply {
            text = "上传单词 · 生成句子 · 突击检查"
            textSize = 14f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 6, 0, 24)
        })

        val statusCard = makeCard("今日状态")
        statusText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
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
            text = "可以连续选择任意张图片，系统会自动识别其中的英文单词。"
            textSize = 12f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 8, 0, 8)
        })

        wordCountText = TextView(this).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF176B4D.toInt())
            setPadding(0, 10, 0, 6)
        }
        wordCard.addView(wordCountText)

        wordPreview = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF33443C.toInt())
            setPadding(0, 4, 0, 8)
        }
        wordCard.addView(wordPreview)
        root.addView(wordCard)

        val generateCard = makeCard("生成短语和句子")
        generateCard.addView(TextView(this).apply {
            text = "句子只使用基础语法词和你上传的单词，避免加入未上传的生词。"
            textSize = 13f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 0, 0, 12)
        })
        generateCard.addView(makeButton("生成学习内容") {
            generateItems()
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
        setTypeface(null, android.graphics.Typeface.BOLD)
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
            setTypeface(null, android.graphics.Typeface.BOLD)
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

    private fun recognizeWords(uris: List<Uri>) {
        Toast.makeText(this, "正在识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var remaining = uris.size

        uris.forEach { uri ->
            try {
                val image = InputImage.fromFilePath(this, uri)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val found = result.text
                            .split(Regex("[^A-Za-z']+"))
                            .map { it.trim('\'').lowercase() }
                            .filter { it.length > 1 }
                        addWords(found.joinToString(", "))
                    }
                    .addOnCompleteListener {
                        remaining--
                        if (remaining <= 0) {
                            recognizer.close()
                            Toast.makeText(this, "图片识别完成", Toast.LENGTH_SHORT).show()
                        }
                    }
            } catch (e: Exception) {
                remaining--
                if (remaining <= 0) recognizer.close()
                Toast.makeText(this, "有一张图片无法读取", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshWordUi() {
        val words = allWords()
        wordCountText.text = "已上传单词：${words.size} 个"
        wordPreview.text = if (words.isEmpty()) {
            "还没有单词，先手动输入或拍照上传。"
        } else {
            words.take(120).joinToString("  ·  ") +
                if (words.size > 120) "\n\n还有 ${words.size - 120} 个单词..." else ""
        }

        val items = (prefs.getStringSet("study_items", emptySet()) ?: emptySet()).toList()
        generatedPreview.text = if (items.isEmpty()) {
            "还没有生成内容，先上传单词再点击生成。"
        } else {
            items.take(30).mapIndexed { index, item -> "${index + 1}. $item" }
                .joinToString("\n") +
                if (items.size > 30) "\n\n还有 ${items.size - 30} 条..." else ""
        }
    }

    private fun generateItems() {
        val words = allWords()
        if (words.size < 2) {
            Toast.makeText(this, "至少需要 2 个单词", Toast.LENGTH_SHORT).show()
            return
        }

        val items = linkedSetOf<String>()
        val shuffled = words.shuffled()
        for (i in shuffled.indices) {
            val a = shuffled[i]
            val b = shuffled[(i + 1) % shuffled.size]
            val c = shuffled[(i + 2) % shuffled.size]

            items.add("$a $b")
            items.add("I can $a.")
            items.add("$a is $b.")
            items.add("This is my $a.")
            items.add("$a and $b.")
            items.add("I want $a and $b.")
            items.add("$a, $b, $c.")
            if (items.size >= 80) break
        }

        prefs.edit().putStringSet("study_items", items).apply()
        refreshWordUi()
        Toast.makeText(this, "已生成 ${items.size} 条学习内容", Toast.LENGTH_SHORT).show()
    }

    private fun randomQuestion(): Question {
        val words = allWords()
        val items = (prefs.getStringSet("study_items", emptySet()) ?: emptySet()).toList()

        if (words.size >= 4 && items.isNotEmpty()) {
            val item = items.random()
            val tokens = item.split(Regex("\\s+"))
                .map { it.trim(',', '.', '?', '!') }
                .filter { it.isNotBlank() && words.contains(it.lowercase()) }

            if (tokens.isNotEmpty()) {
                val answer = tokens.random()
                val pattern = Regex("\\b" + Regex.escape(answer) + "\\b", RegexOption.IGNORE_CASE)
                val blanked = pattern.replace(item, "_____")
                val otherWords = words.filter { !answer.equals(it, ignoreCase = true) }.shuffled()
                val options = (listOf(answer) + otherWords.take(3)).shuffled()
                return Question(blanked, options, options.indexOf(answer), "英语词汇")
            }
        }

        if (words.size >= 4) {
            val answer = words.random()
            val options = (listOf(answer) + words.filter { it != answer }.shuffled().take(3)).shuffled()
            return Question(
                "选择正确的单词拼写：_____",
                options,
                options.indexOf(answer),
                "英语拼写"
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
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
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
