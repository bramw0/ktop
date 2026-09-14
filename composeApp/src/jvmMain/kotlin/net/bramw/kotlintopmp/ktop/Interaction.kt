package net.bramw.kotlintopmp.ktop

import androidx.compose.ui.input.key.Key

enum class UserAction {
    Continue,
}

sealed class Event {
    data class ValueChanged<T>(val taskId: Int, val value: Value<T>) : Event()
    data class UserKeyPressed(val key: Key) : Event()
    data class Action(val action: UserAction) : Event()
    data object Stop : Event()
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

fun <T, V> onAction(action: UserAction, block: Pred<T, V>.() -> Unit): Continuation.OnAction<T, Task<V>> {
    return Continuation.OnAction(action, Pred(block).asFunction())
}
