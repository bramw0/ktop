package net.bramw.kotlintopmp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import kotlinx.coroutines.runBlocking
import solution12.Task
import solution12.TaskAction
import solution12.TaskEvent
import solution12.TaskEvent.UserKeyPressed
import solution12.TaskExecutor
import solution12.TaskValue
import solution12.fullID
import solution12.hasValue
import solution12.isNoValue
import solution12.unstableTaskValue

interface Display {
    fun display(): String
}

@Composable
fun <T> ViewGenericValue(value: T, f: (T) -> String = { it.toString() }) {
    MaterialTheme {
        Text(f(value))
    }
}

@Composable
fun ViewDisplayValue(value: Display) = ViewGenericValue(value) { it.display() }

@Composable
fun ViewStringValue(value: String) = ViewGenericValue(value) { it }

@Composable
fun ViewNumberValue(value: Number) = ViewGenericValue(value)

@Composable
fun UpdateStringValue(task: Task<String>) = @Composable { value: String ->
    UpdateGenericValue(value, task, { oldValue, newValue ->
        Pair(true, oldValue?.copy(first = newValue.toString()))
    }) { it }
}

@Composable
fun UpdateNumberValue(task: Task<Number>) = @Composable { value: Number ->
    // TODO: do not match on Number interface, but match on actual class (Int or Float or etc) instead
    UpdateGenericValue(value, task, { oldValue, newValue ->
        Pair(true, oldValue?.copy(first = newValue.toString().toInt()))
    })
}

@Composable
fun <T> UpdateGenericValue(value: T, task: Task<T>, validator: (TaskValue<T>, CharSequence) -> Pair<Boolean, TaskValue<T>>, f: (T) -> String = { it.toString() }) {
    MaterialTheme {
        OutlinedTextField(
            state = rememberTextFieldState(initialText = f(value)),
            label = { Text("Label") },
            outputTransformation = {
                println("Using value: ${asCharSequence()}")
                val result = validator(task.value, asCharSequence())
                if (result.first) {
                    task.value = result.second
                }
            }
        )
    }
}

@Composable
fun <T> TaskRow(value: T, window: UI.UIWindow, f: @Composable (T) -> Unit) {
    window.task.registerDestructionHandler {
        println("Running handler remove window for task ${fullID()}")
        UI.removeWindow(window)
    }

    Row {
        Button(
            onClick = {
                println("CONTINUING")
                runBlocking {
                    window.task.sendEvent(TaskEvent.Action(TaskAction.ActionContinue))
                }
            }
        ) {
            Text("C")
        }
        Button(
            onClick = {
                println("REMOVING")
                runBlocking {
                    window.task.sendEvent(TaskEvent.Stop)
                }
            }
        ) {
            Text("R")
        }
        f(value)
    }
}

typealias KeyPressListener = (KeyEvent) -> UI.KeyPressResult

object UI {

    private fun wrapWithShow(block: () -> Unit) {
        show()
        block()
    }

    class UIWindow(
        val task: Task<*>,
        block: UIWindow.() -> Unit,
    )
    {
        lateinit var content: @Composable LazyItemScope.() -> Unit
        var keyPressListener: KeyPressListener? = null

        init {
            apply(block)
        }

        fun content(block: @Composable LazyItemScope.() -> Unit) {
            this.content = block
        }

        fun keyPressListener(block: KeyPressListener) {
            this.keyPressListener = block
        }
    }

    data class KeyPressResult(val handled: Boolean, val removeListener: Boolean)

    private lateinit var task: Task<Nothing>

    private val windows = mutableStateListOf<UIWindow>()
    private val isOpen = mutableStateOf(true)

    private var shown = false

    private val keyPressListeners = mutableListOf<KeyPressListener>().apply {
        add { event ->
            if (event.type == KeyEventType.KeyUp && (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.Q)
                && windows.isEmpty()) {
                println("No longer open!")
                isOpen.value = false
                KeyPressResult(handled = true, removeListener = true)
            }
            KeyPressResult(handled = false, removeListener = false)
        }
    }

    fun removeWindow(window: UIWindow) {
        windows.remove(window)
        runBlocking {
            window.task.sendEvent(TaskEvent.Stop)
        }
    }

    fun addKeyPressListener(listener: KeyPressListener) {
        wrapWithShow {
            keyPressListeners.add(listener)
        }
    }

    fun add(task: Task<*>, block: UIWindow.() -> Unit) {
        val window = UIWindow(task, block)
        window.keyPressListener?.let { listener ->
            addKeyPressListener(listener)
        }
        wrapWithShow {
            windows.add(window)
        }
    }

    fun show() {
        if (!shown) {
            shown = true

            task = TaskExecutor.startTask {
                awaitApplication {
                    if (isOpen.value) {
                        Window(
                            onCloseRequest = ::exitApplication, onKeyEvent = { event ->
                                val removedListeners = mutableListOf<KeyPressListener>()

                                val handled = keyPressListeners.map {
                                    val result = it(event)
                                    if (result.removeListener) removedListeners.add(it)
                                    result.handled
                                }.all {
                                    it
                                }
                                keyPressListeners.removeAll(removedListeners)
                                handled
                            }) {
                            LazyColumn {
                                items(windows) { window ->
                                    window.content(this)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


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