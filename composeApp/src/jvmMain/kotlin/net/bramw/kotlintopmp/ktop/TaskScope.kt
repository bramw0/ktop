package net.bramw.kotlintopmp.ktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import net.bramw.kotlintopmp.ktop.Event.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement


suspend fun taskScope(block: suspend TaskScope.() -> Unit) {
    val scope = TaskScope()
    scope.block()
    // Wait until all children jobs have completed
    scope.scope.coroutineContext.job.children.toList().joinAll()
}

class TaskScope {
    val scope = CoroutineScope(Dispatchers.Default)

    @OptIn(ExperimentalAtomicApi::class)
    val nextTaskId: AtomicInt = AtomicInt(0)

    @OptIn(ExperimentalAtomicApi::class)
    fun <T> createTask(initialValue: Value<T> = null, parentTask: Task<*>? = null, work: WorkFunction<T>): Task<T> {
        val taskName = Thread.currentThread().stackTrace[3].methodName
        val id = nextTaskId.fetchAndIncrement()
        val t = Task(id, taskName, initialValue, work)
        parentTask?.let { t.parent = it }

        return t
    }

    /* COMBINATORS */

    fun <T> pure(v: T): Task<T> {
        return createTask(initialValue = stableValue(v)) {}
    }

    fun start(task: Task<*>) {
        task.start(scope)
    }

    fun start(tasks: Collection<Task<*>>) {
        tasks.forEach { start(it) }
    }

    infix fun <T, V> Task<T>.trans(f: (Value<T>) -> Value<V>): Task<V> {
        return createTask {
            val lhs = this@trans
            lhs.subscribe(this)
            lhs.start(scope)

            for (event in this.channel) {
                when (event) {
                    is Stop -> {
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)
                        break
                    }

                    is ValueChanged<*> -> {
                        this.value = f(event.value as Value<T>)
                    }

                    else -> {
                        lhs.sendEvent(event)
                    }
                }
            }
        }
    }

    infix fun <T> Task<T>.parOr(other: Task<T>): Task<T> {
        return createTask {
            val lhs = this@parOr

            lhs.subscribe(this)
            lhs.start(scope)

            other.subscribe(this)
            other.start(scope)

            var lastValueIsLhs = false
            var lhsIsNoValue = false
            var rhsIsNoValue = false

            for (event in this.channel) {
                when (event) {
                    is Stop -> {
                        // Remove our references and propagate the Stop event. Then break the loop
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)

                        other.removeSubscriber(this)
                        other.sendEvent(event)
                        break
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

    infix fun <T, V> Task<T>.parAnd(other: Task<V>): Task<Pair<T, V>> {
        return createTask {
            val lhs = this@parAnd

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
                    is Stop -> {
                        // Remove our references and propagate the Stop event. Then break the loop
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)

                        other.removeSubscriber(this)
                        other.sendEvent(event)
                        break
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

    infix fun <T, V> Task<T>.step(rhs: Collection<Continuation<T, Task<V>>>): Task<V> {
        return createTask {
            val lhs = this@step
            lhs.subscribe(this)
            lhs.start(scope)

            suspend fun doStep(newTask: Task<V>) {
                this.work = newTask.work
                // TODO: Is it correct to copy everything from the new task?
                this.initialValue = newTask.initialValue
                this.id = newTask.id
                this.taskName = "${this.taskName} ${newTask.taskName}"
                this.start(scope, relaunch = true)

                lhs.removeSubscriber(this)
                lhs.sendEvent(Stop)
            }

            for (event in this.channel) {
                when (event) {
                    is Stop -> {
                        lhs.removeSubscriber(this)
                        lhs.sendEvent(event)
                        break
                    }
                    is ValueChanged<*> -> {
                        for (cont in rhs) {
                            if (cont is Continuation.OnValue<T, Task<V>>) {
                                cont.predicate(event.value as Value<T>)?.let { newTask ->
                                    doStep(newTask)
                                    break
                                }
                            }
                        }
                    }
                    is Action -> {
                        for (cont in rhs) {
                            if (cont is Continuation.OnAction<T, Task<V>> && cont.action == event.action) {
                                cont.predicate(this.value as Value<T>)?.let { newTask ->
                                    doStep(newTask)
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
            }
        ))
    }

    infix fun <T, V> Task<T>.seqNoShare(other: Task<V>): Task<V> {
        return (this seq { other })
    }
}