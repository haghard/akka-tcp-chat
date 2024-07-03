package akkastreamchat

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, ZoneId, ZonedDateTime}

import scala.Console.BOLD
import scala.Console.GREEN_B
import scala.Console.RED_B
import scala.Console.RESET
import scala.Console.WHITE
import scala.Console.println
import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp

import com.bastiaanjansen.otp.HMACAlgorithm
import com.bastiaanjansen.otp.TOTPGenerator
import com.typesafe.config.ConfigFactory
import domain.*
import pbdomain.v3.*

object Client {

  val formatter         = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
  val SERVER_DEFAULT_TZ = ZoneId.of(java.util.TimeZone.getDefault().getID())

  def main(args: Array[String]): Unit =
    try {
      val host     = args(0)
      val port     = args(1).toInt
      val username = args(2)

      val cfg             = ConfigFactory.load("application.conf").withFallback(ConfigFactory.load())
      implicit val system = ActorSystem[Nothing](Behaviors.empty, "client", cfg)
      import system.executionContext
      // system.dispatchers.lookup(DispatcherSelector.fromConfig("fixed-pool"))

      run(host, port, Username(username), cfg.getString("server.secret-token")).foreach { _ =>
        println("Exit")
        System.exit(0)
      }
    } catch {
      case NonFatal(ex) =>
        ex.printStackTrace()
        println("Usage: Server [host] [port] [username]")
        System.exit(-1)
    }

  def commandsIn() =
    Source
      .unfoldResource[String, Iterator[String]](
        () => Iterator.continually(StdIn.readLine("> ")),
        iterator => Some(iterator.next()),
        _ => ()
      )
      .map(SendMessage(_))

  def time(ts: Long): String =
    formatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), SERVER_DEFAULT_TZ))

  val serverPbCommandToString = Flow[Try[ServerCommand]].map {
    case Success(pbCmd) =>
      pbCmd match {
        case Welcome(user, txt, ts) =>
          s"[${time(ts)}] Logged in as ${user.name} \n$txt"
        case Alert(txt, ts) =>
          s"[${time(ts)}] $txt"
        case Dm(src, _, txt, ts) =>
          s"${src.name}(DM) [${time(ts)}]]: $txt"
        case Msg(user, txt, ts) =>
          s"${user.name} [${time(ts)}]]: $txt"
        case Disconnect(reason, ts) =>
          s"[${time(ts)}]] Server disconnected because: $reason"
        case ShowAd(txt, ts) =>
          s"${time(ts)}] $txt"
        case ServerCommand.Empty =>
          "Empty"
      }
    case Failure(ex) =>
      s"Error parsing server command: ${ex.getMessage}"
  }

  def run(host: String, port: Int, username: Username, secretToken: String)(implicit
    system: ActorSystem[Nothing]
  ): Future[Done] = {

    val sink =
      ProtocolCodecsV3.ServerCommand.Decoder
        .takeWhile(!_.toOption.exists(_.isInstanceOf[Disconnect]), inclusive = true)
        .via(serverPbCommandToString)
        .concat(Source.single("Disconnected from server"))
        .toMat(
          Sink.foreach { txt =>
            if (txt.startsWith(username.name)) println(s"$GREEN_B$BOLD$WHITE  $txt $RESET")
            else println(s"$RED_B$BOLD$WHITE  $txt $RESET")
          }
        )(Keep.right)

    val connection = Tcp(system).outgoingConnection(host, port)

    /*
    val restartSettings = RestartSettings(1.second, 5.seconds, 0.2).withMaxRestarts(5, 1.minute)
    val restartSource = RestartSource.onFailuresWithBackoff(restartSettings) { () =>
      Source
        .single(ClientCommand.RequestUsername(username))
        .concat(commandsIn(username))
        .via(ClientCommand.encoder)
        .via(connection)
    }
    restartSource.toMat(sink)(Keep.right).run()
     */

    val TOTPGen = new TOTPGenerator.Builder(
      secretToken.getBytes(StandardCharsets.UTF_8) ++ username.name.getBytes(StandardCharsets.UTF_8)
    )
      .withHOTPGenerator { b =>
        b.withPasswordLength(8)
        b.withAlgorithm(HMACAlgorithm.SHA256)
      }
      .withPeriod(Duration.ofSeconds(5))
      .build()
    val otp = TOTPGen.now()

    Source
      .single(RequestUsername(username, com.google.protobuf.ByteString.copyFromUtf8(otp)))
      .concat(commandsIn())
      .via(ProtocolCodecsV3.ClientCommand.Encoder)
      .via(connection)
      .toMat(sink)(Keep.right)
      .run()

    /*Source
      .single(ClientCommand.RequestUsername(username, otp))
      .concat(commandsIn(username))
      .via(ClientCommand.Encoder)
      .via(connection)
      .toMat(sink)(Keep.right)
      .run()*/
  }

  /*
  def run2(host: String, port: Int, username: Username, secretToken: String)(implicit
    system: ActorSystem[Nothing]
  ): Future[Done] = {
    import system.executionContext

    val TOTPGen = new TOTPGenerator.Builder(
      secretToken.getBytes(StandardCharsets.UTF_8) ++ username.name.getBytes(StandardCharsets.UTF_8)
    )
      .withHOTPGenerator { b =>
        b.withPasswordLength(8)
        b.withAlgorithm(HMACAlgorithm.SHA256)
      }
      .withPeriod(Duration.ofSeconds(5))
      .build()
    val otp = TOTPGen.now()

    val in =
      Source
        .single(ClientCommand.RequestUsername(username, otp))
        .concat(commandsIn(username))
        .via(ClientCommand.Encoder)

    val out = ServerCommand.decoder
      .takeWhile(!_.toOption.exists(_.isInstanceOf[ServerCommand.Disconnect]), inclusive = true)
      .via(serverCommandToString)
      .concat(Source.single("Disconnected from server"))
      .toMat(
        Sink.foreach { txt =>
          val ts = formatter.format(ZonedDateTime.ofInstant(Instant.now(), SERVER_DEFAULT_TZ))
          if (txt.startsWith(username.name)) println(s"$GREEN_B$BOLD$WHITE [$ts] $txt $RESET")
          else println(s"$RED_B$BOLD$WHITE [$ts] $txt $RESET")
        }
      )(Keep.right)

    val (connected, done) = in
      .viaMat(
        Tcp(system)
          .outgoingConnection(host, port)
      )(Keep.right)
      .toMat(out)(Keep.both)
      .run()

    connected.foreach { connection =>
      println(s"Connected to ${connection.remoteAddress.getHostString}:${connection.remoteAddress.getPort}")
    }

    done
  }

  def run1(host: String, port: Int, username: Username, secretToken: String)(implicit
    system: ActorSystem[Nothing]
  ): Future[Done] = {
    import system.executionContext

    val sink = ServerCommand.decoder
      .takeWhile(!_.toOption.exists(_.isInstanceOf[ServerCommand.Disconnect]), inclusive = true)
      .via(serverCommandToString)
      .concat(Source.single("Disconnected from server"))
      .toMat(
        Sink.foreach { txt =>
          val ts = formatter.format(ZonedDateTime.ofInstant(Instant.now(), SERVER_DEFAULT_TZ))
          if (txt.startsWith(username.name)) println(s"$GREEN_B$BOLD$WHITE [$ts] $txt $RESET")
          else println(s"$RED_B$BOLD$WHITE [$ts] $txt $RESET")
        }
      )(Keep.right)

    // "halfClose behavior" on the client side. Doc: https://github.com/akka/akka/issues/22163
    val ((outConF, doneF), sink0) = Tcp(system)
      .outgoingConnection(remoteAddress = InetSocketAddress.createUnresolved(host, port), halfClose = true)
      .toMat(sink)(Keep.both)
      .preMaterialize()

    outConF.map { c =>
      println(s"Connected to ${c.remoteAddress.getHostString}:${c.remoteAddress.getPort} Local:${c.localAddress}")

      // val restartSettings = RestartSettings(1.second, 10.seconds, 0.2).withMaxRestarts(10, 1.minute)
      /*val restartSource = RestartSource.onFailuresWithBackoff(restartSettings) { () =>
        Source.single(ClientCommand.RequestUsername(username)).concat(commandsIn(username))
      }*/

      // restartSource

      val TOTPGen = new TOTPGenerator.Builder(
        secretToken.getBytes(StandardCharsets.UTF_8) ++ username.name.getBytes(StandardCharsets.UTF_8)
      )
        .withHOTPGenerator { b =>
          b.withPasswordLength(8)
          b.withAlgorithm(HMACAlgorithm.SHA256)
        }
        .withPeriod(Duration.ofSeconds(5))
        .build()
      val otp = TOTPGen.now()

      Source
        .single(ClientCommand.RequestUsername(username, otp))
        .concat(commandsIn(username))
        .via(ClientCommand.Encoder)
        .to(sink0)
        .run()
    }

    doneF
  }*/

}
