package java.util.concurrent

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

  def defaultThreadFactory(): ThreadFactory = ???

  def newFixedThreadPool(nThreads: Int): ExecutorService = {
    new ThreadPoolExecutor(nThreads, nThreads,
      0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable]())
  }

}
