package java.util.concurrent

trait RejectedExecutionHandler {
  def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit
}
