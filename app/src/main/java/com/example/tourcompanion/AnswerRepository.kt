package com.example.tourcompanion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One place that decides how a question is answered:
 *   1. friendly small talk (hello, thanks, who are you ...)
 *   2. built-in travel tips (works offline)
 *   3. your backend / Claude, if AppConfig.AI_PROXY_URL is set
 *   4. a friendly "no answer" message
 */
class AnswerRepository(context: Context) {

    enum class Source { LOCAL, AI, NONE }

    data class Answer(val text: String, val source: Source)

    private val appContext = context.applicationContext
    private val local = AppServices.queryProcessor(appContext)
    private val remote = AiAnswerClient(AppConfig.AI_PROXY_URL)

    suspend fun answer(question: String): Answer {

        SmallTalk.reply(question)?.let { return Answer(it, Source.LOCAL) }

        val tip = withContext(Dispatchers.Default) {
            local.processQuery(question)
        }
        if (tip != null) return Answer(tip.answer, Source.LOCAL)

        val aiText = remote.ask(question)
        if (aiText != null) return Answer(aiText, Source.AI)

        return Answer(appContext.getString(R.string.no_answer), Source.NONE)
    }
}
