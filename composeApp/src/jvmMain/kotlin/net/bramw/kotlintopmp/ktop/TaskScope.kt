package net.bramw.kotlintopmp.ktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import net.bramw.kotlintopmp.ktop.Event.*
import net.bramw.kotlintopmp.ktop.ui.App
import net.bramw.kotlintopmp.ktop.ui.Window
import kotlin.concurrent.atomics.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.jvmErasure


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

suspend fun <T : Any> Task<T>.handleStop(block: suspend Task<T>.() -> Unit) {
    block()
    println("task $id: cancelling channel")
    channel.cancel()
}

fun highestParent(task: Task<*>): Task<*> {
    var t = task
    while (t.hasParent()) {
        t = task.parent
    }
    return t
}

fun climbParentsAndStop(task: Task<*>) {
    highestParent(task).sendEvent(Stop)
}

fun KType.serialize(allMembers: Boolean = false, v: Any? = null): String {
    val clazz = this@serialize.jvmErasure
    val properties = if (allMembers) clazz.memberProperties else clazz.declaredMemberProperties
    val base = buildString {
        append(this@serialize.serializeTypeArguments())
    }
    return if (properties.isEmpty()) {
        base
    } else {
        buildString {
            append(base)
            append("(")
            properties.forEachIndexed { index, it ->
                append(it.name)
                append("=")
                if (v != null) {
                    append(it.call(v))
                } else {
                    append(it.returnType.serialize(allMembers))
                }
                if (index < properties.size - 1) append(", ")
            }
            append(")")
        }
    }
}

internal fun KType.serializeTypeArguments(): String {
    val clazz = this.jvmErasure
    val base = buildString {
        append(clazz.simpleName ?: "Anonymous")
        if (this@serializeTypeArguments.isMarkedNullable) append("?")
    }

    return if (this.arguments.isEmpty()) {
        base
    } else {
        buildString {
            append(base)
            append("<")
            this@serializeTypeArguments.arguments.forEachIndexed { index, it ->
                append(clazz.typeParameters[index].name)
                append("=")
                it.type?.let { t ->
                    append(t.serializeTypeArguments())
                } ?: append("*")
                if (index < this@serializeTypeArguments.arguments.size - 1) append(", ")
            }
            append(">")
        }
    }
}

const val ENABLE_BORDERS = false

val textBorderModifier = Modifier.border(width = 1.dp, color = if (ENABLE_BORDERS) Color.Red else Color.Transparent)
val rowBorderModifier = Modifier.border(width = 1.dp, color = if (ENABLE_BORDERS) Color.Blue else Color.Transparent)
val columnBorderModifier = Modifier.border(width = 1.dp, color = if (ENABLE_BORDERS) Color.Green else Color.Transparent)

@Composable
fun TextWithBorder(modifier: Modifier, text: String) {
    Text(modifier = modifier, text = text)
}

@Composable
fun TextWithBorder(text: String) {
    TextWithBorder(textBorderModifier, text)
}

internal fun buildEnumString(v: Any, c: KClass<*>): String {
    return buildString {
        append(c.simpleName)
        append(".")
        val name: String? = c.memberProperties.find { it.name == "name" }?.let {
            it.call(v) as? String
        }
        append(name)
        if (c.declaredMemberProperties.isNotEmpty()) {
            append("(")
            c.declaredMemberProperties.forEachIndexed { index, prop ->
                append(prop.name)
                append("=")
                append(prop.call(v))
                if (index < c.declaredMemberProperties.size - 1) append(", ")
            }
            append(")")
        }
    }
}

@Composable
internal fun generateCollectionUI(type: KType, v: Any, c: KClass<*>) {
    val iteratorRef = c.declaredMemberFunctions.filter {
        it.valueParameters.isEmpty()
    }.find {
        it.name == "iterator"
    }
    val iterator = iteratorRef?.call(v) as Iterator<*>
    FlowColumn(modifier = columnBorderModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        iterator.asSequence().forEachIndexed { i, el ->
            el?.let {
                println("$i: ${it::class}")
                FlowRow(modifier = rowBorderModifier) {
                    val elType = type.arguments.last().type
                    elType?.let { type ->
                        recursiveGenerateView(type, it)
                    }
                }
                HorizontalDivider(thickness = 1.dp)
            }
            println("$i: $el")
        }

    }
}

@Composable
internal fun generateMapUI(type: KType, v: Any, c: KClass<*>) {
    // Two possible ways: list all entries row by row, with the key and value in a single row.
//    val entriesRef = c.declaredMemberProperties.find { it.name == "entries" }
//    entriesRef?.let {
//        val entries = it.call(v) as? Set<Map.Entry<*, *>>
//        entries?.forEachIndexed { i, entry ->
//            println("$i: $entry")
//            FlowRow(modifier = rowBorderModifier) {
//                val entryTypes = type.arguments.takeLast(2)
//                val keyType = entryTypes[0].type
//                keyType?.let { t ->
//                    recursiveGenerateView(t, entry.key)
//                } ?: TextWithBorder("Key $i does not have a type")
//                val valueType = entryTypes[1].type
//                valueType?.let { t ->
//                    recursiveGenerateView(t, entry.value)
//                } ?: TextWithBorder("Value $i does not have a type")
//            }
//            println("${entry.key} = ${entry.value}")
//        }
//    }
    // Or: list all keys in a column row by row and list all values in a column row by row. This makes sure that all the values start at the same X position on the screen.
    val keysRef = c.declaredMemberProperties.find { it.name == "keys" }
    val valuesRef = c.declaredMemberProperties.find { it.name == "values" }
    FlowRow(modifier = rowBorderModifier) {
        keysRef?.let {
            val keys = it.call(v) as? Set<*>
            FlowColumn(modifier = columnBorderModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keys?.forEachIndexed { i, key ->
                    println("key $i: $key")
                    val entryTypes = type.arguments.takeLast(2)
                    val keyType = entryTypes[0].type
                    keyType?.let { t ->
                        recursiveGenerateView(t, key)
                    } ?: TextWithBorder("Key $i does not have a type")
                }
            }
        }
        valuesRef?.let {
            val values = it.call(v) as? Collection<*>
            FlowColumn(modifier = columnBorderModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                values?.forEachIndexed { i, value ->
                    println("value $i: $value")
                    val entryTypes = type.arguments.takeLast(2)
                    val valueType = entryTypes[1].type
                    valueType?.let { t ->
                        recursiveGenerateView(t, value)
                    } ?: TextWithBorder("Key $i does not have a type")
                }
            }
        }
    }
}

@Composable
internal fun generateCustomUI(type: KType, v: Any, c: KClass<*>) {
    FlowColumn(
        modifier = columnBorderModifier,
//        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        c.declaredMemberProperties.forEach {
            val returnType = run {
                // KType.jvmErasure returns class kotlin.Any if it is called on a type parameter. Therefore, get the actual runtime type of the type parameter from the container KType and use that instead.
                if (it.returnType.classifier is KTypeParameter) {
                    val param = it.returnType.classifier as KTypeParameter
                    val i = c.typeParameters.indexOf(param)
                    if (i > -1 && i < type.arguments.size) {
                        return@run type.arguments[i].type
                            ?: it.returnType // If it is a star projection, it will display Any using its original return type
                    }
                }

                it.returnType
            }

            if (returnType.jvmErasure.javaPrimitiveType != null || returnType.jvmErasure.isSubclassOf(String::class)) {
                // Show primitive values inline
                FlowRow {
                    TextWithBorder("${it.name}: ${returnType.serializeTypeArguments()}")
                    val newV = it.call(v)
                    println("${it.name}=$newV")
                    recursiveGenerateView(returnType, newV)
                }
            } else {
                TextWithBorder("${it.name}: ${returnType.serializeTypeArguments()}")
                FlowRow(modifier = rowBorderModifier.padding(start = 10.dp)) {
                    val newV = it.call(v)
                    println("${it.name}=$newV")
                    recursiveGenerateView(returnType, newV)
                }
            }
        }
    }
}

@Composable
internal fun recursiveGenerateView(type: KType, value: Any?) {
    val base: @Composable () -> Unit = {
        val c = type.jvmErasure
        value?.let { v ->
            if (c.isSubclassOf(Enum::class) && c.java.isEnum) {
                // enum
                TextWithBorder(
                    modifier = textBorderModifier.padding(start = 10.dp), text = buildEnumString(v, c)
                )
            } else if (c.isSubclassOf(Map::class)) {
                // map
                generateMapUI(type, v, c)
            } else if (c.isSubclassOf(Collection::class)) {
                // TODO: can this be simplified further to a subclass of Iterable, since we only use the iterator?
                // collection: a list or a set or a similar collection
                generateCollectionUI(type, v, c)
            } else {
                if (c.javaPrimitiveType != null || c.isSubclassOf(String::class)) {
                    TextWithBorder(
                        modifier = textBorderModifier.padding(start = 10.dp), text = v.toString()
                    )
                } else {
                    generateCustomUI(type, v, c)
                }
                // TODO: make sure that spacing is correct for nested classes
                // builtin or custom class
            }
        } ?: TextWithBorder("Is null!")
    }

    return base()
}

@Composable
fun generateUI(sig: TaskSignature) {
    // Align the task UI and the stop button in a row.
    FlowRow(modifier = rowBorderModifier.padding(10.dp)) {
        // Align the task UI as a vertical column of elements.
        FlowColumn(modifier = columnBorderModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextWithBorder("${sig.task.taskName}: ${sig.type.serializeTypeArguments()}\t")
            recursiveGenerateView(sig.type, sig.task.value?.inner)
        }
        Button(onClick = { climbParentsAndStop(sig.task) }) {
            Text("Stop!")
        }
    }
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
        val t = Task(id, taskName, initialValue = initialValue, kind = kind, work = work)

        return t
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
        ts.start(ts.systemTasks.runningTasksTask, ts.systemTasks.windowManagerTask, ts.systemTasks.uiBuilderTask)
    }
}

class SystemTasks(ts: TaskScope, ui: App) {
    internal val runningTasks = mutableSetOf<TaskSignature>()

    @OptIn(ExperimentalAtomicApi::class)
    internal val runningTasksCount = AtomicInt(runningTasks.size)

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

    private val displayedTasks: MutableMap<TaskSignature, Window> = mutableMapOf()

    internal val KType.hasUI: Boolean
        get() = this.classifier?.let { it != Nothing::class && it != Any::class && it != Unit::class } ?: false

    val uiBuilderTask = with(ts) {
        runningTasksTask trans {
            println("UI builder transformation called!")
            val newTasks = it subtract displayedTasks.keys
            val removedTasks = displayedTasks.keys subtract it
            // TODO: think about UI tasks started after stepping to that task -> they will send their own task signature instead of that of the step / parent (but the step / parent task is not available in the continuation, or should it be?)
            newTasks.forEach { sig ->
                println("classifier: ${sig.type.classifier}")
                if (sig.type.hasUI) {
                    println("Creating UI for $sig")
                    val window = Window(sig.task, {
                        // TODO: remove once the circular dependency of is solved in UI? dependency is created task needs to send its signature, but the signature depends on the created task
                        if (sig.task.started.value) {
                            println("$sig has started!")
                            generateUI(sig)
                        }
                    })
                    windowManagerTask.sendEvent(UIWindow.Add(window))
                    displayedTasks[sig] = window
                } else {
                    println("UI cannot be generated for task signature $sig")
                }
            }

            removedTasks.forEach { sig ->
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

}