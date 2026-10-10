package ca.pkay.rcloneexplorer.workmanager

import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** TERM permits rclone cleanup; the second stage bounds a stuck child's lifetime. */
internal class ProcessDeadline(private val process:Process, runtimeMillis:Long, private val expired:()->Unit, private val graceMillis:Long=10_000):AutoCloseable {
    private val timer=Timer("rclone-deadline",true)
    private val stopping=AtomicBoolean(false)
    private val deadlineTask=object:TimerTask(){ override fun run(){ expired(); stop() } }
    init { require(runtimeMillis>0); timer.schedule(deadlineTask,runtimeMillis) }
    fun stop() {
        if(!stopping.compareAndSet(false,true)) return
        process.destroy()
        timer.schedule(object:TimerTask(){ override fun run(){
            try { process.exitValue() } catch(alive:IllegalThreadStateException) {
                // Use reflection: destroyForcibly is absent on older supported Android releases.
                runCatching { Process::class.java.getMethod("destroyForcibly").invoke(process) }
                    .onFailure { process.destroy() }
            } finally { timer.cancel() }
        } },graceMillis)
    }
    override fun close() {
        deadlineTask.cancel()
        try { process.exitValue(); timer.cancel() }
        catch(alive:IllegalThreadStateException) { stop() }
    }
}
