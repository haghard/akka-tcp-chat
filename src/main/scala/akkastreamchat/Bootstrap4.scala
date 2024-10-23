package akkastreamchat

import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.TimeZone

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.*
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.stream.{Attributes, BoundedSourceQueue, OverflowStrategy, QueueCompletionResult, QueueOfferResult}
import akka.util.ByteString

import Bootstrap4.*

import akkastreamchat.ChatUserState.{ConnectionId, Protocol2}
import akkastreamchat.domain.Username
import akkastreamchat.pbdomain.v3.ClientCommandMessage.SealedValue
import akkastreamchat.pbdomain.v3.*

object Bootstrap4 {
  private final case object BindFailure extends Reason

  final case class ConnectedUser(u: Username, dm: BoundedSourceQueue[ServerCommand])
  final case class ChatState(
    connectedUser: Map[ConnectionId, ConnectedUser] = Map.empty[String, ConnectedUser],
    pendingConnections: Map[ConnectionId, BoundedSourceQueue[ServerCommand]] = Map.empty,
    cmdToEmit: Option[ServerCommand] = None
  )

  private def offerM(
    queue: SourceQueueWithComplete[ChatUserState.Protocol2],
    msg: ChatUserState.Protocol2
  )(implicit sys: ActorSystem[?], retryAfter: FiniteDuration): Future[Unit] =
    queue
      .offer(msg)
      .flatMap {
        case QueueOfferResult.Enqueued =>
          Future.successful(())
        case QueueOfferResult.Dropped =>
          akka.pattern.after(retryAfter)(offerM(queue, msg))
        case QueueOfferResult.Failure(ex) =>
          Future.failed(ex)
        case other: QueueCompletionResult =>
          Future.failed(new Exception(s"Ubexpected $other"))
      }(ExecutionContext.parasitic)
}

final case class Bootstrap4(
  host: String,
  port: Int
)(implicit system: ActorSystem[Nothing]) {

  import system.executionContext
  val shutdown = CoordinatedShutdown(system)

  val dmQueueSize                         = 1 << 2
  val broadcastQueueSize                  = 1 << 4 // shared queue for all connected client
  implicit val retryAfter: FiniteDuration = 15.millis

  val loggingAdapter = system.toClassic.log
  val secretToken    = system.settings.config.getString("server.secret-token")

  val deadline = FiniteDuration(
    system.settings.config
      .getDuration("akka.coordinated-shutdown.phases.service-unbind.timeout")
      .toNanos,
    NANOSECONDS
  )

  val (inQueue, broadcastHubSrc) =
    Source
      .queue[ChatUserState.Protocol2](1 << 7, OverflowStrategy.backpressure)
      .log("in", cmd => cmd.toString)(loggingAdapter)
      .withAttributes(Attributes.logLevels(akka.event.Logging.InfoLevel))
      // LOG
      .scan(ChatState()) { (state, c) =>
        // println("scan " + c)
        c match {
          case Protocol2.AcceptNewConnection(cId, dm) =>
            state.copy(pendingConnections = state.pendingConnections + (cId -> dm))
          case Protocol2.TcpCmd(cId, cmd) =>
            cmd match {
              case SealedValue.RequestUsername(cmd) =>
                val dm = state.pendingConnections(cId)
                state.copy(
                  connectedUser = state.connectedUser + (cId -> ConnectedUser(cmd.user, dm)),
                  pendingConnections = state.pendingConnections - cId,
                  cmdToEmit =
                    Some(Welcome(cmd.user, s"Welcome to the Chat ${cmd.user.name}!", System.currentTimeMillis()))
                )
              case SealedValue.SendMessage(msg) =>
                // println(state.connectedUser.keySet.mkString(","))
                val cmd = Msg(
                  state.connectedUser.get(cId).map(_.u).getOrElse(Username("none")),
                  msg.text,
                  System.currentTimeMillis(),
                  cId
                )
                state.copy(cmdToEmit = Some(cmd))
              case SealedValue.Empty =>
                state
            }
          case Protocol2.Disconnect(cId) =>
            val disUsr = state.connectedUser.get(cId).map(_.u.name).getOrElse("none")
            val cmd    = Alert(s"$disUsr disconnected", System.currentTimeMillis())
            state.copy(
              pendingConnections = state.pendingConnections - cId,
              connectedUser = state.connectedUser - cId,
              cmdToEmit = Some(cmd)
            )
        }
      }
      .collect { case ChatState(_, _, Some(cmd)) =>
        cmd
      }
      .toMat(BroadcastHub.sink[ServerCommand](2))(Keep.both)
      // .toMat(Sink.asPublisher[ServerCommand](fanout = true))(Keep.both)
      .run()

  val connectionHandler = Sink.foreach[Tcp.IncomingConnection] { incomingConnection =>
    val connectionId  = wvlet.airframe.ulid.ULID.newULID.toString
    val remoteAddress = incomingConnection.remoteAddress

    val (dmQueue, dmScr) = Source.queue[ServerCommand](dmQueueSize).preMaterialize()
    val outgoing = broadcastHubSrc.merge(dmScr, eagerComplete = true).via(ProtocolCodecsV3.ServerCommand.Encoder)

    system.log.info("Connection:{} from {}:{}", connectionId, remoteAddress.getHostString, remoteAddress.getPort)

    val flow =
      Flow
        .lazyFutureFlow { () =>
          inQueue.offer(ChatUserState.Protocol2.AcceptNewConnection(connectionId, dmQueue)).map { _ =>
            val incomingMessages: Sink[ByteString, NotUsed] =
              ProtocolCodecsV3.ClientCommand.Decoder
                .takeWhile(_.isSuccess)
                .collect { case Success(cmd) => cmd }
                .to(
                  Sink.foreachAsync(1)(clientCmd =>
                    offerM(inQueue, ChatUserState.Protocol2.TcpCmd(connectionId, clientCmd.asMessage.sealedValue))
                  )
                )

            Flow.fromSinkAndSourceCoupled(incomingMessages, outgoing)
          }
        }
        .watchTermination() { (_, doneF) =>
          doneF.flatMap(_ => inQueue.offer(ChatUserState.Protocol2.Disconnect(connectionId)))
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

      val drainDelay = deadline - 3.seconds // 7-3=4
      shutdown.addTask(PhaseServiceUnbind, "tcp-unbind") { () =>
        val startTs = System.currentTimeMillis()
        inQueue.complete()
        akka.pattern
          .after(drainDelay)(Future.successful(Done))
          .flatMap(_ =>
            binding
              .unbind()
              .flatMap { _ =>
                inQueue.watchCompletion().map { done =>
                  system.log.info(
                    s"★ ★ ★ CoordinatedShutdown [http-api.unbind] (${System.currentTimeMillis() - startTs})millis ★ ★ ★"
                  )
                  done
                }
              }(ExecutionContext.parasitic)
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
