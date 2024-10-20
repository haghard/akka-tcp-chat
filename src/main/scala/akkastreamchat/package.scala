import java.util.concurrent.{ConcurrentHashMap, ThreadLocalRandom}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}
import scala.util.Success
import scala.util.control.NonFatal

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL}
import akka.stream.{BidiShape, BoundedSourceQueue, FlowShape, QueueCompletionResult, QueueOfferResult}
import akka.util.{ByteString, Timeout}

import akkastreamchat.domain.Username
import akkastreamchat.pbdomain.v3.ServerCommand
import akkastreamchat.pbdomain.v3.ServerCommandMessage.SealedValue

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
        akka.pattern.after(ThreadLocalRandom.current().nextInt(5, 15).millis) {
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
        akka.pattern.after(ThreadLocalRandom.current().nextInt(5, 15).millis) {
          Future(sendDm(to, from, cmd, p))(system.executionContext)
        }
      case r: QueueCompletionResult =>
        system.log.error(s"Unexpected $r")
        p.failure(new Exception(s"Unexpected $r"))
    }

  implicit val to: Timeout = Timeout(4.seconds)

  def tcpBidiFlow(
    conId: String,
    dmQueues: ConcurrentHashMap[Username, BoundedSourceQueue[ServerCommand]],
    broadcastQueue: BoundedSourceQueue[ServerCommand],
    chatState: ActorRef[ChatState.Protocol]
  )(implicit system: ActorSystem[?]): BidiFlow[ByteString, ServerCommand, ServerCommand, ByteString, akka.NotUsed] = {
    implicit val scheduler: Scheduler = system.scheduler
    import system.executionContext

    BidiFlow.fromGraph(
      GraphDSL.create() { implicit b =>
        val inbound: FlowShape[ByteString, ServerCommand] =
          b.add(
            ProtocolCodecsV3.ClientCommand.Decoder
              .takeWhile(_.isSuccess)
              .collect { case Success(cmd) => cmd }
              .mapAsync(1)(clientCmd =>
                chatState.ask[ServerCommand](ChatState.Protocol.TcpCmd(conId, clientCmd.asMessage.sealedValue, _))
              )
          )

        val outbound: FlowShape[ServerCommand, ByteString] =
          b.add(
            Flow[ServerCommand]
              .mapAsync(1) { cmd =>
                cmd.asMessage.sealedValue match {
                  case SealedValue.Message(msg) =>
                    val p = Promise[ServerCommand]()
                    sendOne(broadcastQueue, msg, p)
                    p.future.flatMap { cmd =>
                      Future {
                        // persist
                        system.log.info(s"Persist: ${cmd.asMessage.toProtoString}")
                        Thread.sleep(30)
                        cmd
                      }
                    }
                  case SealedValue.Dm(v) =>
                    try {
                      val from = dmQueues.get(v.src)
                      val to   = dmQueues.get(v.desc)
                      val p    = Promise[ServerCommand]()
                      sendDm(from, to, v, p)
                      p.future.flatMap { cmd =>
                        Future {
                          system.log.info(s"Persist: ${cmd.asMessage.toProtoString}")
                          Thread.sleep(30)
                          cmd
                        }
                      }
                    } catch {
                      case NonFatal(_) =>
                        Future.successful(akkastreamchat.pbdomain.v3.Alert(s"Failed DM $v", System.currentTimeMillis()))
                    }

                  case SealedValue.ShowRecent(cmd) =>
                    Future {
                      system.log.info(s"read: ${cmd.asMessage.toProtoString}")
                      val recent = cmd.withMsgs(
                        Seq(
                          akkastreamchat.pbdomain.v3.Msg(Username("jack"), "aaaaaa", System.currentTimeMillis(), ""),
                          akkastreamchat.pbdomain.v3.Msg(Username("scott"), "bbbbbb", System.currentTimeMillis(), ""),
                          akkastreamchat.pbdomain.v3.Msg(Username("adam"), "ccc", System.currentTimeMillis(), "")
                        )
                      )
                      Thread.sleep(50)
                      recent
                    }
                  case _ =>
                    Future.successful(cmd)
                }
              }
              // we drop messages to self
              .filter(_.asMessage.sealedValue match {
                case SealedValue.Message(c) =>
                  c.conId != conId
                case SealedValue.Dm(c) =>
                  c.conId != conId
                case _ =>
                  true
              })
              .via(ProtocolCodecsV3.ServerCommand.Encoder)
          )

        BidiShape.fromFlows(inbound, outbound)
      }
    )
  }

  /*
  val (sink, pub) =
    MergeHub
      .source[ByteString](1)
      .via(ProtocolCodecsV3.ClientCommand.Decoder2)
      .mapAsync(4) { cmd =>
        Future {
          val serverCmd: ServerCommand = ???
          serverCmd
        }(???)
      }
      .toMat(
        Sink.asPublisher[ServerCommand](false)
      )(Keep.both)
      .run()(???)

  Source.fromPublisher(pub)

  val (sink1, sinkQueue) =
    MergeHub
      .source[ClientCommand](1)
      .scan(???)((_, cc) => _)
      .map(_._2)
      /*.scan(???) { (_, cc) => _ }
      .mapAsync(4) { cmd: ClientCommand =>
        Future {
          cmd match {
            case RequestUsername(user, otp) =>
              //
              ???
            case SendMessage(text) =>
              // broadcast
              ???
            case ClientCommand.Empty =>
              ???
          }

          val serverCmd: ServerCommand = ???
          serverCmd
        }(???)
      }*/
      .toMat(
        Sink.queue[ServerCommand](1).withAttributes(Attributes.inputBuffer(1, 2))
      )(Keep.both)
      .run()(???)

  val sinkActor = ???
  val sink2 =
    MergeHub
      .source[ClientCommand](1)
      .to(Sink.actorRefWithBackpressure(sinkActor, ???, ???, ???))
      .run()(???)
   */
}
