package com.tanhaowen.contextenglish.ai

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/** Single network boundary. Calling generate/runQueued is exclusively a user action. No worker/timer calls it. */
class AiTaskScheduler(private val repository: EnglishRepository, private val client: AiClient = AiClient()) {

    private val db get()=repository.database.writableDatabase
    companion object {
        private val mutex=Mutex()
        const val CACHE_VERSION=2
        fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun parseObject(text: String): JSONObject {
            var cleaned=text.trim().removePrefix("\uFEFF")
            Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$",RegexOption.IGNORE_CASE).matchEntire(cleaned)?.let { cleaned=it.groupValues[1].trim() }
            require(cleaned.length<=100_000) { "AI 内容过长" }
            val tokener=org.json.JSONTokener(cleaned)
            val value=tokener.nextValue()
            require(value is JSONObject && tokener.nextClean()=='\u0000') { "AI 未返回单个完整 JSON 对象" }
            return value
        }
        fun validateExplanation(text: String) {
            val j=parseObject(text)
            require(j.opt("explanation") is String && j.getString("explanation").trim().length in 10..12000) { "解析缺少有效 explanation 字段" }
            val a=j.getJSONArray("suggestions")
            require(a.length() in 1..8)
            repeat(a.length()) { require(a.get(it) is String && a.getString(it).trim().length in 1..1000) }
        }
    }
    private fun key(settings: AiSettings, type: String, prompt: String) = hash(
        "$CACHE_VERSION|${settings.baseUrl.trimEnd('/')}|${settings.apiStyle}|${settings.dailyModel}|$type|$prompt")
    suspend fun queue(settings: AiSettings,type: String,prompt: String): String = withContext(Dispatchers.IO) {
        val key=key(settings,type,prompt)
        db.insertWithOnConflict("ai_tasks",null,ContentValues().apply {
            put("task_key",key); put("content_type",type); put("input_hash",hash(prompt)); put("model",settings.dailyModel)
            put("version",CACHE_VERSION); put("created_at",System.currentTimeMillis()); put("expires_at",0)
            put("status","QUEUED")
        },SQLiteDatabase.CONFLICT_IGNORE)
        key
    }
    suspend fun cached(settings: AiSettings,type: String,prompt: String): String? = withContext(Dispatchers.IO) {
        val now=System.currentTimeMillis()
        db.rawQuery("SELECT content FROM ai_tasks WHERE task_key=? AND status='DONE' AND expires_at>? AND version=?",
            arrayOf(key(settings,type,prompt),now.toString(),CACHE_VERSION.toString())).use { if(it.moveToFirst()) it.getString(0) else null }
    }
    suspend fun generate(settings: AiSettings,prompt: String,maxTokens: Int = 2600,
        canStart: () -> Boolean = { true }): Result<AiCallResult> = request(settings,"assistant",prompt,maxTokens,canStart)

    suspend fun request(settings: AiSettings,type: String,prompt: String,maxTokens: Int = 2600,
        canStart: () -> Boolean = { true },useCache: Boolean = true,validate: (String)->Unit = {}): Result<AiCallResult> = mutex.withLock {
        if(!canStart()) return@withLock Result.failure(AiStartSkippedException())
        val key=queue(settings,type,prompt)
        (if(useCache) cached(settings,type,prompt) else null)?.let { content ->
            try {
                validate(content)
                val actualModel=withContext(Dispatchers.IO) {
                    db.rawQuery("SELECT model FROM ai_tasks WHERE task_key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else settings.dailyModel }
                }
                return@withLock Result.success(AiCallResult(content,TokenUsage(),actualModel,fromCache=true))
            }
            catch(_: Exception) { /* A prior schema is no longer usable. Regenerate only on this user action. */ }
        }
        // Local configuration errors are not network calls and must not enter the spending ledger.
        try { client.validate(settings) } catch(e: Exception) { return@withLock Result.failure(e) }
        val started=System.nanoTime()
        var usageId: Long?=null
        var receivedUsage=false
        try {
            withContext(Dispatchers.IO) {
                usageId=repository.recordAiUsage("未返回（请求：${settings.dailyModel}）",TokenUsage(),settings,status="RUNNING").id
                update(key,"RUNNING",usageId=usageId)
            }
            val result=client.generate(settings,prompt,maxTokens,canStart).getOrThrow()
            val record=withContext(Dispatchers.IO) {
                repository.recordAiUsage(result.model.ifBlank { "未返回（请求：${settings.dailyModel}）" },result.usage,settings,
                    (System.nanoTime()-started)/1_000_000,existingId=usageId)
            }
            usageId=record.id
            receivedUsage=true
            require(result.content.isNotBlank()) { "AI 未返回有效内容，可继续离线学习" }
            validate(result.content)
            withContext(Dispatchers.IO) { update(key,"DONE",result.content,usageId=record.id,model=record.model) }
            Result.success(result.copy(usageId=record.id))
        } catch(e: Exception) {
            withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) {
                if(!receivedUsage && e !is AiStartSkippedException) repository.recordAiUsage("未返回（请求：${settings.dailyModel}）",TokenUsage(),settings,
                    (System.nanoTime()-started)/1_000_000,if(e is CancellationException) "CANCELLED_USAGE_UNKNOWN" else "FAILED_USAGE_UNKNOWN",existingId=usageId)
                if(receivedUsage) db.execSQL("UPDATE ai_usage SET request_status='INVALID_CONTENT' WHERE id=?",arrayOf(usageId))
                update(key,if(e is CancellationException) "CANCELLED" else "FAILED",error=friendlyAiError(e),usageId=usageId)
            }
            if(e is CancellationException) throw e
            Result.failure(e)
        }
    }
    private fun update(key: String,status: String,content: String = "",error: String = "",usageId: Long? = null,model: String? = null) {
        db.update("ai_tasks",ContentValues().apply {
            model?.let { put("model",it) }; put("status",status); put("content",content); put("error",error.take(200)); put("created_at",System.currentTimeMillis())
            put("expires_at",System.currentTimeMillis()+30*86_400_000L); usageId?.let { put("usage_id",it) }
        },"task_key=?",arrayOf(key))
    }
    suspend fun listModels(settings: AiSettings)=client.listModels(settings)
    suspend fun generateDailyReading(settings: AiSettings)=request(settings,"reading", "为基础较弱的高三学生生成一篇160词英语短文、3道四选一题，给中文翻译和答案解析。")
}
