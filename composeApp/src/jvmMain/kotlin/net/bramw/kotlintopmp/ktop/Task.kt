package net.bramw.kotlintopmp.ktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.properties.Delegates


typealias WorkFunction<T> = suspend Task<T>.() -> Unit
private typealias DestructionFunction<T> = Task<T>.() -> Unit

enum class TaskKind {
    PureConst, PureValue, PureExpr, Transform, And, Or, OrLeft, OrRight, Step, Watch, Get, Set, Update, Custom
}

// What are the consequences of assigning an id to every running task, or every created task (that may never run at all)?
class Task<T : Any>(
    var id: Int,
    var taskName: String,
    var initialValue: Value<T>? = null,
    val kind: TaskKind = TaskKind.Custom,
    var work: WorkFunction<T>
) {
    // Reference to Task lifecycle?
    lateinit var handle: Job

    lateinit var parent: Task<*>
    fun hasParent(): Boolean = ::parent.isInitialized

    lateinit var runningTasksHandler: (Throwable?) -> Unit

    var started: MutableState<Boolean> = mutableStateOf(false)

    val valueSubscribersMutex = Mutex()
    var valueSubscribers = mutableSetOf<Task<*>>()

    val channel: Channel<Event> = Channel(UNLIMITED)

    var destructionHandlers = mutableListOf<DestructionFunction<T>>().apply {
        add {
            channel.cancel()
        }
    }
    var destructionHandlersMutex = Mutex()

    var value: Value<T>? by Delegates.vetoable(initialValue) { _, oldValue, newValue ->
        if (!oldValue.isStableValue() && oldValue != newValue) {
            runBlocking {
                valueSubscribersMutex.withLock {
                    valueSubscribers.forEach {
                        it.sendEvent(Event.ValueChanged(id, newValue))
                    }
                }
            }

            if (newValue.isStableValue() && !this::parent.isInitialized) {
                sendEvent(Event.Stop)
            }

            return@vetoable true
        } else {
            return@vetoable false
        }
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

    fun sendEvent(event: Event) {
        val result = channel.trySend(event)
        if (result.isClosed) {
            println("Could not send event $event to task $id, reason: ${result.exceptionOrNull()}")
        } else {
            println("Sent event ${event.name}=$event to task $id")
        }
    }

    fun start(scope: CoroutineScope, relaunch: Boolean = false) {
        if (!this::handle.isInitialized || handle.isCompleted || relaunch) {
            if (this::handle.isInitialized && (handle.isCompleted || relaunch)) {
                // If we relaunch, reset to the initial task value
                this.value = initialValue
            }

            handle = scope.launch(CoroutineName("$id: $taskName"), start = CoroutineStart.LAZY) {
                this@Task.evaluate()
            }

            if (this::runningTasksHandler.isInitialized) {
                handle.invokeOnCompletion(runningTasksHandler)
            }
        }

        handle.start()
        started.value = true
    }

    fun registerDestructionHandler(f: DestructionFunction<T>) {
        runBlocking {
            destructionHandlersMutex.withLock {
                destructionHandlers.add(f)
            }
        }
    }
}
