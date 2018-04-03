package scala.scalanative.runtime

import scala.scalanative.native._
import scala.scalanative.native.{stackalloc, CLong, CCast}
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

final class Monitor private[runtime] {
  // memory leak
  // TODO destroy the mutex and release the memory
  private val mutexPtr: Ptr[pthread_mutex_t] = malloc(pthread_mutex_t_size)
    .asInstanceOf[Ptr[pthread_mutex_t]]
  pthread_mutex_init(mutexPtr, Monitor.mutexAttrPtr)
  // memory leak
  // TODO destroy the condition and release the memory
  private val condPtr: Ptr[pthread_cond_t] = malloc(pthread_cond_t_size)
    .asInstanceOf[Ptr[pthread_cond_t]]
  pthread_cond_init(condPtr, Monitor.condAttrPtr)

  def _notify(): Unit    = pthread_cond_signal(condPtr)
  def _notifyAll(): Unit = pthread_cond_broadcast(condPtr)
  def _wait(): CInt = {
    pthread_cond_wait(condPtr, mutexPtr)
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
  }

  def exit(): Unit = {
    pthread_mutex_unlock(mutexPtr)
  }
}

object Monitor {
  private val mutexAttrPtr: Ptr[pthread_mutexattr_t] = malloc(
    pthread_mutexattr_t_size).asInstanceOf[Ptr[pthread_mutexattr_t]]
  pthread_mutexattr_init(mutexAttrPtr)
  pthread_mutexattr_settype(mutexAttrPtr, PTHREAD_MUTEX_RECURSIVE)

  private val condAttrPtr: Ptr[pthread_condattr_t] = malloc(
    pthread_condattr_t_size).asInstanceOf[Ptr[pthread_cond_t]]
  pthread_condattr_init(condAttrPtr)
  pthread_condattr_setpshared(condAttrPtr, PTHREAD_PROCESS_SHARED)


  private val TAKE_LOCK = Long.MaxValue + 1

  final val LOCK_TYPE_MASK: CLong = Long.MaxValue + 1
  final val MONITOR_POINTER_MASK: CLong = 0x0FFFFFFFFFFFFL
  final val RECURSION_INCREMENT: CLong = 0x01000L
  final val RECURSION_MASK: CLong = 0x0FF0000L
  final val THREAD_ID_MASK: CLong = 0x0FFFFL | LOCK_TYPE_MASK


  private def inflateLock(expectedPtr: Ptr[CLong], threadID: CLong, pointerToAtomic: Ptr[CLong], flag: Boolean): Monitor = {
    var monitor: Monitor = new Monitor
    var obtainedMonitor = false

    var lockValue = Atomic.load_long(pointerToAtomic)

    while (!obtainedMonitor) {
      if ((lockValue & LOCK_TYPE_MASK) != 0) {
        // fat lock enter
        monitor = (lockValue & MONITOR_POINTER_MASK).cast[Ptr[CLong]].cast[Monitor]
        obtainedMonitor = true
      } else {
        // obtain the thin lock and inflate it
        if (Atomic.compare_and_swap_strong_long(pointerToAtomic, expectedPtr, threadID)) {
          val monitorCasted = monitor.cast[Ptr[Byte]].cast[CLong]
          Atomic.store_long(pointerToAtomic, monitorCasted | LOCK_TYPE_MASK)
          obtainedMonitor = true
        }
      }
      lockValue = Atomic.load_long(pointerToAtomic)
    }
    monitor
  }

  /**
    * Called for a shadow lock, immediate inflation
    * @param expectedPtr
    * @param pointerToAtomic
    * @return
    */
  private def inflateLock(expectedPtr: Ptr[CLong], pointerToAtomic: Ptr[CLong]): Monitor = {
    if (Atomic.compare_and_swap_strong_long(pointerToAtomic, expectedPtr, TAKE_LOCK)) {
      val monitor = new Monitor
      Atomic.store_long(pointerToAtomic, monitor.cast[CLong] | LOCK_TYPE_MASK)
      monitor
    } else {
      while (Atomic.load_long(pointerToAtomic) == TAKE_LOCK) {}
      ((!pointerToAtomic) & MONITOR_POINTER_MASK).cast[Monitor]
    }
  }

  def apply(x: java.lang.Object): Monitor = {
    val o = x.asInstanceOf[_Object]
    val pointerToAtomic = o.cast[Ptr[CLong]] + 1L
    val expected = stackalloc[CLong]
    !expected = 0L

    if (x.isInstanceOf[ShadowLock]) {
      inflateLock(expected, pointerToAtomic)
    } else {
      inflateLock(expected, ThreadBase.currentThreadInternal().getId, pointerToAtomic, x.isInstanceOf[ShadowLock])
    }
  }


//  def apply(x: java.lang.Object): Monitor = {
//    val o = x.asInstanceOf[_Object]
//    val pointerToAtomic = o.cast[Ptr[CLong]] + 1L
//    val expected = stackalloc[CLong]
//    !expected = 0L
//
//    ThreadBase.currentThreadInternal()
//
//    if (Atomic.compare_and_swap_strong_long(pointerToAtomic, expected, TAKE_LOCK)) {
//      val monitor = new Monitor
//      Atomic.store_long(pointerToAtomic, monitor.cast[CLong])
//      monitor
//    } else {
//      while (Atomic.load_long(pointerToAtomic) == TAKE_LOCK) {}
//      (!pointerToAtomic).cast[Monitor]
//    }
//  }

  def enter(obj: Object): Unit = {
    val monitor = Monitor(obj)
    monitor.enter()
    if (!obj.isInstanceOf[ShadowLock]) {
      pushLock(obj)
    }
  }

  def exit(obj: Object): Unit = {

    if (!obj.isInstanceOf[ShadowLock]) {
      popLock(obj)
    }

    val o = obj.asInstanceOf[_Object]
    // cast to pointer and move to the address locking part of the header
    val pointerToAtomic: Ptr[CLong] = o.cast[Ptr[CLong]] + 1L

    val lockValue = Atomic.load_long(pointerToAtomic)
    var threadID: CLong = 1L

    if (!obj.isInstanceOf[ShadowLock])
      threadID = ThreadBase.currentThreadInternal().getId

    // thin lock top most unlock
    if (lockValue == threadID) {
      Atomic.store_long(pointerToAtomic, 0L)
      return
    }

    // thin lock recursive unlock
    if ((lockValue & LOCK_TYPE_MASK) == 0) {
      Atomic.store_long(pointerToAtomic, lockValue - RECURSION_INCREMENT)
      return
    }

    // fat lock unlock
    val monitor = (lockValue & MONITOR_POINTER_MASK).cast[Ptr[CLong]].cast[Monitor]
    monitor.exit()
  }

  // this makes no sense

  def _notify(obj: Object): Unit    = {
    val lockValue = Atomic.load_long(obj.cast[Ptr[CLong]] + 1L)
    if ((lockValue & LOCK_TYPE_MASK) != 0)
      (lockValue & MONITOR_POINTER_MASK).cast[Monitor]._notify()
  }

  def _notifyAll(obj: Object): Unit = {
    val lockValue = Atomic.load_long(obj.cast[Ptr[CLong]] + 1L)
    if ((lockValue & LOCK_TYPE_MASK) != 0)
      (lockValue & MONITOR_POINTER_MASK).cast[Monitor]._notifyAll()
  }

  def _wait(obj: Object): Unit = {
    val thread = ThreadBase.currentThreadInternal
    if (thread != null) {
      thread.setLockState(Waiting)
    }

    val lockValue = Atomic.load_long(obj.cast[Ptr[CLong]] + 1L)
    if ((lockValue & LOCK_TYPE_MASK) != 0) {
      // fat monitor wait
      if ((lockValue & MONITOR_POINTER_MASK).cast[Monitor]._wait() == EPERM)
        throw new IllegalMonitorStateException()
    } else if (obj.isInstanceOf[ShadowLock]) {
      // thin monitor inflate and enter a specified number of times
      if ((lockValue & THREAD_ID_MASK) != ThreadBase.currentThreadInternal().getId)
        throw new IllegalMonitorStateException()
      var numberOfEntries = ((lockValue & RECURSION_MASK) >> 16) + 1
      val monitor = Monitor(obj)
      while (numberOfEntries > 0) {
        monitor.enter()
        numberOfEntries -= 1
      }
      monitor.wait()
    } else
      // not possible
      throw new IllegalMonitorStateException()

    if (thread != null) {
      thread.setLockState(Normal)
    }
  }
  def _wait(obj: Object, millis: scala.Long): Unit = Monitor.wait(millis, 0)
  def _wait(obj: Object, millis: scala.Long, nanos: Int): Unit = {
    val thread = ThreadBase.currentThreadInternal
    if (thread != null) {
      thread.setLockState(TimedWaiting)
    }

    val lockValue = Atomic.load_long(obj.cast[Ptr[CLong]] + 1L)
    if ((lockValue & LOCK_TYPE_MASK) != 0) {
      // fat monitor wait
      if ((lockValue & MONITOR_POINTER_MASK).cast[Monitor]._wait(millis, nanos) == EPERM)
        throw new IllegalMonitorStateException()
    } else if (obj.cast[Object].isInstanceOf[ShadowLock]) {
      // thin monitor inflate and enter a specified number of times
      if ((lockValue & THREAD_ID_MASK) != ThreadBase.currentThreadInternal().getId)
        throw new IllegalMonitorStateException()
      var numberOfEntries = ((lockValue & RECURSION_MASK) >> 16) + 1
      val monitor = Monitor(obj)
      while (numberOfEntries > 0) {
        monitor.enter()
        numberOfEntries -= 1
      }
      if (monitor._wait(millis, nanos) == EPERM)
        throw new IllegalMonitorStateException()
    } else
    // not possible
      throw new IllegalMonitorStateException()

    if (thread != null) {
      thread.setLockState(Normal)
    }
  }


  // helpers
  @inline
  private def pushLock(obj: Object): Unit = {
    val thread = ThreadBase.currentThreadInternal()
    if (thread != null) {
      thread.locks(thread.size) = obj.cast[java.lang._Object]
      thread.size += 1
      if (thread.size >= thread.locks.length) {
        val oldArray = thread.locks
        val newArray = new scala.Array[java.lang._Object](oldArray.length * 2)
        System.arraycopy(oldArray, 0, newArray, 0, oldArray.length)
        thread.locks = newArray
      }
    }
  }

  @inline
  private def popLock(obj: Object): Unit = {
    val thread = ThreadBase.currentThreadInternal()
    if (thread != null) {
      if (thread.locks(thread.size - 1) == obj.cast[java.lang._Object]) {
        thread.size -= 1
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
