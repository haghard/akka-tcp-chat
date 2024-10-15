package akkastreamchat

import java.io.NotSerializableException

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.util.ByteString

import com.google.protobuf.any.{Any => ScalaPBAny}

import akkastreamchat.pbdomain.v3.ClientCommandMessage.{SealedValue => C}
import akkastreamchat.pbdomain.v3.ServerCommandMessage.{SealedValue => S}
import akkastreamchat.pbdomain.v3._

/*
  How do we go about parsing Protobuf messages without knowing their type in advance?
  Lets use `com.google.protobuf.any.Any`
 */
object ProtocolCodecsV3 {

  def notSerializable(msg: String) = throw new NotSerializableException(msg)

  //
  val maximumFrameSize = 1024 * 5

  object ClientCommand {
    val Decoder: Flow[ByteString, Try[ClientCommand], akka.NotUsed] =
      Flow[ByteString]
        .via(Framing.simpleFramingProtocolDecoder(maximumFrameSize))
        .map { bsFrame =>
          val pbAny   = ScalaPBAny.parseFrom(bsFrame.toArrayUnsafe())
          val typeUrl = pbAny.typeUrl
          if (pbAny.is[akkastreamchat.pbdomain.v3.ClientCommandMessage]) {
            pbAny.unpack[akkastreamchat.pbdomain.v3.ClientCommandMessage].sealedValue match {
              case c: C.RequestUsername =>
                Success(c.value)
              case c: C.SendMessage =>
                Success(c.value)
              case C.Empty =>
                Failure(notSerializable(s"Unknown ClientCommandMessage: $typeUrl"))
            }
          } else {
            Failure(notSerializable(s"Expected ClientCommandMessage but found: $typeUrl"))
          }
        }

    val Encoder: Flow[ClientCommand, ByteString, akka.NotUsed] =
      Flow
        .fromFunction { (clientCmd: ClientCommand) =>
          ByteString(ScalaPBAny.pack(clientCmd.asMessage).toByteArray)
        }
        .via(Framing.simpleFramingProtocolEncoder(maximumFrameSize))
  }

  object ServerCommand {

    val Decoder: Flow[ByteString, Try[ServerCommand], akka.NotUsed] =
      Flow[ByteString]
        .via(Framing.simpleFramingProtocolDecoder(maximumFrameSize))
        .map { bsFrame =>
          val pbAny   = ScalaPBAny.parseFrom(bsFrame.toArrayUnsafe())
          val typeUrl = pbAny.typeUrl
          if (pbAny.is[akkastreamchat.pbdomain.v3.ServerCommandMessage]) {
            pbAny.unpack[akkastreamchat.pbdomain.v3.ServerCommandMessage].sealedValue match {
              case c: S.Welcome =>
                Success(c.value)
              case c: S.Alert =>
                Success(c.value)
              case c: S.Dm =>
                Success(c.value)
              case c: S.Message =>
                Success(c.value)
              case c: S.Disconnect =>
                Success(c.value)
              case c: S.ShowAd =>
                Success(c.value)
              case c: S.ShowRecent =>
                Success(c.value)
              case S.Empty =>
                Failure(notSerializable(s"Unknown ServerCommandMessage: $typeUrl"))
            }
          } else {
            Failure(notSerializable(s"Expected ServerCommandMessage but found $typeUrl"))
          }
        }

    val Encoder: Flow[ServerCommand, ByteString, akka.NotUsed] =
      Flow
        .fromFunction { (serverCmd: ServerCommand) =>
          ByteString(
            ScalaPBAny.pack[akkastreamchat.pbdomain.v3.ServerCommandMessage](serverCmd.asMessage).toByteArray
          )
        }
        .via(Framing.simpleFramingProtocolEncoder(maximumFrameSize))
  }
}
