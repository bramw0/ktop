package net.bramw.kotlintopmp

import kotlinx.coroutines.delay
import net.bramw.kotlintopmp.ktop.*
import kotlin.time.Duration.Companion.milliseconds

val log: (Event.ValueChanged<*>) -> Value<*> = {
    println("task\t${it.taskId}\tvalue changed to ${it.value}")
    it.value
}


suspend fun main() {
    taskScope {
        val t1 = createTask {
            value = unstableValue("Der")
            delay(100.milliseconds)
            value = null
            delay(100.milliseconds)
            value = stableValue("Die")
        }

        val s = t1 step listOf(
            onValue {
                pred {
                    it.isStableValue()
                }
                then { v ->
                    createTask {
                        value = stableValue(v!!.first + " HAHAHA")
                    }
                }
            },
        )

        val t2 = createTask {
            value = unstableValue("WWW")
            delay(50.milliseconds)
            value = null
            delay(150.milliseconds)
            value = stableValue("XXX")
        }

        val t3 = t1 trans {
            it.value?.let { v ->
                if (v.isStableValue()) stableValue(v.first + " stable!") else unstableValue(v.first + " transformed!")
            } ?: run {
                unstableValue("DWA")
            }
        }

        val t4 = t1 seq {
            createTask {
                value = stableValue(it!!.first + " HAHAHA")
            }
        }

        val parallel = t1 and t2

        val or = t1 or t2

        start(s trans log, parallel trans log, t3 trans log, or trans log, t4 trans log)
    }

    taskScope {
        val d = get(currentDate)

        start(d trans log)
    }
}