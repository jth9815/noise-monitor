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

    var apiKey: String = ""

    suspend fun classify(audioFile: File): ClassifyResult = withContext(Dispatchers.IO) {
        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", """아파트 앞 대로 소음 클립입니다. 발생원을 분류해주세요.
파일크기: ${audioFile.length() / 1024}KB
발생원: 오토바이(고RPM 배기음), 승용차(일반 엔진음), 트럭(저음 디젤), 버스(대형 디젤), 경적(클락션), 기타
반드시 아래 JSON 형식으로만 응답하세요. 마크다운 없이:
{"source":"오토바이"|"승용차"|"트럭"|"버스"|"경적"|"기타","confidence":0.0-1.0,"note":"한국어 설명"}""")
                            })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "audio/mp4")
                                    put("data", base64Audio)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 200)
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext fallback()

            val json = JSONObject(responseBody)
            val text = json
                .optJSONArray("candidates")
                ?.getJSONObject(0)
                ?.getJSONObject("content")
                ?.getJSONArray("parts")
                ?.getJSONObject(0)
                ?.optString("text", "{}") ?: return@withContext fallback()

            val cleaned = text.replace("```json", "").replace("```", "").trim()
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
