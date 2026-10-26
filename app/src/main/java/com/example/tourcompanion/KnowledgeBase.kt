package com.example.tourcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TipEntry(val topic: String, val keywords: String, val answers: List<String>)
data class TipCategory(val id: String, val icon: String, val title: String, val tips: List<TipEntry>)
data class Phrase(val english: String, val native: String, val roman: String)
data class PhraseSet(val title: String, val localeTag: String, val phrases: List<Phrase>)
data class PackingGroup(val title: String, val items: List<String>)

/**
 * Everything Semmozhi "knows" lives in three JSON files in res/raw:
 *   tips.json     - travel tips grouped in categories (also what the chat answers from)
 *   phrases.json  - Tamil and Hindi phrase books
 *   packing.json  - packing checklist
 * Add or edit entries there to teach her more.
 */
object KnowledgeBase {

    @Volatile
    private var cache: List<TipCategory>? = null

    fun categories(context: Context): List<TipCategory> =
        cache ?: synchronized(this) {
            cache ?: parseCategories(readRaw(context, R.raw.tips)).also { cache = it }
        }

    fun category(context: Context, id: String?): TipCategory? =
        categories(context).firstOrNull { it.id == id }

    fun phrases(context: Context, language: String): PhraseSet? {
        val node = JSONObject(readRaw(context, R.raw.phrases)).optJSONObject(language) ?: return null
        val list = node.getJSONArray("phrases")
        return PhraseSet(
            title = node.getString("title"),
            localeTag = node.getString("tag"),
            phrases = List(list.length()) { i ->
                val p = list.getJSONObject(i)
                Phrase(p.getString("en"), p.getString("native"), p.getString("roman"))
            }
        )
    }

    fun packing(context: Context): List<PackingGroup> {
        val groups = JSONObject(readRaw(context, R.raw.packing)).getJSONArray("groups")
        return List(groups.length()) { i ->
            val g = groups.getJSONObject(i)
            PackingGroup(g.getString("title"), g.getJSONArray("items").strings())
        }
    }

    private fun parseCategories(json: String): List<TipCategory> {
        val cats = JSONObject(json).getJSONArray("categories")
        return List(cats.length()) { i ->
            val c = cats.getJSONObject(i)
            val tips = c.getJSONArray("tips")
            TipCategory(
                id = c.getString("id"),
                icon = c.getString("icon"),
                title = c.getString("title"),
                tips = List(tips.length()) { j ->
                    val t = tips.getJSONObject(j)
                    TipEntry(t.getString("topic"), t.getString("keywords"), t.getJSONArray("answers").strings())
                }
            )
        }
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private fun readRaw(context: Context, resId: Int): String =
        context.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
