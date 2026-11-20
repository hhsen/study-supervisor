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
                "瀛︿範绐佸嚮妫€鏌?,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "闅忔満鎻愰啋浣犱笂浼犲涔犺瘉鏄庡苟绛旈"
                enableVibration(true)
            }
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
            .setContentTitle("绐佸嚮妫€鏌ワ細璇疯瘉鏄庝綘姝ｅ湪瀛︿範")
            .setContentText("涓婁紶涓€寮犲涔犵幇鍦虹収鐗囷紝骞跺畬鎴愪竴閬撻")
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
        val workManager = WorkManager.getInstance(context)
        val infos = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK).get()
        if (infos.any { !it.state.isFinished }) return
        scheduleNext(context)
    }

    fun scheduleNext(context: Context) {
        val prefs = context.getSharedPreferences("study_supervisor", Context.MODE_PRIVATE)
        val base = prefs.getInt("interval", 25)
        val delay = Random.nextInt(base, base + 21).toLong()

        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .addTag("study_check")
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
    private var dialogPreview: ImageView? = null
    private var dialogHasPhoto = false
    private var currentQuestion: Question? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            dialogPreview?.setImageURI(uri)
            dialogPreview?.visibility = View.VISIBLE
            dialogHasPhoto = true
        }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val bitmap = result.data?.getParcelableExtra<Bitmap>("data")
            if (bitmap != null) {
                dialogPreview?.setImageBitmap(bitmap)
                dialogPreview?.visibility = View.VISIBLE
                dialogHasPhoto = true
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "鎻愰啋宸插紑鍚? else "鏈幏寰楅€氱煡鏉冮檺",
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
            text = "鑷範鐩戠潱鍛?
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "鎶娾€滄垜鍦ㄥ鈥濆彉鎴愮湅寰楄鐨勮瘉鎹?
            textSize = 14f
            setTextColor(0xFF68756E.toInt())
        })

        statusText = TextView(this).apply {
            text = "绐佸嚮妫€鏌ュ凡寮€鍚?
            textSize = 18f
            setTextColor(0xFF176B4D.toInt())
            setPadding(0, 40, 0, 8)
        }
        root.addView(statusText)

        val subject = editText("瀛︿範瀛︾ / 鍐呭锛屼緥濡傦細楂樼瓑鏁板绗?绔?)
        subject.setText(prefs.getString("subject", ""))
        root.addView(label("瀛︿範瀛︾ / 鍐呭"))
        root.addView(subject)

        val keywords = editText("鍏抽敭璇嶏紝鐢ㄩ€楀彿鍒嗛殧锛屼緥濡傦細鏋侀檺, 瀵兼暟")
        keywords.setText(prefs.getString("keywords", ""))
        root.addView(label("鍏抽敭璇?))
        root.addView(keywords)

        val book = editText("鑻辫鏁欐潗")
        book.setText(prefs.getString("book", "鍒樻檽鐕曘€婅嫳璇洓绾т綘杩樺湪鑳屽崟璇嶅悧銆?))
        root.addView(label("鑻辫鏁欐潗"))
        root.addView(book)

        val pageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pageStart = editText("璧峰椤?).apply {
            setText(prefs.getInt("pageStart", 1).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val pageEnd = editText("缁撴潫椤?).apply {
            setText(prefs.getInt("pageEnd", 20).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        pageRow.addView(pageStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pageRow.addView(pageEnd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(label("鑻辫鍑洪椤电爜鑼冨洿"))
        root.addView(pageRow)

        val spinner = Spinner(this)
        val intervals = arrayOf("15", "25", "45", "60")
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervals.map { "姣?$it鈥?{it.toInt() + 20} 鍒嗛挓" }
        )
        spinner.setSelection(intervals.indexOf(prefs.getInt("interval", 25).toString()).coerceAtLeast(0))
        root.addView(label("绐佸嚮妫€鏌ラ棿闅?))
        root.addView(spinner)

        root.addView(button("淇濆瓨瀛︿範璁″垝") {
            prefs.edit()
                .putString("subject", subject.text.toString().trim())
                .putString("keywords", keywords.text.toString().trim())
                .putString("book", book.text.toString().trim())
                .putInt("pageStart", pageStart.text.toString().toIntOrNull() ?: 1)
                .putInt("pageEnd", pageEnd.text.toString().toIntOrNull() ?: 20)
                .putInt("interval", intervals[spinner.selectedItemPosition].toInt())
                .apply()
            Scheduler.scheduleNext(this)
            Toast.makeText(this, "瀛︿範璁″垝宸蹭繚瀛?, Toast.LENGTH_SHORT).show()
            updateStatus()
        })

        root.addView(button("寮€鍚€氱煡鎻愰啋") {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Scheduler.ensureScheduled(this)
                Toast.makeText(this, "鎻愰啋宸插紑鍚?, Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(button("绔嬪嵆绐佸嚮妫€鏌?) {
            showCheck()
        })

        val history = TextView(this).apply {
            text = "宸插畬鎴愭鏌ワ細${prefs.getInt("done", 0)} 娆?   姝ｇ‘锛?{prefs.getInt("good", 0)} 娆?
            textSize = 14f
            setTextColor(0xFF68756E.toInt())
            setPadding(0, 36, 0, 0)
        }
        root.addView(history)

        setContentView(root)
        updateStatus()
    }

    private fun updateStatus() {
        val done = prefs.getInt("done", 0)
        val good = prefs.getInt("good", 0)
        statusText.text = if (done == 0) {
            "鍑嗗寮€濮嬩粖澶╃殑鑷範"
        } else if (done == good) {
            "浠婂ぉ鐨勭姸鎬佸緢绋筹紝缁х画淇濇寔"
        } else {
            "宸插畬鎴?$done 娆℃鏌ワ紝姝ｇ‘ $good 娆?
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }
    }

    private fun editText(hint: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setPadding(24, 20, 24, 20)
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
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
            subject.contains("鑻辫") || book.contains("鑻辫鍥涚骇") -> Question(
                text = "鏍规嵁 $book 绗?$start鈥?end 椤碉紝涓嬪垪鍝釜璇嶆渶鎺ヨ繎鈥滃潥鎸佷笉鎳堚€濈殑鍚箟锛?,
                options = listOf("persist", "pretend", "provide", "predict"),
                answer = 0,
                subject = "鑻辫鍥涚骇"
            )
            subject.contains("鏁板") -> Question(
                text = if (keywords.isNotEmpty()) {
                    "鍏充簬鈥?{keywords.first()}鈥濓紝涓嬪垪璇存硶姝ｇ‘鐨勬槸锛?
                } else {
                    "鍑芥暟鍦ㄦ煇鐐瑰彲瀵硷紝鏄惁涓€瀹氳兘鎺ㄥ嚭璇ョ偣杩炵画锛?
                },
                options = listOf("鑳?, "涓嶈兘", "涓€瀹氫笉", "鏃犳硶鍒ゆ柇"),
                answer = if (keywords.isNotEmpty()) 1 else 0,
                subject = subject
            )
            subject.contains("鐗╃悊") -> Question(
                text = "鐗涢】绗簩瀹氬緥鐨勮〃杈惧紡鏄紵",
                options = listOf("F=ma", "E=mc虏", "V=IR", "p=mv虏"),
                answer = 0,
                subject = subject
            )
            else -> Question(
                text = if (keywords.isNotEmpty()) {
                    "璇风敤涓€鍙ヨ瘽瑙ｉ噴鈥?{keywords.first()}鈥濈殑鏍稿績鍚箟銆?
                } else {
                    "璇风畝杩颁粖澶╁涔犲唴瀹逛腑鏈€閲嶈鐨勪竴涓粨璁恒€?
                },
                options = listOf("宸叉帉鎻?, "闇€瑕佸涔?, "杩樹笉鐞嗚В", "绋嶅悗琛ュ厖"),
                answer = 0,
                subject = subject.ifEmpty { "瀛︿範" }
            )
        }
    }

    private fun showCheck() {
        currentQuestion = randomQuestion()
        dialogHasPhoto = false

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        val questionText = TextView(this).apply {
            text = currentQuestion?.text
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 16)
        }
        layout.addView(questionText)

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

        val photoButton = Button(this).apply {
            text = "鎷嶇収 / 閫夋嫨瀛︿範璇佹槑"
            setOnClickListener {
                val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (camera.resolveActivity(packageManager) != null) {
                    takePhoto.launch(camera)
                } else {
                    pickPhoto.launch("image/*")
                }
            }
        }
        layout.addView(photoButton)

        val preview = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            maxHeight = 600
            setPadding(0, 16, 0, 8)
        }
        dialogPreview = preview
        layout.addView(preview)

        val result = TextView(this).apply {
            visibility = View.GONE
            setPadding(0, 12, 0, 0)
        }
        layout.addView(result)

        val dialog = AlertDialog.Builder(this)
            .setTitle("绐佸嚮妫€鏌ワ細璇疯瘉鏄庝綘姝ｅ湪瀛︿範")
            .setView(layout)
            .setPositiveButton("鎻愪氦璇佹槑", null)
            .setNegativeButton("绋嶅悗澶勭悊", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedId = options.checkedRadioButtonId
                if (!dialogHasPhoto) {
                    result.visibility = View.VISIBLE
                    result.setTextColor(0xFFB54242.toInt())
                    result.text = "璇峰厛涓婁紶涓€寮犲涔犵幇鍦虹収鐗囥€?
                    return@setOnClickListener
                }
                if (selectedId == -1) {
                    result.visibility = View.VISIBLE
                    result.setTextColor(0xFFB54242.toInt())
                    result.text = "璇峰厛閫夋嫨涓€涓瓟妗堛€?
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
                result.text = if (correct) {
                    "璇佹槑鎻愪氦鎴愬姛锛屽洖绛旀纭紒"
                } else {
                    "鐓х墖宸茶褰曪紝杩欓亾棰樿繕闇€瑕佸涔犮€?
                }

                updateStatus()
                window.decorView.postDelayed({ dialog.dismiss() }, 1200)
            }
        }

        dialog.show()
    }
}
