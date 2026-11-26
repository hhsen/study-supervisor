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
import android.util.Base64
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class Question(
    val text: String,
    val options: List<String>,
    val answer: Int
)

data class VocabEntry(
    val word: String,
    val meaning: String,
    val phrase: String,
    val phraseMeaning: String,
    val sentence: String,
    val sentenceMeaning: String
)

private val OCR_WORDS = mapOf(
    "region" to "地区",
    "regional" to "地区的",
    "origin" to "起源；源头",
    "original" to "最初的；原始的",
    "originate" to "创建；起源",
    "source" to "来源",
    "resource" to "资源",
    "river" to "河；江",
    "derive" to "源于；获得",
    "deprive" to "剥夺；使丧失",
    "size" to "大小；尺寸",
    "seize" to "抓住；夺取",
    "rob" to "抢劫",
    "robbery" to "盗取；抢劫",
    "rub" to "摩擦；揉搓",
    "thrive" to "繁荣；茁壮成长",
    "strive" to "努力；奋斗",
    "effort" to "努力；努力的事",
    "luxury" to "奢侈品；奢华",
    "luxurious" to "奢侈的；豪华的",
    "treasure" to "财富；珍爱",
    "price" to "价格",
    "precious" to "珍贵的",
    "beloved" to "亲爱的；深受喜爱的",
    "cherish" to "珍爱；钟爱",
    "appreciate" to "欣赏；感激",
    "valuable" to "有价值的；很有用的",
    "cost" to "花费；费用",
    "costly" to "昂贵的",
    "evaluate" to "评价；评估",
    "evaluation" to "评价；估计",
    "mate" to "朋友；伙伴",
    "estimate" to "估计",
    "underestimate" to "低估",
    "reckon" to "估算；认为",
    "ultimate" to "最终的",
    "ultimately" to "最终；基本上",
    "intimate" to "亲密的；密友",
    "approximate" to "大约的；近似",
    "approximation" to "大概；大约",
    "approach" to "接近；方法",
    "study" to "学习",
    "book" to "书",
    "word" to "单词",
    "sentence" to "句子",
    "phrase" to "短语",
    "knowledge" to "知识",
    "focus" to "专注",
    "review" to "复习",
    "persist" to "坚持",
    "allocate" to "分配",
    "achieve" to "实现",
    "progress" to "进步",
    "practice" to "练习",
    "remember" to "记住",
    "understand" to "理解",
    "english" to "英语",
    "learn" to "学习",
    "reading" to "阅读",
    "writing" to "写作",
    "important" to "重要的",
    "difficult" to "困难的",
    "success" to "成功",
    "goal" to "目标",
    "plan" to "计划",
    "bias" to "偏见",
    "neutral" to "中立的",
    "depend" to "依靠",
    "independent" to "独立的",
    "independence" to "独立",
    "acute" to "严重的；急性的",
    "severe" to "严重的；严厉的",
    "grave" to "坟墓；严重的",
    "gravity" to "严重性；重力",
    "eve" to "前夕",
    "grieve" to "感到悲痛",
    "grief" to "悲伤；悲痛",
    "relieve" to "缓解；减轻",
    "relief" to "轻松；减轻",
    "believe" to "相信",
    "belief" to "信念；相信",
    "full" to "满的",
    "grateful" to "感激的",
    "gratitude" to "感激",
    "grant" to "拨款；授予",
    "grind" to "磨碎",
    "polish" to "擦亮；润色"
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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.ensureScheduled(context)
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_CHECK = "open_check"
        private val COLOR_BG = 0xFF0B1C2C.toInt()
        private val COLOR_CARD = 0xFF102B3D.toInt()
        private val COLOR_TEAL = 0xFF14B8A6.toInt()
        private val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private val COLOR_MUTED = 0xFFB8D8D4.toInt().toInt()

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
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private var currentPage = "home"
    private var entries: MutableList<VocabEntry> = mutableListOf()
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
            if (!uris.isNullOrEmpty()) importImagesV5(uris)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(this, if (granted) "提醒已开启" else "未获得通知权限", Toast.LENGTH_SHORT).show()
            Scheduler.ensureScheduled(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("study_supervisor", MODE_PRIVATE)
        entries = loadEntries()
        StudyNotifications.createChannel(this)
        buildShell()
        Scheduler.ensureScheduled(this)
        showPage("home")
        if (intent.getBooleanExtra(EXTRA_OPEN_CHECK, false)) showCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHECK, false)) showCheck()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll)

        nav = buildNav()
        root.addView(nav)
        setContentView(root)
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 12, 8, 18)
        }
        listOf("首页", "突击", "词库", "档案", "设置").forEach { label ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            item.addView(TextView(this).apply {
                text = when (label) {
                    "首页" -> "⌂"; "突击" -> "⚡"; "词库" -> "▤"; "档案" -> "◷"; else -> "⚙"
                }
                textSize = 20f
                setTextColor(if (label == currentPage) COLOR_TEAL else 0xFF6B7280.toInt())
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, if (label == currentPage) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (label == currentPage) COLOR_TEAL else 0xFF6B7280.toInt())
            })
            item.setOnClickListener {
                when (label) {
                    "首页" -> showPage("home")
                    "突击" -> showCheck()
                    "词库" -> showPage("vocab")
                    "档案" -> showPage("archive")
                    "设置" -> showPage("settings")
                }
            }
            bar.addView(item)
        }
        return bar
    }

    private fun refreshNav() {
        val old = nav
        val parent = old.parent as LinearLayout
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        nav = buildNav()
        parent.addView(nav, index)
    }

    private fun showPage(page: String) {
        currentPage = page
        content.removeAllViews()
        when (page) {
            "home" -> buildHome()
            "vocab" -> buildVocab()
            "archive" -> buildArchive()
            "settings" -> buildSettings()
        }
        refreshNav()
    }

    private fun buildHome() {
        val pad = 32
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + 8, 48, pad + 8, 36)
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF16A085.toInt(), 0xFF2DD4BF.toInt())
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 42f, 42f, 42f, 42f)
            }
        }
        hero.addView(TextView(this).apply {
            text = "🎓  每日学习突击"
            textSize = 27f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        hero.addView(TextView(this).apply {
            text = "⏳  今日待突击"
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        })
        hero.addView(TextView(this).apply {
            text = "学习时段 08:00–21:00，已连续坚持 ${prefs.getInt("streak", 0)} 天"
            textSize = 15f
            setTextColor(0xFFE6FFFB.toInt())
        })
        hero.addView(TextView(this).apply {
            text = "🔥 今日计划 ${prefs.getInt("done", 0)} 次检查，下一次随机提醒"
            textSize = 14f
            setTextColor(0xFFE6FFFB.toInt())
            setPadding(0, 16, 0, 0)
        })
        hero.addView(makeWhiteButton("📷  开始突击检查") { showCheck() })
        content.addView(hero)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 24, pad, 8)
        }
        stats.addView(makeStat("🔥", "${prefs.getInt("streak", 0)}", "连续坚持", 0xFFFFE8D8.toInt(), 0xFFF97316.toInt()))
        stats.addView(makeStat("🛡️", "${accuracy()}%", "检查通过率", 0xFFDFF8F1.toInt(), COLOR_TEAL))
        stats.addView(makeStat("📚", "${entries.size}", "词库单词", 0xFFE7E6FF.toInt(), 0xFF6366F1.toInt()))
        content.addView(stats)

        content.addView(makeSectionTitle("今日学习计划", pad))
        val plan = makeCard(pad)
        plan.addView(makePlanRow("🕐", "学习时段", "${prefs.getInt("study_start", 8)}:00 – ${prefs.getInt("study_end", 21)}:00"))
        plan.addView(makePlanRow("⚡", "今日突击次数", "${prefs.getInt("interval_min", 25)} 分钟随机"))
        
        plan.addView(makePlanRow("🔔", "本地提醒", if (notificationsEnabled()) "已开启" else "未开启"))
        content.addView(plan)

        content.addView(makeSectionTitle("快捷入口", pad))
        val links = makeCard(pad)
        links.addView(makeLinkRow("▤", "查看词库", "单词、短语、句子和中文意思") { showPage("vocab") })
        links.addView(makeLinkRow("◷", "查看学习档案", "回顾每日检查与答题明细") { showPage("archive") })
        links.addView(makeLinkRow("⚙", "学习设置", "页码范围与突击间隔") { showPage("settings") })
        content.addView(links)

        content.addView(TextView(this).apply {
            text = "已完成检查 ${prefs.getInt("done", 0)} 次 · 正确 ${prefs.getInt("good", 0)} 次"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(pad, 24, pad, 40)
        })
    }

    private fun accuracy(): Int {
        val done = prefs.getInt("done", 0)
        val good = prefs.getInt("good", 0)
        return if (done == 0) 0 else good * 100 / done
    }

    private fun notificationsEnabled(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun makeStat(icon: String, value: String, label: String, bg: Int, fg: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = 26f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 14, 0)
            }
        }
        card.addView(TextView(this).apply {
            text = icon
            textSize = 23f
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = 18f
            }
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(54, 54)
        })
        card.addView(TextView(this).apply {
            text = value
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 0)
        })
        card.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, 4, 0, 0)
        })
        return card
    }

    private fun makeSectionTitle(text: String, pad: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(pad, 28, pad, 12)
        }
    }

    private fun makeCard(pad: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + 4, 12, pad + 4, 16)
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = 28f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, 0, pad, 0) }
        }
    }

    private fun makePlanRow(icon: String, title: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 18, 8, 18)
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 22f
            background = GradientDrawable().apply {
                setColor(0xFF176B62.toInt())
                cornerRadius = 16f
            }
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(52, 52)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                setTextColor(COLOR_MUTED)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 4, 0, 0)
            })
        })
        return row
    }

    private fun makeLinkRow(icon: String, title: String, sub: String, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 20, 8, 20)
            setOnClickListener { action() }
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 24f
            background = GradientDrawable().apply {
                setColor(0xFF176B62.toInt())
                cornerRadius = 18f
            }
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(56, 56)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 13f
                setTextColor(COLOR_MUTED)
                setPadding(0, 4, 0, 0)
            })
        })
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_VERTICAL
        })
        return row
    }

    private fun makeWhiteButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF14B8A6.toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 30 }
            setOnClickListener { action() }
        }
    }

    private fun buildVocab() {
        val pad = 32
        content.addView(TextView(this).apply {
            text = "词库"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(pad, 44, pad, 4)
        })
        content.addView(TextView(this).apply {
            text = "${entries.size} 词"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(pad, 0, pad, 20)
        })

        val search = EditText(this).apply {
            hint = "搜索单词..."
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7FA7AD.toInt())
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = 24f
                setStroke(2, 0xFF1D4758.toInt())
            }
            setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, 0, pad, 0) }
        }
        content.addView(search)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 18, pad, 8)
        }
        actions.addView(makeTealButton("导入图片") { pickWordPhotos.launch("image/*") })
        actions.addView(makeTealButton("添加单词") { showAddDialog() })
        content.addView(actions)
        content.addView(makeTealButton("清空所有单词") {
            AlertDialog.Builder(this)
                .setTitle("清空词库")
                .setMessage("确定删除全部单词吗？此操作无法撤销。")
                .setPositiveButton("全部删除") { _, _ ->
                    entries.clear()
                    saveEntries()
                    showPage("vocab")
                    Toast.makeText(this, "词库已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 8, pad, 40)
        }
        content.addView(list)

        fun render() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val filtered = entries.filter { q.isEmpty() || it.word.contains(q) || it.meaning.contains(q) }
            if (filtered.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = if (entries.isEmpty()) "还没有单词，点击“导入图片”开始。" else "没有找到匹配的单词。"
                    textSize = 15f
                    setTextColor(COLOR_MUTED)
                    setPadding(0, 36, 0, 0)
                    gravity = Gravity.CENTER
                })
                return
            }
            filtered.forEachIndexed { i, e -> list.addView(buildWordCard(i + 1, e, ::render)) }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render()
    }

    private fun makeTealButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(COLOR_TEAL)
                cornerRadius = 18f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener { action() }
        }
    }

    private fun buildWordCard(index: Int, entry: VocabEntry, rerender: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = 26f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = index.toString()
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(COLOR_TEAL)
                cornerRadius = 18f
            }
            layoutParams = LinearLayout.LayoutParams(62, 62)
        })
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = entry.word
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = entry.meaning
                textSize = 15f
                setTextColor(COLOR_MUTED)
                setPadding(0, 4, 0, 0)
            })
        })
        card.addView(top)

        card.addView(TextView(this).apply {
            text = "短语：${entry.phrase}\n中文：${entry.phraseMeaning}"
            textSize = 14f
            setTextColor(0xFFD5E7E5.toInt())
            setPadding(0, 16, 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "句子：${entry.sentence}\n中文：${entry.sentenceMeaning}"
            textSize = 14f
            setTextColor(0xFFD5E7E5.toInt())
            setPadding(0, 10, 0, 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 14, 0, 0)
        }
        actions.addView(makeSmallButton("编辑") { showEditDialog(entry, rerender) })
        actions.addView(makeSmallButton("删除") {
            entries.removeAll { it.word == entry.word }
            saveEntries()
            rerender()
        })
        card.addView(actions)
        return card
    }

    private fun makeSmallButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (text == "删除") 0xFFB45353.toInt() else 0xFF1F6F7A.toInt())
                cornerRadius = 14f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(10, 0, 0, 0) }
            setOnClickListener { action() }
        }
    }

    private fun buildArchive() {
        val pad = 32
        content.addView(TextView(this).apply {
            text = "学习档案"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(pad, 44, pad, 20)
        })

        val records = loadRecords()
        if (records.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "还没有检查记录，先完成一次突击检查。"
                textSize = 15f
                setTextColor(COLOR_MUTED)
                setPadding(pad, 40, pad, 40)
                gravity = Gravity.CENTER
            })
            return
        }

        records.forEach { record ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 24, 28, 24)
                background = GradientDrawable().apply {
                    setColor(COLOR_CARD)
                    cornerRadius = 24f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, 0, pad, 14) }
            }
            card.addView(TextView(this).apply {
                text = if (record.second) "✓ 通过" else "× 需要复习"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (record.second) 0xFF5EEAD4.toInt() else 0xFFFCA5A5.toInt())
            })
            card.addView(TextView(this).apply {
                text = record.first
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, 8, 0, 0)
            })
            content.addView(card)
        }
    }

    private fun buildSettings() {
        val pad = 32
        content.addView(TextView(this).apply {
            text = "设置"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(pad, 44, pad, 20)
        })

        val card = makeCard(pad)
        card.addView(settingLabel("英语教材"))
        card.addView(settingStatic("刘晓燕《英语四级你还在背单词吗》"))
        card.addView(settingLabel("突击间隔（分钟）"))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val min = settingInput(prefs.getInt("interval_min", 25).toString())
        val max = settingInput(prefs.getInt("interval_max", 45).toString())
        times.addView(min, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(max, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(times)

        card.addView(settingLabel("学习时段"))
        val studyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val studyStart = settingInput(prefs.getInt("study_start", 8).toString())
        val studyEnd = settingInput(prefs.getInt("study_end", 21).toString())
        studyRow.addView(studyStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        studyRow.addView(studyEnd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(studyRow)

        card.addView(makeTealButton("保存学习时段") {
            val s = studyStart.text.toString().toIntOrNull() ?: 8
            val e = studyEnd.text.toString().toIntOrNull() ?: 21
            prefs.edit()
                .putInt("study_start", s.coerceIn(0, 23))
                .putInt("study_end", e.coerceIn(1, 24))
                .apply()
            Scheduler.scheduleNext(this)
            Toast.makeText(this, "学习时段已保存", Toast.LENGTH_SHORT).show()
        })
        card.addView(makeTealButton("保存设置") {
            val minV = min.text.toString().toIntOrNull() ?: 25
            val maxV = max.text.toString().toIntOrNull() ?: 45
            prefs.edit()
                .putInt("interval_min", minV.coerceAtLeast(1))
                .putInt("interval_max", maxV.coerceAtLeast(minV))
                .apply()
            Scheduler.scheduleNext(this)
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        })

        card.addView(makeTealButton("打开系统通知设置") {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        })
        card.addView(makeTealButton("开启通知提醒") {
            if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Scheduler.ensureScheduled(this)
                Toast.makeText(this, "提醒已开启", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(card)
    }

    private fun settingLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_MUTED)
            setPadding(0, 16, 0, 6)
        }
    }

    private fun settingStatic(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
        }
    }

    private fun settingInput(value: String): EditText {
        return EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(COLOR_MUTED)
            background = GradientDrawable().apply {
                setColor(0xFF0B2233.toInt())
                cornerRadius = 16f
                setStroke(2, 0xFF1D4758.toInt())
            }
            setPadding(22, 18, 22, 18)
        }
    }

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val word = EditText(this).apply { hint = "英文单词" }
        val meaning = EditText(this).apply { hint = "中文意思" }
        layout.addView(word)
        layout.addView(meaning)
        AlertDialog.Builder(this)
            .setTitle("添加单词")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val w = word.text.toString().trim().lowercase()
                val m = meaning.text.toString().trim()
                if (w.isNotEmpty()) addEntry(w, m.ifEmpty { meaningFor(w) })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog(entry: VocabEntry, rerender: () -> Unit) {
        val input = EditText(this).apply { setText(entry.meaning) }
        AlertDialog.Builder(this)
            .setTitle("编辑 ${entry.word}")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val m = input.text.toString().trim()
                if (m.isNotEmpty()) {
                    val i = entries.indexOfFirst { it.word == entry.word }
                    if (i >= 0) entries[i] = entry.copy(meaning = m)
                    saveEntries()
                    rerender()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun meaningFor(word: String): String {
        return MEANINGS[word] ?: OCR_WORDS[word] ?: "待补充中文意思"
    }

    private fun phraseFor(word: String) = when {
        word.endsWith("ing") -> "keep $word"
        word.endsWith("ed") -> "already $word"
        else -> "my $word"
    }

    private fun sentenceFor(word: String) = when {
        word.endsWith("ing") -> "I am $word now."
        word.endsWith("ed") -> "I $word today."
        else -> "I $word every day."
    }

    private fun levenshtein(left: String, right: String): Int {
        val dp = Array(left.length + 1) { IntArray(right.length + 1) }

        for (i in 0..left.length) dp[i][0] = i
        for (j in 0..right.length) dp[0][j] = j

        for (i in 1..left.length) {
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[left.length][right.length]
    }

    private fun correctWord(raw: String): String {
        val word = raw.lowercase()
        if (OCR_WORDS.containsKey(word)) return word

        return OCR_WORDS.keys
            .minByOrNull { levenshtein(word, it) }
            ?.takeIf { levenshtein(word, it) <= 2 }
            ?: word
    }
    private fun makeEntry(word: String, meaning: String): VocabEntry {
        val phrase = phraseFor(word)
        val sentence = sentenceFor(word)
        return VocabEntry(
            word,
            meaning,
            phrase,
            "与“$meaning”相关",
            sentence,
            "我每天${meaning}。"
        )
    }

    private fun addEntry(word: String, meaning: String) {
        if (entries.any { it.word == word }) return
        entries.add(makeEntry(word, meaning))
        saveEntries()
    }

    private fun saveEntries() {
        val array = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
            o.put("word", e.word); o.put("meaning", e.meaning)
            o.put("phrase", e.phrase); o.put("phraseMeaning", e.phraseMeaning)
            o.put("sentence", e.sentence); o.put("sentenceMeaning", e.sentenceMeaning)
            array.put(o)
        }
        prefs.edit().putString("vocab_json", array.toString()).apply()
    }

    private fun loadEntries(): MutableList<VocabEntry> {
        val result = mutableListOf<VocabEntry>()
        val json = prefs.getString("vocab_json", null)
        if (json != null) {
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    result.add(
                        VocabEntry(
                            o.optString("word"),
                            o.optString("meaning", "待补充中文意思"),
                            o.optString("phrase"),
                            o.optString("phraseMeaning"),
                            o.optString("sentence"),
                            o.optString("sentenceMeaning")
                        )
                    )
                }
            } catch (e: Exception) {
            }
        }
        if (result.isEmpty()) {
            (prefs.getStringSet("words", emptySet()) ?: emptySet()).forEach {
                result.add(makeEntry(it, meaningFor(it)))
            }
        }
        return result
    }

    private fun loadRecords(): List<Pair<String, Boolean>> {
        val json = prefs.getString("records_json", "[]") ?: "[]"
        val list = mutableListOf<Pair<String, Boolean>>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(o.optString("time") to o.optBoolean("ok"))
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveRecord(ok: Boolean) {
        val array = JSONArray()
        loadRecords().forEach { (time, good) ->
            val o = JSONObject(); o.put("time", time); o.put("good", good); array.put(o)
        }
        val o = JSONObject()
        o.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()))
        o.put("good", ok)
        array.put(o)
        prefs.edit().putString("records_json", array.toString()).apply()
    }

    private fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在逐张识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val queue = ArrayDeque(uris)
        val debugTexts = mutableListOf<String>()

        fun next() {
            if (queue.isEmpty()) {
                recognizer.close()
                saveEntries()
                showPage("vocab")
                if (entries.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("OCR 识别结果")
                        .setMessage(debugTexts.joinToString("\n---\n").ifBlank { "OCR 没有返回文字" })
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    Toast.makeText(this, "导入完成，共 ${entries.size} 词", Toast.LENGTH_LONG).show()
                }
                return
            }

            val uri = queue.removeFirst()
            try {
                val bitmap = loadScaledBitmap(uri)
                val image = InputImage.fromBitmap(enhanceForOcr(bitmap), 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text
                        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        val added = mutableSetOf<String>()

                        var pendingEnglish: String? = null
                        lines.forEach { line ->
                            val english = Regex("[A-Za-z]{2,}").findAll(line)
                                .map { it.value.lowercase() }.toList()
                            val chinese = Regex("[\\u4e00-\\u9fff]+").findAll(line)
                                .map { it.value }.toList()

                            if (english.isNotEmpty() && chinese.isNotEmpty()) {
                                english.forEachIndexed { i, word ->
                                    val meaning = chinese.getOrElse(i) { chinese.last() }
                                    if (added.add(word)) addEntry(word, meaning)
                                }
                                pendingEnglish = null
                            } else if (english.isNotEmpty()) {
                                pendingEnglish = english.last()
                            } else if (chinese.isNotEmpty() && pendingEnglish != null) {
                                val word = pendingEnglish!!
                                if (added.add(word)) addEntry(word, chinese.first())
                                pendingEnglish = null
                            }
                        }

                        val allEnglish = Regex("[A-Za-z]{2,}").findAll(text)
                            .map { it.value.lowercase() }.distinct().toList()
                        val allChinese = Regex("[\\u4e00-\\u9fff]+").findAll(text)
                            .map { it.value }.distinct().toList()

                        if (allEnglish.isNotEmpty() && allChinese.isNotEmpty()) {
                            val count = minOf(allEnglish.size, allChinese.size)
                            for (i in 0 until count) {
                                if (added.add(allEnglish[i])) {
                                    addEntry(allEnglish[i], allChinese[i])
                                }
                            }
                        }
                    }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        next()
                    }
            } catch (e: Exception) {
                next()
            }
        }

        next()
    }

    private fun importImagesV2(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在逐张识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()

        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latin = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        val queue = ArrayDeque(uris)
        val debugTexts = mutableListOf<String>()

        fun extract(text: String) {
            val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val added = mutableSetOf<String>()
            var pending: String? = null

            lines.forEach { line ->
                val en = Regex("[A-Za-z]{2,}").findAll(line).map { it.value.lowercase() }.toList()
                val zh = Regex("[\\u4e00-\\u9fff]+").findAll(line).map { it.value }.toList()

                if (en.isNotEmpty() && zh.isNotEmpty()) {
                    en.forEachIndexed { i, word ->
                        if (added.add(word)) addEntry(word, zh.getOrElse(i) { zh.last() })
                    }
                    pending = null
                } else if (en.isNotEmpty()) {
                    pending = en.last()
                } else if (zh.isNotEmpty() && pending != null) {
                    val word = pending!!
                    if (added.add(word)) addEntry(word, zh.first())
                    pending = null
                }
            }

            val allEn = Regex("[A-Za-z]{2,}").findAll(text)
                .map { it.value.lowercase() }.distinct().toList()
            val allZh = Regex("[\\u4e00-\\u9fff]+").findAll(text)
                .map { it.value }.distinct().toList()

            if (allEn.isNotEmpty() && allZh.isNotEmpty()) {
                val count = minOf(allEn.size, allZh.size)
                for (i in 0 until count) {
                    if (added.add(allEn[i])) addEntry(allEn[i], allZh[i])
                }
            } else if (allEn.isNotEmpty()) {
                allEn.forEach { if (added.add(it)) addEntry(it, meaningFor(it)) }
            }
        }

        fun next() {
            if (queue.isEmpty()) {
                chinese.close()
                latin.close()
                saveEntries()
                showPage("vocab")
                if (entries.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("OCR 识别结果")
                        .setMessage(debugTexts.joinToString("\n---\n").ifBlank { "OCR 没有返回文字" })
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    Toast.makeText(this, "导入完成，共 ${entries.size} 词", Toast.LENGTH_LONG).show()
                }
                return
            }

            val uri = queue.removeFirst()
            try {
                val bitmap = loadScaledBitmap(uri)
                val image = InputImage.fromBitmap(enhanceForOcr(bitmap), 0)

                chinese.process(image)
                    .addOnSuccessListener { extract(it.text) }
                    .addOnCompleteListener {
                        latin.process(image)
                            .addOnSuccessListener { extract(it.text) }
                            .addOnCompleteListener {
                                bitmap.recycle()
                                next()
                            }
                    }
            } catch (e: Exception) {
                next()
            }
        }

        next()
    }
    private fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val gray = (r * 299 + g * 587 + b * 114) / 1000
            val boosted = ((gray - 128) * 1.8 + 128).toInt().coerceIn(0, 255)
            val value = if (boosted < 145) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            pixels[i] = value
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    private fun importImagesV3(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()

        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latin = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        val queue = ArrayDeque(uris)
        val debugTexts = mutableListOf<String>()

        fun extractResult(result: com.google.mlkit.vision.text.Text) {
            debugTexts.add(result.text)
            val lines = result.textBlocks.flatMap { block ->
                block.lines.map {
                    it.text to (it.boundingBox?.centerY() ?: 0)
                }
            }

            val englishLines = lines.filter { Regex("[A-Za-z]{2,}").containsMatchIn(it.first) }
            val chineseLines = lines.filter { Regex("[\\u4e00-\\u9fff]+").containsMatchIn(it.first) }

            englishLines.forEach { (line, y) ->
                Regex("[A-Za-z]{2,}").findAll(line)
                    .map { it.value.lowercase() }
                    .forEach { word ->
                        val nearestChinese = chineseLines.minByOrNull {
                            abs(it.second - y)
                        }?.first

                        val meaning = Regex("[\\u4e00-\\u9fff]+")
                            .find(nearestChinese ?: "")
                            ?.value
                            ?: meaningFor(word)

                        addEntry(word, meaning)
                    }
            }

            lines.forEach { line ->
                val en = Regex("[A-Za-z]{2,}").findAll(line.first)
                    .map { it.value.lowercase() }.toList()
                val zh = Regex("[\\u4e00-\\u9fff]+").findAll(line.first)
                    .map { it.value }.toList()

                en.forEachIndexed { index, word ->
                    addEntry(word, zh.getOrElse(index) { zh.firstOrNull() ?: meaningFor(word) })
                }
            }

            val allEn = Regex("[A-Za-z]{2,}").findAll(result.text)
                .map { it.value.lowercase() }.distinct().toList()
            val allZh = Regex("[\\u4e00-\\u9fff]+").findAll(result.text)
                .map { it.value }.distinct().toList()

            if (allEn.isNotEmpty() && allZh.isNotEmpty()) {
                val count = minOf(allEn.size, allZh.size)
                for (i in 0 until count) {
                    addEntry(allEn[i], allZh[i])
                }
            } else if (allEn.isNotEmpty()) {
                allEn.forEach { addEntry(it, meaningFor(it)) }
            }
        }

        fun processImage(
            image: InputImage,
            bitmap: Bitmap?,
            finish: () -> Unit
        ) {
            chinese.process(image)
                .addOnSuccessListener { extractResult(it) }
                .addOnCompleteListener {
                    latin.process(image)
                        .addOnSuccessListener { extractResult(it) }
                        .addOnCompleteListener {
                            bitmap?.recycle()
                            finish()
                        }
                }
        }

        fun processEnhanced(uri: Uri, onDone: () -> Unit) {
            try {
                val bitmap = loadScaledBitmap(uri)
                val image = InputImage.fromBitmap(enhanceForOcr(bitmap), 0)
                processImage(image, bitmap) { onDone() }
            } catch (e: Exception) {
                Toast.makeText(this, "图片读取失败：${e.message}", Toast.LENGTH_SHORT).show()
                onDone()
            }
        }

        fun next() {
            if (queue.isEmpty()) {
                chinese.close()
                latin.close()
                saveEntries()
                showPage("vocab")
                if (entries.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("OCR 识别结果")
                        .setMessage(debugTexts.joinToString("\n---\n").ifBlank { "OCR 没有返回文字" })
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    Toast.makeText(this, "导入完成，共 ${entries.size} 词", Toast.LENGTH_LONG).show()
                }
                return
            }

            val uri = queue.removeFirst()
            val before = entries.size

            try {
                val image = InputImage.fromFilePath(this, uri)
                processImage(image, null) {
                    if (entries.size == before) processEnhanced(uri) { next() } else next()
                }
            } catch (e: Exception) {
                processEnhanced(uri) { next() }
            }
        }

        next()
    }
    private fun extractOcrText(text: String) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val added = mutableSetOf<String>()
        var pendingEnglish: String? = null

        lines.forEach { line ->
            val en = Regex("[A-Za-z]{2,}").findAll(line)
                .map { it.value.lowercase() }.toList()
            val zh = Regex("[\\u4e00-\\u9fff]+").findAll(line)
                .map { it.value }.toList()

            if (en.isNotEmpty() && zh.isNotEmpty()) {
                en.forEachIndexed { index, word ->
                    val meaning = zh.getOrElse(index) { zh.first() }
                    if (added.add(word)) addEntry(word, meaning)
                }
                pendingEnglish = null
            } else if (en.isNotEmpty()) {
                pendingEnglish = en.last()
            } else if (zh.isNotEmpty() && pendingEnglish != null) {
                val word = pendingEnglish!!
                if (added.add(word)) addEntry(word, zh.first())
                pendingEnglish = null
            }
        }

        val allEn = Regex("[A-Za-z]{2,}").findAll(text)
            .map { it.value.lowercase() }.distinct().toList()
        val allZh = Regex("[\\u4e00-\\u9fff]+").findAll(text)
            .map { it.value }.distinct().toList()

        if (allEn.isNotEmpty() && allZh.isNotEmpty()) {
            val count = minOf(allEn.size, allZh.size)
            for (i in 0 until count) {
                if (added.add(allEn[i])) addEntry(allEn[i], allZh[i])
            }
        } else if (allEn.isNotEmpty()) {
            allEn.forEach { if (added.add(it)) addEntry(it, meaningFor(it)) }
        }
    }

    private fun recognizeWithOcrSpace(uri: Uri, callback: (String?) -> Unit) {
        Thread {
            var resultText: String? = null
            try {
                val bitmap = loadScaledBitmap(uri)
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                bitmap.recycle()

                val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                val imageData = URLEncoder.encode("data:image/jpeg;base64,$base64", "UTF-8")
                val postData =
                    "apikey=helloworld&language=chs&OCREngine=2&isTable=true&scale=true&base64Image=$imageData"

                val connection =
                    URL("https://api.ocr.space/parse/image").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )

                connection.outputStream.use {
                    it.write(postData.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)

                    if (!json.optBoolean("IsErroredOnProcessing", true)) {
                        val results = json.optJSONArray("ParsedResults")
                        resultText = results
                            ?.optJSONObject(0)
                            ?.optString("ParsedText")
                            ?.takeIf { it.isNotBlank() }
                    }
                }

                connection.disconnect()
            } catch (e: Exception) {
                resultText = null
            }

            runOnUiThread { callback(resultText) }
        }.start()
    }

    private fun importImagesV4(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()

        val queue = ArrayDeque(uris)
        val debugTexts = mutableListOf<String>()

        fun next() {
            if (queue.isEmpty()) {
                saveEntries()
                showPage("vocab")

                if (entries.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("OCR 识别结果")
                        .setMessage(
                            debugTexts.joinToString("\n---\n")
                                .ifBlank { "OCR 没有返回文字，请尝试更清晰、光线更好的图片。" }
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    Toast.makeText(
                        this,
                        "导入完成，共 ${entries.size} 词",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }

            val uri = queue.removeFirst()
            recognizeWithOcrSpace(uri) { text ->
                if (text.isNullOrBlank()) {
                    debugTexts.add("图片没有识别到文字")
                } else {
                    debugTexts.add(text)
                    extractOcrText(text)
                }
                next()
            }
        }

        next()
    }
    private fun recognizeBitmapWithOcrSpace(bitmap: Bitmap, callback: (String?) -> Unit) {
        Thread {
            var resultText: String? = null
            try {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)

                val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                val imageData = URLEncoder.encode("data:image/jpeg;base64,$base64", "UTF-8")
                val postData =
                    "apikey=helloworld&language=chs&OCREngine=2&isTable=true&scale=true&base64Image=$imageData"

                val connection =
                    URL("https://api.ocr.space/parse/image").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )

                connection.outputStream.use {
                    it.write(postData.toByteArray(Charsets.UTF_8))
                }

                if (connection.responseCode in 200..299) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)

                    if (!json.optBoolean("IsErroredOnProcessing", true)) {
                        resultText = json.optJSONArray("ParsedResults")
                            ?.optJSONObject(0)
                            ?.optString("ParsedText")
                            ?.takeIf { it.isNotBlank() }
                    }
                }

                connection.disconnect()
            } catch (e: Exception) {
                resultText = null
            }

            runOnUiThread { callback(resultText) }
        }.start()
    }

    private fun importImagesV5(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在识别表格图片...", Toast.LENGTH_SHORT).show()

        val imageQueue = ArrayDeque(uris)
        val debugTexts = mutableListOf<String>()

        fun finish() {
            saveEntries()
            showPage("vocab")

            if (entries.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("表格 OCR 识别结果")
                    .setMessage(
                        debugTexts.joinToString("\n---\n")
                            .ifBlank { "OCR 没有返回文字，请尝试更清晰、光线更好的图片。" }
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                Toast.makeText(this, "导入完成，共 ${entries.size} 词", Toast.LENGTH_LONG).show()
            }
        }

        fun processCropQueue(cropQueue: ArrayDeque<Pair<Bitmap, Bitmap>>) {
            if (cropQueue.isEmpty()) {
                if (imageQueue.isEmpty()) finish()
                return
            }

            val (leftBitmap, rightBitmap) = cropQueue.removeFirst()

            recognizeBitmapWithOcrSpace(leftBitmap) { leftText ->
                recognizeBitmapWithOcrSpace(rightBitmap) { rightText ->
                    val combined = listOfNotNull(leftText, rightText)
                        .joinToString("\n")

                    if (combined.isNotBlank()) {
                        debugTexts.add(combined)
                        extractOcrText(combined)
                    }

                    leftBitmap.recycle()
                    rightBitmap.recycle()
                    processCropQueue(cropQueue)
                }
            }
        }

        fun processNextImage() {
            if (imageQueue.isEmpty()) {
                finish()
                return
            }

            val uri = imageQueue.removeFirst()

            try {
                val source = loadScaledBitmap(uri)
                val cropQueue = ArrayDeque<Pair<Bitmap, Bitmap>>()

                val leftEnd = (source.width * 0.32f).toInt()
                val rightStart = (source.width * 0.28f).toInt()
                val rightEnd = (source.width * 0.68f).toInt()
                val chunkHeight = 1100

                var y = 0
                while (y < source.height) {
                    val height = minOf(chunkHeight, source.height - y)

                    val left = Bitmap.createBitmap(
                        source,
                        0,
                        y,
                        leftEnd.coerceAtMost(source.width),
                        height
                    )

                    val right = Bitmap.createBitmap(
                        source,
                        rightStart.coerceAtMost(source.width - 1),
                        y,
                        (rightEnd - rightStart).coerceAtMost(source.width - rightStart),
                        height
                    )

                    cropQueue.add(left to right)
                    y += height
                }

                source.recycle()
                processCropQueue(cropQueue)
            } catch (e: Exception) {
                debugTexts.add("图片裁剪失败：${e.message}")
                processNextImage()
            }
        }

        processNextImage()
    }
    private fun loadScaledBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("无法读取图片")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 2400 || bounds.outHeight / (sample * 2) >= 2400) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("无法解码图片")
    }

    private fun randomQuestion(): Question {
        val words = entries.map { it.word }.distinct()
        if (words.size >= 4) {
            val answer = words.random()
            val options = (listOf(answer) + words.filter { it != answer }.shuffled().take(3)).shuffled()
            return Question("选择与 “$answer” 搭配最自然的短语：\n${phraseFor(answer)}",
                options.map { phraseFor(it) }, options.indexOf(answer))
        }
        return Question("请先导入至少 4 个单词。", listOf("去词库", "稍后再试", "继续", "退出"), 0)
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
        currentQuestion?.options?.forEachIndexed { i, o ->
            options.addView(RadioButton(this).apply {
                text = o; id = View.generateViewId(); tag = i; setPadding(14, 14, 14, 14)
            })
        }
        layout.addView(options)

        val preview = ImageView(this).apply {
            visibility = View.GONE; adjustViewBounds = true; maxHeight = 600
        }
        photoPreview = preview
        layout.addView(Button(this).apply {
            text = "拍照 / 选择学习证明"
            setOnClickListener {
                val camera = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                if (camera.resolveActivity(packageManager) != null) takeProof.launch(camera)
                else pickProof.launch("image/*")
            }
        })
        layout.addView(preview)

        val result = TextView(this).apply { visibility = View.GONE }
        layout.addView(result)

        val dialog = AlertDialog.Builder(this)
            .setTitle("英语突击检查")
            .setView(layout)
            .setPositiveButton("提交证明", null)
            .setNegativeButton("稍后处理", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = options.checkedRadioButtonId
                if (!photoSelected) {
                    result.visibility = View.VISIBLE; result.setTextColor(0xFFB54242.toInt())
                    result.text = "请先上传学习现场照片。"; return@setOnClickListener
                }
                if (id == -1) {
                    result.visibility = View.VISIBLE; result.setTextColor(0xFFB54242.toInt())
                    result.text = "请先选择答案。"; return@setOnClickListener
                }
                val selected = options.findViewById<RadioButton>(id)
                val correct = (selected.tag as Int) == currentQuestion?.answer
                val done = prefs.getInt("done", 0) + 1
                val good = prefs.getInt("good", 0) + if (correct) 1 else 0
                prefs.edit().putInt("done", done).putInt("good", good)
                    .putInt("streak", if (correct) prefs.getInt("streak", 0) + 1 else 0)
                    .apply()
                saveRecord(correct)
                result.visibility = View.VISIBLE
                result.setTextColor(if (correct) 0xFF176B4D.toInt() else 0xFFB54242.toInt())
                result.text = if (correct) "证明提交成功，回答正确！" else "照片已记录，这道题还需要复习。"
                window.decorView.postDelayed({ dialog.dismiss() }, 1200L)
            }
        }
        dialog.show()
    }
}
