/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import language.implicitConversions

import java.util.concurrent.TimeoutException
import akka.actor._
import akka.dispatch.sysmsg._
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.{ Future, Promise, ExecutionContext }
import akka.util.{ Timeout, Unsafe }
import scala.util.{ Success, Failure }

/**
 * This is what is used to complete a Future that is returned from an ask/? call,
 * when it times out.
 */
class AskTimeoutException(message: String, cause: Throwable) extends TimeoutException(message) {
  def this(message: String) = this(message, null: Throwable)
  override def getCause(): Throwable = cause
}

/**
 * This object contains implementation details of the “ask” pattern.
 */
trait AskSupport {

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorRef]], which will defer to the
   * `ask(actorRef, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * val future = actor ? message             // => ask(actor, message)
   * val future = actor ask message           // => ask(actor, message)
   * val future = actor.ask(message)(timeout) // => ask(actor, message)(timeout)
   * }}}
   *
   * All of the above use an implicit [[akka.util.Timeout]].
   */
  implicit def ask(actorRef: ActorRef): AskableActorRef = new AskableActorRef(actorRef)

  /**
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`).
   *
   * <b>Warning:</b>
   * When using future callbacks, inside actors you need to carefully avoid closing over
   * the containing actor’s object, i.e. do not call methods or access mutable state
   * on the enclosing actor from within the callback. This would break the actor
   * encapsulation and may introduce synchronization bugs and race conditions because
   * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
   * there is not yet a way to detect these illegal accesses at compile time.
   *
   * <b>Recommended usage:</b>
   *
   * {{{
   *   val f = ask(worker, request)(timeout)
   *   f.map { response =>
   *     EnrichedMessage(response)
   *   } pipeTo nextActor
   * }}}
   *
   */
  def ask(actorRef: ActorRef, message: Any)(implicit timeout: Timeout): Future[Any] = actorRef ? message

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorSelection]], which will defer to the
   * `ask(actorSelection, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * val future = selection ? message             // => ask(selection, message)
   * val future = selection ask message           // => ask(selection, message)
   * val future = selection.ask(message)(timeout) // => ask(selection, message)(timeout)
   * }}}
   *
   * All of the above use an implicit [[akka.util.Timeout]].
   */
  implicit def ask(actorSelection: ActorSelection): AskableActorSelection = new AskableActorSelection(actorSelection)

  /**
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`).
   *
   * <b>Warning:</b>
   * When using future callbacks, inside actors you need to carefully avoid closing over
   * the containing actor’s object, i.e. do not call methods or access mutable state
   * on the enclosing actor from within the callback. This would break the actor
   * encapsulation and may introduce synchronization bugs and race conditions because
   * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
   * there is not yet a way to detect these illegal accesses at compile time.
   *
   * <b>Recommended usage:</b>
   *
   * {{{
   *   val f = ask(worker, request)(timeout)
   *   f.map { response =>
   *     EnrichedMessage(response)
   *   } pipeTo nextActor
   * }}}
   *
   */
  def ask(actorSelection: ActorSelection, message: Any)(implicit timeout: Timeout): Future[Any] = actorSelection ? message
}

/*
 * Implementation class of the “ask” pattern enrichment of ActorRef
 */
final class AskableActorRef(val actorRef: ActorRef) extends AnyVal {

  def ask(message: Any)(implicit timeout: Timeout): Future[Any] = actorRef match {
    case ref: InternalActorRef if ref.isTerminated ⇒
      actorRef ! message
      Future.failed[Any](new AskTimeoutException(s"Recipient[$actorRef] had already been terminated."))
    case ref: InternalActorRef ⇒
      if (timeout.duration.length <= 0)
        Future.failed[Any](new IllegalArgumentException(s"Timeout length must not be negative, question not sent to [$actorRef]"))
      else {
        val a = PromiseActorRef(ref.provider, timeout, targetName = actorRef.toString)
        actorRef.tell(message, a)
        a.result.future
      }
    case _ ⇒ Future.failed[Any](new IllegalArgumentException(s"Unsupported recipient ActorRef type, question not sent to [$actorRef]"))
  }

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = ask(message)(timeout)
}

/*
 * Implementation class of the “ask” pattern enrichment of ActorSelection
 */
final class AskableActorSelection(val actorSel: ActorSelection) extends AnyVal {

  def ask(message: Any)(implicit timeout: Timeout): Future[Any] = actorSel.anchor match {
    case ref: InternalActorRef ⇒
      if (timeout.duration.length <= 0)
        Future.failed[Any](
          new IllegalArgumentException(s"Timeout length must not be negative, question not sent to [$actorSel]"))
      else {
        val a = PromiseActorRef(ref.provider, timeout, targetName = actorSel.toString)
        actorSel.tell(message, a)
        a.result.future
      }
    case _ ⇒ Future.failed[Any](new IllegalArgumentException(s"Unsupported recipient ActorRef type, question not sent to [$actorSel]"))
  }

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = ask(message)(timeout)
}

/**
 * Akka private optimized representation of the temporary actor spawned to
 * receive the reply to an "ask" operation.
 *
 * INTERNAL API
 */
private[akka] final class PromiseActorRef private (val provider: ActorRefProvider, val result: Promise[Any])
  extends MinimalActorRef {
  import PromiseActorRef._
  import AbstractPromiseActorRef.stateOffset
  import AbstractPromiseActorRef.watchedByOffset

  /**
   * As an optimization for the common (local) case we only register this PromiseActorRef
   * with the provider when the `path` member is actually queried, which happens during
   * serialization (but also during a simple call to `toString`, `equals` or `hashCode`!).
   *
   * Defined states:
   * null                  => started, path not yet created
   * Registering           => currently creating temp path and registering it
   * path: ActorPath       => path is available and was registered
   * StoppedWithPath(path) => stopped, path available
   * Stopped               => stopped, path not yet created
   */
  @volatile
  private[this] var _stateDoNotCallMeDirectly: AnyRef = _

  @volatile
  private[this] var _watchedByDoNotCallMeDirectly: Set[ActorRef] = ActorCell.emptyActorRefSet

  @inline
  private[this] def watchedBy: Set[ActorRef] = Unsafe.instance.getObjectVolatile(this, watchedByOffset).asInstanceOf[Set[ActorRef]]

  @inline
  private[this] def updateWatchedBy(oldWatchedBy: Set[ActorRef], newWatchedBy: Set[ActorRef]): Boolean =
    Unsafe.instance.compareAndSwapObject(this, watchedByOffset, oldWatchedBy, newWatchedBy)

  @tailrec // Returns false if the Promise is already completed
  private[this] final def addWatcher(watcher: ActorRef): Boolean = watchedBy match {
    case null  ⇒ false
    case other ⇒ updateWatchedBy(other, other + watcher) || addWatcher(watcher)
  }

  @tailrec
  private[this] final def remWatcher(watcher: ActorRef): Unit = watchedBy match {
    case null  ⇒ ()
    case other ⇒ if (!updateWatchedBy(other, other - watcher)) remWatcher(watcher)
  }

  @tailrec
  private[this] final def clearWatchers(): Set[ActorRef] = watchedBy match {
    case null  ⇒ ActorCell.emptyActorRefSet
    case other ⇒ if (!updateWatchedBy(other, null)) clearWatchers() else other
  }

  @inline
  private[this] def state: AnyRef = Unsafe.instance.getObjectVolatile(this, stateOffset)

  @inline
  private[this] def updateState(oldState: AnyRef, newState: AnyRef): Boolean =
    Unsafe.instance.compareAndSwapObject(this, stateOffset, oldState, newState)

  @inline
  private[this] def setState(newState: AnyRef): Unit = Unsafe.instance.putObjectVolatile(this, stateOffset, newState)

  override def getParent: InternalActorRef = provider.tempContainer

  def internalCallingThreadExecutionContext: ExecutionContext =
    provider.guardian.underlying.systemImpl.internalCallingThreadExecutionContext

  /**
   * Contract of this method:
   * Must always return the same ActorPath, which must have
   * been registered if we haven't been stopped yet.
   */
  @tailrec
  def path: ActorPath = state match {
    case null ⇒
      if (updateState(null, Registering)) {
        var p: ActorPath = null
        try {
          p = provider.tempPath()
          provider.registerTempActor(this, p)
          p
        } finally { setState(p) }
      } else path
    case p: ActorPath       ⇒ p
    case StoppedWithPath(p) ⇒ p
    case Stopped ⇒
      // even if we are already stopped we still need to produce a proper path
      updateState(Stopped, StoppedWithPath(provider.tempPath()))
      path
    case Registering ⇒ path // spin until registration is completed
  }

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = state match {
    case Stopped | _: StoppedWithPath ⇒ provider.deadLetters ! message
    case _ ⇒
      if (message == null) throw new InvalidMessageException("Message is null")
      if (!(result.tryComplete(
        message match {
          case Status.Success(r) ⇒ Success(r)
          case Status.Failure(f) ⇒ Failure(f)
          case other             ⇒ Success(other)
        }))) provider.deadLetters ! message
  }

  override def sendSystemMessage(message: SystemMessage): Unit = message match {
    case _: Terminate                      ⇒ stop()
    case DeathWatchNotification(a, ec, at) ⇒ this.!(Terminated(a)(existenceConfirmed = ec, addressTerminated = at))
    case Watch(watchee, watcher) ⇒
      if (watchee == this && watcher != this) {
        if (!addWatcher(watcher))
          // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watcher.sendSystemMessage(DeathWatchNotification(watchee, existenceConfirmed = true, addressTerminated = false))
      } else System.err.println("BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, this))
    case Unwatch(watchee, watcher) ⇒
      if (watchee == this && watcher != this) remWatcher(watcher)
      else System.err.println("BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, this))
    case _ ⇒
  }

  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2") override def isTerminated: Boolean = state match {
    case Stopped | _: StoppedWithPath ⇒ true
    case _                            ⇒ false
  }

  @tailrec
  override def stop(): Unit = {
    def ensureCompleted(): Unit = {
      result tryComplete Failure(new ActorKilledException("Stopped"))
      val watchers = clearWatchers()
      if (!watchers.isEmpty) {
        watchers foreach { watcher ⇒
          // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watcher.asInstanceOf[InternalActorRef]
            .sendSystemMessage(DeathWatchNotification(watcher, existenceConfirmed = true, addressTerminated = false))
        }
      }
    }
    state match {
      case null ⇒ // if path was never queried nobody can possibly be watching us, so we don't have to publish termination either
        if (updateState(null, Stopped)) ensureCompleted() else stop()
      case p: ActorPath ⇒
        if (updateState(p, StoppedWithPath(p))) { try ensureCompleted() finally provider.unregisterTempActor(p) } else stop()
      case Stopped | _: StoppedWithPath ⇒ // already stopped
      case Registering                  ⇒ stop() // spin until registration is completed before stopping
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] object PromiseActorRef {
  private case object Registering
  private case object Stopped
  private case class StoppedWithPath(path: ActorPath)

  def apply(provider: ActorRefProvider, timeout: Timeout, targetName: String): PromiseActorRef = {
    val result = Promise[Any]()
    val scheduler = provider.guardian.underlying.system.scheduler
    val a = new PromiseActorRef(provider, result)
    implicit val ec = a.internalCallingThreadExecutionContext
    val f = scheduler.scheduleOnce(timeout.duration) {
      result tryComplete Failure(new AskTimeoutException(s"Ask timed out on [$targetName]"))
    }
    result.future onComplete { _ ⇒ try a.stop() finally f.cancel() }
    a
  }
}
