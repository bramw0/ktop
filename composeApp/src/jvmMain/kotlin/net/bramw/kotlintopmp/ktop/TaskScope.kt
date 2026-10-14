package net.bramw.kotlintopmp.ktop

import kotlinx.coroutines.*
import net.bramw.kotlintopmp.ktop.Event.*
import net.bramw.kotlintopmp.showMainWindow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement


suspend fun taskScope(block: suspend TaskScope.() -> Unit) {
    val tScope = TaskScope()
    tScope.block()
    // Wait until all children jobs have completed
    tScope.scope.coroutineContext.job.children.toList().joinAll()
}

private suspend fun <T> Task<T>.handleStop(block: suspend Task<T>.() -> Unit) {
    block()
    println("task $id: cancelling channel")
    channel.cancel()
}

class TaskScope {
    val scope = CoroutineScope(Dispatchers.Default)

    @OptIn(ExperimentalAtomicApi::class)
    val nextTaskId: AtomicInt = AtomicInt(0)

    @OptIn(ExperimentalAtomicApi::class)
    fun <T> createTask(initialValue: Value<T> = null, parentTask: Task<*>? = null, work: WorkFunction<T>): Task<T> {
        // TODO: remove eventually
        val stackTrace: StackTraceElement =
            Thread.currentThread().stackTrace.firstOrNull { it.fileName == "main.kt" }
                ?: Thread.currentThread().stackTrace[3]
        val taskName = "${stackTrace.methodName} - ${stackTrace.fileName}:${stackTrace.lineNumber}"
        val id = nextTaskId.fetchAndIncrement()
        println("Created task $id: $taskName")
        val t = Task(id, taskName, initialValue, work)
        parentTask?.let { t.parent = it }

        return t
    }

    /* COMBINATORS */

    fun <T> pure(v: T): Task<T> {
        return createTask(initialValue = stableValue(v)) {}
    }

    fun <T> pure(v: Value<T>): Task<T> {
        return createTask(initialValue = v) {}
    }

    fun <T> pure(f: () -> T): Task<T> {
        return createTask(initialValue = stableValue(f())) {}
    }

    suspend fun start(vararg tasks: Task<*>) {
        tasks.map {
            scope.launch(CoroutineName("start task: ${it.taskName}")) {
                it.start(scope)
            }
        }.joinAll() // Wait until all tasks have been started
    }

//    infix fun <T, V> Task<T>.trans(f: (Value<T>) -> Value<V>): Task<V> {
//        return this trans { event: ValueChanged<T> ->
//            f(event.value)
//        }
//    }

    // Check in TopHat and iTasks whether stability of resulting trans task should always be the same as the receiver task (that it transforms)
    infix fun <T, V> Task<T>.trans(f: (ValueChanged<T>) -> Value<V>): Task<V> {
        return createTask {
            val lhs = this@trans
            lhs.subscribe(this)
            lhs.start(scope)

            for (event in this.channel) {
                when (event) {
                    is Stop -> handleStop {
                        // TODO: should canceling be done when a task has a stable value if the task itself still has an active parent?
                        println("task $id: processing Stop")
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)
                    }

                    is ValueChanged<*> -> {
                        this.value = f(event as ValueChanged<T>)
                    }

                    else -> {
                        lhs.sendEvent(event)
                    }
                }
            }
        }
    }

    // TODO: refactor this to use a general `parallel` combinator and adhere to the iTasks implementation and the draft?
    infix fun <T> Task<T>.or(other: Task<T>): Task<T> {
        return createTask {
            val lhs = this@or

            lhs.subscribe(this)
            lhs.start(scope)

            other.subscribe(this)
            other.start(scope)

            var lastValueIsLhs = false
            var lhsIsNoValue = false
            var rhsIsNoValue = false

            for (event in this.channel) {
                when (event) {
                    is Stop -> handleStop {
                        // Remove our references and propagate the Stop event. Then break the loop
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)

                        other.removeSubscriber(this)
                        other.sendEvent(event)
                    }

                    is ValueChanged<*> -> {
                        if (event.taskId == lhs.id) {
                            if (event.value.isNoValue()) {
                                if (!lastValueIsLhs) {
                                    lhsIsNoValue = true
                                } else if (rhsIsNoValue) {
                                    this.value = null
                                }
                            } else {
                                this.value = event.value as Value<T>
                                lastValueIsLhs = true
                            }
                        } else if (event.taskId == other.id) {
                            if (event.value.isNoValue()) {
                                if (lastValueIsLhs) {
                                    rhsIsNoValue = true
                                } else if (lhsIsNoValue) {
                                    this.value = null
                                }
                            } else {
                                this.value = event.value as Value<T>
                                lastValueIsLhs = false
                            }
                        } else {
                            // Not an event we want, tunnel it just in case
                            lhs.sendEvent(event)
                            other.sendEvent(event)
                        }
                    }

                    else -> {
                        // Tunnel events to the child tasks.
                        lhs.sendEvent(event)
                        other.sendEvent(event)
                    }
                }
            }
        }
    }

    // TODO: refactor this to use a general `parallel` combinator and adhere to the iTasks implementation and the draft?
    infix fun <T, V> Task<T>.and(other: Task<V>): Task<Pair<T, V>> {
        return createTask {
            val lhs = this@and

            lhs.subscribe(this)
            lhs.start(scope)

            other.subscribe(this)
            other.start(scope)

            var lastValue: Any? = null
            var lastValueIsLhs = false
            var isRhsStable = false
            var isLhsStable = false

            for (event in this.channel) {

                when (event) {
                    is Stop -> handleStop {
                        // Remove our references and propagate the Stop event. Then break the loop
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)

                        other.removeSubscriber(this)
                        other.sendEvent(event)
                    }

                    is ValueChanged<*> -> {
                        if (event.taskId == lhs.id) {
                            if (event.value.hasValue()) {
                                // We received a value from the lhs task
                                isLhsStable = event.value.isStableValue()
                                if (lastValue != null) {
                                    // We remembered a value.
                                    // If its from the rhs, it is stable, and we are stable, set stable value.
                                    // Else if our last value is already from lhs, update it; we do not have a rhs value present in that case.
                                    // Else our last value must be from rhs and either the received lhs value or the remembered rhs value is not stable, so we set an unstable value.
                                    if (!lastValueIsLhs && isLhsStable && isRhsStable) {
                                        this.value = stableValue(Pair(event.value.first as T, lastValue as V))
                                    } else if (lastValueIsLhs) {
                                        lastValue = event.value.first
                                    } else {
                                        this.value = unstableValue(Pair(event.value.first as T, lastValue as V))
                                    }
                                } else {
                                    // No last value present, set it
                                    lastValue = event.value.first
                                    lastValueIsLhs = true
                                }
                            } else {
                                // We received a no value from the lhs task
                                // Remember the current rhs value if it exists, isRhsStable should still contain this value's stability.
                                // Set value to no value.
                                this.value?.first?.second.let {
                                    lastValue = it
                                    lastValueIsLhs = false
                                }
                                this.value = null
                            }
                        } else if (event.taskId == other.id) {
                            if (event.value.hasValue()) {
                                isRhsStable = event.value.isStableValue()
                                if (lastValue != null) {
                                    if (lastValueIsLhs && isRhsStable && isLhsStable) {
                                        this.value = stableValue(Pair(lastValue as T, event.value.first as V))
                                    } else if (!lastValueIsLhs) {
                                        lastValue = event.value.first
                                    } else {
                                        this.value = unstableValue(Pair(lastValue as T, event.value.first as V))
                                    }
                                } else {
                                    lastValue = event.value.first
                                    lastValueIsLhs = false
                                }
                            } else {
                                // Remember lhs
                                this.value?.first?.first.let {
                                    lastValue = it
                                    lastValueIsLhs = true
                                }
                                this.value = null
                            }
                        } else {
                            // Not an event we want, tunnel it just in case
                            lhs.sendEvent(event)
                            other.sendEvent(event)
                        }
                    }

                    else -> {
                        // Tunnel events to the child tasks.
                        lhs.sendEvent(event)
                        other.sendEvent(event)
                    }
                }
            }
        }
    }

    infix fun <T, V> Task<T>.step(rhs: Continuation<T, Task<V>>): Task<V> {
        return this step listOf(rhs)
    }

    infix fun <T, V> Task<T>.step(rhs: Collection<Continuation<T, Task<V>>>): Task<V> {
        return createTask {
            val lhs = this@step
            lhs.subscribe(this)
            lhs.start(scope)

            for (event in this.channel) {
                when (event) {
                    is DoStep<*> -> {
                        lhs.removeSubscriber(this)
                        if (!event.causedByStableValue) lhs.sendEvent(Stop)

                        val newTask = event.newTask
                        this.work = newTask.work as WorkFunction<V>
                        // TODO: Is it correct to copy everything from the new task?
                        this.initialValue = newTask.initialValue as Value<V>
                        this.id = newTask.id
                        this.taskName = "${this.taskName} -> ${newTask.taskName}"

                        // TODO: this will only replace the current coroutine running the work function, which is a property of the current Task object; the channel of the current Task object will be reused as that of the rhs continuation.
                        this.start(scope, relaunch = true)

                        // TODO: how to empty current channel? Should we empty the current channel?
//                        channel.receiveAsFlow().collect {}
                        break // Break out of current for loop, ending the execution of `this.work`, while keeping the channel intact to be reused by the new instance of `this.work`, which has just been set to `newTask.work`.
                    }

                    is Stop -> handleStop {
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)
                    }

                    is ValueChanged<*> -> {
                        for (cont in rhs) {
                            if (cont is Continuation.OnValue<T, Task<V>>) {
                                cont.predicate(event.value as Value<T>)?.let { newTask ->
                                    println("task $id: step continuing to task ${newTask.id}")
                                    // TODO: what happens if a new Value event was sent before DoStep? Should we use Priority channels?
                                    this.sendEvent(DoStep(newTask, event.value.isStableValue()))
                                    break
                                }
                            }
                        }
                    }

                    is Action -> {
                        for (cont in rhs) {
                            if (cont is Continuation.OnAction<T, Task<V>> && cont.action == event.action) {
                                cont.predicate(this.value as Value<T>)?.let { newTask ->
                                    this.sendEvent(DoStep(newTask, false))
                                    break
                                }
                            }
                        }
                    }

                    else -> {
                        lhs.sendEvent(event)
                    }
                }
            }
        }
    }

    infix fun <T, V> Task<T>.seq(other: Pred<T, V>.(Value<T>) -> Task<V>): Task<V> {
        return (this step listOf(
            onValue {
                pred { v ->
                    v.isStableValue()
                }
                then(other)
            }))
    }

    infix fun <T, V> Task<T>.seqNoShare(other: Task<V>): Task<V> {
        return (this seq { other })
    }

    fun <T> viewInformation(value: T): Task<T> {
        return createTask {
            this.value = unstableValue(value)
            showMainWindow(value)
        }
    }


}

