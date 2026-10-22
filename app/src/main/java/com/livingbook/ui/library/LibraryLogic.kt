package com.livingbook.ui.library

import androidx.compose.ui.graphics.Color
import com.livingbook.data.db.LibraryRow
import kotlin.math.roundToInt

/** Generated cover color: hue from the title hash, dark enough for paper-colored initials to pass WCAG AA. */
fun coverColor(title: String): Color = Color.hsl(Math.floorMod(title.hashCode(), 360).toFloat(), 0.35f, 0.30f)

/** Up to two initials, skipping a leading article ("The Art of War" -> "AO"). */
fun coverInitials(title: String): String {
    val words = title.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }
    val meaningful = if (words.size > 1 && words[0].lowercase() in ARTICLES) words.drop(1) else words
    return meaningful.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

private val ARTICLES = setOf("the", "a", "an")

/** Reading progress 0..1 from the saved block position. */
fun progressFraction(blockIndex: Int?, blockCount: Int): Float =
    if (blockIndex == null || blockCount <= 0) 0f else (blockIndex.toFloat() / blockCount).coerceIn(0f, 1f)

fun progressLabel(row: LibraryRow): String =
    if (row.blockIndex == null) "Not started" else "${(progressFraction(row.blockIndex, row.blockCount) * 100).roundToInt()}%"

/** The book behind "Continue reading": the most recently opened one. Never-opened books do not qualify. */
fun continueBook(rows: List<LibraryRow>): LibraryRow? = rows.filter { it.lastOpenedAt != null }.maxByOrNull { it.lastOpenedAt!! }

fun LibraryRow.warningList(): List<String> = warnings.lines().filter { it.isNotBlank() }
