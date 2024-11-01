package akkastreamchat

import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.TimeZone

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import akka.Done
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.*
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source, Tcp}
import akka.stream.{Attributes, BoundedSourceQueue, QueueCompletionResult, QueueOfferResult}
import akka.util.ByteString

import Bootstrap4.*

import akkastreamchat.domain.Username
import akkastreamchat.pbdomain.v3.*

object Bootstrap4 {

  type ConnectionId = String

  sealed trait Protocol {
    def connectionId: ConnectionId
  }

  object Protocol {
    final case class TcpCmd(
      connectionId: ConnectionId,
      cmd: akkastreamchat.pbdomain.v3.ClientCommandMessage.SealedValue
    ) extends Protocol

    final case class AcceptNewConnection(
      connectionId: ConnectionId,
      dm: BoundedSourceQueue[ServerCommand]
    ) extends Protocol

    final case class Disconnect(
      connectionId: ConnectionId
    ) extends Protocol
  }

  sealed trait InternalInstruction

  object InternalInstruction {
    final case class Broadcast(msg: ServerCommand) extends InternalInstruction

    final case class WriteSingle(msg: ServerCommand, dm: BoundedSourceQueue[ServerCommand]) extends InternalInstruction

    final case class DmMsg(
      msg: akkastreamchat.pbdomain.v3.Dm,
      from: BoundedSourceQueue[ServerCommand],
      to: BoundedSourceQueue[ServerCommand]
    ) extends InternalInstruction

    final case object Empty extends InternalInstruction
  }

  private final case object BindFailure extends Reason

  final case class ConnectedUser(u: Username, dm: BoundedSourceQueue[ServerCommand])

  def callDb(o: InternalInstruction)(implicit
    system: ActorSystem[?],
    retryAfter: FiniteDuration,
    logger: LoggingAdapter
  ): Future[Option[ServerCommand]] = {
    import system.executionContext
    o match {
      case InternalInstruction.Broadcast(msg) =>
        msg.asMessage.sealedValue match {
          case akkastreamchat.pbdomain.v3.ServerCommandMessage.SealedValue.Message(cmd) =>
            Future {
              Thread.sleep(50)
              system.log.info(s"Persist: ${cmd.asMessage.toProtoString}")
              cmd
            }.map(Some(_))
              .recoverWith { case NonFatal(_) =>
                Future.successful(Some(Alert(s"Failed broadcast $msg", System.currentTimeMillis())))
              }

          case _ =>
            Future.successful(Some(msg))
        }

      case InternalInstruction.DmMsg(msg, from, to) =>
        Future {
          system.log.info(s"Persist: ${msg.toProtoString}")
          Thread.sleep(50)
          msg
        }
          .flatMap(msg => offerM(to, msg).zip(offerM(from, msg)).map(_ => None))
          .recoverWith { case NonFatal(_) =>
            Future.successful(Some(Alert(s"Failed DM $msg", System.currentTimeMillis())))
          }

      case InternalInstruction.WriteSingle(msg, dm) =>
        msg.asMessage.sealedValue match {
          case akkastreamchat.pbdomain.v3.ServerCommandMessage.SealedValue.ShowRecent(cmd) =>
            Future {
              val recent = cmd.withMsgs(
                Seq(
                  Msg(Username("jack"), "a", System.currentTimeMillis(), ""),
                  Msg(Username("scott"), "b", System.currentTimeMillis(), ""),
                  Msg(Username("adam"), "c", System.currentTimeMillis(), "")
                )
              )
              system.log.info(s"Fetch recent: ${cmd.asMessage.toProtoString}")
              Thread.sleep(50)
              recent
            }
              .flatMap(msg => offerM(dm, msg).map(_ => None))
              .recoverWith { case NonFatal(_) =>
                Future.successful(Some(Alert(s"Failed $msg", System.currentTimeMillis())))
              }
          case _ =>
            offerM(dm, msg).map(_ => None)
        }

      case InternalInstruction.Empty =>
        Future.failed(new Exception("Boom !!!"))
    }
  }

  private def offerM[T](
    queue: BoundedSourceQueue[T],
    msg: T
  )(implicit sys: ActorSystem[?], retryAfter: FiniteDuration, logger: LoggingAdapter): Future[Unit] =
    queue
      .offer(msg) match {
      case QueueOfferResult.Enqueued =>
        Future.successful(())
      case QueueOfferResult.Dropped =>
        akka.pattern.after(retryAfter)(offerM(queue, msg))
      case QueueOfferResult.Failure(ex) =>
        Future.failed(ex)
      case other: QueueCompletionResult =>
        Future.failed(new Exception(s"Unexpected $other"))
    }
}

final case class Bootstrap4(
  host: String,
  port: Int
)(implicit system: ActorSystem[Nothing]) {

  import system.executionContext

  val dmQueueSize       = 1 << 2
  val incomingQueueSize = 1 << 9

  val shutdown    = CoordinatedShutdown(system)
  val secretToken = system.settings.config.getString("server.secret-token")

  implicit val retryAfter: FiniteDuration = 10.millis
  implicit val logger: LoggingAdapter     = system.toClassic.log

  val deadline = FiniteDuration(
    system.settings.config
      .getDuration("akka.coordinated-shutdown.phases.service-unbind.timeout")
      .toNanos,
    NANOSECONDS
  )

  val (inQueue, inSrc) =
    Source.queue[Protocol](incomingQueueSize).preMaterialize()

  val broadcastHubSrc =
    inSrc
      .log("in", cmd => s"[${cmd.toString} Size:${inQueue.size()}]")(logger)
      .withAttributes(Attributes.logLevels(akka.event.Logging.InfoLevel))
      // LOG
      .scan(ChatState4(secretToken))((state, c) => state.applyCmd(c))
      .collect { case ChatState4(_, _, _, output) if !output.isInstanceOf[InternalInstruction.Empty.type] => output }
      .mapAsync(1) {
        callDb(_)
      }
      .collect { case Some(bc) => bc }
      // .alsoTo(???)
      .toMat(BroadcastHub.sink[ServerCommand](1))(Keep.right)
      .run()

  val connectionHandler = Sink.foreach[Tcp.IncomingConnection] { incomingConnection =>
    val connectionId  = wvlet.airframe.ulid.ULID.newULID.toString
    val remoteAddress = incomingConnection.remoteAddress
    system.log.info("Connection:{} from {}:{}", connectionId, remoteAddress.getHostString, remoteAddress.getPort)

    val (dmQueue, dmScr) = Source.queue[ServerCommand](dmQueueSize).preMaterialize()

    val outgoing: Source[ByteString, NotUsed] =
      broadcastHubSrc
        .merge(dmScr, eagerComplete = true)
        .via(ProtocolCodecsV3.ServerCommand.Encoder)

    val flow =
      Flow
        .lazyFutureFlow { () =>
          offerM(inQueue, Protocol.AcceptNewConnection(connectionId, dmQueue)).map { _ =>
            val incomingMessages: Sink[ByteString, NotUsed] =
              ProtocolCodecsV3.ClientCommand.Decoder
                .takeWhile(_.isSuccess)
                .collect { case Success(cmd) => cmd }
                .to(
                  Sink.foreachAsync(1)(clientCmd =>
                    offerM(inQueue, Protocol.TcpCmd(connectionId, clientCmd.asMessage.sealedValue))
                  )
                )

            Flow.fromSinkAndSourceCoupled(incomingMessages, outgoing)
          }
        }
        .watchTermination() { (_, doneF) =>
          doneF.map(_ => offerM(inQueue, Protocol.Disconnect(connectionId)))
          NotUsed
        }

    incomingConnection.handleWith(flow)
  }

  val cons    = Tcp(system).bind(host, port)
  val binding = cons.watchTermination()(Keep.left).to(connectionHandler).run()

  binding.onComplete {
    case Success(binding) =>
      val totalMemory = ManagementFactory
        .getOperatingSystemMXBean()
        .asInstanceOf[com.sun.management.OperatingSystemMXBean]
        .getTotalMemorySize()

      val jvmInfo =
        s"""
           | Cores:${sys.runtime.availableProcessors()}
           | Memory: {
           |   Total=${sys.runtime.totalMemory() / 1000000}Mb, Max=${sys.runtime.maxMemory() / 1000000}Mb,
           |   Free=${sys.runtime.freeMemory() / 1000000}Mb, RAM=${totalMemory / 1000000}
           | }
           |""".stripMargin

      system.log.info(
        s"""
           |------------- Started: ${binding.localAddress.getHostString} ${binding.localAddress.getPort} ------------------
           |${akkastreamchat.BuildInfo.toString}
           |Environment: [TZ:${TimeZone.getDefault.getID}. Start time:${LocalDateTime.now()}]
           |PID:${ProcessHandle.current().pid()}
           |JVM:
           |$jvmInfo
           |👍✅🚀🧪❌📣🏒🔥🥇😄happy, 😐neutral, 😞sad
           |---------------------------------------------------------------------------------
           |""".stripMargin
      )

      val drainDelay = deadline - 3.seconds // 7-3=4sec to drain msg in-flight
      shutdown.addTask(PhaseServiceUnbind, "tcp-unbind") { () =>
        val startTs = System.currentTimeMillis()
        inQueue.complete()
        akka.pattern
          .after(drainDelay)(Future.successful(Done))
          .flatMap(_ =>
            binding
              .unbind()
              .map { _ =>
                system.log.info(
                  s"★ ★ ★ CoordinatedShutdown [http-api.unbind] (${System.currentTimeMillis() - startTs})millis ★ ★ ★"
                )
                Done
              }
          )
      }

      shutdown.addTask(PhaseServiceRequestsDone, "tcp-terminate") { () =>
        binding.whenUnbound.map { _ =>
          system.log.info(s"★ ★ ★ CoordinatedShutdown [tcp.terminate] ★ ★ ★")
          Done
        }
      }

      shutdown.addTask(PhaseActorSystemTerminate, "actor-system-terminate") { () =>
        Future.successful {
          system.log.info("★ ★ ★ CoordinatedShutdown [actor-system-terminate] ★ ★ ★")
          Done
        }
      }

    case Failure(ex) =>
      system.log.error(s"Shutting down because can't bind to $host:$port", ex)
      shutdown.run(Bootstrap4.BindFailure)
  }
}
