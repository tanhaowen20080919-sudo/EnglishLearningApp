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
            is Number -> runCatching { java.math.BigDecimal(value.toString()).longValueExact() }.getOrNull()?.takeIf { it>=0 }
            is String -> value.toLongOrNull()?.takeIf { it>=0 }
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
        val cache = runCatching { Math.addExact(write,read) }.getOrElse { return TokenUsage() }
        val inclusive = when {
            input != null && separate -> runCatching { Math.addExact(input,cache) }.getOrElse { return TokenUsage() }
            input != null -> input.coerceAtLeast(cache)
            miss != null -> runCatching { Math.addExact(miss,cache) }.getOrElse { return TokenUsage() }
            else -> cache
        }
        val derivedTotal=runCatching { Math.addExact(inclusive,output ?: 0L) }.getOrElse { return TokenUsage() }
        return TokenUsage(inclusive, output ?: 0L, write, read,
            (number(usage, "total_tokens") ?: 0L).coerceAtLeast(derivedTotal),
            known = (input != null || miss != null) && output != null && (separate || input==null || input>=cache),
            cacheCreationKnown = listOf("cache_write_tokens","cache_creation_tokens").any { number(details,it)!=null } ||
                listOf("cache_creation_input_tokens","cache_creation_tokens","cache_write_tokens","prompt_cache_creation_tokens").any { number(usage,it)!=null },
            cacheReadKnown = listOf("cached_tokens","cache_read_tokens").any { number(details,it)!=null } ||
                listOf("prompt_cache_hit_tokens","cache_read_input_tokens","cache_read_tokens","cached_tokens").any { number(usage,it)!=null })
    }
}
