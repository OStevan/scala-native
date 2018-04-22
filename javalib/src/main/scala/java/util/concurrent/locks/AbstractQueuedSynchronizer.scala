package java.util
package concurrent.locks

// ported from Harmony

import java.util
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.scalanative.runtime.CAtomicsImplicits._
import scala.scalanative.runtime.{CAtomicInt, CAtomicLong, CAtomicRef}
import scala.scalanative.native.{CCast, CInt, CLong}


abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    with java.io.Serializable { self =>

  import AbstractQueuedSynchronizer._

  //volatile
  private val head = new AtomicReference[Node]()

  //volatile
  private val tail= new AtomicReference[Node]()

  private val state: CAtomicInt = CAtomicInt()

  protected final def getState: CInt = state.load()

  protected final def setState(newState: CInt): Unit = state.store(newState)

  protected final def compareAndSetState(expect: CInt, update: CInt): Boolean = {
    state.compareAndSwapStrong(expect, update)._1
  }

  private def enq(node: Node): Node = {
    while (true) {
      val t: Node = tail.get()
      if (t == null) {
        if (compareAndSetHead(new Node()))
          tail.set(head.get())
      } else {
        node.prev.set(t)
        if (compareAndSetTail(t, node)) {
          t.next.set(node)
          return t
        }
      }
    }
    // for the compiler
    null.asInstanceOf[Node]
  }

  private def addWaiter(mode: Node): Node = {
    val node: Node = new Node(Thread.currentThread(), mode)

    val pred: Node = tail.get()
    if (pred != null) {
      node.prev.set(pred)
      if (compareAndSetTail(pred, node)) {
        pred.next.set(node)
        return node
      }
    }
    enq(node)
    node
  }

  private def setHead(node: Node): Unit = {
    head.set(node)
    node.thread.set(null)
    node.prev.set(null)
  }

  private def unparkSuccessor(node: Node): Unit = {
    val ws: Int = node.waitStatus.load()
    if (ws < 0)
      compareAndSetWaitStatus(node, ws, 0)

    var s: Node = node.next.get()
    if (s == null || s.waitStatus.load() > 0) {
      s = null
      var t: Node = tail.get()
      while (t != null && t != node) {
        if (t.waitStatus.load() <= 0)
          s = t
        t = t.prev.get()
      }
    }
    if (s != null)
      LockSupport.unpark(s.thread.get())
  }

  private def doReleaseShared(): Unit = {
    var break: Boolean    = false
    var continue: Boolean = false
    while (!break) {
      continue = false
      val h: Node = head.get()
      if (h != null && h != tail.get()) {
        val ws: Int = h.waitStatus.load()
        if (ws == Node.SIGNAL) {
          if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
            continue = true
          if (!continue) unparkSuccessor(h)
        } else if (ws == 0 && !compareAndSetWaitStatus(h, 0, Node.PROPAGATE) && !continue)
          continue = true
      }
      if (h == head.get() && !continue)
        break = true
    }
  }

  private def setHeadAndPropagate(node: Node, propagate: Int): Unit = {
    val h: Node = head.get()
    setHead(node)

    if (propagate > 0 || h == null || h.waitStatus.load() < 0) {
      val s: Node = node.next.get()
      if (s == null || s.isShared)
        doReleaseShared()
    }
  }

  private def cancelAcquire(node: Node): Unit = {
    if (node == null)
      return
    node.thread.set(null)

    var pred: Node = node.prev.get()
    while (pred.waitStatus.load() > 0) pred = pred.prev.get()
    node.prev.set(pred)

    val predNext: Node = pred.next.get()

    node.waitStatus.store(Node.CANCELLED)

    if (node == tail.get() && compareAndSetTail(node, pred)) {
      compareAndSetNext(pred, predNext, null)
    } else {
      val ws: Int = pred.waitStatus.load()
      if (pred != head.get() &&
          (ws == Node.SIGNAL ||
          (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
          pred.thread != null) {
        val next: Node = node.next.get()
        if (next != null && next.waitStatus.load() <= 0)
          compareAndSetNext(pred, pred.next.get(), next)
      } else {
        unparkSuccessor(node)
      }

      node.next.set(node)
    }
  }

  private def parkAndCheckInterrupt(): Boolean = {
    LockSupport.park()
    Thread.interrupted()
  }

  def acquireQueued(node: Node, arg: Int): Boolean = {
    var failed = true
    try {
      var interrupted = false
      while (true) {
        val p: Node = node.predecessor()
        if (p == head.get() && tryAcquire(arg)) {
          setHead(node)
          p.next.set(null)
          failed = false
          return interrupted
        }
        if (shouldParkAfterFailedAcquire(p, node) &&
            parkAndCheckInterrupt())
          interrupted = true
      }
      // for the compiler
      false
    } finally {
      if (failed)
        cancelAcquire(node)
    }
  }

  private def doAcquireInterruptibly(arg: Int): Unit = {
    val node: Node      = addWaiter(Node.EXCLUSIVE)
    var failed: Boolean = true
    try {
      while (true) {
        val p: Node = node.predecessor()
        if (p == head.get() && tryAcquire(arg)) {
          setHead(node)
          p.next.set(null) // help GC)
          failed = false
          return
        }
        if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt())
          throw new InterruptedException
      }
    } finally {
      if (failed)
        cancelAcquire(node)
    }
  }

  private def doAcquireNanos(arg: Int, timeout: Long): Boolean = {
    var nanosTimeout   = timeout
    var lastTime: Long = System.nanoTime()
    val node: Node     = addWaiter(Node.EXCLUSIVE)
    var failed         = true
    try {
      while (true) {
        val p: Node = node.predecessor()
        if (p == head.get() && tryAcquire(arg)) {
          setHead(node)
          p.next.set(null)
          failed = false
          return true
        }
        if (nanosTimeout <= 0)
          return false
        if (shouldParkAfterFailedAcquire(p, node) &&
            nanosTimeout > AbstractQueuedSynchronizer.spinForTimeoutThreshold)
          LockSupport.parkNanos(nanosTimeout)
        val now: Long = System.nanoTime()
        nanosTimeout -= now - lastTime
        lastTime = now
        if (Thread.interrupted())
          throw new InterruptedException()
      }
      // for the compiler
      false
    } finally {
      if (failed)
        cancelAcquire(node)
    }
  }

  private def doAcquireShared(arg: Int): Unit = {
    val node: Node = addWaiter(Node.SHARED)
    var failed     = true
    try {
      var interrupted = true
      while (true) {
        val p: Node = node.predecessor()
        if (p == head.get()) {
          val r: Int = tryAcquireShared(arg)
          if (r >= 0) {
            setHeadAndPropagate(node, r)
            p.next.set(null)
            if (interrupted)
              AbstractQueuedSynchronizer.selfInterrupt()
            failed = false
            return
          }
        }
        if (shouldParkAfterFailedAcquire(p, node) &&
            parkAndCheckInterrupt())
          interrupted = true
      }
    } finally {
      if (failed)
        cancelAcquire(node)
    }
  }

  private def doAcquireSharedInterruptibly(arg: Int): Unit = {
    val node: Node = addWaiter(Node.SHARED)
    var failed     = true
    try {
      while (true) {
        val p: Node = node.predecessor()
        if (p == head.get()) {
          val r: Int = tryAcquireShared(arg)
          if (r >= 0) {
            setHeadAndPropagate(node, r)
            p.next.set(null) // help GC)
            failed = false
            return
          }
        }
        if (shouldParkAfterFailedAcquire(p, node) &&
            parkAndCheckInterrupt())
          throw new InterruptedException()
      }
    } finally {
      if (failed)
        cancelAcquire(node)
    }
  }

  private def doAcquireSharedNanos(arg: Int, nt: Long): Boolean = {
    var nanosTimeout: Long = nt
    var lastTime: Long     = System.nanoTime()
    val node: Node         = addWaiter(Node.SHARED)
    var failed             = true
    try {
      while (true) {
        val p: Node = node.predecessor()
        if (p == head.get()) {
          val r: Int = tryAcquireShared(arg)
          if (r >= 0) {
            setHeadAndPropagate(node, r)
            p.next.set(null)
            failed = false
            return true
          }
        }
        if (nanosTimeout <= 0)
          return false
        if (shouldParkAfterFailedAcquire(p, node) &&
            nanosTimeout > AbstractQueuedSynchronizer.spinForTimeoutThreshold)
          LockSupport.parkNanos(nanosTimeout)
        val now: Long = System.nanoTime()
        nanosTimeout -= now - lastTime
        lastTime = now
        if (Thread.interrupted())
          throw new InterruptedException()
      }
      // for the compiler
      false
    } finally {
      if (failed)
        cancelAcquire(node)
    }
  }

  protected def tryAcquire(arg: Int): Boolean =
    throw new UnsupportedOperationException()

  protected def tryRelease(arg: Int): Boolean =
    throw new UnsupportedOperationException()

  protected def tryAcquireShared(arg: Int): Int =
    throw new UnsupportedOperationException()

  protected def tryReleaseShared(arg: Int): Boolean =
    throw new UnsupportedOperationException()

  protected def isHeldExclusively(): Boolean =
    throw new UnsupportedOperationException()

  final def acquire(arg: Int): Unit = {
    if (!tryAcquire(arg) &&
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
      selfInterrupt()
  }

  final def acquireInterruptibly(arg: Int): Unit = {
    if (Thread.interrupted())
      throw new InterruptedException()
    if (!tryAcquire(arg)) {
      doAcquireInterruptibly(arg)
    }
  }

  final def tryAcquireNanos(arg: Int, nanosTimeout: Long): Boolean = {
    if (Thread.interrupted())
      throw new InterruptedException()
    tryAcquire(arg) || doAcquireNanos(arg, nanosTimeout)
  }

  final def release(arg: Int): Boolean = {
    if (tryRelease(arg)) {
      val h: Node = head.get()
      if (h != null && h.waitStatus.load() != 0)
        unparkSuccessor(h)
      return true
    }
    false
  }

  final def acquireShared(arg: Int): Unit = {
    if (tryAcquireShared(arg) < 0)
      doAcquireShared(arg)
  }

  final def acquireSharedInterruptibly(arg: Int): Unit = {
    if (Thread.interrupted())
      throw new InterruptedException()
    if (tryAcquireShared(arg) < 0)
      doAcquireSharedInterruptibly(arg)
  }

  final def tryAcquireSharedNanos(arg: Int, nanosTimeout: Long): Boolean = {
    if (Thread.interrupted())
      throw new InterruptedException()
    tryAcquireShared(arg) >= 0 || doAcquireSharedNanos(arg, nanosTimeout)
  }

  final def releaseShared(arg: Int): Boolean = {
    if (tryReleaseShared(arg)) {
      doReleaseShared()
      return true
    }
    false
  }

  final def hasQueuedThreads(): Boolean = head.get() != tail.get()

  final def hasContented(): Boolean = head.get() != null

  final def getFirstQueuedThread(): Thread =
    if (head.get() == tail.get()) null else fullGetFirstQueuedThread()

  private def fullGetFirstQueuedThread(): Thread = {
    val h: Node    = head.get()
    val s: Node    = h.next.get()
    val st: Thread = s.thread.get()

    if ((h != null && s != null &&
        s.prev.get() == head.get() && st != null) ||
        (h != null && s != null &&
        s.prev.get() == head.get() && st != null))
      return st

    var t: Node             = tail.get()
    var firstThread: Thread = null
    while (t != null && t != head.get()) {
      val tt: Thread = t.thread.get()
      if (tt != null)
        firstThread = tt
      t = t.prev.get()
    }
    firstThread
  }

  final def isQueued(thread: Thread): Boolean = {
    if (thread == null)
      throw new NullPointerException()
    var p: Node = tail.get()
    while (p != null) {
      if (p.thread.get() == thread)
        return true
      p = p.prev.get()
    }
    false
  }

  final def apparentlyFirstQueuedIsExclusive(): Boolean = {
    val h: Node = head.get()
    val s: Node = h.next.get()
    h != null && s != null &&
    !s.isShared && s.thread.get() != null
  }

  final def hasQueuedPredecessors(): Boolean = {
    val t: Node = tail.get()
    val h: Node = head.get()
    val s: Node = h.next.get()
    h != t && (s == null || s.thread.get() != Thread.currentThread())
  }

  final def getQueueLength: Int = {
    var n       = 0
    var p: Node = tail.get()
    while (p != null) {
      if (p.thread.get() != null)
        n += 1
      p = p.prev.get()
    }
    n
  }

  final def getQueuedThreads(): util.Collection[Thread] = {
    val list: util.ArrayList[Thread] = new util.ArrayList[Thread]()
    var p: Node                      = tail.get()
    while (p != null) {
      val t: Thread = p.thread.get()
      if (t != null)
        list.add(t)
      p = p.prev.get()
    }
    list
  }

  final def getExclusiveQueuedThreads(): util.Collection[Thread] = {
    val list: util.ArrayList[Thread] = new util.ArrayList[Thread]()
    var p: Node                      = tail.get()
    while (p != null) {
      if (!p.isShared) {
        val t: Thread = p.thread.get()
        if (t != null)
          list.add(t)
        p = p.prev.get()
      }
    }
    list
  }

  final def getSharedQueuedThreads(): util.Collection[Thread] = {
    val list: util.ArrayList[Thread] = new util.ArrayList[Thread]()
    var p: Node                      = tail.get()
    while (p != null) {
      if (p.isShared) {
        val t: Thread = p.thread.get()
        if (t != null)
          list.add(t)
        p = p.prev.get()
      }
    }
    list
  }

  override def toString: String = {
    val s: Int    = getState
    val q: String = if (hasQueuedThreads()) "non" else ""
    super.toString + "[State = " + s + ", " + q + "empty queue]"
  }

  final def isOnSyncQueue(node: Node): Boolean = {
    if (node.waitStatus.load() == Node.CONDITION || node.prev.get() == null)
      return false
    if (node.next.get() != null)
      return true
    findNodeFromTail(node)
  }

  private def findNodeFromTail(node: Node): Boolean = {
    var t: Node = tail.get()
    while (true) {
      if (t == node)
        return true
      if (t == null)
        return false
      t = t.prev.get()
    }
    // for the compiler
    false
  }

  final def transferForSignal(node: Node): Boolean = {
    if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
      return false

    val p: Node = enq(node)
    val ws: Int = p.waitStatus.load()
    if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
      LockSupport.unpark(node.thread.get())
    true
  }

  final def transferAfterCancelledWait(node: Node): Boolean = {
    if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
      enq(node)
      return true
    }

    while (!isOnSyncQueue(node)) {
      Thread.`yield`()
    }
    false
  }

  final def fullyRelease(node: Node): Int = {
    var failed = true
    try {
      val savedState: Int = getState
      if (release(savedState)) {
        failed = false
        savedState
      } else {
        throw new IllegalMonitorStateException()
      }
    } finally {
      if (failed)
        node.waitStatus.store(Node.CANCELLED)
    }
  }

  final def owns(
      condition: AbstractQueuedSynchronizer#ConditionObject): Boolean = {
    if (condition == null)
      throw new NullPointerException()
    condition.isOwnedBy(this)
  }

  final def hasWaiters(
      condition: AbstractQueuedSynchronizer#ConditionObject): Boolean = {
    if (!owns(condition))
      throw new IllegalArgumentException("Not owner")
    condition.hasWaiters
  }

  final def getWaitQueueLength(
      condition: AbstractQueuedSynchronizer#ConditionObject): Int = {
    if (!owns(condition))
      throw new IllegalArgumentException()
    condition.getWaitQueueLength
  }

  final def getWaitingThreads(
      condition: AbstractQueuedSynchronizer#ConditionObject)
    : util.Collection[Thread] = {
    if (!owns(condition))
      throw new IllegalArgumentException("Not owner")
    condition.getWaitingThreads
  }

  class ConditionObject extends Condition with java.io.Serializable {

    import ConditionObject._

    private var firstWaiter: Node = _
    private var lastWaiter: Node  = _

    private def addConditionWaiter(): Node = {
      var t: Node = lastWaiter

      if (t != null && t.waitStatus.load() != Node.CONDITION) {
        unlinkCancelledWaiters()
        t = lastWaiter
      }
      val node: Node = new Node(Thread.currentThread(), Node.CONDITION)
      if (t == null)
        firstWaiter = node
      else
        t.nextWaiter = node
      lastWaiter = node
      node
    }

    private def doSignal(f: Node): Unit = {
      var first: Node = f
      do {
        firstWaiter = first.nextWaiter
        if (firstWaiter == null)
          lastWaiter = null
        first.nextWaiter = null
        first = firstWaiter
      } while (!transferForSignal(first) && first != null)
    }

    private def doSignalAll(f: Node) = {
      var first: Node = f
      firstWaiter = null
      lastWaiter = firstWaiter
      do {
        val next: Node = first.nextWaiter
        first.nextWaiter = null
        transferForSignal(first)
        first = next
      } while (first != null)
    }

    private def unlinkCancelledWaiters(): Unit = {
      var t: Node     = firstWaiter
      var trail: Node = null
      while (t != null) {
        val next: Node = t.nextWaiter
        if (t.waitStatus.load() != Node.CONDITION) {
          t.nextWaiter = null
          if (trail == null)
            firstWaiter = next
          else
            trail.nextWaiter = next
          if (next == null)
            lastWaiter = trail
        } else
          trail = t
        t = next
      }
    }

    final def signal(): Unit = {
      if (!isHeldExclusively())
        throw new IllegalMonitorStateException()
      val first: Node = firstWaiter
      if (first != null)
        doSignal(first)
    }

    final def signalAll(): Unit = {
      if (!isHeldExclusively())
        throw new IllegalMonitorStateException()
      val first: Node = firstWaiter
      if (first != null)
        doSignalAll(first)
    }

    final def awaitUninterruptibly(): Unit = {
      val node: Node      = addConditionWaiter()
      val savedState: Int = fullyRelease(node)
      var interrupted     = false
      while (!isOnSyncQueue(node)) {
        LockSupport.park()
        if (Thread.interrupted())
          interrupted = true
      }
      if (acquireQueued(node, savedState) || interrupted)
        AbstractQueuedSynchronizer.selfInterrupt()
    }

    private def checkInterruptWhileWaiting(node: Node): Int = {
      if (Thread.interrupted())
        if (transferAfterCancelledWait(node)) THROW_IE else REINTERRUPT
      else 0
    }

    private def reportInterruptAfterWait(interruptMode: Int): Unit = {
      if (interruptMode == THROW_IE)
        throw new InterruptedException()
      else if (interruptMode == REINTERRUPT)
        AbstractQueuedSynchronizer.selfInterrupt()
    }

    final def await(): Unit = {
      if (Thread.interrupted())
        throw new InterruptedException()
      val node: Node      = addConditionWaiter()
      val savedState: Int = fullyRelease(node)
      var interruptMode   = 0
      var break: Boolean  = false
      while (!isOnSyncQueue(node) && !break) {
        LockSupport.park()
        interruptMode = checkInterruptWhileWaiting(node)
        if (interruptMode != 0)
          break = true
      }
      if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT
      if (node.nextWaiter != null)
        unlinkCancelledWaiters()
      if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode)
    }

    final def awaitNanos(nt: Long): Long = {
      var nanosTimeout: Long = nt
      if (Thread.interrupted())
        throw new InterruptedException()
      val node: Node         = addConditionWaiter()
      val savedState: Int    = fullyRelease(node)
      var lastTime: Long     = System.nanoTime()
      var interruptMode: Int = 0
      var break: Boolean     = false
      while (!isOnSyncQueue(node) && !break) {
        if (nanosTimeout <= 0L) {
          transferAfterCancelledWait(node)
          break = true
        }
        if (!break) {
          LockSupport.parkNanos(nanosTimeout)
          interruptMode = checkInterruptWhileWaiting(node)
          if (interruptMode != 0)
            break = true

          if (!break) {
            val now: Long = System.nanoTime()
            nanosTimeout -= now - lastTime
            lastTime = now
          }
        }
      }
      if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT
      if (node.nextWaiter != null)
        unlinkCancelledWaiters()
      if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode)
      nanosTimeout - (System.nanoTime() - lastTime)
    }

    final def awaitUntil(deadline: Date): Boolean = {
      if (deadline == null)
        throw new NullPointerException()
      val abstime: Long = deadline.getTime()
      if (Thread.interrupted())
        throw new InterruptedException()
      val node: Node      = addConditionWaiter()
      val savedState: Int = fullyRelease(node)
      var timedout        = false
      var interruptMode   = 0
      var break: Boolean  = false
      while (!isOnSyncQueue(node) && !break) {
        if (System.currentTimeMillis() > abstime) {
          timedout = transferAfterCancelledWait(node)
          break = true
        }
        if (!break) {
          LockSupport.parkUntil(abstime)
          interruptMode = checkInterruptWhileWaiting(node)
          if (interruptMode != 0)
            break = true
        }
      }
      if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT
      if (node.nextWaiter != null)
        unlinkCancelledWaiters()
      if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode)
      !timedout
    }

    final def await(time: Long, unit: TimeUnit): Boolean = {
      if (unit == null)
        throw new NullPointerException()
      var nanosTimeout: Long = unit.toNanos(time)
      if (Thread.interrupted())
        throw new InterruptedException()
      val node: Node         = addConditionWaiter()
      val savedState: Int    = fullyRelease(node)
      var lastTime: Long     = System.nanoTime()
      var timedout           = false
      var interruptMode: Int = 0
      var break: Boolean     = false
      while (!isOnSyncQueue(node) && !break) {
        if (nanosTimeout <= 0L) {
          timedout = transferAfterCancelledWait(node)
          break = true
        }
        if (!break) {
          if (nanosTimeout >= spinForTimeoutThreshold)
            LockSupport.parkNanos(nanosTimeout)
          interruptMode = checkInterruptWhileWaiting(node)
          if (interruptMode != 0)
            break = true
          if (!break) {
            val now: Long = System.nanoTime()
            nanosTimeout -= now - lastTime
            lastTime = now
          }
        }
      }
      if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT
      if (node.nextWaiter != null)
        unlinkCancelledWaiters()
      if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode)
      !timedout
    }

    final def isOwnedBy(sync: AbstractQueuedSynchronizer): Boolean =
      sync == AbstractQueuedSynchronizer.this

    protected[locks] final def hasWaiters(): Boolean = {
      if (!isHeldExclusively())
        throw new IllegalMonitorStateException()
      var w: Node = firstWaiter
      while (w != null) {
        if (w.waitStatus.load() == Node.CONDITION)
          return true
        w = w.nextWaiter
      }
      false
    }

    protected[locks] final def getWaitQueueLength(): Int = {
      if (!isHeldExclusively())
        throw new IllegalMonitorStateException()
      var n       = 0
      var w: Node = firstWaiter
      while (w != null) {
        if (w.waitStatus.load() == Node.CONDITION)
          n += 1
        w = w.nextWaiter
      }
      n
    }

    protected[locks] final def getWaitingThreads(): util.Collection[Thread] = {
      if (!isHeldExclusively())
        throw new IllegalMonitorStateException()
      val list: util.ArrayList[Thread] = new util.ArrayList[Thread]()
      var w: Node                      = firstWaiter
      while (w != null) {
        if (w.waitStatus.load() == Node.CONDITION) {
          val t: Thread = w.thread.get()
          if (t != null)
            list.add(t)
          w = w.nextWaiter
        }
      }
      list
    }

  }

  object ConditionObject {

    private final val serialVersionUID: CLong = 1173984872572414699L

    private final val REINTERRUPT: CInt = 1

    private final val THROW_IE: CInt = -1

  }

  private final def compareAndSetHead(update: Node): Boolean =
    head.compareAndSet(head.get(), update)

  private final def compareAndSetTail(expect: Node, update: Node): Boolean =
    tail.compareAndSet(expect, update)
}

object AbstractQueuedSynchronizer {

  private final val serialVersionUID: Long = 7373984972572414691L

  final val spinForTimeoutThreshold: Long = 1000L

  private def shouldParkAfterFailedAcquire(p: Node, node: Node): Boolean = {
    var pred: Node = p
    val ws: Int = pred.waitStatus.load()
    if (ws == Node.SIGNAL) return true
    if (ws > 0) {
      do {
        pred = pred.prev.get()
        node.prev.set(pred)
      } while (pred.waitStatus.load() > 0)
      pred.next.set(node)
    } else {
      compareAndSetWaitStatus(pred, ws, Node.SIGNAL)
    }
    false
  }

  private def selfInterrupt(): Unit = Thread.currentThread().interrupt()

  private final def compareAndSetWaitStatus(node: Node,
                                            expect: Int,
                                            update: Int): Boolean =
    node.waitStatus.compareAndSwapStrong(expect, update)._1

  private final def compareAndSetNext(node: Node, expect: Node, update: Node) =
    node.next.compareAndSet(expect, update)

  final class Node {

    import Node._

    var waitStatus: CAtomicInt = CAtomicInt()

    //volatile
    var prev = new AtomicReference[Node]

    //volatile
    var next = new AtomicReference[Node]

    //volatile
    var thread = new AtomicReference[Thread]

    var nextWaiter: Node = _

    def this(thread: Thread, mode: Node) = {
      this()
      this.nextWaiter = mode
      this.thread.set(thread)
    }

    def this(thread: Thread, waitStatus: Int) = {
      this()
      this.waitStatus.store(waitStatus)
      this.thread.set(thread)
    }

    def isShared: Boolean = nextWaiter == SHARED

    def predecessor(): Node = {
      val p = prev.get()
      if (p == null)
        throw new NullPointerException()
      else p
    }

  }

  object Node {

    final val SHARED: Node = new Node()

    final val EXCLUSIVE: Node = null

    final val CANCELLED: CInt = 1

    final val SIGNAL: CInt = -1

    final val CONDITION: CInt = -2

    final val PROPAGATE: CInt = -3

  }
}
