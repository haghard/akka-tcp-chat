package akkastreamchat

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.BoundedSourceQueue

import akkastreamchat.domain.Username
import akkastreamchat.pbdomain.v3.ClientCommandMessage.SealedValue
import akkastreamchat.pbdomain.v3.*

object ChatState {

  sealed trait Protocol
  object Protocol {

    final case class TcpCmd(
      cId: String,
      cmd: akkastreamchat.pbdomain.v3.ClientCommandMessage.SealedValue,
      replyTo: ActorRef[akkastreamchat.pbdomain.v3.ServerCommand]
    ) extends Protocol

    final case class AcceptNewConnection(
      cId: String,
      dm: BoundedSourceQueue[ServerCommand]
    ) extends Protocol

    final case class Disconnect(
      cId: String
    ) extends Protocol
  }

  type ConnectionId = String

  def apply(
    secretToken: String,
    broadcastQueue: BoundedSourceQueue[ServerCommand],
    dmQueues: ConcurrentHashMap[Username, BoundedSourceQueue[ServerCommand]],
    connectedUser: Map[ConnectionId, Username] = Map.empty[String, Username],
    pendingConnections: Map[ConnectionId, BoundedSourceQueue[ServerCommand]] = Map.empty
  ): Behavior[Protocol] =
    Behaviors.setup { ctx =>
      active(secretToken, broadcastQueue, dmQueues, connectedUser, pendingConnections)(ctx)
    }

  private def active(
    secretToken: String,
    broadcastQueue: BoundedSourceQueue[ServerCommand],
    dmQueues: ConcurrentHashMap[Username, BoundedSourceQueue[ServerCommand]],
    connectedUser: Map[ConnectionId, Username],
    pendingConnections: Map[ConnectionId, BoundedSourceQueue[ServerCommand]]
  )(implicit ctx: ActorContext[Protocol]): Behavior[Protocol] = {
    val logger = ctx.log
    Behaviors.receiveMessagePartial {
      case Protocol.AcceptNewConnection(cId, dm) =>
        logger.info(s"New pending con: ${cId} online:${connectedUser.size}")
        apply(secretToken, broadcastQueue, dmQueues, connectedUser, pendingConnections + (cId -> dm))

      case Protocol.Disconnect(cId) =>
        val disUsr       = connectedUser.get(cId)
        val updatedUsers = connectedUser - cId
        disUsr.foreach(dmQueues.remove(_))
        logger.info(
          s"Client [{}:{}] disconnected ❌. Online:[{}]",
          cId,
          disUsr.getOrElse(""),
          updatedUsers.keySet.mkString(",")
        )
        //
        broadcastQueue.offer(Alert(s"${disUsr.getOrElse("")} disconnected", System.currentTimeMillis()))
        apply(secretToken, broadcastQueue, dmQueues, updatedUsers, pendingConnections - cId)

      case Protocol.TcpCmd(cId, clientCmd, replyTo) =>
        clientCmd match {
          case SealedValue.RequestUsername(c) =>
            if (pendingConnections.contains(cId)) {
              connectedUser.values.find(_ == c.user) match { // ???
                case Some(_) =>
                  replyTo.tell(Disconnect(s"${c.user.name} already taken", System.currentTimeMillis()))
                  apply(secretToken, broadcastQueue, dmQueues, connectedUser, pendingConnections - cId)
                case None =>
                  import com.bastiaanjansen.otp._
                  val TOTP = {
                    val sBts =
                      secretToken.getBytes(StandardCharsets.UTF_8) ++ c.user.name.getBytes(StandardCharsets.UTF_8)
                    new TOTPGenerator.Builder(sBts)
                      .withHOTPGenerator { b =>
                        b.withPasswordLength(8)
                        b.withAlgorithm(HMACAlgorithm.SHA256)
                      }
                      .withPeriod(java.time.Duration.ofSeconds(5))
                      .build()
                  }

                  if (TOTP.verify(c.otp.toStringUtf8)) {
                    val dm           = pendingConnections(cId)
                    val updatedUsers = connectedUser + (cId -> c.user)
                    dmQueues.put(c.user, dm)
                    logger.info(s"Authorized $cId with ${c.user.name}/${c.otp.toStringUtf8}")
                    replyTo.tell(Welcome(c.user, "Welcome to the Chat!", System.currentTimeMillis()))
                    apply(secretToken, broadcastQueue, dmQueues, updatedUsers, pendingConnections - cId)
                  } else {
                    logger.warn(s"Auth error:${cId}/${c.user.name}/${c.otp.toStringUtf8}")
                    replyTo.tell(Disconnect(s"Auth error: ${c.user.name}", System.currentTimeMillis()))
                    apply(secretToken, broadcastQueue, dmQueues, connectedUser, pendingConnections - cId)
                  }

              }
            } else {
              replyTo.tell(Disconnect(s"$cId not found!", System.currentTimeMillis()))
              Behaviors.same
            }

          case SealedValue.SendMessage(c) =>
            connectedUser.get(cId) match {
              case Some(u) =>
                if (c.text.startsWith("/")) {
                  val reply =
                    c.text match {
                      case "/users" =>
                        Alert(connectedUser.values.map(_.name).mkString(","), System.currentTimeMillis())
                      case "/help" =>
                        Alert(s"[users|dm]", System.currentTimeMillis())
                      case "/quit" =>
                        Disconnect("quit", System.currentTimeMillis())
                      case "/recent" =>
                        ShowRecent(u, Seq.empty)
                      case otherCmd =>
                        if (otherCmd.startsWith("/dm") || otherCmd.startsWith("/DM")) {
                          val segments  = otherCmd.split(":")
                          val recipient = segments(1)
                          val text      = segments(2)

                          val from = connectedUser.values.find(_ == u).isDefined
                          val to   = connectedUser.values.find(_ == Username(recipient)).isDefined

                          if (from && to) Dm(u, Username(recipient), text, System.currentTimeMillis(), cId)
                          else Alert(s"$u can't DM to $recipient. User's offline", System.currentTimeMillis())
                        } else Alert(s"Unknown cmd: $otherCmd !", System.currentTimeMillis())
                    }
                  replyTo.tell(reply)
                  Behaviors.same
                } else {
                  val broadcastMsg = Msg(u, c.text, System.currentTimeMillis(), cId)
                  replyTo.tell(broadcastMsg)
                  Behaviors.same
                }

              case None =>
                replyTo.tell(Disconnect(s"$cId not found!", System.currentTimeMillis()))
                Behaviors.same
            }

          case SealedValue.Empty =>
            replyTo.tell(Disconnect(s"$cId not found!", System.currentTimeMillis()))
            Behaviors.same
        }
    }
  }
}
