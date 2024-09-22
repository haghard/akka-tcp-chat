package akkastreamchat

import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.ExecutionContext
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
import akka.stream.Attributes
import akka.stream.BoundedSourceQueue
import akka.stream.KillSwitches
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.util.ByteString

import domain.*

import akkastreamchat.pbdomain.v3.ServerCommandMessage.SealedValue
import akkastreamchat.pbdomain.v3.*

object Bootstrap {
  private final case object BindFailure extends Reason
}

final case class Bootstrap(
  host: String,
  port: Int,
  users: ConcurrentHashMap[Username, UUID],
  outgoingCons: ConcurrentHashMap[UUID, BoundedSourceQueue[ServerCommand]]
)(implicit
  system: ActorSystem[Nothing]
) {
  import system.executionContext
  val shutdown = CoordinatedShutdown(system)

  val sinkQueueSize      = 1 << 5
  val broadcastQueueSize = 1 << 7

  val loggingAdapter = system.toClassic.log
  val secretToken    = system.settings.config.getString("server.secret-token")
  val deadline = FiniteDuration(
    system.settings.config.getDuration("akka.coordinated-shutdown.default-phase-timeout").toNanos,
    NANOSECONDS
  )

  val showAdEvery                 = 90.seconds
  val (sharedBroadcastQueue, src) = Source.queue[ServerCommand](broadcastQueueSize).preMaterialize()

  val (ks, incomingSrc) =
    src
      .log("chat", cmd => s"$cmd bq-size:${sharedBroadcastQueue.size()}")(loggingAdapter)
      .withAttributes(Attributes.logLevels(akka.event.Logging.InfoLevel))
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(BroadcastHub.sink(sinkQueueSize))(Keep.both)
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
    .to(Sink.foreach(sharedBroadcastQueue.offer(_)))
    .run()

  val handler = Sink.foreach[Tcp.IncomingConnection] { incomingConnection =>
    val connectionId                     = UUID.randomUUID()
    val remoteAddress: InetSocketAddress = incomingConnection.remoteAddress
    system.log.info("New client {} from {}:{}", connectionId, remoteAddress.getHostString, remoteAddress.getPort)

    val (outgoingCon, outgoingScr) = Source.queue[ServerCommand](sinkQueueSize).preMaterialize()
    outgoingCons.putIfAbsent(connectionId, outgoingCon)

    val cFlow: Flow[ByteString, ByteString, NotUsed] =
      ProtocolCodecsV3.ClientCommand.Decoder
        .takeWhile {
          case Success(cmd) =>
            cmd match {
              case SendMessage(Quit) => false
              case _                 => true
            }
          case Failure(_) => false
        }
        .statefulMapConcat { () =>
          var userState: State = Idle(secretToken, connectionId, remoteAddress, users, outgoingCons)(system.log)

          { clientCommand =>
            val r = clientCommand match {
              case Success(cmd) =>
                val (updatedState, reply) = userState.applyCmd(cmd.asMessage)
                userState = updatedState
                reply
              case Failure(parseError) =>
                Reply(
                  Alert(s"Invalid cmd: ${parseError.getMessage}", System.currentTimeMillis()),
                  ReplyType.Direct
                )
            }

            r.`type` match {
              case ReplyType.Broadcast =>
                writeOut(sharedBroadcastQueue, r.cmd)(system.log)
              case ReplyType.Direct =>
                val res =
                  r.cmd.asMessage.sealedValue match {
                    case SealedValue.Dm(dm) =>
                      val recipientId     = users.get(dm.desc)
                      val recipientOutCon = outgoingCons.get(recipientId)
                      writeOut(recipientOutCon, dm)(system.log)
                    case _ =>
                      Nil
                  }
                r.cmd :: res
            }
          }
        }
        .merge(incomingSrc.merge(outgoingScr, eagerComplete = true), eagerComplete = true)
        .watchTermination() { (_, termination) =>
          termination.onComplete { _ =>
            users
              .entrySet()
              .forEach { entry =>
                if (entry.getValue == connectionId) {
                  users.remove(entry.getKey)
                  system.log.info(s"Client [{}:{}] disconnected ❌", connectionId, entry.getKey.name)
                  sharedBroadcastQueue
                    .offer(Alert(s"${entry.getKey.name} disconnected", System.currentTimeMillis()))
                }
              }
          }
          NotUsed
        }
        .via(ProtocolCodecsV3.ServerCommand.Encoder)

    incomingConnection.handleWith(cFlow)
  }

  val cons    = Tcp(system).bind(host, port)
  val binding = cons.watchTermination()(Keep.left).to(handler).run()

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

      val delay = deadline - 1.seconds
      shutdown.addTask(PhaseBeforeServiceUnbind, "before-tcp-unbind") { () =>
        Future {
          system.log.info("★ ★ ★ CoordinatedShutdown [before-unbind] ★ ★ ★")
          users
            .entrySet()
            .forEach { entry =>
              writeOut(
                sharedBroadcastQueue,
                Alert(s"${entry.getKey.name} disconnected", System.currentTimeMillis())
              )(system.log)
            }
          Done
        }.flatMap(_ => akka.pattern.after(delay)(Future.successful(Done)))(ExecutionContext.parasitic)
      }

      shutdown.addTask(PhaseServiceUnbind, "tcp-unbind") { () =>
        try sharedBroadcastQueue.complete()
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
        binding.whenUnbound.map { _ =>
          try {
            sharedBroadcastQueue.fail(new Exception("BroadcastQueue.complete timeout!"))
            ks.abort(new Exception("Ks.abort timeout!"))
          } catch {
            case NonFatal(_) =>
          }
          system.log.info("★ ★ ★ CoordinatedShutdown [tcp.terminate]  ★ ★ ★")
          Done
        }(ExecutionContext.parasitic)
      }

      shutdown.addTask(PhaseActorSystemTerminate, "actor-system-terminate") { () =>
        Future.successful {
          system.log.info("★ ★ ★ CoordinatedShutdown [actor-system-terminate] ★ ★ ★")
          Done
        }
      }

    case Failure(ex) =>
      system.log.error(s"Shutting down because can't bind to $host:$port", ex)
      shutdown.run(Bootstrap.BindFailure)
  }

  // binding
  /*

  val f =
    Tcp(system)
      .bind(host, port)
      .to(
        Sink.foreach { incomingConnection =>
          val connectionId                     = UUID.randomUUID()
          val remoteAddress: InetSocketAddress = incomingConnection.remoteAddress
          system.log
            .info("New client {} from {}:{}", connectionId, remoteAddress.getHostString, remoteAddress.getPort)

          val (outgoingCon, outgoingScr) = Source.queue[ServerCommand](sinkQueueSize).preMaterialize()
          outgoingCons.putIfAbsent(connectionId, outgoingCon)

          val cFlow: Flow[ByteString, ByteString, NotUsed] =
            ProtocolCodecs.ClientCommand.Decoder
              .takeWhile {
                case Success(cmd) =>
                  cmd match {
                    case SendMessagePB(Quit) => false
                    case _                   => true
                  }
                case Failure(_) => false
              }
              .statefulMapConcat { () =>
                var userState: State = Idle(secretToken, connectionId, remoteAddress, users, outgoingCons)(system.log)

                { clientCommand =>
                  val r = clientCommand match {
                    case Success(cmd) =>
                      val (updatedState, reply) = userState.applyCmd(cmd)
                      userState = updatedState
                      reply
                    case Failure(parseError) =>
                      Reply(
                        AlertPB(s"Invalid cmd: ${parseError.getMessage}", System.currentTimeMillis()),
                        ReplyType.Direct
                      )
                  }

                  r.`type` match {
                    case ReplyType.Broadcast =>
                      writeChannel(sharedBroadcastQueue, r.cmd)(system.log)
                    case ReplyType.Direct =>
                      r.cmd :: Nil
                  }
                }
              }
              .merge(incomingSrc.merge(outgoingScr, true), true)
              .watchTermination() { (_, termination) =>
                termination.onComplete { _ =>
                  users
                    .entrySet()
                    .forEach { entry =>
                      if (entry.getValue == connectionId) {
                        users.remove(entry.getKey)
                        system.log.info("Client [{}:{}] disconnected ❌", connectionId, entry.getKey.name)
                        sharedBroadcastQueue
                          .offer(AlertPB(s"${entry.getKey.name} disconnected", System.currentTimeMillis()))
                      }
                    }
                }
                NotUsed
              }
              .via(ProtocolCodecs.ServerCommand.Encoder)

          // incomingConnection.flow.joinMat(connectionFlow)(Keep.right).run()
          incomingConnection.handleWith(cFlow)
        }
      )
      .run()

  f.onComplete {
    case Success(binding) =>
      val totalMemory = ManagementFactory
        .getOperatingSystemMXBean()
        .asInstanceOf[com.sun.management.OperatingSystemMXBean]
        .getTotalMemorySize()

      val jvmInfo = {
        val rntm = Runtime.getRuntime()
        s"Cores:${rntm.availableProcessors()} Memory:[Total=${rntm.totalMemory() / 1000000}Mb, Max=${rntm
            .maxMemory() / 1000000}Mb, Free=${rntm.freeMemory() / 1000000}Mb, RAM=${totalMemory / 1000000} ]"
      }

      system.log.info(
        s"""
             |------------- Started: ${binding.localAddress.getHostString} ${binding.localAddress.getPort} ------------------
             |${akkastreamchat.BuildInfo.toString}
             |Environment: [TZ:${TimeZone.getDefault.getID}. Start time:${LocalDateTime.now()}]
             |PID:${ProcessHandle.current().pid()} JVM: $jvmInfo
             |👍✅🚀🧪❌😄📣
             |---------------------------------------------------------------------------------
             |""".stripMargin
      )

      val delay = deadline - 1.seconds
      shutdown.addTask(PhaseBeforeServiceUnbind, "before-tcp-unbind") { () =>
        Future {
          system.log.info("★ ★ ★ CoordinatedShutdown [before-unbind] ★ ★ ★")
          users
            .entrySet()
            .forEach { entry =>
              writeChannel(
                sharedBroadcastQueue,
                AlertPB(s"${entry.getKey.name} disconnected", System.currentTimeMillis())
              )(system.log)
            }
          Done
        }.flatMap(_ => akka.pattern.after(delay)(Future.successful(Done)))(ExecutionContext.parasitic)
      }

      shutdown.addTask(PhaseServiceUnbind, "tcp-unbind") { () =>
        try sharedBroadcastQueue.complete()
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
        binding.whenUnbound.map { _ =>
          try {
            sharedBroadcastQueue.fail(new Exception("BroadcastQueue.complete timeout!"))
            ks.abort(new Exception("Ks.abort timeout!"))
          } catch {
            case NonFatal(_) =>
          }
          system.log.info("★ ★ ★ CoordinatedShutdown [tcp.terminate]  ★ ★ ★")
          Done
        }(ExecutionContext.parasitic)
      }

      shutdown.addTask(PhaseActorSystemTerminate, "actor-system-terminate") { () =>
        Future.successful {
          system.log.info("★ ★ ★ CoordinatedShutdown [actor-system-terminate] ★ ★ ★")
          Done
        }
      }

    case Failure(ex) =>
      system.log.error(s"Shutting down because can't bind to $host:$port", ex)
      shutdown.run(Bootstrap.BindFailure)
  }
   */

}
