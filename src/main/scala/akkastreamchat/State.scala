package akkastreamchat

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.EnumerationHasAsScala

import akka.stream.BoundedSourceQueue

import domain._
import org.slf4j.Logger

import akkastreamchat.pbdomain.v3.ClientCommandMessage.SealedValue
import akkastreamchat.pbdomain.v3._

sealed trait State {
  def applyCmd(command: ClientCommandMessage): (State, Reply)
}

final case class Idle(
  secretToken: String,
  connectionId: String,
  remoteAddress: InetSocketAddress,
  users: ConcurrentHashMap[Username, String],
  outgoingChannels: ConcurrentHashMap[String, BoundedSourceQueue[ServerCommand]]
)(implicit log: Logger)
    extends State { self =>

  def applyCmd(cmd: ClientCommandMessage): (State, Reply) =
    cmd.sealedValue match {
      case SealedValue.RequestUsername(c) =>
        import com.bastiaanjansen.otp._
        val TOTP = {
          val sBts = secretToken.getBytes(StandardCharsets.UTF_8) ++ c.user.name.getBytes(StandardCharsets.UTF_8)
          new TOTPGenerator.Builder(sBts)
            .withHOTPGenerator { b =>
              b.withPasswordLength(8)
              b.withAlgorithm(HMACAlgorithm.SHA256)
            }
            .withPeriod(java.time.Duration.ofSeconds(5))
            .build()
        }
        if (TOTP.verify(c.otp.toStringUtf8)) {
          if (users.putIfAbsent(c.user, connectionId) == null) {
            log.info(
              s"Authorized $connectionId/${c.user.name} from ${remoteAddress.getHostString}:${remoteAddress.getPort}"
            )
            (
              Active(connectionId, c.user, users, outgoingChannels),
              Reply(Welcome(c.user, "Welcome to the Chat!", System.currentTimeMillis()), ReplyType.Direct)
            )
          } else {
            (
              self,
              Reply(Disconnect(s"${c.user.name} already taken", System.currentTimeMillis()), ReplyType.Direct)
            )
          }
        } else {
          (self, Reply(Disconnect(s"Auth error: ${c.user.name}", System.currentTimeMillis()), ReplyType.Direct))
        }

      case SealedValue.SendMessage(_) | SealedValue.Empty =>
        (self, Reply(Disconnect("Specify username first", System.currentTimeMillis()), ReplyType.Direct))
    }

  override def toString: String =
    s"Idle(${connectionId},users=${users})"

}

final case class Active(
  connectionId: String,
  username: Username,
  users: ConcurrentHashMap[Username, String],
  outgoingCons: ConcurrentHashMap[String, BoundedSourceQueue[ServerCommand]]
) extends State {
  self =>

  val dmSeparator = ":"

  override def applyCmd(cmd: ClientCommandMessage): (State, Reply) = {
    val response = cmd.sealedValue match {
      case SealedValue.SendMessage(cmd) =>
        if (cmd.text.startsWith("/")) {
          cmd.text match {
            case "/users" =>
              Reply(
                Alert(users.keys().asScala.map(_.name).mkString(", "), System.currentTimeMillis()),
                ReplyType.Direct
              )
            case "/recent" =>
              Reply(ShowRecent(username, Seq.empty), ReplyType.Direct)
            case other =>
              // /dm:adam:hello world
              // /DM:adam:hello world1111
              if (cmd.text.startsWith("/dm") || cmd.text.startsWith("/DM")) {
                val segments  = cmd.text.split(dmSeparator)
                val recipient = segments(1)
                val text      = segments(2)

                val a = users.keySet().contains(username)
                val b = users.keySet().contains(Username(recipient))

                if (a && b) {
                  val dmMsg = Dm(username, Username(recipient), text, System.currentTimeMillis(), "")
                  Reply(dmMsg, ReplyType.Direct)
                } else {
                  Reply(
                    Alert(s"$username can't DM to $recipient. User's offline", System.currentTimeMillis()),
                    ReplyType.Direct
                  )
                }
              } else
                Reply(
                  Alert(s"Unknown command ${other.getClass.getName}", System.currentTimeMillis()),
                  ReplyType.Direct
                )
          }
        } else {
          Reply(Msg(username, cmd.text, System.currentTimeMillis(), ""), ReplyType.Broadcast)
        }

      case c: SealedValue =>
        Reply(
          Disconnect(s"Unexpected cmd: ${c.getClass.getName} in Active", System.currentTimeMillis()),
          ReplyType.Direct
        )
    }
    (self, response)
  }

  override def toString: String =
    s"Active(${connectionId},${username.name},users=$users)"
}
