package net.bramw.kotlintopmp.ktop

import kotlinx.coroutines.yield
import net.bramw.kotlintopmp.ktop.Event.DoStep
import net.bramw.kotlintopmp.ktop.Event.Stop
import net.bramw.kotlintopmp.ktop.Event.User
import net.bramw.kotlintopmp.ktop.Event.ValueChanged


fun <T : Any> TaskScope.pure(v: T): Task<T> = createTask(initialValue = stableValue(v), kind = TaskKind.PureConst) {}
fun <T : Any> TaskScope.pure(v: Value<T>?): Task<T> = createTask(initialValue = v, kind = TaskKind.PureValue) {}
fun <T : Any> TaskScope.pure(f: () -> T): Task<T> =
    createTask(initialValue = stableValue(f()), kind = TaskKind.PureExpr) {}

context(ts: TaskScope)
infix fun <T : Any, V : Any> Task<T>.trans(f: (T) -> V): Task<V> = this transValue {
    it.value?.let { tmp ->
        Value(f(tmp.inner), tmp.stability)
    }
}

// TODO: Check in TopHat and iTasks whether stability of resulting trans task should always be the same as the receiver task (that it transforms)
// yes! keep stability
context(ts: TaskScope)
infix fun <T : Any, V : Any> Task<T>.transValue(f: (ValueChanged<T>) -> Value<V>?): Task<V> = with(ts) {
    return createTask(kind = TaskKind.Transform) {
        val lhs = this@transValue
        lhs.parent = this
        lhs.subscribe(this)
        lhs.start(scope)

        for (event in this.channel) {
            when (event) {
                is Stop -> handleStop {
                    // TODO: should canceling be done when a task has a stable value (by sending Stop to itself) if the task itself still has an active parent?
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

            yield()
        }
    }
}

context(ts: TaskScope)
infix fun <T : Any> Task<*>.orRight(other: Task<T>): Task<T> = with(ts) {
    return createTask(kind = TaskKind.OrRight) {
        val lhs = this@orRight

        lhs.parent = this
        lhs.subscribe(this)
        lhs.start(scope)

        other.parent = this
        other.subscribe(this)
        other.start(scope)

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
                    if (event.taskId == other.id) {
                        this.value = event.value as Value<T>?
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

            yield()
        }
    }
}

context(ts: TaskScope)
infix fun <T : Any> Task<T>.orLeft(other: Task<*>): Task<T> = with(ts) {
    return createTask(kind = TaskKind.OrLeft) {
        val lhs = this@orLeft

        lhs.parent = this
        lhs.subscribe(this)
        lhs.start(scope)

        other.parent = this
        other.subscribe(this)
        other.start(scope)

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
                        this.value = event.value as Value<T>?
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

            yield()
        }
    }

}

context(ts: TaskScope)
// TODO: refactor this to use a general `parallel` combinator and adhere to the iTasks implementation and the draft?
infix fun <T : Any> Task<T>.or(other: Task<T>): Task<T> = with(ts) {
    return createTask(kind = TaskKind.Or) {
        val lhs = this@or

        lhs.parent = this
        lhs.subscribe(this)
        lhs.start(scope)

        other.parent = this
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
                            this.value = event.value as Value<T>?
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
                            this.value = event.value as Value<T>?
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

            yield()
        }
    }

}

context(ts: TaskScope)
// TODO: refactor this to use a general `parallel` combinator and adhere to the iTasks implementation and the draft?
infix fun <T : Any, V : Any> Task<T>.and(other: Task<V>): Task<Pair<T, V>> = with(ts) {
    return createTask(kind = TaskKind.And) {
        val lhs = this@and

        lhs.parent = this
        lhs.subscribe(this)
        lhs.start(scope)

        other.parent = this
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
                                    this.value = stableValue(Pair(event.value.inner as T, lastValue as V))
                                } else if (lastValueIsLhs) {
                                    lastValue = event.value.inner
                                } else {
                                    this.value = unstableValue(Pair(event.value.inner as T, lastValue as V))
                                }
                            } else {
                                // No last value present, set it
                                lastValue = event.value.inner
                                lastValueIsLhs = true
                            }
                        } else {
                            // We received a no value from the lhs task
                            // Remember the current rhs value if it exists, isRhsStable should still contain this value's stability.
                            // Set value to no value.
                            this.value?.inner?.second.let {
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
                                    this.value = stableValue(Pair(lastValue as T, event.value.inner as V))
                                } else if (!lastValueIsLhs) {
                                    lastValue = event.value.inner
                                } else {
                                    this.value = unstableValue(Pair(lastValue as T, event.value.inner as V))
                                }
                            } else {
                                lastValue = event.value.inner
                                lastValueIsLhs = false
                            }
                        } else {
                            // Remember lhs
                            this.value?.inner?.first.let {
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

            yield()
        }
    }

}

context(ts: TaskScope)
infix fun <T : Any, V : Any> Task<T>.step(rhs: Continuation<T, Task<V>>): Task<V> = this step listOf(rhs)

context(ts: TaskScope)
infix fun <T : Any, V : Any> Task<T>.step(rhs: Collection<Continuation<T, Task<V>>>): Task<V> = with(ts) {
    return createTask(kind = TaskKind.Step) {
        val lhs = this@step
        lhs.parent = this
        lhs.subscribe(this)
        lhs.start(scope)

        for (event in this.channel) {
            when (event) {
                is DoStep<*> -> {
                    lhs.removeSubscriber(this)
                    lhs.sendEvent(Stop)

                    val newTask = event.newTask
                    this.work = newTask.work as WorkFunction<V>
                    // TODO: Is it correct to copy everything from the new task?
                    this.initialValue = newTask.initialValue as Value<V>?
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
                            cont.predicate(event.value as Value<T>?)?.let { newTask ->
                                println("task $id: step continuing to task ${newTask.id}")
                                // TODO: what happens if a new Value event was sent before DoStep? Should we use Priority channels?
                                this.sendEvent(DoStep(newTask, event.value.isStableValue()))
                                break
                            }
                        }
                    }
                }

                is User.Action -> {
                    for (cont in rhs) {
                        if (cont is Continuation.OnAction<T, Task<V>> && cont.action == event.action) {
                            cont.predicate(this.value as Value<T>?)?.let { newTask ->
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

            yield()
        }
    }
}

context(ts: TaskScope)
infix fun <T : Any, V : Any> Task<T>.seq(other: Pred<T, V>.(Value<T>?) -> Task<V>): Task<V> = with(ts) {
    return (this@seq step listOf(
        onValue {
            pred { v ->
                v.isStableValue()
            }
            then(other)
        }))
}

context(ts: TaskScope)
infix fun <T : Any, V : Any> Task<T>.seqNoShare(other: Task<V>): Task<V> = with(ts) {
    return (this@seqNoShare seq { other })
}
