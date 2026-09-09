package solution12

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import solution12.TaskStability.*
import kotlin.properties.Delegates

enum class TaskStability {
    Stable, Unstable,
}

sealed class TaskEvent {
    data class Continued<T>(val t: Task<T>) : TaskEvent()
    data class UserKeyPressed(val key: Key) : TaskEvent()
    data class Action(val action: TaskAction) : TaskEvent()
    data object Stop : TaskEvent()
}

typealias TaskValue<T> = Pair<T, TaskStability>?

fun <T> unstableTaskValue(value: T): TaskValue<T> = if (value == null) null else TaskValue(value, Unstable)
fun <T> stableTaskValue(value: T): TaskValue<T> = TaskValue(value, Stable)
fun <T> TaskValue<T>.isStableValue(): Boolean = this != null && second == Stable
fun <T> TaskValue<T>.isNoValue(): Boolean = !hasValue()
fun <T> TaskValue<T>.isUnstableValue(): Boolean = this != null && second == Unstable
fun <T> TaskValue<T>.hasValue(): Boolean = this != null

typealias TaskWorkFunction<T> = suspend Task<T>.(TaskValue<T>) -> Unit
typealias TaskSubscriberFunction<T> = Task<T>.(TaskValue<T>) -> Unit
typealias TaskChannelSubscriberFunction<T> = Task<T>.(ChannelResult<TaskEvent>) -> Boolean
typealias TaskDestructionFunction<T> = Task<T>.() -> Unit

class TaskChannel<T>(val task: Task<T>) {
    val channel: Channel<TaskEvent> = Channel(UNLIMITED)
    var channelSubscribers = mutableListOf<TaskChannelSubscriberFunction<T>>()
    val channelSubscribersMutex = Mutex()

    fun subscribe(f: TaskChannelSubscriberFunction<T>) {
        runBlocking {
            channelSubscribersMutex.withLock {
                channelSubscribers.add(f)
            }
        }
    }

    suspend fun receiveCatching(): ChannelResult<TaskEvent> {
        val result = channel.receiveCatching()

        val removedSubscribers = mutableListOf<TaskChannelSubscriberFunction<T>>();

        channelSubscribers.forEach { f ->
            if (f(task, result)) {
                removedSubscribers.add(f)
            }
        }

        if (removedSubscribers.isNotEmpty()) {
            channelSubscribers.removeAll(removedSubscribers)
        }

        return result
    }

    fun cancel() {
        channel.cancel()
    }

    suspend fun send(element: TaskEvent) {
        channel.send(element)
    }
}

class Task<T>(val taskName: String, val initialValue: TaskValue<T> = null, val work: TaskWorkFunction<T>) {
    // Reference to Task lifecycle?
    lateinit var handle: Job

    lateinit var parent: Task<*>

    val valueSubscribersMutex = Mutex()
    var valueSubscribers = mutableListOf<TaskSubscriberFunction<T>>()
    val channel = TaskChannel(this)

    var destructionHandlers = mutableListOf<TaskDestructionFunction<T>>().apply {
        add {
            channel.cancel()
        }
    }
    var destructionHandlersMutex = Mutex()

    var value: TaskValue<T> by Delegates.vetoable(initialValue) { _, oldValue, newValue ->
        // TODO: use channels to offload new value so current thread is freed up
        if (!oldValue.isStableValue() && oldValue != newValue) {
            println("${fullID()} old value = $oldValue, new value = $newValue")
            valueSubscribers.forEach {
                this.it(newValue)
            }

            if (newValue.isStableValue()) {
                println("cancelling channel of ${fullID()}")
                channel.cancel()
            }

            return@vetoable true
        } else {
            println("${fullID()} Vetoing assignment from $oldValue to $newValue")
            return@vetoable false
        }
    }

    suspend fun evaluate() {
        if (value.isStableValue()) return
        this@Task.work(value)
    }

    fun subscribe(emitNoValue: Boolean = false, f: TaskSubscriberFunction<T>) {
        runBlocking {
            valueSubscribersMutex.withLock {
                valueSubscribers.add(f)
            }
        }

        if (emitNoValue || !value.isNoValue()) this.f(value)
    }

    fun subscribeWithoutInitialValue(f: TaskSubscriberFunction<T>) {
        runBlocking {
            valueSubscribersMutex.withLock {
                valueSubscribers.add(f)
            }
        }
    }

    suspend fun sendEvent(event: TaskEvent) {
        println("sending event $event to ${fullID()}")
        channel.send(event)
        println("sent event $event to ${fullID()}")
    }

    fun copy(): Task<T> {
        val t = TaskExecutor.createTask(initialValue = initialValue, work = work)
        t.valueSubscribers.addAll(valueSubscribers)
        return t
    }

    fun start(): Task<T> {
        if (handle.isCompleted) {
            println("Launching new coroutine by copy")
            val t = copy()
            t.handle.start()

            return t
        } else {
            handle.start()
            return this
        }
    }

    fun registerDestructionHandler(f: TaskDestructionFunction<T>) {
        runBlocking {
            destructionHandlersMutex.withLock {
                destructionHandlers.add(f)
            }
        }
    }
}
