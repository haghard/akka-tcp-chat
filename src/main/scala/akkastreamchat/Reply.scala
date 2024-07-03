package akkastreamchat

import akkastreamchat.pbdomain.v3.ServerCommand

sealed trait ReplyType
object ReplyType {
  object Broadcast extends ReplyType
  object Direct    extends ReplyType
}

final case class Reply(cmd: ServerCommand, `type`: ReplyType)
