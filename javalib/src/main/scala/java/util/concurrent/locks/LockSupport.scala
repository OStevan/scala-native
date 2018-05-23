package java.util
package concurrent.locks

private class LockSupport {}

object LockSupport {

  // initial implementation of LockSupport based on Java 1.5
  // for methods added in 1.6 need to change the Thread implementation
  // for now keep it as it is

  def unpark(thread: Thread): Unit = {
    if (thread != null)
      thread.threadUnpark()
  }

  def park(): Unit = {
    val thread = Thread.currentThread()
    thread.threadPark()
  }

  def parkNanos(nanos: Long): Unit = ???

  def parkUntil(deadline: Long): Unit = ???

  def park(obj: Object): Unit = ???

  def parkNanos(obj: Object, nanos: Long): Unit = ???

  def parkUntil(obj: Object, nanos: Long): Unit = ???

}
