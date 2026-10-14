package net.bramw.kotlintopmp.ktop

import androidx.compose.ui.input.key.Key

enum class UserAction {
    Continue,
}

sealed class Event(val name: String) {
    data class ValueChanged<T>(val taskId: Int, val value: Value<T>) : Event("ValueChanged")
    data class UserKeyPressed(val key: Key) : Event("UserKeyPressed")
    data class Action(val action: UserAction) : Event("Action")
    data class DoStep<T>(val newTask: Task<T>, val causedByStableValue: Boolean) : Event("DoStep")

    data object Stop : Event("Stop")
}

class Pred<T, V>(block: Pred<T, V>.() -> Unit) {
    lateinit var pred: Pred<T, V>.(Value<T>) -> Boolean
    lateinit var then: Pred<T, V>.(Value<T>) -> Task<V>

    init {
        apply(block)
    }

    fun pred(block: Pred<T, V>.(Value<T>) -> Boolean) {
        this.pred = block
    }

    fun then(block: Pred<T, V>.(Value<T>) -> Task<V>) {
        this.then = block
    }

    fun asFunction(): (Value<T>) -> Task<V>? {
        return { v ->
            if (pred(v)) {
                then(v)
            } else {
                null
            }
        }
    }
}

typealias ContinuationPredicate<T, V> = (Value<T>) -> V?

sealed class Continuation<T, V> {
    data class OnValue<T, V>(val predicate: ContinuationPredicate<T, V>) : Continuation<T, V>()
    data class OnAction<T, V>(val action: UserAction, val predicate: ContinuationPredicate<T, V>) :
        Continuation<T, V>()
}

fun <T, V> onValue(block: Pred<T, V>.() -> Unit): Continuation.OnValue<T, Task<V>> {
    return Continuation.OnValue(Pred(block).asFunction())
}

fun <T, V> ifStable(block: Pred<T, V>.(T) -> Task<V>): Continuation.OnValue<T, Task<V>> {
    return onValue {
        pred {
            it.isStableValue()
        }
        then { value ->
            block(value!!.first)
        }
    }
}

fun <T, V> ifUnstable(block: Pred<T, V>.(T) -> Task<V>): Continuation.OnValue<T, Task<V>> {
    return onValue {
        pred {
            it.isUnstableValue()
        }
        then { value ->
            block(value!!.first)
        }
    }
}

fun <T, V> ifNoValue(block: Pred<T, V>.() -> Task<V>): Continuation.OnValue<T, Task<V>> {
    return onValue {
        pred {
            it.isNoValue()
        }
        then {
            block()
        }
    }
}

fun <T, V> onAction(action: UserAction, block: Pred<T, V>.() -> Unit): Continuation.OnAction<T, Task<V>> {
    return Continuation.OnAction(action, Pred(block).asFunction())
}
