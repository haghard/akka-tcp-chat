package akkastreamchat

import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.concurrent.{ConcurrentHashMap, ThreadLocalRandom}

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import akka.Done
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.*
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source, Tcp}
import akka.stream.{Attributes, BoundedSourceQueue, KillSwitches}
import akka.util.{ByteString, Timeout}

import akkastreamchat.domain.Username
import akkastreamchat.pbdomain.v3.*

object Bootstrap3 {
  private final case object BindFailure extends Reason
}

final case class Bootstrap3(
  host: String,
  port: Int
)(implicit system: ActorSystem[Nothing]) {

  import system.executionContext
  val shutdown = CoordinatedShutdown(system)

  val dmQueueSize        = 1 << 2
  val broadcastQueueSize = 1 << 4 // shared queue for all connected client

  val loggingAdapter = system.toClassic.log
  val secretToken    = system.settings.config.getString("server.secret-token")
  val deadline = FiniteDuration(
    system.settings.config.getDuration("akka.coordinated-shutdown.default-phase-timeout").toNanos,
    NANOSECONDS
  )

  val showAdEvery           = 90.seconds
  val (broadcastQueue, src) = Source.queue[ServerCommand](broadcastQueueSize).preMaterialize()

  val (ks, sharedBroadcastSrc) =
    src
      .log("chat", cmd => s"$cmd bq-size:${broadcastQueue.size()}")(loggingAdapter)
      .withAttributes(Attributes.logLevels(akka.event.Logging.InfoLevel))
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(BroadcastHub.sink[ServerCommand](1))(Keep.both)
      .run()

  val vs =
    Vector(
      "1. Bloom Filters",
      "2. Consistent Hashing",
      "3. Quorum",
      "4. Leader and Follower",
      "5. Heartbeat",
      "6. Fencing",
      "7. Write-ahead Log (WAL)",
      "8. Segmented Log",
      "9. High-Water mark",
      "10. Lease",
      "11. Gossip Protocol",
      "12. Phi Accrual Failure Detection",
      "13. Split-brain",
      "14. Checksum",
      "15. CAP Theorem",
      "16. PACELEC Theorem",
      "17. Hinted Handoff",
      "18. Read Repair",
      "19. Merkle Trees"
    )

  Source
    .tick(
      showAdEvery,
      showAdEvery,
      ()
    )
    .map(_ => ShowAd(vs(ThreadLocalRandom.current().nextInt(0, vs.size)), System.currentTimeMillis()))
    .to(Sink.foreach(broadcastQueue.offer(_)))
    .run()

  val dmQueues = new ConcurrentHashMap[Username, BoundedSourceQueue[ServerCommand]]()

  val chatState: ActorRef[ChatUserState.Protocol] =
    system.systemActorOf(ChatUserState(secretToken, broadcastQueue, dmQueues), "chat-state")

  implicit val to: Timeout = Timeout(3.seconds)

  val connectionHandler = Sink.foreach[Tcp.IncomingConnection] { incomingConnection =>
    val connectionId  = wvlet.airframe.ulid.ULID.newULID.toString
    val remoteAddress = incomingConnection.remoteAddress
    system.log.info("New Connection({}) from {}:{}", connectionId, remoteAddress.getHostString, remoteAddress.getPort)

    val (dmQueue, dmScr) = Source.queue[ServerCommand](dmQueueSize).preMaterialize()

    val mergedDmAndBroadcastSrc: Source[ServerCommand, NotUsed] =
      sharedBroadcastSrc.merge(dmScr, eagerComplete = true)

    chatState.tell(ChatUserState.Protocol.AcceptNewConnection(connectionId, dmQueue))

    val chatConFlow: Flow[ByteString, ByteString, NotUsed] =
      tcpBidiFlow(connectionId, dmQueues, broadcastQueue, chatState)
        .join(Flow.fromFunction[ServerCommand, ServerCommand](identity))

    val flow = chatConFlow
      .merge(mergedDmAndBroadcastSrc.via(ProtocolCodecsV3.ServerCommand.Encoder), eagerComplete = true)
      .watchTermination() { (_, doneF) =>
        doneF.onComplete(_ => chatState.tell(ChatUserState.Protocol.Disconnect(connectionId)))
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

      val delay = deadline - 2.seconds
      shutdown.addTask(PhaseServiceUnbind, "tcp-unbind") { () =>
        try broadcastQueue.complete()
        catch {
          case NonFatal(_) =>
        }
        ks.shutdown()
        binding
          .unbind()
          .map { _ =>
            system.log.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
            Done
          }(ExecutionContext.parasitic)
      }

      shutdown.addTask(PhaseServiceRequestsDone, "tcp-terminate") { () =>
        binding.whenUnbound
          .map { _ =>
            try {
              broadcastQueue.fail(new Exception("BroadcastQueue.complete timeout!"))
              ks.abort(new Exception("Ks.abort timeout!"))
            } catch {
              case NonFatal(_) =>
            }
            system.log.info("★ ★ ★ CoordinatedShutdown [tcp.terminate]  ★ ★ ★")
            Done
          }
          .flatMap(_ => akka.pattern.after(delay)(Future.successful(Done)))
      }

      shutdown.addTask(PhaseActorSystemTerminate, "actor-system-terminate") { () =>
        Future.successful {
          system.log.info("★ ★ ★ CoordinatedShutdown [actor-system-terminate] ★ ★ ★")
          Done
        }
      }

    case Failure(ex) =>
      system.log.error(s"Shutting down because can't bind to $host:$port", ex)
      shutdown.run(Bootstrap3.BindFailure)
  }
}
