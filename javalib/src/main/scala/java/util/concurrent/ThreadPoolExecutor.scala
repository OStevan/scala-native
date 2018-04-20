package java.util.concurrent
import java.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.{AbstractQueuedSynchronizer, Condition, ReentrantLock}
import scala.collection.JavaConverters._

import scala.compat.Platform.ConcurrentModificationException

// Ported from Harmony
// https://github.com/apache/harmony/blob/724deb045a85b722c961d8b5a83ac7a697319441/classlib/modules/concurrent/src/main/java/java/util/concurrent/ThreadPoolExecutor.java

class ThreadPoolExecutor(@volatile private var corePoolSize: Int, @volatile private var maximumPoolSize: Int,
                         keepATime: Long, val unit: TimeUnit, private val workQueue: BlockingQueue[Runnable],
                        @volatile private var threadFactory: ThreadFactory, @volatile private var handler: RejectedExecutionHandler)
  extends AbstractExecutorService {

  if (corePoolSize < 0 || maximumPoolSize <=0 || maximumPoolSize < corePoolSize || keepATime < 0)
    throw new IllegalArgumentException

  if (workQueue == null || threadFactory == null || handler == null)
    throw new NullPointerException


  val ctl: AtomicInteger = new AtomicInteger(ThreadPoolExecutor.ctlOf(ThreadPoolExecutor.RUNNING, 0))

  private var keepAliveTime = unit.toNanos(keepATime)

  private val mainLock: ReentrantLock = new ReentrantLock() {}

  private val workers: util.HashSet[Worker] = new util.HashSet[Worker]()

  private val termination: Condition = mainLock.newCondition()

  private var largestPoolSize: Int = 0

  private var completedTaskCount: Long = 0

  @volatile
  private var allowCoreThreadTimeOut: Boolean = _

  def this(corePoolSize: Int, maximumPoolSize: Int, keepAliveTime: Long,
           unit: TimeUnit, workQueue: BlockingQueue[Runnable]) =
    this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
      Executors.defaultThreadFactory(), ThreadPoolExecutor.defaultHandler)

  def this(corePoolSize: Int, maximumPoolSize: Int, keepAliveTime: Long,
           unit: TimeUnit, workQueue: BlockingQueue[Runnable], threadFactory: ThreadFactory) =
    this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
      threadFactory, ThreadPoolExecutor.defaultHandler)

  def this(corePoolSize: Int, maximumPoolSize: Int, keepAliveTime: Long,
           unit: TimeUnit, workQueue: BlockingQueue[Runnable], handler: RejectedExecutionHandler) =
    this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
      Executors.defaultThreadFactory(), handler)

  override def shutdown(): Unit = {
    val mainLock = this.mainLock
    mainLock.lock()
    println("inside lock")
    try {
      checkShutdownAccess()
      println("advance run state")
      advanceRunState(ThreadPoolExecutor.SHUTDOWN)
      println("interrupt idle workers")
      interruptIdleWorkers()
      println("on shutdown")
      onShutdown()
    } finally {
      mainLock.unlock()
    }
    tryTerminate()
  }

  override def shutdownNow(): util.List[Runnable] = {
    var tasks: java.util.List[Runnable] = null
    val mainLock = this.mainLock
    mainLock.lock()
    try {
      checkShutdownAccess()
      advanceRunState(ThreadPoolExecutor.STOP)
      interruptWorkers()
      tasks = drainQueue()
    } finally {
      mainLock.unlock()
    }
    tryTerminate()
    tasks
  }

  override def isShutdown: Boolean = !ThreadPoolExecutor.isRunning(ctl.get())

  def isTerminating: Boolean = {
    val c = ctl.get()
    !ThreadPoolExecutor.isRunning(c) && ThreadPoolExecutor.runStateLessThan(c, ThreadPoolExecutor.TERMINATED)
  }

  override def isTerminated: Boolean = ThreadPoolExecutor.runStateAtLeast(ctl.get(), ThreadPoolExecutor.TERMINATED)

  @throws[InterruptedException]
  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    var nanos: Long = unit.toNanos(timeout)
    val mainLock = this.mainLock
    mainLock.lock()
    var flag = true
    var res = false
    try {
      while (flag) {
        if (ThreadPoolExecutor.runStateAtLeast(ctl.get(), ThreadPoolExecutor.TERMINATED)) {
          flag = false
          res = true
        } else if (nanos <= 0) {
          flag = false
          res = false
        } else {
          nanos = termination.awaitNanos(nanos)
        }
      }
    } finally {
      mainLock.unlock()
    }
    res
  }

  override def finalize(): Unit = shutdown()

  def setThreadFactory(threadFactory: ThreadFactory): Unit = {
    if (threadFactory == null)
      throw new NullPointerException
    this.threadFactory = threadFactory
  }

  def getThreadFactory(): ThreadFactory = threadFactory

  def setRejectedExecutionHandler(handler: RejectedExecutionHandler): Unit = {
    if (handler == null)
      throw new NullPointerException
    this.handler = handler
  }

  def getRejectedExecutionHandler(): RejectedExecutionHandler = handler

  def setCorePoolSize(corePoolSize: Int): Unit = {
    if (corePoolSize < 0)
      throw new IllegalArgumentException
    val delta = corePoolSize - this.corePoolSize
    this.corePoolSize = corePoolSize

    if (ThreadPoolExecutor.workerCountOf(ctl.get()) > corePoolSize)
      interruptIdleWorkers()
    else if (delta > 0) {
      var k = Math.min(delta, workQueue.size())
      while (k > 0 && addWorker(null, true)) {
        k -= 1
        if (workQueue.isEmpty)
          k = 0
      }
    }
  }

  def getCorePoolSize(): Int = corePoolSize

  def prestartCoreThread(): Boolean = ThreadPoolExecutor.workerCountOf(ctl.get()) < corePoolSize &&
  addWorker(null, true)

  def prestartAllCoreThreads(): Int = {
    var count = 0
    while (addWorker(null, true))
      count += 1
    count
  }

  def allowsCoreThreadTimeOut(): Boolean = allowCoreThreadTimeOut

  def allowCoreThreadTimeOut(value: Boolean): Unit = {
    if (value && keepAliveTime <= 0)
      throw new IllegalArgumentException("Core threads must have nonzero keep alive times")
    if (value != allowCoreThreadTimeOut) {
      allowCoreThreadTimeOut = value
      if (value)
        interruptIdleWorkers()
    }
  }

  def setMaximumPoolSize(maximumPoolSize: Int): Unit = {
    if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
      throw new IllegalArgumentException
    this.maximumPoolSize = maximumPoolSize
    if (ThreadPoolExecutor.workerCountOf(ctl.get()) > maximumPoolSize)
      interruptIdleWorkers()
  }

  def getMaximumPoolSize: Int = maximumPoolSize

  def setKeepAliveTime(time: Long, unit: TimeUnit): Unit = {
    if (time < 0)
      throw new IllegalArgumentException
    if (time == 0 && allowCoreThreadTimeOut)
      throw new IllegalArgumentException("Core threads must have nonzero keep alive times")
    val keepAliveTime = unit.toNanos(time)
    val delta = keepAliveTime - this.keepAliveTime
    this.keepAliveTime = keepAliveTime
    if (delta < 0)
      interruptIdleWorkers()
  }

  def getKeepAliveTime(unit: TimeUnit): Long = unit.convert(keepAliveTime, TimeUnit.NANOSECONDS)

  def getQueue(): BlockingQueue[Runnable] = workQueue

  def remove(runnable: Runnable): Boolean = {
    val removed = workQueue.remove(runnable)
    tryTerminate()
    removed
  }

  def purge(): Unit = {
    val q = workQueue
    try {
      val it = q.iterator()
      while (it.hasNext){
        val r = it.next()
        if (r.isInstanceOf[Future[_]] && (r.asInstanceOf[Future[_]]).isCancelled)
          it.remove()
      }
    } catch {
      case _: ConcurrentModificationException =>
        val arr: Array[Runnable] = q.toArray(new Array[Runnable](q.size()))
        for (r: Runnable <- arr
            if r.isInstanceOf[Future[_]] && r.asInstanceOf[Future[_]].isCancelled) {
          q.remove(r)
        }
    }
    tryTerminate()
  }


  def getPoolSize: Int = {
    val mainLock = this.mainLock
    mainLock.lock()
    try {
      if (ThreadPoolExecutor.runStateAtLeast(ctl.get(), ThreadPoolExecutor.TIDYING))
        0
      else
        workers.size()
    } finally {
      mainLock.unlock()
    }
  }

  def getActiveCount: Int = {
    val mainLock = this.mainLock
    mainLock.lock()
    try {
      var n = 0
      val it = workers.iterator()
      while (it.hasNext) {
        if (it.next().isLocked())
          n += 1
      }
      n
    } finally {
      mainLock.unlock()
    }
  }

  def getLargestPoolSize: Int = {
    val mainLock = this.mainLock
    mainLock.lock()
    try {
      largestPoolSize
    } finally {
      mainLock.unlock()
    }
  }

  def getTaskCount: Long = {
    val mainLock = this.mainLock
    mainLock.lock()
    try {
      var count = completedTaskCount
      val it = workers.iterator()
      while (it.hasNext) {
        val worker = it.next()
        count += worker.completedTasks
        if (worker.isLocked())
          count += 1
      }
      count + workQueue.size()
    } finally {
      mainLock.unlock()
    }
  }

  def getCompletedTaskCount: Long = {
    val mainLock = this.mainLock
    mainLock.lock()
    try {
      var count = completedTaskCount
      val it = workers.iterator()
      while (it.hasNext)
        count += it.next().completedTasks
      count
    } finally {
      mainLock.unlock()
    }
  }

  protected def beforeExecute(thread: Thread, runnable: Runnable) = {}

  protected def afterExecute(runnable: Runnable, throwable: Throwable) = {}

  protected def terminated() = {}


  override def execute(command: Runnable): Unit = {
    if (command == null)
      throw new NullPointerException

    var c = ctl.get()

    if (ThreadPoolExecutor.workerCountOf(c) < corePoolSize) {
      if (addWorker(command, true))
        return
      c = ctl.get()
    }

    if (ThreadPoolExecutor.isRunning(c) && workQueue.offer(command)) {
      val recheck = ctl.get()
      if (!ThreadPoolExecutor.isRunning(recheck) && remove(command))
        reject(command)
      else if (ThreadPoolExecutor.workerCountOf(recheck) == 0)
        addWorker(null, false)
    } else if (!addWorker(command, false))
      reject(command)
  }

  private def compareAndIncrementWorkerCount(expect: Int) = ctl.compareAndSet(expect, expect + 1)

  private def compareAndDecrementWorkerCount(expect: Int) = ctl.compareAndSet(expect, expect - 1)

  private def decrementWorkerCount(): Unit = {
    do {
    } while ( {
      !compareAndDecrementWorkerCount(ctl.get)
    })
  }

  @SerialVersionUID(6138294804551838833L)
  private final class Worker(var firstTask: Runnable) extends AbstractQueuedSynchronizer with Runnable {
    val thread: Thread = getThreadFactory.newThread(this)

    @volatile
    var completedTasks: Long = 0

    override def run(): Unit = runWorker(this)

    override protected def isHeldExclusively(): Boolean = {
      getState == 1
    }

    override protected def tryAcquire(arg: Int): Boolean = {
      if (compareAndSetState(0, 1)) {
        setExclusiveOwnerThread(Thread.currentThread())
        true
      } else false
    }

    override protected def tryRelease(arg: Int): Boolean = {
      setExclusiveOwnerThread(null)
      setState(0)
      true
    }

    def lock(): Unit = acquire(1)

    def tryLock(): Boolean = tryAcquire(1)

    def unlock(): Unit = release(1)

    def isLocked(): Boolean = isHeldExclusively()

  }

  private def advanceRunState(targetState: Int): Unit = {
    while (true) {
      val c: Int = ctl.get()
      if (ThreadPoolExecutor.runStateAtLeast(c, targetState) ||
        ctl.compareAndSet(c, ThreadPoolExecutor.ctlOf(targetState, ThreadPoolExecutor.workerCountOf(c))))
        return
    }
  }

  protected final def tryTerminate(): Unit = {
    while (true) {
      val c: Int = ctl.get()
      if (ThreadPoolExecutor.isRunning(c) ||
        ThreadPoolExecutor.runStateAtLeast(c, ThreadPoolExecutor.TIDYING) ||
        (ThreadPoolExecutor.runStateOf(c) == ThreadPoolExecutor.SHUTDOWN && !workQueue.isEmpty))
        return
      if (ThreadPoolExecutor.workerCountOf(c) != 0) {
        interruptIdleWorkers(ThreadPoolExecutor.ONLY_ONE)
        return
      }

      val mainLock: ReentrantLock = this.mainLock

      mainLock.lock()

      try {
        if (ctl.compareAndSet(c, ThreadPoolExecutor.ctlOf(ThreadPoolExecutor.TIDYING, 0))) {
          try {
            terminated()
          } finally {
            ctl.set(ThreadPoolExecutor.ctlOf(ThreadPoolExecutor.TERMINATED, 0))
            termination.signalAll()
          }
          return
        }
      } finally {
        mainLock.unlock()
      }
    }
  }

  private def checkShutdownAccess(): Unit = {
  }

  private def interruptWorkers(): Unit = {
    val mainLock: ReentrantLock = this.mainLock
    mainLock.lock()
    try {
      val iterator = workers.iterator()
      while (iterator.hasNext) {
        try {
          iterator.next().thread.interrupt()
        } catch {
          case _: SecurityException =>
        }
      }
    } finally {
      mainLock.unlock()
    }
  }


  private def interruptIdleWorkers(bool: Boolean): Unit = {
    val mainLock: ReentrantLock = this.mainLock
    mainLock.lock()
    try {
      val iterator = workers.iterator()
      while (iterator.hasNext) {
        val worker = iterator.next()
        val thread: Thread = worker.thread
        if (!thread.isInterrupted && worker.tryLock()) {
          try {
            thread.interrupt()
          } catch {
            case _: SecurityException =>
          } finally {
            worker.unlock()
          }
        }

      }
    } finally {
      mainLock.unlock()
    }
  }

  private def interruptIdleWorkers(): Unit = {
    interruptIdleWorkers(false)
  }

  private def clearInterruptsForTaskRun(): Unit = {
    if (ThreadPoolExecutor.runStateLessThan(ctl.get(), ThreadPoolExecutor.STOP) &&
      Thread.interrupted() &&
      ThreadPoolExecutor.runStateAtLeast(ctl.get(), ThreadPoolExecutor.STOP)) {
      Thread.currentThread().interrupt()
    }
  }

  private[concurrent] final def reject(command: Runnable): Unit = {
    handler.rejectedExecution(command, this)
  }

  private[concurrent] def onShutdown() {}

  private[concurrent] def isRunningOrShutdown(shutdownOk: Boolean): Boolean = {
    val rs = ThreadPoolExecutor.runStateOf(ctl.get())
    rs == ThreadPoolExecutor.RUNNING || (rs == ThreadPoolExecutor.SHUTDOWN && shutdownOk)
  }

  private def drainQueue(): util.List[Runnable] = {
    val q: BlockingQueue[Runnable] = workQueue
    val taskList: util.List[Runnable] = new util.ArrayList[Runnable]()
    q.drainTo(taskList)
    if (!q.isEmpty) {
      val array = q.toArray(new Array[Runnable](0))
      for (r <- array) {
        if (q.remove(r))
          taskList.add(r)
      }
    }
    taskList
  }


  private def addWorker(firstTask: Runnable, core: Boolean): Boolean = {
    var flag: Boolean = true
    while (flag) {
      var c = ctl.get()
      val rs = ThreadPoolExecutor.runStateOf(c)

      if (rs >= ThreadPoolExecutor.SHUTDOWN &&
        !(rs == ThreadPoolExecutor.SHUTDOWN &&
          firstTask == null &&
          !workQueue.isEmpty)) {
        return false
      }

      var innerFlag: Boolean = true
      while (innerFlag) {
        val wc = ThreadPoolExecutor.workerCountOf(c)

        if (wc >= ThreadPoolExecutor.CAPACITY ||
          wc >= (if (core) corePoolSize else maximumPoolSize))
          return false

        if (compareAndIncrementWorkerCount(c)) {
          innerFlag = false
          flag = false
        }

        c = ctl.get()

        if (!innerFlag && ThreadPoolExecutor.runStateOf(c) != rs) {
          innerFlag = false
        }
      }
    }

    val worker: Worker = new Worker(firstTask)
    val t = worker.thread

    val mainLock = this.mainLock

    mainLock.lock()

    try {
      val c = ctl.get()
      val rs = ThreadPoolExecutor.runStateOf(c)

      if (t == null ||
        (rs >= ThreadPoolExecutor.SHUTDOWN &&
          !(rs == ThreadPoolExecutor.SHUTDOWN &&
            firstTask == null))) {
        decrementWorkerCount()
        tryTerminate()
        return false
      }

      workers.add(worker)

      val s = workers.size()

      if (s > largestPoolSize)
        largestPoolSize = s
    } finally {
      mainLock.unlock()
    }

    t.start()

    true
  }

  private def processWorkerExit(worker: Worker, completedAbruptly: Boolean): Unit = {
    if (completedAbruptly)
      decrementWorkerCount()

    val mainLock = this.mainLock

    mainLock.lock()

    try {
      completedTaskCount += 1
      workers.remove(worker)
    } finally {
      mainLock.unlock()
    }

    tryTerminate()

    val c = ctl.get()
    if (ThreadPoolExecutor.runStateLessThan(c, ThreadPoolExecutor.STOP)) {
      if (!completedAbruptly) {
        var min = if (allowCoreThreadTimeOut) 0 else corePoolSize
        if (min == 0 && !workQueue.isEmpty)
          min = 1
        if (ThreadPoolExecutor.workerCountOf(c) >= min) {
          return
        }
      }
      addWorker(null, false)
    }
  }

  private def getTask(timedOut: Boolean = false): Runnable = {
    var c = ctl.get()
    val rs = ThreadPoolExecutor.runStateOf(c)

    if (rs >= ThreadPoolExecutor.SHUTDOWN && (rs >= ThreadPoolExecutor.STOP || workQueue.isEmpty)) {
      decrementWorkerCount()
      return null
    }

    var timed = false

    innerLoop() match {
      case 0 => return null
      case 2 => getTask(timedOut)
      case _ =>
    }

    def innerLoop(): Int = {
      val wc = ThreadPoolExecutor.workerCountOf(c)
      timed = allowCoreThreadTimeOut || wc > corePoolSize

      if (wc <= maximumPoolSize && !(timedOut && timed)) {
        1
      } else if (compareAndDecrementWorkerCount(c)) {
        0
      } else {
        c = ctl.get()

        if (ThreadPoolExecutor.runStateOf(c) != rs) {
          2
        } else
          innerLoop()
      }
    }

    try {
      val r: Runnable = if (timed) workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) else workQueue.take
      if (r != null)
        return r
      getTask(true)
    } catch {
      case _: InterruptedException => getTask(false)
    }

  }

  protected[concurrent] def runWorker(worker: Worker): Unit = {
    var task: Runnable = worker.firstTask
    worker.firstTask = null
    var completedAbruptly = true

    try {
      if (task == null)
        task = getTask()
      while (task != null) {
        worker.lock()
        clearInterruptsForTaskRun()
        try {
          beforeExecute(worker.thread, task)
          var thrown: Throwable = null
          try {
            task.run()
          } catch {
            case x: RuntimeException => thrown = x; throw x
            case x: Error => thrown = x; throw x
            case x: Throwable => thrown = x; throw new Error(x)
          } finally {
            afterExecute(task, thrown)
          }
        } finally {
          task = null
          worker.completedTasks += 1
          worker.unlock()
        }
        completedAbruptly = false
      }
    } finally {
      processWorkerExit(worker, completedAbruptly)
    }

  }
}

object ThreadPoolExecutor {

  class CallerRunsPolicy extends RejectedExecutionHandler {
    override def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit = {
      if (!executor.isShutdown)
        r.run()
    }
  }

  class AbortPolicy extends RejectedExecutionHandler {
    override def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit =
      throw new RejectedExecutionException()
  }

  class DiscardPolicy extends RejectedExecutionHandler {
    override def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit =
      if (!executor.isShutdown) {
        executor.getQueue.poll()
        executor.execute(r)
      }
  }

  private val COUNT_BITS = Integer.SIZE - 3
  private val CAPACITY = (1 << COUNT_BITS) - 1
  private val ONLY_ONE = true

  // runState is stored in the high-order bits
  private val RUNNING = -1 << COUNT_BITS
  private val SHUTDOWN = 0 << COUNT_BITS
  private val STOP = 1 << COUNT_BITS
  private val TIDYING = 2 << COUNT_BITS
  private val TERMINATED = 3 << COUNT_BITS

  // Packing and unpacking ctl
  private def runStateOf(capacity: Int) = capacity & ~CAPACITY

  private def workerCountOf(capacity: Int) = capacity & CAPACITY

  private def ctlOf(rs: Int, wc: Int) = rs | wc

  private def runStateLessThan(c: Int, s: Int) = c < s

  private def runStateAtLeast(c: Int, s: Int) = c >= s

  private def isRunning(c: Int) = c < SHUTDOWN

  private val defaultHandler: RejectedExecutionHandler = new AbortPolicy

  private val shutdownPerm: RuntimePermission = new RuntimePermission("modifyThread")
}
