package com.an.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AN SDK mock client v0.1 — имитирует работу без реальной модели.
 */
class ANClient(
    private val apiKey: String = "",
    private val baseUrl: String = "https://api.example.com",
) {

    suspend fun send(prompt: String): String {
        delay(400)
        return mockReply(prompt)
    }

    fun stream(prompt: String): Flow<String> = flow {
        val reply = mockReply(prompt)
        for (word in reply.split(" ")) {
            delay(120)
            emit("$word ")
        }
    }

    private fun mockReply(prompt: String): String {
        if (prompt.isBlank()) return "Пустой запрос."
        return "Это мок-ответ AN SDK на: \"$prompt\". " +
            "Реальная модель будет подключена в следующих версиях."
    }
}
