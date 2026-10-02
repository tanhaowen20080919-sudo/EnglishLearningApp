package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.data.TokenUsage
import org.json.JSONObject

/** Normalize inclusive OpenAI/DeepSeek counts and separate Anthropic-style gateway counts.
 * OpenAI: https://developers.openai.com/api/docs/guides/prompt-caching
 * DeepSeek: https://api-docs.deepseek.com/guides/kv_cache
 * Anthropic: https://platform.claude.com/docs/en/build-with-claude/prompt-caching
 * Cache writes and reads are input partitions, never an extra charge on ordinary input.
 */
object UsageParser {
    fun parse(response: JSONObject, cacheInputMode: String = "auto"): TokenUsage {
        val usage = response.optJSONObject("usage") ?: return TokenUsage()
        val details = usage.optJSONObject("prompt_tokens_details")
            ?: usage.optJSONObject("input_tokens_details") ?: JSONObject()
        fun number(obj: JSONObject, key: String): Long? = when (val value = obj.opt(key)) {
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0)
            is String -> value.toLongOrNull()?.coerceAtLeast(0)
            else -> null
        }
        fun first(vararg values: Long?) = values.firstOrNull { it != null }
        val write = first(number(details, "cache_write_tokens"), number(details, "cache_creation_tokens"),
            number(usage, "cache_creation_input_tokens"), number(usage, "cache_creation_tokens"),
            number(usage, "cache_write_tokens"), number(usage, "prompt_cache_creation_tokens")) ?: 0L
        val read = first(number(usage, "prompt_cache_hit_tokens"), number(details, "cached_tokens"),
            number(details, "cache_read_tokens"), number(usage, "cache_read_input_tokens"),
            number(usage, "cache_read_tokens"), number(usage, "cached_tokens")) ?: 0L
        val input = first(number(usage, "prompt_tokens"), number(usage, "input_tokens"))
        val miss = number(usage, "prompt_cache_miss_tokens")
        val output = first(number(usage, "completion_tokens"), number(usage, "output_tokens"))
        val separate = when (cacheInputMode) {
            "separate" -> true
            "included" -> false
            else -> !usage.has("prompt_tokens") && !usage.has("input_tokens_details") &&
                response.optString("object") != "response" &&
                (usage.has("cache_creation_input_tokens") || usage.has("cache_read_input_tokens"))
        }
        val cache = write + read
        val inclusive = when {
            input != null && separate -> input + cache
            input != null -> input.coerceAtLeast(cache)
            miss != null -> miss + cache
            else -> cache
        }
        return TokenUsage(inclusive, output ?: 0L, write, read,
            (number(usage, "total_tokens") ?: 0L).coerceAtLeast(inclusive + (output ?: 0L)),
            known = (input != null || miss != null) && output != null)
    }
}
