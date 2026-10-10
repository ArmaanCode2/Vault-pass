package com.example.domain.exportimport

/**
 * The VaultPass plain-text (.txt) export format. VaultPass Desktop reads and writes the same format
 * with the same rules (its PlainTextFormat.kt): keep the two in step.
 *
 * Per entry the writer emits "Title: <title>", a blank line, then label blocks, each "<Label>:",
 * the value and a blank line: Username and Password always, then when set Favorite ("Yes"),
 * Website, Notes (may span lines), Category, Tags (joined with ", ") and "Custom.<key>" for each
 * custom field. The entry ends with "---" and a blank line.
 *
 * [parse] reads that layout by position (F9, D12): an empty value stays empty instead of taking
 * the next label, "---" inside a value doesn't split the entry, spaces are kept, and notes keep
 * their blank lines. Files written by older versions read the same way; their custom fields had no
 * "Custom." prefix, and any "<key>:" line after the known fields is read as one (directly after
 * Notes it stays part of the notes). Hand edits are allowed for: spaces around a label or "---"
 * and on a blank line are ignored (the writer never puts any there). [inexactEntries] counts
 * entries the format can't hold exactly, so the export can warn before writing them.
 */
object TxtVaultFormat {

    data class Record(
        val title: String,
        val username: String = "",
        val password: String = "",
        val favorite: Boolean = false,
        val website: String = "",
        val notes: String = "",
        /** Null when the file has no Category line. */
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val customFields: List<Pair<String, String>> = emptyList()
    )

    data class Parsed(val records: List<Record>, val invalidCount: Int)

    private const val TITLE = "Title:"
    private const val SEPARATOR = "---"
    private const val CUSTOM_PREFIX = "Custom."
    private val SINGLE_LINE_LABELS = setOf("Username:", "Password:", "Favorite:", "Website:", "Category:", "Tags:")

    /** True if [text] looks like this format (a UTF-8 BOM before it is allowed). */
    fun looksLikeTxt(text: String): Boolean = text.removePrefix("\uFEFF").trimStart().startsWith(TITLE)

    fun parse(text: String): Parsed {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").split("\n")
        val records = mutableListOf<Record>()
        var invalid = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                isBlankLine(line) || isSeparator(line) -> i++
                !line.startsWith(TITLE) -> {
                    // Text outside an entry: skipped up to the next entry, counted once.
                    while (i < lines.size && !lines[i].startsWith(TITLE)) i++
                    invalid++
                }
                else -> {
                    var end = i + 1
                    while (end < lines.size && !isEntryEnd(lines, end)) end++
                    val record = parseEntry(lines.subList(i, end))
                    if (record != null) records += record else invalid++
                    i = end + 1
                }
            }
        }
        return Parsed(records, invalid)
    }

    /** "---", then only blank lines up to the next "Title:" line or the end of the file. */
    private fun isEntryEnd(lines: List<String>, k: Int): Boolean {
        if (!isSeparator(lines[k])) return false
        var m = k + 1
        while (m < lines.size && isBlankLine(lines[m])) m++
        return m >= lines.size || lines[m].startsWith(TITLE)
    }

    private fun isBlankLine(line: String): Boolean = line.isBlank()

    private fun isSeparator(line: String): Boolean = line.trim() == SEPARATOR

    private fun parseEntry(entry: List<String>): Record? {
        val title = entry[0].removePrefix(TITLE).let { if (it.startsWith(" ")) it.substring(1) else it }
        if (title.isBlank()) return null

        var username = ""
        var password = ""
        var favorite = false
        var website = ""
        var notes = ""
        var category: String? = null
        var tags = emptyList<String>()
        val customFields = mutableListOf<Pair<String, String>>()
        val strayLines = mutableListOf<String>()

        val body = entry.subList(1, entry.size)
        var p = 0
        while (p < body.size) {
            val line = body[p]
            val label = line.trim()
            when {
                isBlankLine(line) -> p++
                label in SINGLE_LINE_LABELS -> {
                    val value = body.getOrElse(p + 1) { "" }
                    when (label) {
                        "Username:" -> username = value
                        "Password:" -> password = value
                        "Favorite:" -> favorite = value.trim().equals("Yes", ignoreCase = true)
                        "Website:" -> website = value
                        "Category:" -> category = value
                        "Tags:" -> tags = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    p += 2
                }
                label == "Notes:" -> {
                    val end = notesEnd(body, p + 1)
                    notes = body.subList(p + 1, end).joinToString("\n")
                    p = end
                }
                label.length > 1 && label.endsWith(":") -> {
                    customFields += label.dropLast(1).removePrefix(CUSTOM_PREFIX) to body.getOrElse(p + 1) { "" }
                    p += 2
                }
                else -> {
                    // Not something this writer emits (a hand edit): kept in the notes, never dropped.
                    strayLines += line
                    p++
                }
            }
        }
        if (strayLines.isNotEmpty()) {
            notes = if (notes.isEmpty()) strayLines.joinToString("\n") else notes + "\n" + strayLines.joinToString("\n")
        }
        return Record(title, username, password, favorite, website, notes, category, tags, customFields)
    }

    /** Notes run to the blank line before the next label the writer puts after them, or to the end. */
    private fun notesEnd(body: List<String>, start: Int): Int {
        var m = start
        while (m < body.size) {
            if (isBlankLine(body[m]) && (m + 1 >= body.size || isLabelAfterNotes(body[m + 1]))) return m
            m++
        }
        return body.size
    }

    private fun isLabelAfterNotes(line: String): Boolean {
        val label = line.trim()
        return label == "Category:" || label == "Tags:" || (label.startsWith(CUSTOM_PREFIX) && label.endsWith(":"))
    }

    /** How many of [records] would not come back exactly from a TXT export. */
    fun inexactEntries(records: List<Record>): Int = records.count { !isExact(it) }

    private fun isExact(r: Record): Boolean {
        val singleLine = listOfNotNull(r.title, r.username, r.password, r.website, r.category) +
            r.tags + r.customFields.flatMap { listOf(it.first, it.second) }
        if (r.title.isBlank()) return false
        if (singleLine.any { it.contains('\n') || it.contains('\r') }) return false
        if (r.tags.any { it.isEmpty() || it.contains(',') || it != it.trim() }) return false
        // A blank category reads back as the default one.
        if (r.category != null && r.category.isBlank()) return false
        if (r.notes.contains('\r')) return false
        val noteLines = r.notes.split("\n")
        for (idx in noteLines.indices) {
            val line = noteLines[idx]
            // A blank line and then a label would end the notes early.
            if (isBlankLine(line) && idx + 1 < noteLines.size && isLabelAfterNotes(noteLines[idx + 1])) return false
            // "---" and then (after blank lines) a Title line would end the entry.
            if (isSeparator(line)) {
                var m = idx + 1
                while (m < noteLines.size && isBlankLine(noteLines[m])) m++
                if (m < noteLines.size && noteLines[m].startsWith(TITLE)) return false
            }
        }
        return true
    }
}
