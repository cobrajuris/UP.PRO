package com.uppro.nero.assistant

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** O que o Nero entendeu de uma frase. */
sealed interface Command {
    data class AddReminder(val title: String, val at: LocalDateTime) : Command
    /** Entendeu que é lembrete, mas sem dia nem hora. */
    data class ReminderNeedsTime(val title: String) : Command
    data class AddNote(val text: String) : Command
    data class StartTimer(val durationMs: Long, val label: String) : Command
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : Command
    data class Unknown(val text: String) : Command
}

/**
 * Interpretador offline de frases em português, sem IA.
 * Ex.: "lembre que amanhã às 15h tenho dentista", "anota comprar pão",
 * "timer de 10 minutos", "me acorda às 6 e meia".
 */
object CommandParser {

    private const val NUM =
        "(\\d{1,3}|um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|catorze|quatorze|quinze|" +
            "dezesseis|dezessete|dezoito|dezenove|vinte|trinta|quarenta|cinquenta|sessenta|noventa|cem)"

    private val WORDS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13,
        "catorze" to 14, "quatorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18,
        "dezenove" to 19, "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
        "noventa" to 90, "cem" to 100,
    )

    private val MONTHS = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )

    private val WEEKDAYS = mapOf(
        "segunda" to DayOfWeek.MONDAY, "terca" to DayOfWeek.TUESDAY, "quarta" to DayOfWeek.WEDNESDAY,
        "quinta" to DayOfWeek.THURSDAY, "sexta" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY,
    )

    private val TIMER_WORDS = Regex("\\b(timer|temporizador|cronometro|contagem regressiva)\\b")
    private val ALARM_WORDS = Regex("\\b(alarme|despertador|me acord[ae]|acorda[r]?(?:-| )me|me desperta|desperta[r]?(?:-| )me)\\b")
    private val NOTE_START = Regex("^(?:nero[,.]?\\s+)?(anota[r]?|anote|nota|escreve[r]?|escreva|salva[r]?|salve|guarda[r]?|guarde|adiciona[r]?|adicione)\\b")
    private val NOTE_ANY = Regex("\\b(nas? notas?|no bloco de notas|bloco de notas)\\b")
    private val REMINDER_WORDS = Regex(
        "\\b(lembr\\w*|me avis\\w*|avis[ae]\\w*|agend\\w*|marc[ae]\\w*|compromisso|reuniao|consulta|tenho|preciso)\\b",
    )

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Command {
        val text = input.trim().trimEnd('.', '!', '?')
        if (text.isBlank()) return Command.Unknown(input)
        val p = Phrase(text)
        val n = p.norm

        // ---------- Timer ----------
        if (TIMER_WORDS.containsMatchIn(n) && !REMINDER_WORDS.containsMatchIn(n.replace(Regex("\\bmarc\\w*"), ""))) {
            val ms = p.takeDuration(defaultUnitMinutes = true)
            p.remove(TIMER_WORDS)
            p.remove(Regex("^(?:nero[,.]?\\s+)?(?:poe|ponha|coloca|coloque|inicia|inicie|comeca|comece|liga|ligue|faz|faca|cria|crie|marca|marque|um|uma)\\b"))
            if (ms <= 0) return Command.Unknown(text)
            return Command.StartTimer(ms, p.cleanTitle(leading = listOf("de", "para", "pra", "do", "da", "o", "a", "por")))
        }

        // ---------- Alarme ----------
        if (ALARM_WORDS.containsMatchIn(n)) {
            val time = p.takeTime() ?: return Command.Unknown(text)
            p.takeDate(now)
            p.remove(ALARM_WORDS)
            p.remove(Regex("\\b(poe|ponha|coloca|coloque|cria|crie|um|uma|liga|ligue)\\b"))
            return Command.SetAlarm(time.hour, time.minute, p.cleanTitle(leading = listOf("para", "pra", "de")))
        }

        // ---------- Nota ----------
        val noteStart = NOTE_START.find(n)
        if (noteStart != null || NOTE_ANY.containsMatchIn(n)) {
            if (noteStart != null) p.removeRange(noteStart.range)
            p.remove(NOTE_ANY)
            val body = p.cleanTitle(leading = listOf("ai", "ae", "que", "isso", "pra mim", "para mim", "o seguinte"))
            return if (body.isBlank()) Command.Unknown(text) else Command.AddNote(body)
        }

        // ---------- Lembrete ----------
        val relative = p.takeRelative(now)
        val date = if (relative == null) p.takeDate(now) else null
        val period = p.peekPeriod()
        val time = if (relative == null) p.takeTime() else null
        p.takePeriod()
        val isReminder = REMINDER_WORDS.containsMatchIn(n) || relative != null || date != null || time != null
        if (!isReminder) return Command.Unknown(text)

        p.remove(Regex("^(?:nero[,.]?\\s+)?"))
        p.remove(
            Regex(
                "\\b(?:me\\s+)?(?:lembre-me|lembra-me|lembrar|lembre|lembra|lembrete|me avise|me avisa|avise-me|avisa-me|avise|avisa|agende|agenda|agendar|marque|marca|marcar)(?:\\s+(?:me|eu))?\\b",
            ),
        )
        val title = p.cleanTitle(
            leading = listOf(
                "que", "de", "do", "da", "para", "pra", "eu", "tenho que", "tenho", "preciso", "vou", "um", "uma",
                "o", "a", "sobre", "lembrete",
            ),
        ).ifBlank { "Lembrete" }

        val at: LocalDateTime = when {
            relative != null -> relative
            date != null || time != null || period != null -> {
                val t = time ?: when (period) {
                    "manha" -> LocalTime.of(9, 0)
                    "tarde" -> LocalTime.of(15, 0)
                    "noite" -> LocalTime.of(20, 0)
                    "madrugada" -> LocalTime.of(6, 0)
                    else -> LocalTime.of(9, 0)
                }
                var d = date ?: now.toLocalDate()
                var dt = LocalDateTime.of(d, t)
                if (date == null && !dt.isAfter(now)) {
                    d = d.plusDays(1)
                    dt = LocalDateTime.of(d, t)
                }
                dt
            }
            else -> return Command.ReminderNeedsTime(title)
        }
        return Command.AddReminder(title, at)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Frase com máscara de remoção: as buscas são feitas na versão sem acento e minúscula
     * ([norm]), mantendo o mesmo índice do texto original para recortar o título.
     */
    private class Phrase(val original: String) {
        val norm: String
        private val removed: BooleanArray
        private val source: String

        init {
            val lower = original.lowercase()
            val sb = StringBuilder()
            for (c in lower) {
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                sb.append(base)
            }
            norm = sb.toString()
            source = if (lower.length == original.length) original else lower
            removed = BooleanArray(norm.length)
        }

        /** Texto ainda não removido (normalizado), com o mapa de índices. */
        private fun view(): Pair<String, IntArray> {
            val sb = StringBuilder()
            val idx = ArrayList<Int>()
            for (i in norm.indices) if (!removed[i]) {
                sb.append(norm[i]); idx.add(i)
            }
            return sb.toString() to idx.toIntArray()
        }

        fun removeRange(r: IntRange) {
            for (i in r) if (i in removed.indices) removed[i] = true
        }

        /** Procura no texto restante e remove o primeiro trecho encontrado. */
        fun find(regex: Regex): MatchResult? {
            val (v, idx) = view()
            val m = regex.find(v) ?: return null
            if (m.range.isEmpty()) return m
            for (i in m.range) removed[idx[i]] = true
            return m
        }

        fun peek(regex: Regex): MatchResult? = regex.find(view().first)

        fun remove(regex: Regex) {
            find(regex)
        }

        fun takeDuration(defaultUnitMinutes: Boolean): Long {
            var total = 0L
            while (true) {
                if (find(Regex("\\bmeia hora\\b")) != null) {
                    total += 30 * 60_000L; continue
                }
                val m = find(Regex("\\b$NUM(?:\\s+e\\s+$NUM)?(\\s+e\\s+meia)?\\s*(horas?|h|minutos?|mins?|segundos?|seg|s)\\b(\\s+e\\s+meia)?")) ?: break
                val value = number(m.groupValues[1], m.groupValues[2])
                val half = m.groupValues[3].isNotBlank() || m.groupValues[5].isNotBlank()
                val unit = m.groupValues[4]
                total += when {
                    unit.startsWith("h") -> value * 3_600_000L + if (half) 1_800_000L else 0L
                    unit.startsWith("min") -> value * 60_000L + if (half) 30_000L else 0L
                    else -> value * 1000L
                }
            }
            if (total == 0L && defaultUnitMinutes) {
                val m = find(Regex("\\b$NUM\\b"))
                if (m != null) total = number(m.groupValues[1], "") * 60_000L
            }
            return total
        }

        /** "daqui a 20 minutos", "em 2 horas", "daqui a 3 dias". */
        fun takeRelative(now: LocalDateTime): LocalDateTime? {
            val m = find(Regex("\\b(?:daqui\\s+a|daqui|em|dentro\\s+de)\\s+(?:$NUM(?:\\s+e\\s+$NUM)?|(meia))\\s*(minutos?|mins?|horas?|h|dias?)\\b"))
                ?: return null
            val unit = m.groupValues[4]
            val value = if (m.groupValues[3] == "meia") 0L else number(m.groupValues[1], m.groupValues[2])
            return when {
                unit.startsWith("min") -> now.plusMinutes(value)
                unit.startsWith("h") -> if (m.groupValues[3] == "meia") now.plusMinutes(30) else now.plusHours(value)
                else -> {
                    val day = now.plusDays(value)
                    val t = takeTime() ?: LocalTime.of(9, 0)
                    LocalDateTime.of(day.toLocalDate(), t)
                }
            }
        }

        fun takeDate(now: LocalDateTime): LocalDate? {
            val today = now.toLocalDate()
            find(Regex("\\bdepois\\s+de\\s+amanha\\b"))?.let { return today.plusDays(2) }
            find(Regex("\\bamanha\\b"))?.let { return today.plusDays(1) }
            find(Regex("\\bhoje\\b"))?.let { return today }
            find(Regex("\\b(?:(?:na|no|nesta|neste|nessa|nesse|proxima|proximo|esta|este)\\s+)?(segunda|terca|quarta|quinta|sexta|sabado|domingo)(?:[- ]feira)?(?:\\s+que\\s+vem)?\\b"))?.let { m ->
                val dow = WEEKDAYS.getValue(m.groupValues[1])
                return if (today.dayOfWeek == dow) today else today.with(TemporalAdjusters.next(dow))
            }
            find(Regex("\\b(?:(?:na\\s+)?semana\\s+que\\s+vem|proxima\\s+semana)\\b"))?.let { return today.plusDays(7) }
            find(Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b"))?.let { m ->
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt()
                var y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
                if (d !in 1..31 || mo !in 1..12) return null
                var date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return null
                if (m.groupValues[3].isEmpty() && date.isBefore(today)) {
                    y += 1
                    date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return null
                }
                return date
            }
            val monthAlt = MONTHS.joinToString("|")
            find(Regex("\\b(?:no\\s+)?dia\\s+$NUM(?:\\s+de\\s+($monthAlt))?\\b|\\b(\\d{1,2})\\s+de\\s+($monthAlt)\\b"))?.let { m ->
                val dayStr = m.groupValues[1].ifEmpty { m.groupValues[3] }
                val monthStr = m.groupValues[2].ifEmpty { m.groupValues[4] }
                val d = number(dayStr, "").toInt()
                if (d !in 1..31) return null
                return if (monthStr.isNotEmpty()) {
                    val mo = MONTHS.indexOf(monthStr) + 1
                    var date = runCatching { LocalDate.of(today.year, mo, d) }.getOrNull() ?: return null
                    if (date.isBefore(today)) date = date.plusYears(1)
                    date
                } else {
                    var date = runCatching { today.withDayOfMonth(d) }.getOrNull()
                    if (date == null || date.isBefore(today)) {
                        date = runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
                    }
                    date
                }
            }
            return null
        }

        fun peekPeriod(): String? =
            peek(Regex("\\b(?:da|de|na|a|pela)\\s+(manha|tarde|noite|madrugada)\\b"))?.groupValues?.get(1)

        fun takePeriod(): String? =
            find(Regex("\\b(?:da|de|na|a|pela)\\s+(manha|tarde|noite|madrugada)\\b"))?.groupValues?.get(1)

        /** "15h", "15h30", "15:30", "às 3 da tarde", "às três e meia", "meio-dia". */
        fun takeTime(): LocalTime? {
            find(Regex("\\b(?:(?:as|ao|a|pelo|pelas)\\s+)?meio[- ]dia(?:\\s+e\\s+meia)?\\b"))?.let { m ->
                return LocalTime.of(12, if (m.value.endsWith("meia")) 30 else 0)
            }
            find(Regex("\\b(?:(?:as|a|pela)\\s+)?meia[- ]noite\\b"))?.let { return LocalTime.of(0, 0) }

            val period = peekPeriod()
            val suffix = "(?:\\s+e\\s+(meia|$NUM)(?:\\s+minutos?)?)?"
            var hour: Int
            var minute: Int
            val digital = find(Regex("\\b(?:(?:as|a|pelas|ate\\s+as)\\s+)?(\\d{1,2})\\s*(?::|h)\\s*(\\d{2})\\b"))
            if (digital != null) {
                hour = digital.groupValues[1].toInt()
                minute = digital.groupValues[2].toInt()
            } else {
                val m = find(Regex("\\b(?:(?:as|a|pelas|ate\\s+as)\\s+)?(\\d{1,2})\\s*(?:h|horas?)\\b$suffix"))
                    ?: find(Regex("\\b(?:as|pelas|ate\\s+as)\\s+$NUM\\b$suffix"))
                    ?: return null
                hour = number(m.groupValues[1], "").toInt()
                minute = when (val extra = m.groupValues[2]) {
                    "" -> 0
                    "meia" -> 30
                    else -> number(extra, "").toInt()
                }
            }
            if (hour > 23 || minute > 59) return null
            when (period) {
                "tarde", "noite" -> if (hour in 1..11) hour += 12
                "manha", "madrugada" -> if (hour == 12) hour = 0
            }
            return LocalTime.of(hour, minute)
        }

        fun number(a: String, b: String): Long {
            val first = a.toLongOrNull() ?: WORDS[a]?.toLong() ?: 0L
            val second = if (b.isEmpty()) 0L else b.toLongOrNull() ?: WORDS[b]?.toLong() ?: 0L
            return first + second
        }

        /** Título final com a capitalização original, sem conectores soltos nas pontas. */
        fun cleanTitle(leading: List<String>): String {
            // Remove conectores do começo repetidamente ("que tenho" -> "").
            val leadRegex = Regex("^\\s*(?:" + leading.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b[\\s,:]*")
            repeat(4) { find(leadRegex) }
            val trail = Regex("[\\s,]+(?:as|a|ao|no|na|de|do|da|e|para|pra|em|que|pelas)\\s*$")
            repeat(3) { find(trail) }
            val sb = StringBuilder()
            for (i in source.indices) if (!removed[i]) sb.append(source[i])
            val cleaned = sb.toString()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\s+([,.!?])"), "$1")
                .trim(' ', ',', '.', ':', '-', ';')
            return cleaned.replaceFirstChar { it.uppercase() }
        }
    }
}
