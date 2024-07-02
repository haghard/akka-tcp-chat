package akkastreamchat

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.EnumerationHasAsScala

import akka.stream.BoundedSourceQueue

import domain._
import org.slf4j.Logger

import akkastreamchat.pbdomain.v1._

sealed trait State {
  def applyCmd(command: ClientCommand): (State, Reply)
}

final case class Idle(
  secretToken: String,
  connectionId: UUID,
  remoteAddress: InetSocketAddress,
  users: ConcurrentHashMap[Username, UUID],
  outgoingChannels: ConcurrentHashMap[UUID, BoundedSourceQueue[ServerCommand]]
)(implicit log: Logger)
    extends State { self =>

  def applyCmd(cmd: ClientCommand): (State, Reply) =
    cmd match {
      case RequestUsernamePB(newUsername, otp) =>
        import com.bastiaanjansen.otp._
        val TOTP = {
          val sBts = secretToken.getBytes(StandardCharsets.UTF_8) ++ newUsername.name.getBytes(StandardCharsets.UTF_8)
          new TOTPGenerator.Builder(sBts)
            .withHOTPGenerator { b =>
              b.withPasswordLength(8)
              b.withAlgorithm(HMACAlgorithm.SHA256)
            }
            .withPeriod(java.time.Duration.ofSeconds(5))
            .build()
        }
        if (TOTP.verify(otp.toStringUtf8)) {
          if (users.putIfAbsent(newUsername, connectionId) == null) {
            log.info(
              s"Authorized $connectionId/${newUsername.name} from ${remoteAddress.getHostString}:${remoteAddress.getPort}"
            )
            (
              Active(connectionId, newUsername, users, outgoingChannels),
              Reply(WelcomePB(newUsername, "Welcome to the Chat!", System.currentTimeMillis()), ReplyType.Direct)
            )
          } else {
            (
              self,
              Reply(DisconnectPB(s"${newUsername.name} already taken", System.currentTimeMillis()), ReplyType.Direct)
            )
          }
        } else {
          (self, Reply(DisconnectPB(s"Auth error: ${newUsername.name}", System.currentTimeMillis()), ReplyType.Direct))
        }
      case _ =>
        (self, Reply(DisconnectPB("Specify username first", System.currentTimeMillis()), ReplyType.Direct))
    }

  override def toString: String =
    s"Idle(${connectionId.toString},users=${users})"

}

final case class Active(
  connectionId: UUID,
  username: Username,
  users: ConcurrentHashMap[Username, UUID],
  outgoingCons: ConcurrentHashMap[UUID, BoundedSourceQueue[ServerCommand]]
)(implicit log: Logger)
    extends State {
  self =>

  val dmSeparator = ":"

  override def applyCmd(cmd: ClientCommand): (State, Reply) = {
    val response = cmd match {
      case SendMessagePB(cmd) =>
        if (cmd.startsWith("/")) {
          cmd match {
            case "/users" =>
              Reply(
                AlertPB(users.keys().asScala.map(_.name).mkString(", "), System.currentTimeMillis()),
                ReplyType.Direct
              )
            case other =>
              // /dm:adam:hello world
              if (cmd.startsWith("/dm") || cmd.startsWith("/DM")) {
                val segments  = cmd.split(dmSeparator)
                val recipient = segments(1)
                val text      = segments(2)

                val a = users.keySet().contains(username)
                val b = users.keySet().contains(Username(recipient))

                if (a && b) {
                  val dmMsg           = DmPB(username, Username(recipient), text, System.currentTimeMillis())
                  val recipientId     = users.get(Username(recipient))
                  val recipientOutCon = outgoingCons.get(recipientId)
                  writeChannel(recipientOutCon, dmMsg)
                  Reply(dmMsg, ReplyType.Direct)
                } else {
                  Reply(
                    AlertPB(s"$username can't DM to $recipient. User's offline", System.currentTimeMillis()),
                    ReplyType.Direct
                  )
                }
              } else
                Reply(
                  AlertPB(s"Unknown command ${other.getClass.getName}", System.currentTimeMillis()),
                  ReplyType.Direct
                )
          }
        } else {
          Reply(MsgPB(username, cmd, System.currentTimeMillis()), ReplyType.Broadcast)
        }
      case c: RequestUsernamePB =>
        Reply(
          DisconnectPB(s"Unexpected cmd: ${c.getClass.getName} in Active", System.currentTimeMillis()),
          ReplyType.Direct
        )
    }
    (self, response)
  }

  override def toString: String =
    s"Active(${connectionId.toString},${username.name},users=${users})"
}
