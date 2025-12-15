package solution12

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.bramw.kotlintopmp.*
import solution12.TaskEvent.*
import solution12.TaskStability.Stable
import solution12.TaskStability.Unstable
import kotlin.reflect.full.isSubclassOf

typealias TaskContinuationPredicate<T, V> = (TaskValue<T>) -> V?

enum class TaskAction {
    ActionContinue,
}

sealed class TaskContinuation<T, V> {
    data class OnValue<T, V>(val predicate: TaskContinuationPredicate<T, V>) : TaskContinuation<T, V>()
    data class OnAction<T, V>(val action: TaskAction, val predicate: TaskContinuationPredicate<T, V>) :
        TaskContinuation<T, V>()
}

class Pred<T, V>(block: Pred<T, V>.() -> Unit) {
    lateinit var pred: Pred<T, V>.(TaskValue<T>) -> Boolean
    lateinit var then: Pred<T, V>.(TaskValue<T>) -> Task<V>

    init {
        apply(block)
    }

    fun pred(block: Pred<T, V>.(TaskValue<T>) -> Boolean) {
        this.pred = block
    }

    fun then(block: Pred<T, V>.(TaskValue<T>) -> Task<V>) {
        this.then = block
    }

    fun asFunction(): (TaskValue<T>) -> Task<V>? {
        return { v ->
            if (pred(v)) {
                then(v)
            } else {
                null
            }
        }
    }
}

fun <T, V> ifPred(block: Pred<T, V>.() -> Unit): (TaskValue<T>) -> Task<V>? {
    return Pred(block).asFunction()
}

suspend fun suspendAndTunnelEvents(channel: TaskChannel<*>, target: Task<*>) {
    suspendAndReceiveEvents(channel) {
        target.sendEvent(it)
        it is Stop
    }
}

suspend fun suspendAndTunnelEvents(channel: TaskChannel<*>, targets: Collection<Task<*>>) {
    suspendAndReceiveEvents(channel) { event ->
        targets.forEach {
            it.sendEvent(event)
        }
        event is Stop
    }
}

suspend fun suspendAndReceiveEvents(channel: TaskChannel<*>, block: suspend (TaskEvent) -> Boolean) {
    while (true) {
        val result = channel.receiveCatching()
        val event = result.getOrNull() ?: break

        if (block(event)) break
    }
}


infix fun <T, V> Task<T>.trans(f: Task<T>.(TaskValue<T>) -> TaskValue<V>): Task<V> {
    val work: TaskWorkFunction<V> = work@{
        if (this@trans.handle.isActive) {
            this@trans.subscribe { v ->
                println("Transform called")
                val value = this@trans.f(v)
                if (value.isStableValue()) this@trans.channel.cancel()
                this@work.value = value
            }
            suspendAndTunnelEvents(channel, this@trans)

            this.destructionHandlers.forEach { this.it() }
            this@trans.destructionHandlers.forEach { this@trans.it() }
        } else {
            this@trans.subscribeWithoutInitialValue { v ->
                println("Transform called")
                val value = this@trans.f(v)
                if (value.isStableValue()) this@trans.channel.cancel()
                this@work.value = value
            }
            val newThis = this@trans.start()
            suspendAndTunnelEvents(channel, newThis)

            this.destructionHandlers.forEach { this.it() }
            newThis.destructionHandlers.forEach { newThis.it() }
        }
    }

    return if (handle.isActive) {
        TaskExecutor.startTask(work = work)
    } else {
        TaskExecutor.createTask(work = work)
    }
}

infix fun <T, V> Task<T>.step(rhs: Collection<TaskContinuation<T, Task<V>>>): Task<V> {
    fun doContinue(taskV: Task<V>, taskT: Task<T>, result: Task<V>) {
        runBlocking {
            taskV.sendEvent(Continued(result))
            taskT.sendEvent(Stop)
        }
    }

    return TaskExecutor.createTask {
        var lhs = this@step
        println("current step lhs = ${lhs.fullID()}")
        lhs.subscribeWithoutInitialValue {
            for (cont in rhs) {
                if (cont is TaskContinuation.OnValue<T, Task<V>>) {
                    cont.predicate(it)?.let { result ->
                        doContinue(this@createTask, this@step, result)
                        break
                    }
                }
            }
        }
        lhs.channel.subscribe {
            it.getOrNull()?.let { event ->
                println("Received event $event")
                if (event is Action) {
                    for (cont in rhs) {
                        if (cont is TaskContinuation.OnAction<T, Task<V>> && cont.action == event.action) {
                            cont.predicate(value)?.let { result ->
                                doContinue(this@createTask, this@step, result)
                                return@subscribe true
                            }
                        }
                    }
                } else if (event is Stop) {
                    runBlocking {
                        this@createTask.sendEvent(event)
                    }
                    return@subscribe true
                }
            }

            return@subscribe false
        }

        lhs = lhs.start()
        println("new step lhs = ${lhs.fullID()}")

        val continuedTask: suspend () -> Task<V>? = suspend continuedTask@{
            while (true) {
                println("step waiting to receive on channel of ${fullID()}")
                val result = this.channel.receiveCatching()
                val event = result.getOrNull() ?: run {
                    println("received channel cancel to step lhs = ${lhs.fullID()}")
                    break
                }

                if (event is Continued<*>) {
                    println("received task ${event.t.hashCode().toString(16)}")
                    return@continuedTask event.t as Task<V>? // TODO: check that this is always ok
                } else if (event is Stop) {
                    println("STEP RECEIVED STOP!")
                    destructionHandlers.forEach { this.it() }
                    break
                }
            }

            return@continuedTask null
        }

        val task = continuedTask()
        println("continuing with ${task.fullID()}")
        // TODO: how to rewrite current task to be Task<V> and make sure that the 'step' task no longer exists?
        task?.let { notNullTask ->
            notNullTask.subscribe { v ->
                this@createTask.value = v
            }

            // TODO: view & update tasks have references to their own tasks, not to the combinator step task that controls them. How to make sure that a Remove action in the UI, which sends a Stop action to the view / update task itself, also stops the 'step' task? Such that the solution works for every task, even non UI tasks?
            // Possible solution: let every task have a reference to their 'parent' in the task graph (so either null or their most inner combinator task). Each combinator task will have a parent as well.

            notNullTask.channel.subscribe {
                it.getOrNull()?.let { event ->
                    if (event is Stop) {

                        return@subscribe true
                    }
                }

                return@subscribe false
            }

            val newTask = notNullTask.start()
            suspendAndTunnelEvents(this@createTask.channel, newTask)

            println("New task is Stopped!")

            this@createTask.destructionHandlers.forEach { this@createTask.it() }
            newTask.destructionHandlers.forEach { newTask.it() }
        }
    }
}

infix fun <T, V> Task<T>.seq(other: Pred<T, V>.(TaskValue<T>) -> Task<V>): Task<V> {
    return (this step listOf(
        TaskContinuation.OnValue(ifPred {
            pred { v ->
                v.isStableValue()
            }
            then(other)
        })
    ))
}

infix fun <T, V> Task<T>.seqNoShare(other: Task<V>): Task<V> {
    return (this seq { other })
}

infix fun <T, V> Task<T>.par(other: Task<V>): Task<Pair<TaskValue<T>, TaskValue<V>>> {
    // Initial value has Unstable stability since the current values of both tasks
    // cannot be used, as they may be restarted.
    val initialValue: TaskValue<Pair<TaskValue<T>, TaskValue<V>>> = unstableTaskValue(Pair(null, null))

    return TaskExecutor.createTask(initialValue) {
        var lhs = this@par
        var rhs = other

        val valueMutex = Mutex()

        // TODO: restarting tasks should be handled differently; what if a task is already started and will never update its value since it has a Stable task value, but it is not yet done running? is this an error in task design or should this be handled correctly in task combinators?
        lhs.subscribeWithoutInitialValue { v ->
            runBlocking {
                valueMutex.withLock {
                    this@createTask.value?.let { notNullValue ->
                        val value = notNullValue.copy(first = notNullValue.first.copy(first = v))

                        if (value.first.first.isStableValue() && value.first.second.isStableValue()) {
                            this@createTask.value = value.copy(second = Stable)
                        } else {
                            this@createTask.value = value
                        }
                    }
                }
            }
        }

        rhs.subscribeWithoutInitialValue { v ->
            runBlocking {
                valueMutex.withLock {
                    this@createTask.value?.let { notNullValue ->
                        val value = notNullValue.copy(first = notNullValue.first.copy(second = v))

                        if (value.first.first.isStableValue() && value.first.second.isStableValue()) {
                            this@createTask.value = value.copy(second = Stable)
                        } else {
                            this@createTask.value = value
                        }
                    }
                }
            }
        }

        lhs = lhs.start()
        rhs = rhs.start()

        suspendAndTunnelEvents(channel, listOf(lhs, rhs))

        destructionHandlers.forEach { this.it() }
        lhs.destructionHandlers.forEach { lhs.it() }
        rhs.destructionHandlers.forEach { rhs.it() }
    }
}

fun <T> MutableList<Task<T>>.par(): Task<MutableList<TaskValue<T>>> {
    val initialValue = Pair(
        MutableList<TaskValue<T>>(size) { null }, if (isEmpty()) Stable else Unstable
    )

    // Immediately start the new task
    return TaskExecutor.createTask(initialValue) {
        forEachIndexed { index, task ->
            task.subscribeWithoutInitialValue { v ->
                this@createTask.value?.let { notNullValue ->
                    println("set idx $index in ${notNullValue.first} to $v")
                    notNullValue.first[index] = v

                    if (notNullValue.first.all { tv -> tv.isStableValue() }) {
                        this@createTask.value = TaskValue(notNullValue.first, Stable)
                    }
                }
            }

            this@par[index] = task.start()
        }

        suspendAndTunnelEvents(channel, this@par)

        destructionHandlers.forEach { this.it() }
        this@par.forEach { task -> task.destructionHandlers.forEach { task.it() } }
    }
}

infix fun <T> Task<T>.parOr(rhs: Task<T>): Task<T> {
    return (this par rhs).trans { v ->
        var value: TaskValue<T> = null
        v?.let { notNullValue ->
            println("Transforming value $notNullValue")
            val firstValue = notNullValue.first.first.hasValue()
            val secondValue = notNullValue.first.second.hasValue()

            if (!firstValue && secondValue) {
                value = notNullValue.first.second
            } else if (firstValue && !secondValue) {
                value = notNullValue.first.first
            } else if (firstValue && secondValue) {
                val stability =
                    if (notNullValue.first.first.isStableValue() || notNullValue.first.second.isStableValue()) {
                        Stable
                    } else {
                        Unstable
                    }

                value = if (notNullValue.first.second.isStableValue()) {
                    notNullValue.first.second
                } else {
                    notNullValue.first.first!!.copy(second = stability)
                }
            }
        }

        println("Returning value $value")
        return@trans value
    }
}

infix fun <T, V> Task<T>.parAnd(rhs: Task<V>): Task<Pair<TaskValue<T>, TaskValue<V>>> {
    return (this par rhs).trans { v ->
        var value: TaskValue<Pair<TaskValue<T>, TaskValue<V>>> = null
        v?.let { notNullValue ->
            val bothStable = notNullValue.first.first.isStableValue() && notNullValue.first.second.isStableValue()
            if (notNullValue.first.first.hasValue() && notNullValue.first.second.hasValue()) {
                value = notNullValue.copy(second = if (bothStable) Stable else Unstable)
            }
        }

        return@trans value
    }
}

//fun enterInformation(transform: (String) -> String): Task<String> {
//    return TaskExecutor.createTask {
//        this.value = null
//
//        uiTask {
//            section {
//                input()
//            }.runUntilSignal {
//                onInputChanged {
//                    this@createTask.value = unstableTaskValue(transform(input))
//                    signal()
//                }
//            }
//        }.start()
//
//        while (true) {
//            val result = channel.receiveCatching()
//            val event = result.getOrNull() ?: break
//
//            if (event is UserKeyPressed) {
//                println("User pressed: ${event.key}")
//            }
//        }
//    }
//}

fun <T : Any> viewInformation(value: T): Task<T> {
    return if (value::class == String::class) {
        viewStringInformation(value as String) as Task<T>
    } else if (value::class.isSubclassOf(Number::class)) {
        println("value is ${value::class}")
        viewNumberInformation(value as Number) as Task<T>
    } else if (value::class.isSubclassOf(Display::class)) {
        viewDisplayInformation(value as Display) as Task<T>
    } else {
        println("viewInformation not implemented for ${value::class}")
        pure(value)
    }
}

fun viewDisplayInformation(value: Display): Task<Display> = viewGenericInformation(value, ::ViewDisplayValue)
fun viewStringInformation(value: String): Task<String> = viewGenericInformation(value, ::ViewStringValue)
fun viewNumberInformation(value: Number): Task<Number> = viewGenericInformation(value, ::ViewNumberValue)

fun <T> viewGenericInformation(value: T, f: @Composable (T) -> Unit): Task<T> {
    return TaskExecutor.createTask {
        if (value == null) {
            this.value = null
        } else {
            this.value = unstableTaskValue(value)
        }

        UI.add(this) {
            content {
                TaskRow(value, this@add, f)
            }
        }

        println("Suspending in view!")
        // Always return false. We only want it to stop when the user has requested the task to stop.
        suspendAndReceiveEvents(channel) { event ->
            when (event) {
                is Stop -> {
                    println("View received stop!")
                    destructionHandlers.forEach { this.it() }
                    true
                }

                is Action -> {
                    println("Received action: $event")
                    false
                }

                else -> {
                    false
                }
            }
        }
    }
}

fun <T> List<T>.copy(index: Int? = null, value: T? = null): List<T> {
    return List(size) { i ->
        if (index != null && value != null && i == index) {
            value
        } else {
            this[i]
        }
    }
}

fun <T : Any> updateInformation(value: List<T>): Task<List<T>> {
    return TaskExecutor.createTask(initialValue = unstableTaskValue(value)) {
        val valuesMutex = Mutex()
        value.forEachIndexed { i, v ->
            val task = updateInformation(v)
            task.subscribe {
                runBlocking {
                    valuesMutex.withLock {
                        this@createTask.value?.let { notNullValue ->
                            val list = notNullValue.first
                            this@createTask.value = unstableTaskValue(list.copy(i, it?.first))
                        }
                    }
                }
            }

            task.start()
        }
    }
}

// Cannot update information of null.
fun <T : Any> updateInformation(value: T): Task<T> {
    return if (value::class == String::class) {
        updateStringInformation(value as String) as Task<T>
    } else if (value::class.isSubclassOf(Number::class)) {
        println("value is ${value::class}")
        updateNumberInformation(value as Number) as Task<T>
    } else {
        println("updateInformation not implemented for ${value::class}")
        pure(value)
    }
}

fun updateStringInformation(value: String): Task<String> = updateGenericInformation(value, ::UpdateStringValue)
fun updateNumberInformation(value: Number): Task<Number> = updateGenericInformation(value, ::UpdateNumberValue)


fun <T> updateListInformation(
    value: Collection<T>,
    validator: (Int, TaskValue<Collection<T>>, CharSequence) -> Pair<Boolean, TaskValue<Collection<T>>>
): Task<Collection<T>> {
    return updateGenericInformation(value) { task ->
        { v ->
            Column {
                value.forEachIndexed { i, v ->
                    MaterialTheme {
                        OutlinedTextField(
                            state = rememberTextFieldState(initialText = v.toString()),
                            label = { Text("Label") },
                            outputTransformation = {
                                println("Using value: ${asCharSequence()}")
                                val result = validator(i, task.value, asCharSequence())
                                if (result.first) {
                                    task.value = result.second
                                }
                            })
                    }
                }
            }
        }
    }
}

//inline fun <reified T> updateListInformation(value: Collection<T>): Task<MutableList<TaskValue<T>>> {
//    val tasks = value.map { updateInformation(it) }.toMutableList()
//    return tasks.par()
//}

fun <T> updateGenericInformation(value: T, f: @Composable (Task<T>) -> @Composable (T) -> Unit): Task<T> {
    return TaskExecutor.createTask {
        if (value == null) {
            this.value = null
        } else {
            this.value = unstableTaskValue(value)
        }

        UI.add(this) {
            content {
                TaskRow(value, this@add, f(this@createTask))
            }
        }

        suspendAndReceiveEvents(channel) { event ->
            return@suspendAndReceiveEvents when (event) {
                is UserKeyPressed -> {
                    println("User pressed key: $event.key")
                    true
                }

                is Stop -> {
                    destructionHandlers.forEach { this.it() }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }
}

fun <T> pure(v: T): Task<T> {
    return TaskExecutor.createTask(stableTaskValue(v)) {}
}

fun start(task: Task<*>) {
    task.start()
}

fun start(tasks: Collection<Task<*>>) {
    tasks.forEach { task -> start(task) }
}
