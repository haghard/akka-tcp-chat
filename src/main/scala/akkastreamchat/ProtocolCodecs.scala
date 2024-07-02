package akkastreamchat

import java.io.NotSerializableException

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.util.ByteString

import pbdomain.v1._

object ProtocolCodecs {

  def notSerializable(msg: String) = throw new NotSerializableException(msg)

  val MaxMessageLength = 1024 * 1

  object ClientCommand {

    val Decoder: Flow[ByteString, Try[ClientCommand], akka.NotUsed] =
      Flow[ByteString]
        .via(Framing.simpleFramingProtocolDecoder(MaxMessageLength))
        .map { bsFrame =>
          val pbAny   = com.google.protobuf.any.Any.parseFrom(bsFrame.toArrayUnsafe())
          val typeUrl = pbAny.typeUrl
          if (pbAny.is[RequestUsernamePB])
            Success(pbAny.unpack[RequestUsernamePB])
          else if (pbAny.is[SendMessagePB])
            Success(pbAny.unpack[SendMessagePB])
          else
            Failure(notSerializable(s"Unknown: $typeUrl"))
        }

    val Encoder: Flow[ClientCommand, ByteString, akka.NotUsed] =
      Flow
        .fromFunction { (clientCmd: ClientCommand) =>
          ByteString(com.google.protobuf.any.Any.pack(clientCmd).toByteArray)
        }
        .via(Framing.simpleFramingProtocolEncoder(MaxMessageLength))
  }

  object ServerCommand {

    val Decoder: Flow[ByteString, Try[ServerCommand], akka.NotUsed] =
      Flow[ByteString]
        .via(Framing.simpleFramingProtocolDecoder(MaxMessageLength))
        .map { bsFrame =>
          val pbAny   = com.google.protobuf.any.Any.parseFrom(bsFrame.toArrayUnsafe())
          val typeUrl = pbAny.typeUrl
          if (pbAny.is[WelcomePB]) Success(pbAny.unpack[WelcomePB])
          else if (pbAny.is[AlertPB]) Success(pbAny.unpack[AlertPB])
          else if (pbAny.is[DmPB]) Success(pbAny.unpack[DmPB])
          else if (pbAny.is[MsgPB]) Success(pbAny.unpack[MsgPB])
          else if (pbAny.is[DisconnectPB]) Success(pbAny.unpack[DisconnectPB])
          else if (pbAny.is[ShowAdPB]) Success(pbAny.unpack[ShowAdPB])
          else Failure(notSerializable(s"Unknown: $typeUrl"))
        }

    val Encoder: Flow[ServerCommand, ByteString, akka.NotUsed] =
      Flow
        .fromFunction { (serverCmd: ServerCommand) =>
          ByteString(com.google.protobuf.any.Any.pack(serverCmd).toByteArray)
        }
        .via(Framing.simpleFramingProtocolEncoder(MaxMessageLength))
  }
}
