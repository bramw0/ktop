package solution12

import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/*
 Executor environment to automatically execute tasks in parallel.

 This is used to facilitate the continuous execution of tasks, by giving each task
 its own coroutine. The task can update its value and notify observers of its new value.
 */
data object TaskExecutor {
    @OptIn(ExperimentalAtomicApi::class)
    val launchedJobs: AtomicInt = AtomicInt(0)
    val scope = CoroutineScope(Dispatchers.Default)
    private var currentJob: Job

    init {
        currentJob = launch {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                println("Cancellation exception!")
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun decrementLaunchedJobs(t: Throwable?) {
        val v = launchedJobs.decrementAndFetch()
        if (v == 0) {
            println("cancelling scope")
            scope.cancel()
        }
        println("after decrement: $v")
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun incrementLaunchedJobs() {
        val v = launchedJobs.incrementAndFetch()
        println("after increment: $v")
    }

    suspend fun join() {
        currentJob.cancel()
        scope.coroutineContext.job.join()
    }

    fun <T> launchTask(t: Task<T>): Job {
        val j = launch(start = CoroutineStart.LAZY) {
            t.evaluate()
        }
        println("Launched ${t.fullID(j)}")
        return j
    }

    fun launch(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = start, block = block)
        incrementLaunchedJobs()
        job.invokeOnCompletion(::decrementLaunchedJobs)
        return job
    }

    fun <T> createTask(initialValue: TaskValue<T> = null, parentTask: Task<*>? = null, work: TaskWorkFunction<T>): Task<T> {
        val taskName = Thread.currentThread().stackTrace[3].methodName
        val t = Task(taskName, initialValue, work)
        parentTask?.let { t.parent = it }
        t.handle = launchTask(t)

        return t
    }

    fun <T> startTask(initialValue: TaskValue<T> = null, work: TaskWorkFunction<T>): Task<T> {
        val t = createTask(initialValue = initialValue, work = work)
        start(t)
        return t
    }

    fun execute(block: suspend TaskExecutor.() -> Unit) {
        launch {
            this@TaskExecutor.block()
        }
    }
}
