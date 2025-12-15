package net.bramw.kotlintopmp

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import solution12.TaskAction
import solution12.TaskContinuation
import solution12.TaskExecutor
import solution12.hasValue
import solution12.ifPred
import solution12.parAnd
import solution12.stableTaskValue
import solution12.start
import solution12.step
import solution12.unstableTaskValue
import solution12.updateInformation
import solution12.updateStringInformation
import solution12.viewInformation

data class Person(val fname: String, val lname: String) : Display {
    override fun display(): String {
        return "My name is $fname $lname"
    }
}

fun main() {
    with(TaskExecutor) {
        execute {
            val t6 = createTask {
                value = unstableTaskValue("Der")
                delay(1000)
                value = null
                delay(1000)
                value = stableTaskValue("Die")
            }

            val t7 = createTask {
                value = unstableTaskValue("Kip")
                delay(2000)
                value = stableTaskValue("Lip")
            }

            val t8 = t6 parAnd t7
            t8.subscribe {
                println("t8: New value: $it")
            }
            val p = Person("Bram", "Weessies")

            val info = viewInformation(p) step listOf(
                TaskContinuation.OnAction(
                    TaskAction.ActionContinue,
                    ifPred {
                        pred {
                            it.hasValue()
                        }
                        then { v ->
                            v?.let {
                                viewInformation(it.first.copy(lname = "Weeshuis"))
                            } ?: run {
                                viewInformation(p)
                            }
                        }
                    }
                )
            )

            val text = updateInformation("Hey")
            val l = updateInformation(listOf("Je", "moeder"))

            start(listOf(info, t8, text, l))
        }

        println("Done with execution")

        runBlocking {
            join()
        }
    }
}