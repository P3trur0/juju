package juju.domain

import akka.actor.{ActorLogging, Props}
import akka.persistence.PersistentActor
import juju.messages.{DomainEvent, Command}

class AggregateRootNotInitializedException extends Exception

abstract class AggregateRootFactory[A<: AggregateRoot[_]] {
  def props : Props
}

trait AggregateState {
  type StateMachine = PartialFunction[DomainEvent, AggregateState]
  def apply: StateMachine
}

object AggregateRoot {
  trait AggregateIdResolution[A <: AggregateRoot[_]] {
    def resolve(command: Command) : String
  }

  trait AggregateHandlersResolution[A <: AggregateRoot[_]] {
    def resolve() : Seq[Class[_ <: Command]]
  }
}

abstract class AggregateRoot[S <: AggregateState]
  extends PersistentActor with ActorLogging {

  def id = self.path.parent.name + '_' + self.path.name
  override def persistenceId: String = id
  log.debug(s"created AggregateRoot ${this.getClass.getCanonicalName} with id $id")
  private var stateOpt: Option[S] = None

  def isStateInitialized = !stateOpt.isEmpty
  protected def state = if (isStateInitialized) stateOpt.get else throw new AggregateRootNotInitializedException

  type AggregateStateFactory = PartialFunction[DomainEvent, S]
  val factory : AggregateStateFactory

  def handle : Receive

  def nextState(event: DomainEvent): S = {
    stateOpt match {
      case _ if stateOpt.isEmpty => factory.apply(event)
      case _ =>
        state.apply(event).asInstanceOf[S]
    }
  }

  override def receiveRecover: Receive = {
    case e: DomainEvent =>
      val next = nextState(e)
      stateOpt = Some(next)
    case _ =>
  }

  def raise(event: DomainEvent) = {
    persist(event) {
      case e: DomainEvent =>
        val next = nextState(event)
        stateOpt = Some(next)
        log.debug(s"event $e persisted and state has been changed")
        sender ! e
      case _ =>
    }
  }

  def raise(events: Seq[DomainEvent]) : Unit = {
    val s = sender
    events match {
      //TODO: make persist and send back events fault tolerant
      case e +: rest =>
        val next = nextState(e)
        stateOpt = Some(next)
        log.debug(s"event $e persisted and state has been changed")
        sender ! e
        raise(rest)
      case e +: Nil =>
        raise(e)
      case Nil =>
    }
  }


  override def receiveCommand: Receive = {
    case cmd: Command =>handle(cmd)
    case _ =>
  }
}