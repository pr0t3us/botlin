package info.mizoguche.botlin.feature.cron

import com.google.gson.Gson
import info.mizoguche.botlin.Botlin
import info.mizoguche.botlin.BotlinFeatureFactory
import info.mizoguche.botlin.BotlinFeatureId
import info.mizoguche.botlin.BotlinMessageRequest
import info.mizoguche.botlin.feature.command.BotlinCommand
import info.mizoguche.botlin.feature.command.CommandFeature
import info.mizoguche.botlin.feature.cron.Cron.Schedule
import info.mizoguche.botlin.feature.redis.BotlinStoreGetRequest
import info.mizoguche.botlin.feature.redis.BotlinStoreSetRequest
import it.sauronsoftware.cron4j.Scheduler
import java.util.Random
import java.util.regex.Pattern

class Cron : CommandFeature() {
    override val command: String
        get() = "cron"
    override val description: String
        get() = "Set schedule"
    override val usage: String
        get() = """
            |ex. Let botlin echo hello at every morning 10:00AM(Required echo command)
            |cron add \"* 10 * * *\" @botlin echo hello
            |   adds a new schedule
            |
            |cron list
            |   show added schedules
            |
            |cron remove <schedule id>
            |   delete the specified schedule
            """.trimMargin()

    private val schedulers: Map<Int, Scheduler> = HashMap()
    private val random: Random = Random()

    private fun createScheduleId(): Int {
        if (schedulers.count() == MAX_SCHEDULES) {
            throw IllegalStateException("schedule count is at capacity")
        }

        var idCandidate = random.nextInt(10000)
        while (schedulers.containsKey(idCandidate)) {
            idCandidate = random.nextInt(10000)
        }

        return idCandidate
    }


    data class Schedule(val id: Int, val channelId: String, val cron: String, val command: String)
    data class Schedules(val schedules: MutableList<Schedule>)
    private interface Subcommand {
        fun execute()
    }

    private class AddCommand(val id: BotlinFeatureId, val command: BotlinCommand, val schedule: Schedule) : Subcommand {
        override fun execute() {
            val storeGetReq = BotlinStoreGetRequest(id) {
                val schedules = gson.fromJson<Schedules>(it, Schedules::class.java) ?: Schedules(mutableListOf())
                schedules.schedules.add(schedule)
                val json = gson.toJson(schedules)
                val setReq = BotlinStoreSetRequest(id, json)
                command.botlin.publish<BotlinStoreSetRequest>(setReq)
                startSchedule(command.botlin, schedule)
                command.msgEvent.reply("Created schedule.")
            }
            command.botlin.publish<BotlinStoreGetRequest>(storeGetReq)
        }
    }

    private class ListCommand(val id: BotlinFeatureId, val command: BotlinCommand) : Subcommand {
        override fun execute() {
            val storeGetReq = BotlinStoreGetRequest(id) {
                val schedules = gson.fromJson<Schedules>(it, Schedules::class.java) ?: Schedules(mutableListOf())
                val list = schedules.schedules.joinToString("\n") {
                    "${it.id.toString().padStart(4, ' ')}: \"${it.cron}\" ${it.command}"
                }
                command.msgEvent.reply("""
                |```
                |$list
                |```""".trimMargin())
            }
            command.botlin.publish<BotlinStoreGetRequest>(storeGetReq)
        }
    }

    private class RemoveCommand(val id: BotlinFeatureId, val command: BotlinCommand, val scheduleId: Int) : Subcommand {
        override fun execute() {
            val storeGetReq = BotlinStoreGetRequest(id) {
                val schedules = gson.fromJson<Schedules>(it, Schedules::class.java) ?: Schedules(mutableListOf())
                schedules.schedules.removeIf { it.id == scheduleId }
                stopSchedule(scheduleId)
                val json = gson.toJson(schedules)
                val setReq = BotlinStoreSetRequest(id, json)
                command.botlin.publish<BotlinStoreSetRequest>(setReq)
                command.msgEvent.reply("Removed schedule.")
            }
            command.botlin.publish<BotlinStoreGetRequest>(storeGetReq)
        }
    }

    private fun parse(command: BotlinCommand): Subcommand {
        if (command.args == "list") {
            return ListCommand(id, command)
        }

        val matcherAdd = ADD_COMMAND_PATTERN.matcher(command.args)
        if (matcherAdd.matches()) {
            val cron = matcherAdd.group(1)
            val com = "${command.msgEvent.session.mentionPrefix} ${matcherAdd.group(2)}"
            val schedule = Schedule(createScheduleId(), command.msgEvent.channelId, cron, com)
            return AddCommand(id, command, schedule)
        }

        val matcherRemove = REMOVE_COMMAND_PATTERN.matcher(command.args)
        if (matcherRemove.matches()) {
            val scheduleId = matcherRemove.group(1).toInt()
            return RemoveCommand(id, command, scheduleId)
        }

        throw IllegalArgumentException("invalid args: ${command.args}")
    }

    override fun onCommandPublishing(command: BotlinCommand) {
        try {
            val subcommand = parse(command)
            subcommand.execute()
        } catch (e: IllegalArgumentException) {
            command.msgEvent.reply("error: invalid args. confirm cron tab.")
        }
    }

    override fun onStart(botlin: Botlin) {
        val storeGetReq = BotlinStoreGetRequest(id) {
            val schedules = gson.fromJson<Schedules>(it, Schedules::class.java) ?: Schedules(mutableListOf())
            schedules.schedules.forEach {
                startSchedule(botlin, it)
            }
        }
        botlin.publish<BotlinStoreGetRequest>(storeGetReq)
    }

    override val id: BotlinFeatureId
        get() = BotlinFeatureId("Cron")


    class Configuration

    companion object Factory : BotlinFeatureFactory<Configuration, Cron> {
        private val ADD_COMMAND_PATTERN = Pattern.compile("add \"(.+? .+? .+? .+? .+?)\" (.+)")
        private val REMOVE_COMMAND_PATTERN = Pattern.compile("remove (\\d+?)")
        private val gson = Gson()

        val MAX_SCHEDULES = 10000

        override fun create(configure: Configuration.() -> Unit): Cron {
            return Cron()
        }
    }
}

private val startedSchedules = mutableMapOf<Int, Scheduler>()

private fun startSchedule(botlin: Botlin, schedule: Schedule) {
    val scheduler = Scheduler().apply {
        schedule(schedule.cron) {
            botlin.publish<BotlinMessageRequest>(BotlinMessageRequest(
                    channelId = schedule.channelId,
                    message = schedule.command
            ))
        }
    }
    scheduler.start()
    startedSchedules.put(schedule.id, scheduler)
}

private fun stopSchedule(scheduleId: Int) {
    val scheduler = startedSchedules[scheduleId] ?: return
    scheduler.stop()
}