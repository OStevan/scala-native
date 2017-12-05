package java.lang

import java.util

import scala.scalanative.native.stdlib.{free, malloc}
import scala.scalanative.native.{
  CFunctionPtr,
  CInt,
  Ptr,
  ULong,
  signal,
  sizeof,
  stackalloc
}
import scala.scalanative.posix.pthread._
import scala.scalanative.posix.sched._
import scala.scalanative.posix.sys.types.{
  pthread_attr_t,
  pthread_key_t,
  pthread_t
}
import scala.scalanative.runtime.{
  CAtomicInt,
  NativeThread,
  ShadowLock,
  ThreadBase
}

// Ported from Harmony

class Thread private (
    parentThread: Thread, // only the main thread does not have a parent (= null)
    rawGroup: ThreadGroup,
    // Thread's target - a Runnable object whose run method should be invoked
    private val target: Runnable,
    rawName: String,
    // Stack size to be passes to VM for thread execution
    val stackSize: scala.Long,
    mainThread: scala.Boolean)
    extends ThreadBase
    with Runnable {

  import java.lang.Thread._

  private var livenessState = CAtomicInt(internalNew)

  // Thread's ID
  private val threadId: scala.Long = getNextThreadId

  // Thread's name
  // throws NullPointerException if the given name is null
  private[this] var name: String =
    if (rawName != THREAD) rawName.toString else THREAD + threadId

  // This thread's thread group
  private[lang] var group: ThreadGroup =
    if (rawGroup != null) rawGroup else parentThread.group
  group.checkGroup()

  // Indicates whether this thread was marked as daemon
  private var daemon: scala.Boolean =
    if (!mainThread) parentThread.daemon else false

  // Thread's priority
  private var priority: Int = if (!mainThread) parentThread.priority else 5

  // main thread is not started via Thread.start, set it up manually
  if (mainThread) {
    group.add(this)
    livenessState.store(internalRunnable)
  }

  // Indicates if the thread was already started
  def started: scala.Boolean = {
    val value = livenessState.load()
    value > internalNew
  }

  // Uncaught exception handler for this thread
  private var exceptionHandler: Thread.UncaughtExceptionHandler = _

  // The underlying pthread ID
  /*
   * NOTE: This is used to keep track of the pthread linked to this Thread,
   * it might be easier/better to handle this at lower level
   */
  private var underlying: pthread_t = 0.asInstanceOf[ULong]

  private val sleepMutex = new Object

  // Synchronization is done using internal lock
  val lock: Object = new Object
  // ThreadLocal values : local and inheritable
  var localValues: ThreadLocal.Values = _

  var inheritableValues: ThreadLocal.Values =
    if (parentThread != null && parentThread.inheritableValues != null) {
      new ThreadLocal.Values(parentThread.inheritableValues)
    } else null

  checkGCWatermark()
  checkAccess()

  def this(group: ThreadGroup,
           target: Runnable,
           name: String,
           stacksize: scala.Long) = {
    this(Thread.currentThread(),
         group,
         target,
         name,
         stacksize,
         mainThread = false)
  }

  def this() = this(null, null, Thread.THREAD, 0)

  def this(target: Runnable) = this(null, target, Thread.THREAD, 0)

  def this(target: Runnable, name: String) = this(null, target, name, 0)

  def this(name: String) = this(null, null, name, 0)

  def this(group: ThreadGroup, target: Runnable) =
    this(group, target, Thread.THREAD, 0)

  def this(group: ThreadGroup, target: Runnable, name: String) =
    this(group, target, name, 0)

  def this(group: ThreadGroup, name: String) = this(group, null, name, 0)

  final def checkAccess(): Unit = ()

  override final def clone(): AnyRef =
    throw new CloneNotSupportedException("Thread.clone() is not meaningful")

  @deprecated
  def countStackFrames: Int = 0 //deprecated

  @deprecated
  def destroy(): Unit =
    // this method is not implemented
    throw new NoSuchMethodError()

  final def getName: String = name

  final def getPriority: Int = priority

  final def getThreadGroup: ThreadGroup = group

  def getId: scala.Long = threadId

  def interrupt(): Unit = {
    checkAccess()
    livenessState.compareAndSwapStrong(internalStarting, internalInterrupted)
    livenessState.compareAndSwapStrong(internalRunnable, internalInterrupted)
    sleepMutex.synchronized {
      sleepMutex.notify()
    }
  }

  final def isAlive: scala.Boolean = {
    val value = livenessState.load()
    value == internalRunnable || value == internalStarting
  }

  final def isDaemon: scala.Boolean = daemon

  def isInterrupted: scala.Boolean = {
    val value = livenessState.load()
    value == internalInterrupted || value == internalInterruptedTerminated
  }

  final def join(): Unit = {
    () // workaround the synchronized methods not generating monitor calls
    synchronized {
      while (isAlive) wait()
    }
  }

  final def join(ml: scala.Long): Unit = {
    var millis: scala.Long = ml
    if (millis == 0)
      join()
    else {
      val end: scala.Long         = System.currentTimeMillis() + millis
      var continue: scala.Boolean = true
      while (isAlive && continue) {
        synchronized {
          if (isAlive) {
            wait(millis)
          }
        }
        millis = end - System.currentTimeMillis()
        if (millis <= 0)
          continue = false
      }
    }
  }

  final def join(ml: scala.Long, n: Int): Unit = {
    var nanos: Int         = n
    var millis: scala.Long = ml
    if (millis < 0 || nanos < 0 || nanos > 999999)
      throw new IllegalArgumentException()
    else if (millis == 0 && nanos == 0)
      join()
    else {
      val end: scala.Long         = System.nanoTime() + 1000000 * millis + nanos.toLong
      var rest: scala.Long        = 0L
      var continue: scala.Boolean = true
      while (isAlive && continue) {
        synchronized {
          if (isAlive) {
            wait(millis, nanos)
          }
        }
        rest = end - System.nanoTime()
        if (rest <= 0)
          continue = false
        if (continue) {
          nanos = (rest % 1000000).toInt
          millis = rest / 1000000
        }
      }
    }
  }

  @deprecated
  final def resume(): Unit = {
    checkAccess()
    if (started && NativeThread.resume(underlying) != 0)
      throw new RuntimeException(
        "Error while trying to unpark thread " + toString)
  }

  def run(): Unit = {
    if (target != null) {
      target.run()
    }
  }

  private var stackTraceTs = 0L
  // not initializing to empty to no trigger System class initialization
  private var lastStackTrace: Array[StackTraceElement] =
    new Array[StackTraceElement](0)
  def getStackTrace: Array[StackTraceElement] = {
    if (this == Thread.currentThread()) {
      lastStackTrace = new Throwable().getStackTrace
      synchronized {
        stackTraceTs += 1
        notifyAll()
      }
    } else {
      val oldTs = stackTraceTs
      pthread_kill(underlying, currentThreadStackTraceSignal)
      synchronized {
        while (stackTraceTs <= oldTs && isAlive) {
          // trigger getStackTrace on that thread
          wait()
        }
      }
    }
    lastStackTrace
  }

  private def classLoadersNotSupported =
    throw new NotImplementedError("Custom class loaders not supported")
  @deprecated
  def setContextClassLoader(classLoader: ClassLoader): Unit =
    classLoadersNotSupported
  @deprecated
  def getContextClassLoader: ClassLoader = classLoadersNotSupported

  final def setDaemon(daemon: scala.Boolean): Unit = {
    lock.synchronized {
      checkAccess()
      if (isAlive)
        throw new IllegalThreadStateException()
      this.daemon = daemon
    }
  }

  final def setName(name: String): Unit = {
    checkAccess()
    // throws NullPointerException if the given name is null
    this.name = name.toString
  }

  final def setPriority(priority: Int): Unit = {
    checkAccess()
    if (priority > Thread.MAX_PRIORITY || priority < Thread.MIN_PRIORITY)
      throw new IllegalArgumentException("Wrong Thread priority value")
    val groupLocal = group
    if (groupLocal != null) {
      val maxPriorityInGroup = groupLocal.getMaxPriority
      // min(priority,maxPriorityInGroup)
      this.priority =
        if (priority > maxPriorityInGroup) maxPriorityInGroup else priority
      if (isAlive) {
        NativeThread.setPriority(underlying, Thread.toNativePriority(priority))
      }
    }
  }

  def start(): Unit = {
    if (!livenessState.compareAndSwapStrong(internalNew, internalStarting)._1) {
      //this thread was started
      throw new IllegalThreadStateException("This thread was already started!")
    }
    // adding the thread to the thread group
    group.add(this)

    val threadPtr = malloc(sizeof[Thread]).asInstanceOf[Ptr[Thread]]
    !threadPtr = this

    val id = stackalloc[pthread_t]
    // pthread_attr_t is a struct, not a ULong
    val attrs = stackalloc[scala.Byte](pthread_attr_t_size)
      .asInstanceOf[Ptr[pthread_attr_t]]
    pthread_attr_init(attrs)
    NativeThread.attrSetPriority(attrs, Thread.toNativePriority(priority))
    if (stackSize > 0) {
      pthread_attr_setstacksize(attrs, stackSize)
    }

    val status =
      pthread_create(id,
                     attrs,
                     callRunRoutine,
                     threadPtr.asInstanceOf[Ptr[scala.Byte]])
    if (status != 0)
      throw new Exception(
        "Failed to create new thread, pthread error " + status)

    underlying = !id

  }

  def getState: State = {
    val value = livenessState.load()
    if (value == internalNew) {
      State.NEW
    } else if (value == internalStarting) {
      State.RUNNABLE
    } else if (value == internalRunnable) {
      val lockState = getLockState
      if (lockState == ThreadBase.Blocked) {
        State.BLOCKED
      } else if (lockState == ThreadBase.Waiting) {
        State.WAITING
      } else if (lockState == ThreadBase.TimedWaiting) {
        State.TIMED_WAITING
      } else {
        State.RUNNABLE
      }
    } else {
      State.TERMINATED
    }
  }

  @deprecated
  final def stop(): Unit = {
    lock.synchronized {
      if (isAlive)
        stop(new ThreadDeath())
    }
  }

  @deprecated
  final def stop(throwable: Throwable): Unit = {
    if (throwable == null)
      throw new NullPointerException("The argument is null!")
    lock.synchronized {
      if (isAlive) {
        val status: Int = pthread_cancel(underlying)
        if (status != 0)
          throw new InternalError("Pthread error " + status)
      }
    }
  }

  @deprecated
  final def suspend(): Unit = {
    checkAccess()
    if (started && NativeThread.suspend(underlying) != 0)
      throw new RuntimeException(
        "Error while trying to park thread " + toString)
  }

  override def toString: String = {
    val threadGroup: ThreadGroup = group
    val s: String                = if (threadGroup == null) "" else threadGroup.name
    "Thread[" + name + "," + priority + "," + s + "]"
  }

  private def checkGCWatermark(): Unit = {
    currentGCWatermarkCount += 1
    if (currentGCWatermarkCount % GC_WATERMARK_MAX_COUNT == 0)
      System.gc()
  }

  def getUncaughtExceptionHandler: Thread.UncaughtExceptionHandler = {
    if (exceptionHandler != null)
      return exceptionHandler
    getThreadGroup
  }

  def setUncaughtExceptionHandler(eh: Thread.UncaughtExceptionHandler): Unit =
    exceptionHandler = eh

  def threadModuleBase = Thread
}

object Thread extends scala.scalanative.runtime.ThreadModuleBase {

  val myThreadKey: pthread_key_t = {
    val ptr = stackalloc[pthread_key_t]
    pthread_key_create(ptr, null)
    !ptr
  }

  // defined as Ptr[Void] => Ptr[Void]
  // called as Ptr[Thread] => Ptr[Void]
  private def callRun(p: Ptr[scala.Byte]): Ptr[scala.Byte] = {
    val thread = !p.asInstanceOf[Ptr[Thread]]
    pthread_setspecific(myThreadKey, p)
    free(p)
    if (thread.underlying == 0L.asInstanceOf[ULong]) {
      // the called hasn't set the underlying thread id yet
      // make sure it is initialized
      thread.underlying = pthread_self()
    }
    thread.livenessState
      .compareAndSwapStrong(internalStarting, internalRunnable)
    try {
      thread.run()
    } catch {
      case e: Throwable =>
        thread.getUncaughtExceptionHandler.uncaughtException(thread, e)
    } finally {
      post(thread)
    }

    null.asInstanceOf[Ptr[scala.Byte]]
  }

  private def post(thread: Thread) = {
    shutdownMutex.synchronized {
      thread.group.remove(thread)
      shutdownMutex.notifyAll()
    }
    thread synchronized {
      thread.livenessState
        .compareAndSwapStrong(internalRunnable, internalTerminated)
      thread.livenessState
        .compareAndSwapStrong(internalInterrupted,
                              internalInterruptedTerminated)
      thread.notifyAll()
    }
  }

  // internal liveness state values
  // waiting and blocked handled separately
  private final val internalNew                   = 0
  private final val internalStarting              = 1
  private final val internalRunnable              = 2
  private final val internalInterrupted           = 3
  private final val internalTerminated            = 4
  private final val internalInterruptedTerminated = 5

  final class State private (override val toString: String)

  object State {
    final val NEW           = new State("NEW")
    final val RUNNABLE      = new State("RUNNABLE")
    final val BLOCKED       = new State("BLOCKED")
    final val WAITING       = new State("WAITING")
    final val TIMED_WAITING = new State("TIMED_WAITING")
    final val TERMINATED    = new State("TERMINATED")
  }

  private val callRunRoutine = CFunctionPtr.fromFunction1(callRun)

  private val lock: Object = new Object

  final val MAX_PRIORITY: Int  = 10
  final val MIN_PRIORITY: Int  = 1
  final val NORM_PRIORITY: Int = 5

  private def toNativePriority(priority: Int) = {
    val range = NativeThread.THREAD_MAX_PRIORITY - NativeThread.THREAD_MIN_PRIORITY
    if (range == 0) {
      NativeThread.THREAD_MAX_PRIORITY
    } else {
      priority * range / 10
    }
  }

  final val STACK_TRACE_INDENT: String = "    "

  // Default uncaught exception handler
  private var defaultExceptionHandler: UncaughtExceptionHandler = _

  // Counter used to generate thread's ID
  private var threadOrdinalNum: scala.Long = 0L

  // used to generate a default thread name
  private final val THREAD: String = "Thread-"

  // System thread group for keeping helper threads
  var systemThreadGroup: ThreadGroup = _

  // Number of threads that was created w/o garbage collection //TODO
  private var currentGCWatermarkCount: Int = 0

  // Max number of threads to be created w/o GC, required collect dead Thread references
  private final val GC_WATERMARK_MAX_COUNT: Int = 700

  def activeCount: Int = currentThread().group.activeCount()

  def currentThread(): Thread = {
    val ptr = pthread_getspecific(myThreadKey).asInstanceOf[Ptr[Thread]]
    if (ptr != null) !ptr else mainThread
  }

  def dumpStack(): Unit = {
    val stack: Array[StackTraceElement] = new Throwable().getStackTrace
    System.err.println("Stack trace")
    var i: Int = 0
    while (i < stack.length) {
      System.err.println(STACK_TRACE_INDENT + stack(i))
      i += 1
    }
  }

  def enumerate(list: Array[Thread]): Int =
    currentThread().group.enumerate(list)

  def holdsLock(obj: Object): scala.Boolean =
    currentThread().asInstanceOf[ThreadBase].holdsLock(obj)

  def `yield`(): Unit = {
    sched_yield()
  }

  def getAllStackTraces: java.util.Map[Thread, Array[StackTraceElement]] = {
    var threadsCount: Int          = mainThreadGroup.activeCount() + 1
    var count: Int                 = 0
    var liveThreads: Array[Thread] = Array.empty
    var break: scala.Boolean       = false
    while (!break) {
      liveThreads = new Array[Thread](threadsCount)
      count = mainThreadGroup.enumerateThreads(liveThreads, 0, recurse = true)
      if (count == threadsCount) {
        threadsCount *= 2
      } else
        break = true
    }

    val map: java.util.Map[Thread, Array[StackTraceElement]] =
      new util.HashMap[Thread, Array[StackTraceElement]](count + 1)
    var i: Int = 0
    while (i < count) {
      val ste: Array[StackTraceElement] = liveThreads(i).getStackTrace
      if (ste.length != 0)
        map.put(liveThreads(i), ste)
      i += 1
    }

    map
  }

  def getDefaultUncaughtExceptionHandler: UncaughtExceptionHandler =
    defaultExceptionHandler

  def setDefaultUncaughtExceptionHandler(eh: UncaughtExceptionHandler): Unit =
    defaultExceptionHandler = eh

  //synchronized
  private def getNextThreadId: scala.Long = {
    threadOrdinalNum += 1
    threadOrdinalNum
  }

  def interrupted(): scala.Boolean = {
    currentThread().livenessState
      .compareAndSwapStrong(internalInterrupted, internalRunnable)
      ._1
  }

  def sleep(millis: scala.Long, nanos: scala.Int): Unit = {
    val sleepMutex: Object = currentThread().sleepMutex
    sleepMutex.synchronized {
      sleepMutex.wait(millis, nanos)
    }
    if (interrupted()) {
      throw new InterruptedException("Interrupted during sleep")
    }
  }

  def sleep(millis: scala.Long): Unit = sleep(millis, 0)

  trait UncaughtExceptionHandler {
    def uncaughtException(t: Thread, e: Throwable)
  }

  private val mainThreadGroup: ThreadGroup =
    new ThreadGroup(parent = null, name = "system", mainGroup = true)

  private val mainThread = new Thread(parentThread = null,
                                      mainThreadGroup,
                                      target = null,
                                      "main",
                                      0,
                                      mainThread = true)

  private def currentThreadStackTrace(signal: CInt): Unit = {
    currentThread().getStackTrace
  }
  private val currentThreadStackTracePtr =
    CFunctionPtr.fromFunction1(currentThreadStackTrace _)
  private val currentThreadStackTraceSignal = signal.SIGUSR2
  signal.signal(currentThreadStackTraceSignal, currentThreadStackTracePtr)

  def mainThreadEnds(): Unit = {
    shutdownMutex.synchronized {
      mainThreadGroup.remove(mainThread)
      shutdownMutex.notifyAll()
    }
    mainThread.synchronized {
      mainThread.livenessState
        .compareAndSwapStrong(internalRunnable, internalTerminated)
      mainThread.livenessState
        .compareAndSwapStrong(internalInterrupted, internalInterruptedTerminated)
      mainThread.notifyAll()
    }
  }

  private val shutdownMutex = new ShadowLock

  def shutdownCheckLoop(): Unit = {
    shutdownMutex.synchronized {
      while (mainThreadGroup.nonDaemonThreadExists) {
        shutdownMutex.wait()
      }
    }
  }
}
