package net.bramw.kotlintopmp.ktop

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.properties.Delegates



typealias WorkFunction<T> = suspend Task<T>.() -> Unit
private typealias DestructionFunction<T> = Task<T>.() -> Unit

fun <T> Task<T>?.fullID(): String {
    return "(${this?.id} - ${this?.taskName} - ${this?.value})"
}

// What are the consequences of assigning an id to every running task, or every created task (that may never run at all)?
class Task<T>(var id: Int, var taskName: String, var initialValue: Value<T> = null, var work: WorkFunction<T>) {
    // Reference to Task lifecycle?
    lateinit var handle: Job

    lateinit var parent: Task<*>

    val valueSubscribersMutex = Mutex()
    var valueSubscribers = mutableSetOf<Task<*>>()

    val channel: Channel<Event> = Channel(UNLIMITED)

    var destructionHandlers = mutableListOf<DestructionFunction<T>>().apply {
        add {
            channel.cancel()
        }
    }
    var destructionHandlersMutex = Mutex()

    var value: Value<T> by Delegates.vetoable(initialValue) { _, oldValue, newValue ->
        if (!oldValue.isStableValue() && oldValue != newValue) {
            runBlocking {
                valueSubscribersMutex.withLock {
                    valueSubscribers.forEach {
                        it.sendEvent(Event.ValueChanged(id, newValue))
                    }
                }
            }

            if (newValue.isStableValue()) {
                runBlocking {
                    sendEvent(Event.Stop)
                }
                // TODO: do we still need to cancel the channel?
                channel.cancel()
            }

            return@vetoable true
        } else {
            return@vetoable false
        }
    }


    init {
        println("Created task ${fullID()}")
    }

    suspend fun evaluate() {
        if (value.isStableValue()) return
        this@Task.work()
    }

    suspend fun removeSubscriber(t: Task<*>) {
        valueSubscribersMutex.withLock {
            valueSubscribers.remove(t)
        }
    }

    suspend fun subscribe(t: Task<*>, emitNoValue: Boolean = false) {
        valueSubscribersMutex.withLock {
            valueSubscribers.add(t)
        }

        if (emitNoValue || !value.isNoValue()) t.sendEvent(Event.ValueChanged(id, value))
    }

    suspend fun sendEvent(event: Event) {
        channel.send(event)
    }

    fun start(scope: CoroutineScope, relaunch: Boolean = false) {
        if (!this::handle.isInitialized || handle.isCompleted || relaunch) {
            if (this::handle.isInitialized && (handle.isCompleted || relaunch)) {
                // If we relaunch, reset to the initial task value
                this.value = initialValue
            }

            handle = scope.launch(start = CoroutineStart.LAZY) {
                this@Task.evaluate()
            }
        }

        handle.start()
    }

    fun registerDestructionHandler(f: DestructionFunction<T>) {
        runBlocking {
            destructionHandlersMutex.withLock {
                destructionHandlers.add(f)
            }
        }
    }
}
