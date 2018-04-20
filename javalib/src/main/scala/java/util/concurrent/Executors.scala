package java.util.concurrent

import java.util.concurrent.atomic.AtomicInteger

// Ported from Harmony

class Executors {}

object Executors {

  def callable[T](task: Runnable, result: T): Callable[T] = {
    if (task == null)
      throw new NullPointerException()
    new RunnableAdapter[T](task, result)
  }

  final class RunnableAdapter[T](val task: Runnable, val result: T)
      extends Callable[T] {

    override def call(): T = {
      task.run()
      result
    }

  }

  def defaultThreadFactory(): ThreadFactory = new DefaultThreadFactory()


  def newFixedThreadPool(nThreads: Int): ExecutorService = {
    new ThreadPoolExecutor(nThreads, nThreads,
      0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable]())
  }

  class DefaultThreadFactory extends ThreadFactory {
    val group = Thread.currentThread().getThreadGroup
    val threadNumber = new AtomicInteger(1)
    val namePrefix = "pool-" +
      DefaultThreadFactory.poolNumber.getAndIncrement() +
      "-thread-"

    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(group, r,
        namePrefix + threadNumber.getAndIncrement(),
        0)
      if (thread.isDaemon)
        thread.setDaemon(false)
      if (thread.getPriority != Thread.NORM_PRIORITY)
        thread.setPriority(Thread.NORM_PRIORITY)
      thread
    }
  }

  object DefaultThreadFactory {
    val poolNumber = new AtomicInteger(1)
  }

}
