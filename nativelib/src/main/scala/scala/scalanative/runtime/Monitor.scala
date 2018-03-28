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

final class Monitor private[runtime] {

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
  }

  // TODO change exiting to accommodate fat lock
  def exit(): Unit = {
    pthread_mutex_unlock(mutexPtr)
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
  //  private[runtime] val monitorCreationMutexPtr: Ptr[pthread_mutex_t] = malloc(
  //    pthread_mutex_t_size)
  //    .asInstanceOf[Ptr[pthread_mutex_t]]
  //  pthread_mutex_init(monitorCreationMutexPtr, Monitor.mutexAttrPtr)

  /**
    * Thin lock implementation specific mask;
    */
  // lock masks
  final val LOCK_TYPE_MASK: CLong = Long.MaxValue + 1
  final val MONITOR_POINTER_MASK: CLong = 0x0FFFFFFFFFFFFL
  final val RECURSION_INCREMENT: CLong = 0x01000L
  final val RECURSION_MASK: CLong = 0x0FF0000L
  final val THREAD_ID_MASK: CLong = 0x0FFFFL | LOCK_TYPE_MASK

  /**
    * Used to return the monitor instance associated with an java.lang.Object
    */
  def apply(x: java.lang.Object): Monitor = {
    new Monitor()
  }

  // TODO refactor, maybe some casts are not necessary, possibly put backoff
  def enter(obj: java.lang.Object): Unit = {
    // allocate a space for expected val
    val expectedPtr: Ptr[CLong] = stackalloc[CLong]
    // start the locking by expecting that the lock is thin and unlocked
    !expectedPtr = 0L


    val o = obj.asInstanceOf[_Object]
    // cast to pointer and move to the address locking part of the header
    val pointerToAtomic: Ptr[CLong] = o.cast[Ptr[CLong]] + 1

    val threadID: CLong = ThreadBase.currentThreadInternal().getId
    // thin lock try enter no contention

    if (Atomic.compare_and_swap_strong_long(pointerToAtomic, expectedPtr, threadID))
    // thin lock and you are taking it
      return

    val lockValue = Atomic.load_long(pointerToAtomic)
    // check if you are the owner of thin lock and return
    if (((lockValue & LOCK_TYPE_MASK) == 0) &&
      // current thread is the owner increment pin count
      (lockValue & RECURSION_MASK) < RECURSION_MASK &&
      (lockValue & THREAD_ID_MASK) == threadID) {
      Atomic.store_long(pointerToAtomic, lockValue + RECURSION_INCREMENT)
      return
    }

    // need to inflate the lock or it is already inflated

    var obtainedMonitor = false

    while (!obtainedMonitor) {
      if ((lockValue & LOCK_TYPE_MASK) != 0) {
        // fat lock enter
        val monitor = (lockValue & MONITOR_POINTER_MASK).cast[Ptr[CLong]].cast[Monitor]
        monitor.enter()
        obtainedMonitor = true
      } else {
        // obtain the thin lock and inflate it
        if (Atomic.compare_and_swap_strong_long(pointerToAtomic, expectedPtr, threadID)) {
          val monitor = Monitor(obj)
          val monitorCasted = monitor.cast[Ptr[Byte]].cast[CLong]
          Atomic.store_long(pointerToAtomic, monitorCasted | LOCK_TYPE_MASK)
          obtainedMonitor = true
          monitor.enter()
        }
      }
    }

    if (!obj.isInstanceOf[ShadowLock])
      pushLock(obj)

  }

  def exit(obj: java.lang.Object): Unit = {

      if (!obj.isInstanceOf[ShadowLock]) {
        popLock(obj)
      }

      val o = obj.asInstanceOf[_Object]
      // cast to pointer and move to the address locking part of the header
      val pointerToAtomic: Ptr[CLong] = o.cast[Ptr[CLong]] + 1

      val threadID: CLong = ThreadBase.currentThreadInternal().getId

      val lockValue = Atomic.load_long(pointerToAtomic)
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


  @inline
  private def pushLock(obj: java.lang.Object): Unit = {
    val thread = ThreadBase.currentThreadInternal()
    val x = obj.asInstanceOf[java.lang._Object]
    if (thread != null) {
      thread.locks(thread.size) = x
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
  private def popLock(obj: java.lang.Object): Unit = {
    val thread = ThreadBase.currentThreadInternal()
    if (thread != null) {
      if (thread.locks(thread.size - 1) == obj) {
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
