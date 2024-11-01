package akkastreamchat

import java.nio.charset.StandardCharsets

import akka.event.LoggingAdapter
import akka.stream.BoundedSourceQueue

import akkastreamchat.Bootstrap4.*
import akkastreamchat.domain.Username
import akkastreamchat.pbdomain.v3.ClientCommandMessage.SealedValue
import akkastreamchat.pbdomain.v3.*

final case class ChatState4(
  secretToken: String,
  connectedUser: Map[ConnectionId, ConnectedUser] = Map.empty[String, ConnectedUser],
  pendingConnections: Map[ConnectionId, BoundedSourceQueue[ServerCommand]] = Map.empty,
  output: InternalInstruction = InternalInstruction.Empty
)(implicit logger: LoggingAdapter) {
  self =>

  def applyCmd(cmd: Protocol): ChatState4 =
    cmd match {
      case Protocol.AcceptNewConnection(cId, dm) =>
        self.copy(pendingConnections = self.pendingConnections + (cId -> dm))
      case Protocol.TcpCmd(cId, cmd) =>
        cmd match {
          case SealedValue.RequestUsername(cmd) =>
            if (pendingConnections.contains(cId)) {
              val dmq = self.pendingConnections(cId)

              // connectedUser.keySet()
              // connectedUser.inverse().get()
              connectedUser.values.find(_.u == cmd.user) match { // ???
                case Some(_) =>
                  val msg = InternalInstruction.WriteSingle(
                    Disconnect(s"${cmd.user.name} already connected", System.currentTimeMillis()),
                    dmq
                  )
                  self.copy(output = msg)
                case None =>
                  import com.bastiaanjansen.otp._
                  val TOTP = {
                    val sBts =
                      secretToken.getBytes(StandardCharsets.UTF_8) ++ cmd.user.name.getBytes(StandardCharsets.UTF_8)
                    new TOTPGenerator.Builder(sBts)
                      .withHOTPGenerator { b =>
                        b.withPasswordLength(8)
                        b.withAlgorithm(HMACAlgorithm.SHA256)
                      }
                      .withPeriod(java.time.Duration.ofSeconds(5))
                      .build()
                  }

                  if (TOTP.verify(cmd.otp.toStringUtf8)) {
                    logger.info(s"Authorized $cId with ${cmd.user.name}/${cmd.otp.toStringUtf8}")
                    val msg = InternalInstruction.WriteSingle(
                      Welcome(cmd.user, s"Welcome to the Chat ${cmd.user.name}!", System.currentTimeMillis()),
                      dmq
                    )
                    self.copy(
                      connectedUser = self.connectedUser + (cId -> ConnectedUser(cmd.user, dmq)),
                      pendingConnections = self.pendingConnections - cId,
                      output = msg
                    )
                  } else {
                    logger.warning(s"Auth error:$cId/${cmd.user.name}/${cmd.otp.toStringUtf8}")
                    val msg = Disconnect(s"Auth error: ${cmd.user.name}", System.currentTimeMillis())
                    self.copy(
                      pendingConnections = self.pendingConnections - cId,
                      output = InternalInstruction.WriteSingle(msg, dmq)
                    )
                  }
              }
            } else {
              logger.info(s"{} not found! ❌", cId)
              self
            }

          case SealedValue.SendMessage(msg) =>
            self.connectedUser.get(cId) match {
              case Some(u) =>
                if (msg.text.startsWith("/")) {
                  msg.text match {
                    case "/users" =>
                      self.copy(
                        output = InternalInstruction.WriteSingle(
                          Alert(
                            self.connectedUser.values.map(_.u.name).mkString("[", ",", "]"),
                            System.currentTimeMillis()
                          ),
                          u.dm
                        )
                      )
                    case "/quit" =>
                      val out = InternalInstruction.WriteSingle(Disconnect("quit", System.currentTimeMillis()), u.dm)
                      self.copy(output = out)
                    case "/recent" =>
                      self.copy(output = InternalInstruction.WriteSingle(ShowRecent(u.u, Seq.empty), u.dm))
                    case "/help" =>
                      val msg = Alert(s"[users|dm|quit|recent]", System.currentTimeMillis())
                      self.copy(output = InternalInstruction.WriteSingle(msg, u.dm))
                    case otherCmd =>
                      if (otherCmd.startsWith("/dm") || otherCmd.startsWith("/DM")) {
                        val segments  = otherCmd.split(":")
                        val recipient = segments(1)
                        val text      = segments(2)
                        val cu        = self.connectedUser.values

                        // TODO: improve
                        val from = cu.find(_.u == u.u).map(_.dm)
                        val to   = cu.find(_.u == Username(recipient)).map(_.dm)

                        (from, to) match {
                          case (Some(from), Some(to)) =>
                            val msg = Dm(u.u, Username(recipient), text, System.currentTimeMillis(), cId)
                            self.copy(output = InternalInstruction.DmMsg(msg, from, to))
                          case (None, Some(to)) =>
                            val msg =
                              Alert(s"$u can't DM to $recipient. User's offline", System.currentTimeMillis())
                            self.copy(output = InternalInstruction.WriteSingle(msg, to))
                          case (Some(from), None) =>
                            val msg =
                              Alert(s"$u can't DM to $recipient. User's offline", System.currentTimeMillis())
                            self.copy(output = InternalInstruction.WriteSingle(msg, from))
                          case _ =>
                            self
                        }

                      } else {
                        val msg = Alert(s"Unknown cmd: $otherCmd !. Use /help", System.currentTimeMillis())
                        self.copy(output = InternalInstruction.WriteSingle(msg, u.dm))
                      }
                  }
                } else {
                  val broadcastMsg = Msg(u.u, msg.text, System.currentTimeMillis(), cId)
                  self.copy(output = InternalInstruction.Broadcast(broadcastMsg))
                }

              case None =>
                val msg = Disconnect(s"$cId not found!", System.currentTimeMillis())
                self.copy(output = InternalInstruction.Broadcast(msg))
            }

          case SealedValue.Empty =>
            self
        }

      case Protocol.Disconnect(cId) =>
        val disUsr = self.connectedUser.get(cId).map(_.u.name).getOrElse("none")
        val msg    = Alert(s"$disUsr disconnected", System.currentTimeMillis())
        logger.info(s"Client [{}:{}] disconnected ❌", cId, disUsr)
        val s =
          self.copy(
            connectedUser = self.connectedUser - cId,
            output = InternalInstruction.Broadcast(msg)
          )
        logger.info("{}", s.toString)
        s
    }

  override def toString: String =
    s"Online users:[${connectedUser.keySet.take(10).mkString(",")}]"
}
