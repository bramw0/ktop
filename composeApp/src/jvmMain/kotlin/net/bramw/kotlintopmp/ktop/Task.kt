package net.bramw.kotlintopmp.ktop

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.bramw.kotlintopmp.ktop.Stability.*
import kotlin.properties.Delegates

enum class Stability {
    Stable, Unstable,
}

enum class UserAction {
    Continue,
}

sealed class Event {
    data class Continued<T>(val t: Task<T>) : Event()
    data class UserKeyPressed(val key: Key) : Event()
    data class Action(val action: UserAction) : Event()
    data object Stop : Event()
}

typealias Value<T> = Pair<T, Stability>?

fun <T> unstableValue(value: T): Value<T> = if (value == null) null else Value(value, Unstable)
fun <T> stableValue(value: T): Value<T> = Value(value, Stable)
fun <T> Value<T>.isStableValue(): Boolean = this != null && second == Stable
fun <T> Value<T>.isNoValue(): Boolean = !hasValue()
fun <T> Value<T>.isUnstableValue(): Boolean = this != null && second == Unstable
fun <T> Value<T>.hasValue(): Boolean = this != null

typealias WorkFunction<T> = suspend Task<T>.(Value<T>) -> Unit
private typealias SubscriberFunction<T> = Task<T>.(Value<T>) -> Unit
private typealias ChannelSubscriberFunction<T> = Task<T>.(ChannelResult<Event>) -> Boolean
private typealias DestructionFunction<T> = Task<T>.() -> Unit

class TaskChannel<T>(val task: Task<T>) {
    val channel: Channel<Event> = Channel(UNLIMITED)
    var channelSubscribers = mutableListOf<ChannelSubscriberFunction<T>>()
    val channelSubscribersMutex = Mutex()

    fun subscribe(f: ChannelSubscriberFunction<T>) {
        runBlocking {
            channelSubscribersMutex.withLock {
                channelSubscribers.add(f)
            }
        }
    }

    suspend fun receiveCatching(): ChannelResult<Event> {
        val result = channel.receiveCatching()

        val removedSubscribers = mutableListOf<ChannelSubscriberFunction<T>>();

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

    suspend fun send(element: Event) {
        channel.send(element)
    }
}

class Task<T>(val taskName: String, val initialValue: Value<T> = null, val work: WorkFunction<T>) {
    // Reference to Task lifecycle?
    lateinit var handle: Job

    lateinit var parent: Task<*>

    val valueSubscribersMutex = Mutex()
    var valueSubscribers = mutableListOf<SubscriberFunction<T>>()
    val channel = TaskChannel(this)

    var destructionHandlers = mutableListOf<DestructionFunction<T>>().apply {
        add {
            channel.cancel()
        }
    }
    var destructionHandlersMutex = Mutex()

    var value: Value<T> by Delegates.vetoable(initialValue) { _, oldValue, newValue ->
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

    fun subscribe(emitNoValue: Boolean = false, f: SubscriberFunction<T>) {
        runBlocking {
            valueSubscribersMutex.withLock {
                valueSubscribers.add(f)
            }
        }

        if (emitNoValue || !value.isNoValue()) this.f(value)
    }

    fun subscribeWithoutInitialValue(f: SubscriberFunction<T>) {
        runBlocking {
            valueSubscribersMutex.withLock {
                valueSubscribers.add(f)
            }
        }
    }

    suspend fun sendEvent(event: Event) {
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

    fun registerDestructionHandler(f: DestructionFunction<T>) {
        runBlocking {
            destructionHandlersMutex.withLock {
                destructionHandlers.add(f)
            }
        }
    }
}
