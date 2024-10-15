import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

import akka.actor.typed.ActorSystem
import akka.stream.BoundedSourceQueue
import akka.stream.QueueCompletionResult
import akka.stream.QueueOfferResult

import akkastreamchat.pbdomain.v3.ServerCommand

package object akkastreamchat {

  val Quit = "/quit"

  def sendOne(
    out: BoundedSourceQueue[ServerCommand],
    cmd: ServerCommand,
    p: Promise[ServerCommand]
  )(implicit system: ActorSystem[?]): Unit =
    out.offer(cmd) match {
      case QueueOfferResult.Enqueued =>
        p.trySuccess(cmd)
      case QueueOfferResult.Dropped =>
        akka.pattern.after(ThreadLocalRandom.current().nextInt(5, 25).millis) {
          Future(sendOne(out, cmd, p))(system.executionContext)
        }
      case r: QueueCompletionResult =>
        p.failure(new Exception(s"Unexpected $r"))
    }

  def sendDm(
    to: BoundedSourceQueue[ServerCommand],
    from: BoundedSourceQueue[ServerCommand],
    cmd: ServerCommand,
    p: Promise[ServerCommand]
  )(implicit system: ActorSystem[?]): Unit =
    to.offer(cmd) match {
      case QueueOfferResult.Enqueued =>
        sendOne(from, cmd, p)
      case QueueOfferResult.Dropped =>
        akka.pattern.after(ThreadLocalRandom.current().nextInt(5, 25).millis) {
          Future(sendDm(to, from, cmd, p))(system.executionContext)
        }
      case r: QueueCompletionResult =>
        system.log.error(s"Unexpected $r")
        p.failure(new Exception(s"Unexpected $r"))
    }
}
