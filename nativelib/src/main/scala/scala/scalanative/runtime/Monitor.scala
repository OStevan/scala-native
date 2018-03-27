package scala.scalanative.runtime

import scala.scalanative.native._
import scala.scalanative.native.stdlib.malloc
import scala.scalanative.posix.errno.{EBUSY, EPERM}
import scala.scalanative.posix.pthread._
import scala.scalanative.posix.sys.time.{
  CLOCK_REALTIME,
  clock_gettime,
  timespec
}
import scala.scalanative.posix.sys.types.{
  pthread_cond_t,
  pthread_condattr_t,
  pthread_mutex_t,
  pthread_mutexattr_t
}
import scala.scalanative.runtime.ThreadBase._

final class Monitor private[runtime] (shadow: Boolean) {

  // memory leak possible solution is to call free in finalize
  private val mutexPtr: Ptr[pthread_mutex_t] = malloc(pthread_mutex_t_size)
    .asInstanceOf[Ptr[pthread_mutex_t]]
  pthread_mutex_init(mutexPtr, Monitor.mutexAttrPtr)

  // memory leak possible solution is to call free in finalize
  private val condPtr: Ptr[pthread_cond_t] = malloc(pthread_cond_t_size)
    .asInstanceOf[Ptr[pthread_cond_t]]
  pthread_cond_init(condPtr, Monitor.condAttrPtr)


  def _notify(): Unit    = pthread_cond_signal(condPtr)
  def _notifyAll(): Unit = pthread_cond_broadcast(condPtr)


  def _wait(): Unit = {
    val thread = ThreadBase.currentThreadInternal
    if (thread != null) {
      thread.setLockState(Waiting)
    }
    val returnVal = pthread_cond_wait(condPtr, mutexPtr)
    if (thread != null) {
      thread.setLockState(Normal)
    }
    if (returnVal == EPERM) {
      throw new IllegalMonitorStateException()
    }
  }
  def _wait(millis: scala.Long): Unit = _wait(millis, 0)


  def _wait(millis: scala.Long, nanos: Int): Unit = {
    val thread = ThreadBase.currentThreadInternal
    if (thread != null) {
      thread.setLockState(TimedWaiting)
    }
    val tsPtr = stackalloc[timespec]
    clock_gettime(CLOCK_REALTIME, tsPtr)
    val curSeconds     = !tsPtr._1
    val curNanos       = !tsPtr._2
    val overflownNanos = curNanos + nanos + (millis % 1000) * 1000000

    val deadlineNanos   = overflownNanos % 1000000000
    val deadLineSeconds = curSeconds + millis / 1000 + overflownNanos / 1000000000

    !tsPtr._1 = deadLineSeconds
    !tsPtr._2 = deadlineNanos

    val returnVal = pthread_cond_timedwait(condPtr, mutexPtr, tsPtr)
    if (thread != null) {
      thread.setLockState(Normal)
    }
    if (returnVal == EPERM) {
      throw new IllegalMonitorStateException()
    }
  }

  // TODO change entering to accommodate fat locks
  def enter(): Unit = {
    if (pthread_mutex_trylock(mutexPtr) == EBUSY) {
      val thread = ThreadBase.currentThreadInternal()
      if (thread != null) {
        thread.setLockState(Blocked)
        // try again and block until you get one
        pthread_mutex_lock(mutexPtr)
        // finally got the lock
        thread.setLockState(Normal)
      } else {
        // Thread class in not initialized yet, just try again
        pthread_mutex_lock(mutexPtr)
      }
    }
    if (!shadow) {
      pushLock()
    }
  }

  // TODO change exiting to accommodate fat lock
  def exit(): Unit = {
    if (!shadow) {
      popLock()
    }
    pthread_mutex_unlock(mutexPtr)
  }

  // TODO remove this from monitor and put in monitor ops
  @inline
  private def pushLock(): Unit = {
    val thread = ThreadBase.currentThreadInternal()
    if (thread != null) {
      thread.locks(thread.size) = this
      thread.size += 1
      if (thread.size >= thread.locks.length) {
        val oldArray = thread.locks
        val newArray = new scala.Array[Monitor](oldArray.length * 2)
        System.arraycopy(oldArray, 0, newArray, 0, oldArray.length)
        thread.locks = newArray
      }
    }
  }

  // TODO remove this from monitor and put in monitor ops
  @inline
  private def popLock(): Unit = {
    val thread = ThreadBase.currentThreadInternal()
    if (thread != null) {
      if (thread.locks(thread.size - 1) == this) {
        thread.size -= 1
      }
    }
  }
}

object Monitor {
  /**
    * Attributes of a standard mutex*/
  private val mutexAttrPtr: Ptr[pthread_mutexattr_t] = malloc(
    pthread_mutexattr_t_size).asInstanceOf[Ptr[pthread_mutexattr_t]]
  pthread_mutexattr_init(mutexAttrPtr)
  pthread_mutexattr_settype(mutexAttrPtr, PTHREAD_MUTEX_RECURSIVE)

  /**
    * Attributes of a standard condition variable.
    */
  private val condAttrPtr: Ptr[pthread_condattr_t] = malloc(
    pthread_condattr_t_size).asInstanceOf[Ptr[pthread_cond_t]]
  pthread_condattr_init(condAttrPtr)
  pthread_condattr_setpshared(condAttrPtr, PTHREAD_PROCESS_SHARED)

  /**
    * Mutex to prevent race condition on Monitor object creation when first calling
    * synchronize on an Object
    */
  // TODO do not use this any more
  private[runtime] val monitorCreationMutexPtr: Ptr[pthread_mutex_t] = malloc(
    pthread_mutex_t_size)
    .asInstanceOf[Ptr[pthread_mutex_t]]
  pthread_mutex_init(monitorCreationMutexPtr, Monitor.mutexAttrPtr)

  /**
    * Used to return the monitor instance associated with an java.lang.Object
    */
  // TODO this can now just create a single monitor instance
  def apply(x: java.lang.Object): Monitor = {
    val o = x.asInstanceOf[_Object]
    if (o.__monitor != null) {
      o.__monitor
    } else {
      try {
        pthread_mutex_lock(monitorCreationMutexPtr)
        if (o.__monitor == null) {
          o.__monitor = new Monitor(x.isInstanceOf[ShadowLock])
        }
        o.__monitor
      } finally {
        pthread_mutex_unlock(monitorCreationMutexPtr)
      }
    }
  }
}

/**
 * Cannot be checked with Thread.holdsLock
 */
class ShadowLock {
  // workaround so lock is freed when exception is thrown
  def safeSynchronized[T](f: => T): T = {
    var throwable: Throwable = null
    val result = synchronized {
      try {
        f
      } catch {
        case t: Throwable =>
          throwable = t
      }
    }
    if (throwable != null) {
      throw throwable
    } else {
      result.asInstanceOf[T]
    }

  }
}
