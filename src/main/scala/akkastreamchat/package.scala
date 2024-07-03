import akka.stream.BoundedSourceQueue
import akka.stream.QueueCompletionResult
import akka.stream.QueueOfferResult

import org.slf4j.Logger

import akkastreamchat.pbdomain.v3.ServerCommand

package object akkastreamchat {

  val Quit = "/quit"

  def writeChannel(
    broadcastQueue: BoundedSourceQueue[ServerCommand],
    cmd: ServerCommand,
    i: Int = 0,
    limit: Int = 32
  )(implicit log: Logger): List[ServerCommand] =
    if (i < limit) {
      broadcastQueue.offer(cmd) match {
        case QueueOfferResult.Enqueued =>
          Nil
        case QueueOfferResult.Dropped =>
          Thread.sleep(50)
          writeChannel(broadcastQueue, cmd, i + 1, limit)
        case r: QueueCompletionResult =>
          throw new Exception(s"Unexpected $r")
      }
    } else {
      log.warn("Failed to broadcast {}", cmd)
      Nil
    }

}
