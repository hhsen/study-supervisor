package com.study.supervisor

import android.Manifest
import android.app.Activity
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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

data class VocabEntry(
    val word: String,
    val meaning: String,
    val phrase: String,
    val phraseMeaning: String,
    val sentence: String,
    val sentenceMeaning: String
)

class VocabularyActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var countText: TextView
    private var entries: MutableList<VocabEntry> = mutableListOf()

    private val pickWordPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) importImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("study_supervisor", MODE_PRIVATE)
        entries = loadEntries()
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1C2C.toInt())
        }

        root.addView(buildHeader())
        root.addView(buildToolbar())

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 32)
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(0, 0, 1f))

        root.addView(buildBottomNav())
        setContentView(root)
        renderList()
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 24)
            background = GradientDrawable().apply {
                setColor(0xFF123B4A.toInt())
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 36f, 36f, 36f, 36f)
            }
        }

        header.addView(TextView(this).apply {
            text = "词库"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        countText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB8D8D4.toInt())
            setPadding(0, 8, 0, 0)
        }
        header.addView(countText)

        return header
    }

    private fun buildToolbar(): LinearLayout {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        searchInput = EditText(this).apply {
            hint = "搜索单词..."
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7FA7AD.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF102B3D.toInt())
                cornerRadius = 24f
                setStroke(2, 0xFF1D4758.toInt())
            }
            setPadding(32, 22, 32, 22)
        }
        toolbar.addView(searchInput)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 18, 0, 8)
        }

        actions.addView(makeAccentButton("导入图片") {
            pickWordPhotos.launch("image/*")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        actions.addView(makeAccentButton("添加单词") {
            showAddDialog()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        toolbar.addView(actions)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderList() }
            override fun afterTextChanged(s: Editable?) {}
        })

        return toolbar
    }

    private fun makeAccentButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF14B8A6.toInt())
                cornerRadius = 18f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 12, 0)
            layoutParams = lp
            setOnClickListener { action() }
        }
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 12, 8, 18)
        }

        val items = listOf(
            "首页" to false,
            "突击" to false,
            "词库" to true,
            "档案" to false,
            "设置" to false
        )

        items.forEach { (label, active) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            item.addView(TextView(this).apply {
                text = if (active) "📚" else "•"
                textSize = 20f
                setTextColor(if (active) 0xFF14B8A6.toInt() else 0xFF6B7280.toInt())
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) 0xFF14B8A6.toInt() else 0xFF6B7280.toInt())
            })
            item.setOnClickListener {
                when (label) {
                    "首页" -> finish()
                    "突击" -> {
                        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CHECK, true))
                        finish()
                    }
                    "设置" -> {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                    else -> Unit
                }
            }
            nav.addView(item)
        }

        return nav
    }

    private fun renderList() {
        val query = searchInput.text.toString().trim().lowercase()
        listContainer.removeAllViews()

        val filtered = entries.filter {
            query.isEmpty() || it.word.contains(query) || it.meaning.contains(query)
        }

        countText.text = "第 ${prefs.getInt("pageStart", 1)}-${prefs.getInt("pageEnd", 20)} 页 · ${entries.size} 词"

        if (filtered.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (entries.isEmpty()) "还没有单词，点击“导入图片”开始。" else "没有找到匹配的单词。"
                textSize = 15f
                setTextColor(0xFFB8D8D4.toInt())
                setPadding(0, 40, 0, 0)
                gravity = Gravity.CENTER
            })
            return
        }

        filtered.forEachIndexed { index, entry ->
            listContainer.addView(buildWordCard(index + 1, entry))
        }
    }

    private fun buildWordCard(index: Int, entry: VocabEntry): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(0xFF102B3D.toInt())
                cornerRadius = 26f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 16
            layoutParams = lp
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = index.toString()
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF14B8A6.toInt())
                cornerRadius = 18f
            }
            layoutParams = LinearLayout.LayoutParams(64, 64)
        })

        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(this@VocabularyActivity).apply {
                text = entry.word
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@VocabularyActivity).apply {
                text = entry.meaning
                textSize = 15f
                setTextColor(0xFFB8D8D4.toInt())
                setPadding(0, 4, 0, 0)
            })
        })
        card.addView(top)

        card.addView(TextView(this).apply {
            text = "短语：${entry.phrase}\n中文：${entry.phraseMeaning}"
            textSize = 14f
            setTextColor(0xFFD5E7E5.toInt())
            setPadding(0, 18, 0, 0)
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
            setPadding(0, 16, 0, 0)
        }
        actions.addView(makeSmallButton("编辑") { showEditDialog(entry) })
        actions.addView(makeSmallButton("删除") { deleteEntry(entry) })
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

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val wordInput = EditText(this).apply { hint = "英文单词" }
        val meaningInput = EditText(this).apply { hint = "中文意思" }
        layout.addView(wordInput)
        layout.addView(meaningInput)

        AlertDialog.Builder(this)
            .setTitle("添加单词")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val word = wordInput.text.toString().trim().lowercase()
                val meaning = meaningInput.text.toString().trim()
                if (word.isNotEmpty()) {
                    addEntry(word, meaning.ifEmpty { "待补充中文意思" })
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog(entry: VocabEntry) {
        val input = EditText(this).apply {
            setText(entry.meaning)
            hint = "中文意思"
        }
        AlertDialog.Builder(this)
            .setTitle("编辑 ${entry.word} 的中文意思")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newMeaning = input.text.toString().trim()
                if (newMeaning.isNotEmpty()) {
                    val index = entries.indexOfFirst { it.word == entry.word }
                    if (index >= 0) {
                        entries[index] = entry.copy(meaning = newMeaning)
                        saveEntries()
                        renderList()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEntry(entry: VocabEntry) {
        entries.removeAll { it.word == entry.word }
        saveEntries()
        renderList()
    }

    private fun makeGeneratedContent(word: String, meaning: String): VocabEntry {
        val phrase = "$word every day"
        val phraseMeaning = "每天${meaning}"
        val sentence = "I $word every day."
        val sentenceMeaning = "我每天${meaning}。"
        return VocabEntry(word, meaning, phrase, phraseMeaning, sentence, sentenceMeaning)
    }

    private fun addEntry(word: String, meaning: String) {
        if (entries.any { it.word == word }) {
            Toast.makeText(this, "这个单词已经存在", Toast.LENGTH_SHORT).show()
            return
        }
        entries.add(makeGeneratedContent(word, meaning))
        saveEntries()
        renderList()
    }

    private fun saveEntries() {
        val array = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject()
            obj.put("word", e.word)
            obj.put("meaning", e.meaning)
            obj.put("phrase", e.phrase)
            obj.put("phraseMeaning", e.phraseMeaning)
            obj.put("sentence", e.sentence)
            obj.put("sentenceMeaning", e.sentenceMeaning)
            array.put(obj)
        }
        prefs.edit().putString("vocab_json", array.toString()).apply()
    }

    private fun loadEntries(): MutableList<VocabEntry> {
        val result = mutableListOf<VocabEntry>()
        val oldWords = prefs.getStringSet("words", emptySet()) ?: emptySet()
        oldWords.forEach { result.add(makeGeneratedContent(it, "待补充中文意思")) }

        val json = prefs.getString("vocab_json", null)
        if (json != null) {
            try {
                val array = JSONArray(json)
                result.clear()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(
                        VocabEntry(
                            obj.optString("word"),
                            obj.optString("meaning", "待补充中文意思"),
                            obj.optString("phrase"),
                            obj.optString("phraseMeaning"),
                            obj.optString("sentence"),
                            obj.optString("sentenceMeaning")
                        )
                    )
                }
            } catch (e: Exception) {
            }
        }
        return result
    }

    private fun importImages(uris: List<Uri>) {
        Toast.makeText(this, "正在逐张识别 ${uris.size} 张图片...", Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val queue = ArrayDeque(uris)

        fun next() {
            if (queue.isEmpty()) {
                recognizer.close()
                saveEntries()
                renderList()
                Toast.makeText(this, "导入完成", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = queue.removeFirst()
            try {
                val bitmap = loadScaledBitmap(uri)
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val lines = result.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        var pendingEnglish: String? = null

                        lines.forEach { line ->
                            val english = Regex("[A-Za-z]{2,}").find(line)?.value?.lowercase()
                            val chinese = Regex("[\\u4e00-\\u9fff]+").find(line)?.value

                            if (english != null && chinese != null) {
                                addEntry(english, chinese)
                                pendingEnglish = null
                            } else if (english != null) {
                                pendingEnglish = english
                            } else if (chinese != null && pendingEnglish != null) {
                                addEntry(pendingEnglish!!, chinese)
                                pendingEnglish = null
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
}
