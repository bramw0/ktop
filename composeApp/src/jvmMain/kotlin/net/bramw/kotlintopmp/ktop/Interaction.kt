package net.bramw.kotlintopmp.ktop

import androidx.compose.ui.input.key.Key
import net.bramw.kotlintopmp.ktop.ui.Window
import kotlin.reflect.KType

enum class UserAction {
    Continue,
}

sealed class Event(val name: String) {
    sealed class SDS {
        data object Publish : Event("SDS.Publish")
        data class ValueChanged<T>(val value: T) : Event("SDS.ValueChanged")
    }
    sealed class RunningTask {
        data class Add(val signature: TaskSignature) : Event("RunningTask.Add")
        data class Remove(val signature: TaskSignature) : Event("RunningTask.Remove")
        data class RequestCount(val task: Task<*>) : Event("RunningTask.RequestCount")
        data class CountResult(val count: Int) : Event("RunningTask.CountResult")
    }
    sealed class User {
        data class KeyPressed(val key: Key) : Event("User.KeyPressed")
        data class Action(val action: UserAction) : Event("User.Action")
    }

    sealed class UIWindow {
        data class Add(val window: Window) : Event("UIWindow.Add")
        data class Remove(val window: Window) : Event("UIWindow.Remove")
    }
    data class ValueChanged<T : Any>(val taskId: Int, val value: Value<T>?) : Event("ValueChanged")
    data class DoStep<T : Any>(val newTask: Task<T>, val causedByStableValue: Boolean) : Event("DoStep")

    data object Stop : Event("Stop")
}

data class TaskSignature(val type: KType, val task: Task<*>)

class Pred<T : Any, V : Any>(block: Pred<T, V>.() -> Unit) {
    lateinit var pred: Pred<T, V>.(Value<T>?) -> Boolean
    lateinit var then: Pred<T, V>.(Value<T>?) -> Task<V>

    init {
        apply(block)
    }

    fun pred(block: Pred<T, V>.(Value<T>?) -> Boolean) {
        this.pred = block
    }

    fun then(block: Pred<T, V>.(Value<T>?) -> Task<V>) {
        this.then = block
    }

    fun asFunction(): (Value<T>?) -> Task<V>? {
        return { v ->
            if (pred(v)) {
                then(v)
            } else {
                null
            }
        }
    }
}

typealias ContinuationPredicate<T, V> = (Value<T>?) -> V?

sealed class Continuation<T, V> {
    data class OnValue<T : Any, V>(val predicate: ContinuationPredicate<T, V>) : Continuation<T, V>()
    data class OnAction<T : Any, V>(val action: UserAction, val predicate: ContinuationPredicate<T, V>) :
        Continuation<T, V>()
}

fun <T : Any, V : Any> onValue(block: Pred<T, V>.() -> Unit): Continuation.OnValue<T, Task<V>> {
    return Continuation.OnValue(Pred(block).asFunction())
}

fun <T : Any, V : Any> ifStable(block: Pred<T, V>.(T) -> Task<V>): Continuation.OnValue<T, Task<V>> {
    return onValue {
        pred {
            it.isStableValue()
        }
        then { value ->
            block(value!!.inner)
        }
    }
}

fun <T : Any, V : Any> ifUnstable(block: Pred<T, V>.(T) -> Task<V>): Continuation.OnValue<T, Task<V>> {
    return onValue {
        pred {
            it.isUnstableValue()
        }
        then { value ->
            block(value!!.inner)
        }
    }
}

fun <T : Any, V : Any> ifNoValue(block: Pred<T, V>.() -> Task<V>): Continuation.OnValue<T, Task<V>> {
    return onValue {
        pred {
            it.isNoValue()
        }
        then {
            block()
        }
    }
}

fun <T : Any, V : Any> onAction(action: UserAction, block: Pred<T, V>.() -> Unit): Continuation.OnAction<T, Task<V>> {
    return Continuation.OnAction(action, Pred(block).asFunction())
}
