package akkastreamchat

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration._

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.BoundedSourceQueue

import com.typesafe.config.ConfigFactory
import domain._

import akkastreamchat.pbdomain.v3.ServerCommand

//  https://doc.akka.io/docs/akka/current/stream/stream-io.html
object Server {

  val users    = new ConcurrentHashMap[Username, String]()
  val dmQueues = new ConcurrentHashMap[String, BoundedSourceQueue[ServerCommand]]()

  def main(args: Array[String]): Unit = {
    val host = args(0)
    val port = args(1).toInt

    val cfg             = ConfigFactory.load("application.conf").withFallback(ConfigFactory.load())
    implicit val system = ActorSystem[Nothing](Behaviors.empty, "server", cfg)
    val deadline = FiniteDuration(
      system.settings.config.getDuration("akka.coordinated-shutdown.default-phase-timeout").toNanos,
      NANOSECONDS
    )

    Bootstrap(host, port, users, dmQueues)

    val _ = scala.io.StdIn.readLine()
    system.log.info("★ ★ ★ ★ ★ ★  Shutting down ❌... ★ ★ ★ ★ ★ ★")
    system.terminate()
    scala.concurrent.Await.result(system.whenTerminated, deadline)
  }
}
