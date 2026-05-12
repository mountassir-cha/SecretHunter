package com.secrethunter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

data class RegexRule(
    val id: String,
    val name: String,
    val category: String,
    val severity: String,
    val pattern: Pattern,
)

class RegexDetector(context: Context) {

    private val rules: List<RegexRule> = loadRules(context)

    fun rulesCount(): Int = rules.size

    fun matchLine(
        line: CharSequence,
        lineNumber: Int,
        filePath: String,
        foundAtMillis: Long,
    ): List<Secret> {
        if (line.isBlank()) return emptyList()
        val out = ArrayList<Secret>()
        for (rule in rules) {
            val matcher = rule.pattern.matcher(line)
            while (matcher.find()) {
                val snippet = extractSnippet(matcher).take(SNIPPET_MAX)
                out.add(
                    Secret(
                        id = 0,
                        ruleId = rule.id,
                        ruleName = rule.name,
                        category = rule.category,
                        severity = rule.severity,
                        snippet = snippet,
                        filePath = filePath,
                        lineNumber = lineNumber,
                        foundAtMillis = foundAtMillis,
                    ),
                )
            }
        }
        return out
    }

    private fun extractSnippet(matcher: java.util.regex.Matcher): String {
        val gc = matcher.groupCount()
        if (gc >= 2) {
            val g2 = matcher.group(2)
            if (!g2.isNullOrBlank()) return g2
        }
        if (gc >= 1) {
            val g1 = matcher.group(1)
            if (!g1.isNullOrBlank()) return g1
        }
        return matcher.group() ?: ""
    }

    companion object {
        private const val SNIPPET_MAX = 240

        private fun loadRules(context: Context): List<RegexRule> {
            val raw = context.assets.open("regex_rules.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            val list = ArrayList<RegexRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                val patternStr = o.getString("pattern")
                val pattern = Pattern.compile(patternStr)
                list.add(
                    RegexRule(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        category = o.optString("category", "OTHER"),
                        severity = o.getString("severity"),
                        pattern = pattern,
                    ),
                )
            }
            return list
        }
    }
}
