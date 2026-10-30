package com.example.tourcompanion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnswerRepository(context: Context) {

    enum class Source {
        LOCAL, BACKEND, NONE
    }

    data class Answer(
        val text: String,
        val source: Source
    )

    private val appContext = context.applicationContext

    private val backend = AiAnswerClient(AppConfig.AI_PROXY_URL)

    suspend fun answer(question: String): Answer {

        SmallTalk.reply(question)?.let {
            return Answer(it, Source.LOCAL)
        }

        val backendAnswer = backend.ask(question)

        if (backendAnswer != null) {
            return Answer(
                backendAnswer,
                Source.BACKEND
            )
        }

        val local = AppServices.queryProcessor(appContext)

        val tip = withContext(Dispatchers.Default) {
            local.processQuery(question)
        }

        if (tip != null) {
            return Answer(
                tip.answer,
                Source.LOCAL
            )
        }

        return Answer(
            appContext.getString(R.string.no_answer),
            Source.NONE
        )
    }
}