package io.github.rosemoe.sora.app

import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import java.time.Duration
import kotlin.math.min

internal object VSCodeSyntaxSelfTest {

    data class Options(
        val perLanguageSamplesLimit: Int = 4,
        val perSampleTokenPreviewLimit: Int = 10,
        val timeLimitNativeSeconds: Long = 2,
        val timeLimitJoniSeconds: Long = 5
    )

    data class LanguageReport(
        val ok: Boolean,
        val suspectedNoHighlight: Boolean,
        val stoppedEarly: Boolean,
        val text: String
    )

    suspend fun run(
        languages: List<VSCodeIntegration.VSCodeLanguageInfo>,
        options: Options = Options(),
        isNativeOniguruma: () -> Boolean,
        ensureGrammarLoaded: suspend (VSCodeIntegration.VSCodeLanguageInfo) -> Result<IGrammar>,
        sampleProvider: (VSCodeIntegration.VSCodeLanguageInfo) -> List<String>
    ): String {
        val timeLimit = if (isNativeOniguruma()) {
            Duration.ofSeconds(options.timeLimitNativeSeconds)
        } else {
            Duration.ofSeconds(options.timeLimitJoniSeconds)
        }

        val reports = ArrayList<LanguageReport>(languages.size)
        for (info in languages) {
            val report = runSingleLanguage(
                info = info,
                timeLimit = timeLimit,
                options = options,
                ensureGrammarLoaded = ensureGrammarLoaded,
                sampleProvider = sampleProvider
            )
            reports.add(report)
        }

        val okCount = reports.count { it.ok }
        val failCount = reports.size - okCount
        val suspectedCount = reports.count { it.suspectedNoHighlight }
        val stoppedEarlyCount = reports.count { it.stoppedEarly }

        return buildString {
            appendLine("VS Code 语法自测报告")
            appendLine("总数：${reports.size}，成功：$okCount，失败：$failCount，疑似无高亮：$suspectedCount，超时停止：$stoppedEarlyCount")
            appendLine()
            for (r in reports) {
                appendLine(r.text.trimEnd())
                appendLine()
            }
        }.trimEnd()
    }

    private suspend fun runSingleLanguage(
        info: VSCodeIntegration.VSCodeLanguageInfo,
        timeLimit: Duration,
        options: Options,
        ensureGrammarLoaded: suspend (VSCodeIntegration.VSCodeLanguageInfo) -> Result<IGrammar>,
        sampleProvider: (VSCodeIntegration.VSCodeLanguageInfo) -> List<String>
    ): LanguageReport {
        val baseHeader = buildString {
            append("[").append(info.displayName)
            if (info.displayName != info.id) append(" / ").append(info.id)
            append("] ")
            append(info.scopeName)
            val ext = info.extensionName?.takeIf { it.isNotEmpty() }
            if (ext != null) append(" — ").append(ext)
        }

        val grammarResult = runCatching { ensureGrammarLoaded(info) }.getOrElse { Result.failure(it) }
        val grammar = grammarResult.getOrNull()
        if (grammar == null) {
            val err = grammarResult.exceptionOrNull()
            val msg = err?.let { "${it::class.java.name}: ${it.message.orEmpty()}" } ?: "unknown"
            return LanguageReport(
                ok = false,
                suspectedNoHighlight = false,
                stoppedEarly = false,
                text = buildString {
                    appendLine(baseHeader)
                    appendLine("状态：失败")
                    appendLine("错误：$msg")
                    appendLine("grammar：${info.grammarPath ?: "无"}")
                    appendLine("languageConfiguration：${info.languageConfigurationPath ?: "无"}")
                }
            )
        }

        val samples = sampleProvider(info).take(options.perLanguageSamplesLimit).toMutableList()
        if (isHtml(info)) {
            samples.add(
                """
                <style>
                body { color: red; }
                </style>
                <script>
                const x = 1;
                </script>
                """.trimIndent()
            )
        }

        var anyMultiToken = false
        var anyStoppedEarly = false
        var anyNonRootScope = false

        val details = buildString {
            appendLine(baseHeader)
            appendLine("状态：成功")
            appendLine("grammar：${info.grammarPath ?: "无"}")
            appendLine("languageConfiguration：${info.languageConfigurationPath ?: "无"}")
            if (info.embeddedLanguages.isNotEmpty()) {
                appendLine("embeddedLanguages：${info.embeddedLanguages.size}")
            }

            for ((idx, sample) in samples.withIndex()) {
                val snippet = sample.replace("\r\n", "\n")
                val lines = snippet.split('\n')
                var state: IStateStack? = null
                val preview = buildString {
                    var previewTokens = 0
                    for (line in lines) {
                        val r = grammar.tokenizeLine(line, state, timeLimit)
                        state = r.ruleStack
                        if (r.isStoppedEarly) anyStoppedEarly = true

                        val tokens = r.tokens
                        if (tokens.size > 1) anyMultiToken = true
                        if (tokens.any { t -> t.scopes.any { s -> s != info.scopeName && s != "unknown" } }) {
                            anyNonRootScope = true
                        }

                        for (t in tokens) {
                            if (previewTokens >= options.perSampleTokenPreviewLimit) break
                            val start = t.startIndex
                            val end = t.endIndex
                            val text = safeSlice(line, start, end)
                            val scopeTail = t.scopes.takeLast(min(3, t.scopes.size)).joinToString(" ")
                            append(previewTokens.toString().padStart(2, '0'))
                            append(": ")
                            append(start).append("..").append(end)
                            append("  ").append(text)
                            append("  ").append(scopeTail)
                            appendLine()
                            previewTokens++
                        }
                        if (previewTokens >= options.perSampleTokenPreviewLimit) break
                    }
                }

                appendLine("样例 #${idx + 1}:")
                appendLine(snippet.trimEnd())
                appendLine("tokens:")
                appendLine(preview.trimEnd().ifEmpty { "（无 token 输出）" })
            }
        }

        val suspectedNoHighlight = !anyMultiToken || !anyNonRootScope
        return LanguageReport(
            ok = true,
            suspectedNoHighlight = suspectedNoHighlight,
            stoppedEarly = anyStoppedEarly,
            text = details
        )
    }

    private fun isHtml(info: VSCodeIntegration.VSCodeLanguageInfo): Boolean {
        if (info.id.equals("html", ignoreCase = true)) return true
        if (info.scopeName.startsWith("text.html", ignoreCase = true)) return true
        return false
    }

    private fun safeSlice(s: String, start: Int, end: Int): String {
        val a = start.coerceIn(0, s.length)
        val b = end.coerceIn(a, s.length)
        val raw = s.substring(a, b)
        return raw.replace("\t", "\\t").replace("\n", "\\n")
    }
}

