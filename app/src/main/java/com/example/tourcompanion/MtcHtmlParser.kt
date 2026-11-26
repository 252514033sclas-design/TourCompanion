package com.example.tourcompanion

import java.util.regex.Pattern

/**
 * Lightweight, safe parser for HTML fragments returned by the official MTC website (mtcbus.tn.gov.in).
 * Extracts origin and destination option values, scheduled departure times (HH:MM), and CSRF tokens.
 *
 * Designed to never crash on unexpected or malformed HTML.
 */
object MtcHtmlParser {

    private val OPTION_PATTERN = Pattern.compile(
        "<option[^>]*value=[\"']([^\"']*)[\"'][^>]*>(.*?)</option>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val TIME_STAGE_PATTERN = Pattern.compile(
        "(?:class=[\"']stage[\"'][^>]*>|fa-clock-o[^>]*>)\\s*<span>?\\s*(?:<[^>]+>\\s*)*([01]?[0-9]|2[0-3]):([0-5][0-9])",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERAL_TIME_PATTERN = Pattern.compile(
        "\\b([01]?[0-9]|2[0-3]):([0-5][0-9])\\b"
    )

    private val DEST_HEADING_PATTERN = Pattern.compile(
        "<h6>(.*?)</h6>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val CSRF_PATTERN = Pattern.compile(
        "(?:\\^|name=[\"']csrf_test_name[\"']\\s+value=[\"'])([a-f0-9]{32})",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Parses origin options from the HTML returned by /Home/getoriginbyroute.
     */
    fun parseOrigins(html: String?): List<String> {
        return parseOptions(html)
    }

    /**
     * Parses destination options from the HTML returned by /Home/getdestinationrouteorigin.
     */
    fun parseDestinations(html: String?): List<String> {
        return parseOptions(html)
    }

    /**
     * Parses scheduled departure times (HH:MM) from the HTML returned by /Home/ajaxbustimingsearch
     * or /Home/getdestinationrouteorigin.
     */
    fun parseTimings(html: String?): List<String> {
        if (html.isNullOrBlank()) return emptyList()

        val results = LinkedHashSet<String>()

        // 1. First try stage-specific clock icon patterns
        val stageMatcher = TIME_STAGE_PATTERN.matcher(html)
        while (stageMatcher.find()) {
            val hh = stageMatcher.group(1)?.padStart(2, '0')
            val mm = stageMatcher.group(2)?.padStart(2, '0')
            if (hh != null && mm != null) {
                results.add("$hh:$mm")
            }
        }

        // 2. Fallback to general time pattern if stage pattern found nothing
        if (results.isEmpty()) {
            // Strip caret and csrf token to avoid false match
            val contentOnly = html.split('^').firstOrNull() ?: html
            val genMatcher = GENERAL_TIME_PATTERN.matcher(contentOnly)
            while (genMatcher.find()) {
                val hh = genMatcher.group(1)?.padStart(2, '0')
                val mm = genMatcher.group(2)?.padStart(2, '0')
                if (hh != null && mm != null) {
                    results.add("$hh:$mm")
                }
            }
        }

        return results.toList()
    }

    /**
     * Extracts destination title if present inside <h6> in timing HTML.
     */
    fun extractDestinationHeading(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val matcher = DEST_HEADING_PATTERN.matcher(html)
        if (matcher.find()) {
            val raw = matcher.group(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            if (!raw.isNullOrEmpty()) return raw
        }
        return null
    }

    /**
     * Extracts the trailing CSRF token from the caret delimiter or hidden input.
     */
    fun extractCsrfToken(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val matcher = CSRF_PATTERN.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    private fun parseOptions(html: String?): List<String> {
        if (html.isNullOrBlank()) return emptyList()

        val results = LinkedHashSet<String>()
        val matcher = OPTION_PATTERN.matcher(html)

        while (matcher.find()) {
            val valAttr = matcher.group(1)?.trim() ?: ""
            val innerText = matcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

            // Prefer value attribute if not empty, otherwise inner text
            val candidate = if (valAttr.isNotEmpty()) valAttr else innerText
            val clean = candidate.trim()

            if (clean.isNotEmpty() &&
                !clean.equals("0", ignoreCase = true) &&
                !clean.contains("--origin--", ignoreCase = true) &&
                !clean.contains("--destination--", ignoreCase = true) &&
                !clean.contains("--select--", ignoreCase = true)
            ) {
                results.add(clean)
            }
        }

        return results.toList()
    }
}
