package net.bramw.kotlintopmp.ktop

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.bramw.kotlintopmp.ktop.ui.App
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import net.bramw.kotlintopmp.ktop.Event.*
import net.bramw.kotlintopmp.ktop.sds.ReadWriteShared
import net.bramw.kotlintopmp.ktop.sds.watch
import net.bramw.kotlintopmp.ktop.ui.Window
import net.bramw.kotlintopmp.ktop.ui.generateUI
import org.jetbrains.skia.TextBlob
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.reflect.KType

class SystemTasks(ts: TaskScope, ui: App) {
    internal val KType.hasUI: Boolean
        get() = this.classifier?.let { it != Nothing::class && it != Any::class && it != Unit::class } ?: false

    internal val runningTasks = mutableSetOf<TaskSignature>()

    @OptIn(ExperimentalAtomicApi::class)
    internal val runningTasksCount = AtomicInt(runningTasks.size)

//    @OptIn(ExperimentalAtomicApi::class)
//    internal val runningTasksCount = AtomicInt(0)

    private val displayedTasks: MutableMap<TaskSignature, Window> = mutableMapOf()

    @OptIn(ExperimentalAtomicApi::class)
    val runningTasksTask: Task<Set<TaskSignature>> = ts.createTask(name = "runningTasksTask") {
        println("task $id: Running tasks task started!")
        for (event in channel) {
            when (event) {
                is Stop -> handleStop {}
                is RunningTask.Add -> {
                    println("runningTasks: added ${event.signature}")
                    runningTasks.add(event.signature)
                    runningTasksCount.incrementAndFetch()
                    this.value = unstableValue(runningTasks.toSet())
                }

                is RunningTask.Remove -> {
                    println("runningTasks: removed ${event.signature}")
                    runningTasks.remove(event.signature)
                    runningTasksCount.decrementAndFetch()
                    this.value = unstableValue(runningTasks.toSet())
                }

                else -> {}
            }

            yield()
        }
    }

    // TODO: make this thread safe?
    val runningTaskValues = mutableStateMapOf<TaskId, Any?>()

    val valueCollectorTask: Task<Nothing> = ts.createTask(name = "valueCollectorTask") {
        for (event in channel) {
            when (event) {
                is ValueChanged<*> -> {
                    runningTaskValues[event.taskId] = event.value.asNullable()
                }
                else -> {}
            }
            yield()
        }
    }

    val uiBuilderTask = with(ts) {
        runningTasksTask trans {
            println("UI builder transformation called!")
            val newTasks = it subtract displayedTasks.keys
            val removedTasks = displayedTasks.keys subtract it
            // TODO: think about UI tasks started after stepping to that task -> they will send their own task signature instead of that of the step / parent (but the step / parent task is not available in the continuation, or should it be?)
            newTasks.forEach { sig ->
                // TODO: where to put this instead / how to handle value collecting instead of doing a runblocking here?
                runBlocking {
                    sig.task.subscribe(valueCollectorTask, emitNoValue = true)
                }

                println("classifier: ${sig.type.classifier}")
                if (sig.type.hasUI) {
                    println("Creating UI for $sig")
                    val window = Window(sig.task, { v ->
                        // TODO: remove once the circular dependency is solved in UI? dependency is created task needs to send its signature, but the signature depends on the created task
                        if (sig.task.started.value) {
                            println("$sig has started!")
                            generateUI(sig, v)
                        }
                    })
                    windowManagerTask.sendEvent(UIWindow.Add(window))
                    displayedTasks[sig] = window
                } else {
                    println("UI cannot be generated for task signature $sig")
                }
            }

            removedTasks.forEach { sig ->
                // TODO: where to put this instead / how to handle value collecting instead of doing a runblocking here?
                runBlocking {
                    sig.task.removeSubscriber(valueCollectorTask)
                }

                displayedTasks[sig]?.let { window ->
                    windowManagerTask.sendEvent(UIWindow.Remove(window))
                    displayedTasks.remove(sig)
                } ?: println("displayedTasks[$sig] does not exist")
            }

            println("it: $it, newTasks: $newTasks, removedTasks: $removedTasks, displayedTasks: ${displayedTasks.keys}")
        }
    }

    val windowManagerTask: Task<Nothing> = ts.createTask(name = "windowManagerTask") {
        println("task $id: Window manager task started!")
        for (event in channel) {
            when (event) {
                is Stop -> handleStop {}
                is UIWindow.Add -> {
                    println("Adding window: ${event.window}")
                    if (ui.windows.add(event.window)) {
                        println("Added window!")
                    } else {
                        println("Could not add window")
                    }
                }

                is UIWindow.Remove -> {
                    ui.windows.remove(event.window)
                }

                else -> {}
            }

            yield()
        }
    }

    internal val tasks = arrayOf(windowManagerTask, valueCollectorTask, runningTasksTask, uiBuilderTask)
}
