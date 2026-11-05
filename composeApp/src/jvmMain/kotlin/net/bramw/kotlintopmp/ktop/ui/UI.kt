package net.bramw.kotlintopmp.ktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import net.bramw.kotlintopmp.ktop.*
import net.bramw.kotlintopmp.ktop.sds.ReadWriteShared
import net.bramw.kotlintopmp.ktop.sds.watch
import net.bramw.kotlintopmp.ktop.sds.withShared
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf
import dev.nucleusframework.composenativetray.tray.api.Tray as NucleusTray

// TODO: convert to generic version as every viewInformation will have the same content except for Int::class

//fun <T : Any> TaskScope._baseViewInformation(value: T, signature: TaskSignature<T>): Task<T> {
//    return withShared(value) {
//        viewSharedInformation(it).also { t ->
//            t.signature = signature.apply { task = t}
//            t.runningTasksHandler = { runningTasksTask.sendEvent(Event.RemoveRunningTask(t.signature)) }
//            runningTasksTask.sendEvent(Event.AddRunningTask(t.signature))
//        }
//    }
//}
//
//fun TaskScope.viewInformation(value: Int): Task<Int> {
//    return _baseViewInformation(value, TaskSignature.SingleTaskSignature(Int::class))
//}
//
//fun TaskScope.viewInformation(value: String): Task<String> {
//    return _baseViewInformation(value, TaskSignature.SingleTaskSignature(String::class))
//}
//
//inline fun <reified T : Any> TaskScope.viewInformation(value: Collection<T>): Task<Collection<T>> {
//    return _baseViewInformation(value, TaskSignature.CollectionTaskSignature(T::class))
//}
//
//inline fun <reified K : Any, reified V : Any> TaskScope.viewInformation(value: Map<K, V>): Task<Map<K, V>> {
//    return _baseViewInformation(value, TaskSignature.MapTaskSignature(K::class, V::class))
//}
//
//inline fun <reified A : Any, reified B : Any> TaskScope.viewInformation(value: Pair<A, B>): Task<Pair<A, B>> {
//    return _baseViewInformation(value, TaskSignature.PairTaskSignature(A::class, B::class))
//}

inline fun <reified T : Any> TaskScope.viewInformation(value: T): Task<T> {
    return withShared(value) {
        viewSharedInformation<T>(it)
    }
}

// TODO: somehow change this such that the running task is only sent when the task is actually started, but such that it also contains the correct task in its signature (circular dependency?)
inline fun <reified R : Any> TaskScope.viewSharedInformation(sds: ReadWriteShared<R, *>): Task<R> {
    return watch(sds).also { t ->
        val signature = TaskSignature(typeOf<R>(), t)
        t.runningTasksHandler = {
            systemTasks.runningTasksTask.sendEvent(Event.RunningTask.Remove(signature))
        }
        // TODO: this will only send the watch(sds) task to the running task list; should this be transformed to the highest parent? But the parent is not yet available in the step if it is created in a continuation...
        systemTasks.runningTasksTask.sendEvent(Event.RunningTask.Add(signature))
    }
}

// Window cannot be generic since it is instantiated dynamically; therefore the type of value cannot be known at compile time either.
typealias UIContentFun = @Composable (value: Any?) -> Unit
data class Window(val task: Task<*>, val content: UIContentFun)

class App(val ts: TaskScope) {
    private val isVisible = mutableStateOf(true)
    private val wantsToQuit = mutableStateOf(false)
    internal val windows = mutableStateListOf<Window>()

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
            isVisible.value = false
            return true
        }
        return false
    }

    private fun ApplicationScope.exit() {
//        windows.forEach {
//            it.task.sendEvent(Event.Stop)
//        }
        exitApplication()
    }

    private val trayIcon = object : Painter() {
        override val intrinsicSize = Size(256f, 256f)

        override fun DrawScope.onDraw() {
            drawOval(Color.Green)
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Composable
    context(t: Task<Nothing>)
    fun ApplicationScope.DisplayApp() = with(t) {
        LaunchedEffect(Unit) {
            for (event in channel) {
                if (event is Event.Stop) handleStop {
                    println("UI received stop, exiting")
                    exitApplication()
                }
            }
        }
        if (!wantsToQuit.value) {
            Window(onCloseRequest = {
                val count = ts.systemTasks.runningTasksCount.load()
                if (count > 0) isVisible.value = false
                else exitApplication()
            }, visible = isVisible.value, onPreviewKeyEvent = ::handleKeyEvent) {
                var remindRunningTasks by remember { mutableStateOf(false) }

                Column {
                    Button(onClick = {
                        val count = ts.systemTasks.runningTasksCount.load()
                        if (count > 0) remindRunningTasks = true
                        else wantsToQuit.value = true }) {
                        Text("Quit")
                    }

                    if (remindRunningTasks) {
                        DialogWindow(onCloseRequest = {
                            remindRunningTasks = false
                        }, title = "Cannot quit with still running tasks") {
                            Row {
                                Text("Cannot quit with still running tasks")
                                Button(
                                    onClick = { remindRunningTasks = false }
                                ) {
                                    Text("Close dialog")
                                }
                                Button(
                                    onClick = { remindRunningTasks = false; isVisible.value = false}
                                ) {
                                    Text("Put application in tray")
                                }
                            }
                        }
                    }

                    Button(onClick = {
                        println(windows)
                        println(windows.size)
                    }) {
                        Text("Test")
                    }
                    Text(windows.size.toString())

                    LazyColumn {
                        items(windows, key = { it.task.id }) {
                            val value = ts.systemTasks.runningTaskValues.getOrDefault(it.task.id, null)
                            it.content(value)
                        }
                    }

                }
            }

            if (!isVisible.value) {
                if (isTraySupported) {
                    Tray(trayIcon, tooltip = "KTop", menu = {
                        Item("Open", onClick = { isVisible.value = true })
                        Item("Exit", onClick = ::exitApplication)
                    })
                } else {
                    NucleusTray(
                        trayIcon,
                        tooltip = "KTop",
                    ) {
                        Item("Open", onClick = { isVisible.value = true })
                        Item("Exit", onClick = ::exitApplication)
                    }
                }
            }
        } else {
            exitApplication()
        }
    }

    val applicationTask: Task<Nothing> = ts.createTask(name = "applicationTask") {
        awaitApplication {
            DisplayApp()
        }

        println("Exiting main window task")

        for (event in channel) {
            if (event is Event.Stop) handleStop {}
        }
    }
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
fun generateUI(sig: TaskSignature, value: Any?) {
    // Align the task UI and the stop button in a row.
    FlowRow(modifier = rowBorderModifier.padding(10.dp)) {
        // Align the task UI as a vertical column of elements.
        FlowColumn(modifier = columnBorderModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextWithBorder("${sig.task.taskName}: ${sig.type.serializeTypeArguments()}\t")
            recursiveGenerateView(sig.type, value)
        }
        Button(onClick = { sig.task.climbParentsAndStop() }) {
            Text("Stop!")
        }
    }
}
