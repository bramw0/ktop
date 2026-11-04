package net.bramw.kotlintopmp.ktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.bramw.kotlintopmp.ktop.*
import net.bramw.kotlintopmp.ktop.sds.ReadWriteShared
import net.bramw.kotlintopmp.ktop.sds.watch
import net.bramw.kotlintopmp.ktop.sds.withShared
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
        t.runningTasksHandler = { systemTasks.runningTasksTask.sendEvent(Event.RunningTask.Remove(signature)) }
        // TODO: this will only send the watch(sds) task to the running task list; should this be transformed to the highest parent? But the parent is not yet available in the step if it is created in a continuation...
        systemTasks.runningTasksTask.sendEvent(Event.RunningTask.Add(signature))
    }
}

typealias UIContentFun = @Composable LazyItemScope.() -> Unit
data class Window(val task: Task<*>, val content: UIContentFun)

class App(val ts: TaskScope) {
    private var started = false
    private val startedMutex = Mutex(false)
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
        val scope = rememberCoroutineScope()
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
                        items(windows, key = { it.task.id }) { it.content(this) }
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
        startedMutex.withLock {
            this@App.started = false
        }

        for (event in channel) {
            if (event is Event.Stop) handleStop {}
        }
    }


    suspend fun TaskScope.show() {
        startedMutex.lock()
        if (!started) {
            started = true
            startedMutex.unlock()
            start(applicationTask)
            println("Started task!")
        } else {
            startedMutex.unlock()
        }
    }
}


//interface Display {
//    fun display(): String
//}
//
//@Composable
//fun <T> ViewGenericValue(value: T, f: (T) -> String = { it.toString() }) {
//    MaterialTheme {
//        Text(f(value))
//    }
//}
//
//@Composable
//fun ViewDisplayValue(value: Display) = ViewGenericValue(value) { it.display() }
//
//@Composable
//fun ViewStringValue(value: String) = ViewGenericValue(value) { it }
//
//@Composable
//fun ViewNumberValue(value: Number) = ViewGenericValue(value)
//
//@Composable
//fun UpdateStringValue(task: Task<String>) = @Composable { value: String ->
//    UpdateGenericValue(value, task, { oldValue, newValue ->
//        Pair(true, oldValue?.copy(first = newValue.toString()))
//    }) { it }
//}
//
//@Composable
//fun UpdateNumberValue(task: Task<Number>) = @Composable { value: Number ->
//    // TODO: do not match on Number interface, but match on actual class (Int or Float or etc) instead
//    UpdateGenericValue(value, task, { oldValue, newValue ->
//        Pair(true, oldValue?.copy(first = newValue.toString().toInt()))
//    })
//}
//
//@Composable
//fun <T> UpdateGenericValue(
//    value: T,
//    task: Task<T>,
//    validator: (Value<T>, CharSequence) -> Pair<Boolean, Value<T>>,
//    f: (T) -> String = { it.toString() }
//) {
//    MaterialTheme {
//        OutlinedTextField(
//            state = rememberTextFieldState(initialText = f(value)),
//            label = { Text("Label") },
//            outputTransformation = {
//                println("Using value: ${asCharSequence()}")
//                val result = validator(task.value, asCharSequence())
//                if (result.first) {
//                    task.value = result.second
//                }
//            })
//    }
//}
//
//@Composable
//fun <T> TaskRow(value: T, window: UI.UIWindow, f: @Composable (T) -> Unit) {
//    window.task.registerDestructionHandler {
//        println("Running handler remove window for task ${fullID()}")
//        UI.removeWindow(window)
//    }
//
//    Row {
//        Button(
//            onClick = {
//                println("CONTINUING")
//                runBlocking {
//                    window.task.sendEvent(Event.Action(UserAction.Continue))
//                }
//            }) {
//            Text("C")
//        }
//        Button(
//            onClick = {
//                println("REMOVING")
//                runBlocking {
//                    window.task.sendEvent(Event.Stop)
//                }
//            }) {
//            Text("R")
//        }
//        f(value)
//    }
//}
//
//typealias KeyPressListener = (KeyEvent) -> UI.KeyPressResult
//
//object UI {
//
//    private fun wrapWithShow(block: () -> Unit) {
//        show()
//        block()
//    }
//
//    class UIWindow(
//        val task: Task<*>,
//        block: UIWindow.() -> Unit,
//    ) {
//        lateinit var content: @Composable LazyItemScope.() -> Unit
//        var keyPressListener: KeyPressListener? = null
//
//        init {
//            apply(block)
//        }
//
//        fun content(block: @Composable LazyItemScope.() -> Unit) {
//            this.content = block
//        }
//
//        fun keyPressListener(block: KeyPressListener) {
//            this.keyPressListener = block
//        }
//    }
//
//    data class KeyPressResult(val handled: Boolean, val removeListener: Boolean)
//
//    private lateinit var task: Task<Nothing>
//
//    private val windows = mutableStateListOf<UIWindow>()
//    private val isOpen = mutableStateOf(true)
//
//    private var shown = false
//
//    private val keyPressListeners = mutableListOf<KeyPressListener>().apply {
//        add { event ->
//            if (event.type == KeyEventType.KeyUp && (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.Q) && windows.isEmpty()) {
//                println("No longer open!")
//                isOpen.value = false
//                KeyPressResult(handled = true, removeListener = true)
//            }
//            KeyPressResult(handled = false, removeListener = false)
//        }
//    }
//
//    fun removeWindow(window: UIWindow) {
//        windows.remove(window)
//        runBlocking {
//            window.task.sendEvent(Event.Stop)
//        }
//    }
//
//    fun addKeyPressListener(listener: KeyPressListener) {
//        wrapWithShow {
//            keyPressListeners.add(listener)
//        }
//    }
//
//    fun add(task: Task<*>, block: UIWindow.() -> Unit) {
//        val window = UIWindow(task, block)
//        window.keyPressListener?.let { listener ->
//            addKeyPressListener(listener)
//        }
//        wrapWithShow {
//            windows.add(window)
//        }
//    }
//
//    fun show() {
//        if (!shown) {
//            shown = true
//
////            task = TaskExecutor.startTask {
////                awaitApplication {
////                    if (isOpen.value) {
////                        Window(
////                            onCloseRequest = ::exitApplication, onKeyEvent = { event ->
////                                val removedListeners = mutableListOf<KeyPressListener>()
////
////                                val handled = keyPressListeners.map {
////                                    val result = it(event)
////                                    if (result.removeListener) removedListeners.add(it)
////                                    result.handled
////                                }.all {
////                                    it
////                                }
////                                keyPressListeners.removeAll(removedListeners)
////                                handled
////                            }) {
////                            LazyColumn {
////                                items(windows) { window ->
////                                    window.content(this)
////                                }
////                            }
////                        }
////                    }
////                }
////            }
//        }
//    }
//}


//                println("Running forEach")
//                windows.forEach { window ->
//                    println("Showing window $window")
//                    if (window.isOpen) {
//                        Window(
//                            onCloseRequest = { window.isOpen = false },
//                            title = window.title,
//                            content = window.block,
//                            onKeyEvent = { event ->
//                                if (event.type == KeyEventType.KeyUp && (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.Q)) {
//                                    runBlocking {
//                                        window.task.sendEvent(UserKeyPressed(event.key))
//                                    }
//                                    window.isOpen = false
//                                }
//                                println(event)
//                                false
//                            }
//                        )
//                    }
//                }


//@Composable
//@Preview
//fun App(channel: SendChannel<TaskEvent>, onClick: () -> Unit) {
//    MaterialTheme {
//        var showContent by remember { mutableStateOf(false) }
//        Column(
//            modifier = Modifier
//                .background(MaterialTheme.colorScheme.primaryContainer)
//                .safeContentPadding()
//                .fillMaxSize(),
//            horizontalAlignment = Alignment.CenterHorizontally,
//        ) {
//            Button(onClick = {
//                onClick()
//                showContent = !showContent
//                runBlocking {
//                    channel.send(TaskEvent.UserKeyPressed("Q"))
//                }
//            }) {
//                Text("Click me!")
//            }
//            AnimatedVisibility(showContent) {
//                val greeting = remember { Greeting().greet() }
//                Column(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalAlignment = Alignment.CenterHorizontally,
//                ) {
//                    Image(painterResource(Res.drawable.compose_multiplatform), null)
//                    Text("Compose: $greeting")
//                }
//            }
//        }
//    }
//}
