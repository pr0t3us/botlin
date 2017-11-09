package info.mizoguche.botlin.engine

import info.mizoguche.botlin.BotMessage
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch

typealias PipelineInterceptor<TContext> = suspend (TContext) -> Unit
typealias MessageInterceptor = PipelineInterceptor<BotMessage>

class MessagePipelineContext(val interceptors: List<MessageInterceptor>, val message: BotMessage) {
    suspend fun proceed() {
        interceptors.forEach { it.invoke(message) }
    }
}

class SlackEngine : BotEngine {
    private val messageInterceptors = mutableListOf<MessageInterceptor>()
    val interceptors: List<MessageInterceptor>
        get() = messageInterceptors

    override fun intercept(interceptor: MessageInterceptor) {
        messageInterceptors.add(interceptor)
    }

    override inline fun execute(message: BotMessage): Job {
        val context = MessagePipelineContext(interceptors, message)
        return launch { context.proceed() }
    }
}