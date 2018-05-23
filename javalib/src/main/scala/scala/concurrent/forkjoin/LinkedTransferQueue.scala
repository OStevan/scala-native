package scala.concurrent.forkjoin

import java.util
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

import scala.scalanative.native.{CInt, CLong, Ptr, sysinfo, CCast}
import scala.scalanative.runtime.CAtomicInt

class LinkedTransferQueue[E <: AnyRef] extends util.AbstractQueue[E] with TransferQueue[E]{
  import scala.concurrent.forkjoin.LinkedTransferQueue._
  protected[forkjoin] val head = new AtomicReference[Node]()
  private val tail = new AtomicReference[Node]()
  private val sweepVotes = new CAtomicInt()

  def this(c: util.Collection[_ <: E]) = {
    this()
    addAll(c)
  }

  private def casTail(cmp: Node, value: Node): Boolean = {
    tail.compareAndSet(cmp, value)
  }
  private def casHead(cmp: Node, value: Node): Boolean = {
    head.compareAndSet(cmp, value)
  }
  private def casSweepVotes(cmp: CInt, value: CInt): Boolean = {
    sweepVotes.compareAndSwapStrong(cmp, value)._1
  }

  private def xfer(e: E, haveData: Boolean, how: Int, nanos: Long): E = {
    if (haveData && (e == null))
      throw new NullPointerException
    var s: Node = null

    var outerFlag = true

    while (outerFlag) {
      var innerFlag = true
      while (innerFlag) {
        var h = head.get()
        var p = h
        val isData = p.isData
        val item = p.item.get()
        if (item != p && (item != null) == isData) {
          if (isData == haveData)
            innerFlag = false
          else if (p.casItem(item, e.asInstanceOf[AnyRef])) {
            var q = p
            while (q != h) {
              val n = q.next.get()
              if (head.get() == h && casHead(h, if (n== null) q else n)) {
                h.forgetNext()
                innerFlag = false
              }
              if (innerFlag) {
                h = head.get()
                q = h.next.get()
                if (h == null || q == null || !q.isMatched())
                  innerFlag = false
              }
            }
            if (innerFlag) {
              LockSupport.park(p.waiter)
              return item.asInstanceOf[E]
            }
          }
        }

        if (innerFlag) {
          val n = p.next.get()
          p = if (p != n) n else {h = head.get(); h}
        }
      }

      if (how != NOW) {
        if (s == null)
          s = new Node(e.asInstanceOf[AnyRef], haveData)
        val pred = tryAppend(s, haveData)
        if (pred != null && how != ASYNC)
          return awaitMatch(s, pred, e, how == TIMED, nanos)
        if (pred != null && how == ASYNC)
          return e
      } else
        return e
    }
    null.asInstanceOf[E]
  }

  private def tryAppend(node: Node, haveData: Boolean): Node = {
    var t = tail.get()
    var p = t
    var nodeInside = node
    while (true) {
      var n: Node = null
      var u: Node = null

      if (p == null && {p = head.get(); p} == null) {
        if (casHead(null, nodeInside))
          return nodeInside
      } else if ({n = p.next.get(); n} != null) {
        p = if (p != t && t != {u = tail.get(); u}) {t = u; t} else if (p != n) n else null
      } else if (!p.casNext(null, nodeInside))
        p = p.next.get()
      else {
        if (p != t) {
          while ((tail.get() != t || !casTail(t, nodeInside)) &&
            {t = tail.get(); t} != null &&
            {nodeInside = t.next.get(); nodeInside} != null &&
            {nodeInside = nodeInside.next.get(); nodeInside} != null && nodeInside != t) {}
        }
        return p
      }
    }
    null.asInstanceOf[Node]
  }

  private def awaitMatch(node: Node, pred: Node, e: E, timed: Boolean, nanos: Long): E = {
    var lastTime = if (timed) System.nanoTime() else 0L
    val thread = Thread.currentThread()
    var spins = -1
    var randomYields: ThreadLocalRandom = null
    var nanosTime = nanos
    while (true) {
      val item: AnyRef = node.item.get()
      // workaround for error
      // (nativelib/compile:compileIncremental) scala.reflect.internal.FatalError: object BoxesRunTime does not have a decl equals
      // when using simple item != e
      // this way we get the address of these objects as CLong and compare them
      if (item.cast[CLong] != e.asInstanceOf[AnyRef].cast[CLong]) {
        node.forgetContents()
        return item.asInstanceOf[E]
      }
      if ((thread.isInterrupted || (timed && nanos <= 0)) &&
        node.casItem(e.asInstanceOf, node)) {
        unsplice(pred, node)
        return e
      }

      if (spins < 0) {
        if ( {
          spins = spinsFor(pred, node.isData);
          spins
        } > 0) {
          randomYields = ThreadLocalRandom.current
        }
      } else if (spins > 0) {
        spins -= 1
        if (randomYields.nextInt(CHAINED_SPINS) == 0)
          Thread.`yield`()
      }
      else if (node.waiter.get() == null) {
        node.waiter.set(thread)
      } else if (timed) {
        val now = System.nanoTime()
        if ( {
          nanosTime -= now - lastTime; nanosTime
        } > 0)
          LockSupport.parkNanos(this, nanosTime)
        lastTime = now
      }
    }
    null.asInstanceOf
  }

  private[forkjoin] def succ(p: Node): Node = {
    val next: Node = p.next.get()
    if (p == next) head.get() else next
  }

  private def firstOfMode(isData: Boolean): Node = {
    var node = head.get()
    while (node != null) {
      if (!node.isMatched)
        return if (node.isData == isData) node else null
      node = succ(node)
    }
    null
  }

  private def firstDataItem(): E = {
    var node = head.get()
    while (node != null) {
      val item: AnyRef = node.item.get()
      if (node.isData) {
        if (item != null && item != node)
          return node.asInstanceOf[E]
      } else if (item == null) {
        return null.asInstanceOf[E]
      }
      node = succ(node)
    }
    null.asInstanceOf[E]
  }

  private def countOfMode(data: Boolean): Int = {
    var count = 0
    var node = head.get()
    var flag = true
    while (flag && node != null) {
      if (!node.isMatched) {
        if (node.isData != data)
          return 0
        count += 1
        if (count == Integer.MAX_VALUE)
          flag = false
      }
      if (flag) {
        val n: Node = node.next.get()
        if (n != node) {
          node = n
        } else {
          count = 0
          node = head.get()
        }
      }
    }
    count
  }

  sealed class Itr extends java.util.Iterator[E] {
    private var nextNode: Node = _
    private var nextItem: E = null.asInstanceOf[E]
    private var lastRet: Node = _
    private var lastPred: Node = _

    advance(null)

    private def advance(prev: Node): Unit = {
      var r: Node = null
      var b: Node = null
      if ({r = lastRet; r} != null && !r.isMatched) {
        lastPred = r
      } else if ({b = lastPred; b} == null || b.isMatched)
        lastPred = null
      else {
        var s: Node = null
        var n: Node = null
        while ({s = b.next.get(); s} != null &&
          s != b && s.isMatched &&
          {n = s.next.get(); s} != null && n != s)
          b.casNext(s, n)
      }

      this.lastRet = prev

      var p = prev
      var s, n: Node = null
      var continue = false
      var flag = true
      while (flag) {
        continue = false
        s = if (p == null) head.get() else p.next.get()
        if (s == null)
          flag = false
        else if (s == p) {
          p = null
          continue = true
        }
        if (!continue && flag) {
          val item: AnyRef = s.item.get()
          if (s.isData) {
            if (item != null && item != s) {
              nextItem = item.asInstanceOf[E]
              nextNode = s
              return
            }
          } else if (item == null)
            flag = false

          if (flag) {
            if (p == null)
              p = s
            else if ({n = s.next.get(); n} == null)
              flag = false
            else if (s == n)
              p = null
            else
              p.casNext(s, n)
          }
        }
      }
      nextItem = null.asInstanceOf[E]
      nextNode = null
    }

    override final def hasNext: Boolean = nextNode != null

    override final def next(): E = {
      val p = nextNode
      if (p == null) throw new NoSuchElementException
      val e = nextItem
      advance(p)
      e
    }

    final def remove() = {
      val lastRet = this.lastRet
      if (lastRet == null)
        throw new IllegalStateException()
      this.lastRet = null
      if (lastRet.tryMatchData())
        unsplice(lastPred, lastRet)
    }
  }

  private[forkjoin] final def unsplice(pred: Node, s: Node): Unit = {
    s.forgetContents()

    if (pred != null && pred != s && pred.next.get() == s) {
      val n: Node = s.next.get()

      if (n == null ||
        (n != s && pred.casNext(s, n) && pred.isMatched)) {
        var breakFlag = false
        while (!breakFlag) {
          val h = head.get()
          if (h == pred || h ==s || h == null)
            return
          if (!h.isMatched) {
            val hn: Node = h.next.get()
            if (hn == null)
              return
            if (hn != h && casHead(h, hn))
              h.forgetNext()
          } else
            breakFlag = true
        }

        if (pred.next.get() != pred && s.next.get() != s) {
          var breakFlag = false
          while (!breakFlag) {
            var v = sweepVotes.load()
            if (v < LinkedTransferQueue.SWEEP_THRESHOLD) {
              if (casSweepVotes(v, v + 1))
                breakFlag = true
            } else if (casSweepVotes(v, 0)) {
              sweep()
              breakFlag = true
            }
          }
        }
      }
    }
  }

  private def sweep(): Unit = {
    var p, s, n: Node = null
    p = head.get()
    while (p != null && {s = p.next.get(); s} != null) {
      if (!s.isMatched)
        p = s
      else if ({n = s.next.get(); n} == null)
        return
      else if (s == n)
        p = head.get()
      else
        p.casNext(s, n)
    }
  }

  private def findAndRemove(obj: Object): Boolean = {
    if (obj != null) {
      var pred: Node = null
      var p: Node = head.get()
      var breakFlag = false
      while (p != null && !breakFlag) {
        val item: AnyRef = p.item.get()
        if (p.isData) {
          if (item != null && item != p && obj.equals(item) &&
            p.tryMatchData()) {
            unsplice(pred, p)
            return true
          }
        } else if (item == null)
          breakFlag = true
        if (!breakFlag) {
          pred = p
          if ({p = p.next.get(); p} == pred) {
            pred = null
            p = head.get()
          }
        }
      }
    }
    false
  }

  override def put(e: E): Unit = {
    xfer(e, haveData = true, ASYNC, 0)
  }

  override def offer(e: E, timeout: Long, unit: TimeUnit): Boolean = {
    xfer(e, true, ASYNC, 0)
    true
  }

  override def offer(e: E): Boolean = {
    xfer(e, true, ASYNC, 0)
    true
  }

  override def add(e: E): Boolean = {
    xfer(e, true, ASYNC, 0)
    true
  }

  override def tryTransfer(e: E): Boolean = xfer(e, true, NOW, 0) == null

  override def transfer(e: E): Unit =
    if (xfer(e, true, SYNC, 0) != null) {
      Thread.interrupted()
      throw new InterruptedException
    }

  override def tryTransfer(e: E, timeout: Long, timeUnit: TimeUnit): Boolean =
    if (xfer(e, true, TIMED, timeUnit.toNanos(timeout)) == null) {
      true
    } else if (!Thread.interrupted()) {
      false
    } else {
      throw new InterruptedException
    }

  override def take(): E = {
    val e: E = xfer(null.asInstanceOf, false, SYNC, 0)
    if (e != null)
      e
    else {
      Thread.interrupted()
      throw new InterruptedException
    }
  }

  override def poll(): E = xfer(null.asInstanceOf, false, NOW, 0)

  override def poll(timeout: Long, unit: TimeUnit): E = {
    val e = xfer(null.asInstanceOf, false, TIMED, unit.toNanos(timeout))
    if (e != null || !Thread.interrupted())
      e
    else
      throw new InterruptedException
  }

  override def drainTo(c: util.Collection[_ >: E]): CInt =
    if (c == null)
      throw new NullPointerException
    else if (c == this)
      throw new IllegalArgumentException
    else {
      var n = 0
      var e: E = null.asInstanceOf
      while ({e = poll(); e} != null) {
        c.add(e)
        n += 1
      }
      n
    }

  override def drainTo(c: util.Collection[_ >: E], maxElements: CInt): CInt =
    if (c == null)
      throw new NullPointerException
    else if (c == this)
      throw new IllegalArgumentException
    else {
      var n = 0
      var e: E = null.asInstanceOf
      while (n < maxElements && {e = poll(); e} != null) {
        c.add(e)
        n += 1
      }
      n
    }

  override def iterator(): util.Iterator[E] = new Itr()

  override def peek(): E = firstDataItem()

  override def isEmpty: Boolean = {
    var p = head.get()
    while (p != null) {
      if (!p.isMatched)
        return !p.isData
      p = succ(p)
    }
    true
  }

  override def hasWaitingConsumer: Boolean = firstOfMode(false) != null

  override def size(): Int = countOfMode(false)

  override def getWaitingConsumerCount: Int = countOfMode(false)

  override def remove(ref: AnyRef): Boolean = findAndRemove(ref)

  override def contains(o: scala.Any): Boolean = {
    if (o == null) return false
    var p = head.get()
    while (p != null) {
      val item: AnyRef = p.item.get()
      if (p.isData) {
        if (item != null && item != p && o.equals(item))
          return true
      } else if (item == null)
        return false
      p = succ(p)
    }
    false
  }

  override def remainingCapacity(): Int = Integer.MAX_VALUE
}

object LinkedTransferQueue {
  private val serialVersionUID = -3223113410248163686L
  private val MP = sysinfo.get_nprocs > 1
  private val FRONT_SPINS: CInt = 1 << 7
  private val CHAINED_SPINS: CInt = FRONT_SPINS >>> 1

  private[forkjoin] val SWEEP_THRESHOLD = 32
  private val NOW: CInt = 0
  private val ASYNC: CInt = 1
  private val SYNC: CInt = 2
  private val TIMED: CInt = 3

  private def spinsFor(pred: Node, haveData: Boolean): Int = {
    if (MP && pred != null) {
      if (pred.isData !=  haveData)
        return FRONT_SPINS + CHAINED_SPINS
      if (pred.isMatched())
        return FRONT_SPINS
      if (pred.waiter.get() == null)
        return CHAINED_SPINS
    }
    0
  }

  private[forkjoin] class Node(initialItem: AnyRef, private[forkjoin] val isData: Boolean) {
    private[forkjoin] val item = new AtomicReference[AnyRef](initialItem)
    private[forkjoin] val next = new AtomicReference[Node]()
    private[forkjoin] val waiter = new AtomicReference[Thread]()

    private[forkjoin] final def casNext(node: Node, value: Node): Boolean = next.compareAndSet(node, value)
    private[forkjoin] final def casItem(cmp: AnyRef, value: AnyRef): Boolean = item.compareAndSet(cmp, value)
    private[forkjoin] final def forgetNext(): Unit = next.set(this)
    private[forkjoin] final def forgetContents(): Unit = {
      item.set(this)
      waiter.set(null)
    }

    private[forkjoin] final def isMatched(): Boolean = {
      val x = item.get()
      x == this || ((x == null) == isData)
    }
    private[forkjoin] final def isUnmatchedRequest(): Boolean = !isData && item.get() == null
    private[forkjoin] final def cannotPrecede(haveData: Boolean) = {
      val d = isData
      val x = item.get()
      d != haveData && x != this && (x!= null) == d
    }
    private[forkjoin] final def tryMatchData() = {
      val x = item.get()
      if (x != null && x != this && casItem(x, null)) {
        LockSupport.unpark(waiter.get())
        true
      } else
        false
    }
  }

  // methods for serialization not needed
}
