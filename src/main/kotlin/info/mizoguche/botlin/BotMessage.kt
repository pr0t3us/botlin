package info.mizoguche.botlin

import info.mizoguche.botlin.engine.BotEngineId

interface BotMessage {
    val engineId: BotEngineId
    val channelId: String

    /**
     * Received message omitted mentionPrefix
     */
    val message: String
    val rawMessage: String
    val sender: BotMessageSender
    val session: BotMessageSession
    val isMention: Boolean
        get() = message != rawMessage

    fun reply(body: String)
}

interface BotMessageSender {
    val senderId: String
    val senderUserName: String
    val senderDisplayName: String
}

interface BotMessageSession {
    val mentionChar: String
    val userName: String
    val mentionPrefix: String
        get() = "$mentionChar$userName"
}

data class BotMessageRequest(val engineId: BotEngineId, val channelId: String, val message: String)
