package com.study.supervisor

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object DoubaoAi {

    private const val API_URL = "https://ark.cn-beijing.volces.com/api/v3/responses"
    private const val MODEL_ID = "doubao-seed-2-0-pro-260215"

    private val mainHandler = Handler(Looper.getMainLooper())

    data class WordItem(
        val word: String,
        val meaning: String,
        val page: Int = 0
    )

    data class PhotoReview(
        val passed: Boolean,
        val reason: String
    )

    data class TextReview(
        val hasOutOfSyllabus: Boolean,
        val words: List<String>
    )

    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        val scaled = if (bitmap.width > 2200 || bitmap.height > 2200) {
            val scale = minOf(
                2200f / bitmap.width,
                2200f / bitmap.height
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }

        scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)
        if (scaled !== bitmap) scaled.recycle()

        return "data:image/jpeg;base64," +
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun request(
        apiKey: String,
        prompt: String,
        imageBase64: String?,
        temperature: Double,
        callback: (Result<String>) -> Unit
    ) {
        Thread {
            try {
                val content = JSONArray()

                if (!imageBase64.isNullOrBlank()) {
                    content.put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", imageBase64)
                    })
                }

                content.put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", prompt)
                })

                val payload = JSONObject().apply {
                    put("model", MODEL_ID)
                    put("temperature", temperature)
                    put("input", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    }))
                }

                val connection = URL(API_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 60000
                connection.readTimeout = 120000
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")

                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                }

                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
                }
                connection.disconnect()

                if (code !in 200..299) {
                    throw RuntimeException("API $code: ${body.take(500)}")
                }

                val text = extractResponseText(body)
                if (text.isBlank()) {
                    throw RuntimeException("模型没有返回文字")
                }

                mainHandler.post { callback(Result.success(text)) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(Result.failure(e))
                }
            }
        }.start()
    }

    private fun extractResponseText(body: String): String {
        val json = JSONObject(body)

        if (json.has("output_text")) {
            return json.optString("output_text")
        }

        val output = json.optJSONArray("output")
        val texts = mutableListOf<String>()

        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val content = item.optJSONArray("content") ?: continue

                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    val type = part.optString("type")

                    if (type == "output_text" || type == "text") {
                        texts.add(part.optString("text"))
                    }
                }
            }
        }

        return texts.joinToString("\n").trim()
    }

    private fun extractJsonArray(text: String): JSONArray {
        val cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')

        if (start >= 0 && end > start) {
            return JSONArray(cleaned.substring(start, end + 1))
        }

        throw IllegalArgumentException("模型未返回 JSON 数组")
    }

    fun recognizeWords(
        apiKey: String,
        bitmap: Bitmap,
        callback: (Result<List<WordItem>>) -> Unit
    ) {
        val prompt = """
            你是四级单词表识图助手。
            完整识别图片中的英文单词及中文释义。
            忽略页码、序号、标题、表格线和复习勾选栏。
            严格按照以下 JSON 数组输出，不要输出解释：
            [{"word":"英文单词","meaning":"中文释义"}]
            如果释义缺失，请根据四级常见词义补全最合理的中文释义。
        """.trimIndent()

        request(apiKey, prompt, bitmapToDataUrl(bitmap), 0.1) { result ->
            callback(
                result.mapCatching { text ->
                    val array = extractJsonArray(text)
                    val items = mutableListOf<WordItem>()

                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val word = listOf("word", "w", "text")
                            .mapNotNull { obj.optString(it).takeIf { value -> value.isNotBlank() } }
                            .firstOrNull()
                            ?.trim()
                            ?.lowercase()
                            ?: continue

                        val meaning = listOf("meaning", "释义", "cn", "definition")
                            .mapNotNull { obj.optString(it).takeIf { value -> value.isNotBlank() } }
                            .firstOrNull()
                            ?.trim()
                            ?: continue

                        if (word.isNotBlank() && meaning.isNotBlank()) {
                            items.add(WordItem(word, meaning))
                        }
                    }

                    if (items.isEmpty()) {
                        throw IllegalArgumentException("没有解析到单词")
                    }

                    items
                }
            )
        }
    }

    fun reviewPhoto(
        apiKey: String,
        bitmap: Bitmap,
        callback: (Result<PhotoReview>) -> Unit
    ) {
        val prompt = """
            你是学习监督助手。
            判断照片中是否出现真实的学习迹象，例如正在书写的作业、教材、笔记、单词表或学习屏幕。
            只输出 JSON：
            {"passed":true,"reason":"简短中文理由"}
        """.trimIndent()

        request(apiKey, prompt, bitmapToDataUrl(bitmap), 0.2) { result ->
            callback(
                result.mapCatching { text ->
                    val cleaned = text
                        .replace("```json", "")
                        .replace("```", "")
                        .trim()
                    val start = cleaned.indexOf('{')
                    val end = cleaned.lastIndexOf('}')

                    val json = JSONObject(cleaned.substring(start, end + 1))
                    PhotoReview(
                        passed = json.optBoolean("passed", false),
                        reason = json.optString("reason", "未说明原因")
                    )
                }
            )
        }
    }

    fun generateEssay(
        apiKey: String,
        words: List<String>,
        callback: (Result<String>) -> Unit
    ) {
        val prompt = """
            你是四级英语写作助手。
            使用下列四级词汇写一篇 120 到 160 词的英语短文：
            ${words.joinToString(", ")}
            要求：
            1. 难度符合大学英语四级。
            2. 尽量使用给定词汇。
            3. 结构完整，表达自然。
            4. 只输出英文短文，不要标题和解释。
        """.trimIndent()

        request(apiKey, prompt, null, 0.7) { result ->
            callback(result.map { it.trim() })
        }
    }

    fun auditEssay(
        apiKey: String,
        essay: String,
        knownWords: List<String>,
        callback: (Result<TextReview>) -> Unit
    ) {
        val prompt = """
            你是四级词汇审查助手。
            以下单词属于允许词汇表：
            ${knownWords.joinToString(", ")}
            请检查英语短文中是否有超出该词表的实义词。
            忽略基础语法词和标点。
            只输出 JSON：
            {"hasOutOfSyllabus":true,"words":["word1","word2"]}
        """.trimIndent() + "\n\n短文：\n$essay"

        request(apiKey, prompt, null, 0.0) { result ->
            callback(
                result.mapCatching { text ->
                    val cleaned = text
                        .replace("```json", "")
                        .replace("```", "")
                        .trim()
                    val start = cleaned.indexOf('{')
                    val end = cleaned.lastIndexOf('}')
                    val json = JSONObject(cleaned.substring(start, end + 1))

                    val words = mutableListOf<String>()
                    val array = json.optJSONArray("words")
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            words.add(array.optString(i))
                        }
                    }

                    TextReview(
                        hasOutOfSyllabus = json.optBoolean(
                            "hasOutOfSyllabus",
                            words.isNotEmpty()
                        ),
                        words = words.filter { it.isNotBlank() }
                    )
                }
            )
        }
    }

    fun rewriteEssay(
        apiKey: String,
        essay: String,
        bannedWords: List<String>,
        callback: (Result<String>) -> Unit
    ) {
        val prompt = """
            请重写下面的四级英语短文。
            不要使用这些超纲词：${bannedWords.joinToString(", ")}
            保持 120 到 160 词，保持主题和结构。
            只输出英文短文，不要解释。
        """.trimIndent() + "\n\n原文：\n$essay"

        request(apiKey, prompt, null, 0.2) { result ->
            callback(result.map { it.trim() })
        }
    }
}
