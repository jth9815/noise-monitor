package com.noiseMonitor

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ClassifyResult(
    val source: String,
    val confidence: Float,
    val note: String
)

object ClaudeClassifier {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // API 키는 앱 설정에서 입력받아 사용
    var apiKey: String = ""

    suspend fun classify(audioFile: File): ClassifyResult = withContext(Dispatchers.IO) {
        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            val headerSnippet = base64Audio.take(80)

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 200)
                put("system", """You are a noise classification AI. Respond ONLY with valid JSON, no markdown:
{"source":"오토바이"|"승용차"|"트럭"|"버스"|"경적"|"기타","confidence":0.0-1.0,"note":"brief Korean description"}""")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", """아파트 앞 대로 소음 클립입니다. 발생원을 분류해주세요.
파일크기: ${audioFile.length() / 1024}KB, 오디오 헤더: ${headerSnippet}...
발생원: 오토바이(고RPM 배기음), 승용차(일반 엔진음), 트럭(저음 디젤), 버스(대형 디젤), 경적(클락션), 기타""")
                            })
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext fallback()

            val json = JSONObject(responseBody)
            val contentArr = json.optJSONArray("content") ?: return@withContext fallback()
            var resultText = ""
            for (i in 0 until contentArr.length()) {
                val block = contentArr.getJSONObject(i)
                if (block.optString("type") == "text") {
                    resultText = block.optString("text", "")
                    break
                }
            }

            val cleaned = resultText.replace("```json", "").replace("```", "").trim()
            val result = JSONObject(cleaned)
            ClassifyResult(
                source = result.optString("source", "기타"),
                confidence = result.optDouble("confidence", 0.5).toFloat(),
                note = result.optString("note", "")
            )
        } catch (e: Exception) {
            fallback()
        }
    }

    private fun fallback() = ClassifyResult("기타", 0.5f, "분류 실패")
}
