package net.bramw.kotlintopmp.ktop

import kotlinx.coroutines.*
import net.bramw.kotlintopmp.ktop.sds.set
import net.bramw.kotlintopmp.ktop.ui.App
import kotlin.concurrent.atomics.*
import kotlin.reflect.typeOf


suspend fun taskScope(block: suspend TaskScope.() -> Unit) {
    val tScope = TaskScope()
    val uiScope = UIScope(tScope)
    // TODO: should this be started here or not?
    uiScope.startUITasks()
    tScope.block()
    // Wait until all children jobs have completed
    tScope.scope.coroutineContext.job.children.toList().joinAll()
    // Stop all UI or other administrative tasks
    // TODO: only for testing - remove afterwards (https://stackoverflow.com/questions/28754627/check-whether-we-are-in-intellij-idea-debugger)
//    val isDebug: Boolean = ManagementFactory.getRuntimeMXBean().inputArguments.toString().indexOf("jdwp") >= 0
//    if (isDebug) {
//        uiScope.stopUITasks()
//        tScope.uiTasks.forEach {
//            if (!it.hasParent()) {
//                it.sendEvent(Stop)
//            }
//        }
//    }
    uiScope.scope.coroutineContext.job.children.toList().joinAll()
}


class TaskScope {
    @OptIn(ExperimentalAtomicApi::class)
    internal var nextTaskId: AtomicInt = AtomicInt(0)
    val scope = CoroutineScope(Dispatchers.Default)

    val ui = App(this)
    val systemTasks = SystemTasks(this, ui)

    @OptIn(ExperimentalAtomicApi::class)
    fun <T : Any> createTask(
        initialValue: Value<T>? = null, kind: TaskKind = TaskKind.Custom, uiTask: Boolean = false, name: String? = null, work: WorkFunction<T>
    ): Task<T> {
        val taskName = name ?: run {
            val stackTrace: StackTraceElement = Thread.currentThread().stackTrace.firstOrNull { it.fileName == "main.kt" }
                ?: Thread.currentThread().stackTrace[3]
            "${stackTrace.methodName} - ${stackTrace.fileName}:${stackTrace.lineNumber}"
        }
        // TODO: remove eventually
        val id = nextTaskId.fetchAndIncrement()
        println("Created task $id: $taskName")

        return Task(id, taskName, initialValue = initialValue, kind = kind, work = work)
    }

    suspend fun start(vararg tasks: Task<*>) {
        tasks.map {
            scope.launch(CoroutineName("start task: ${it.taskName}")) {
                it.start(scope)
            }
        }.joinAll() // Wait until all tasks have been started
    }
}

class UIScope(val ts: TaskScope) {
    internal val scope = CoroutineScope(Dispatchers.Main)

    suspend fun startUITasks() {
        ts.ui.applicationTask.start(scope)
        ts.start(*ts.systemTasks.tasks)
    }
}