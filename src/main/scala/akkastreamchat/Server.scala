package akkastreamchat

import scala.concurrent.duration._

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors

import com.typesafe.config.ConfigFactory

//  https://doc.akka.io/docs/akka/current/stream/stream-io.html
object Server {

  def main(args: Array[String]): Unit = {
    val host = args(0)
    val port = args(1).toInt

    val cfg             = ConfigFactory.load("application.conf").withFallback(ConfigFactory.load())
    implicit val system = ActorSystem[Nothing](Behaviors.empty, "server", cfg)
    val deadline = FiniteDuration(
      system.settings.config.getDuration("akka.coordinated-shutdown.default-phase-timeout").toNanos,
      NANOSECONDS
    )

    /*val users    = new ConcurrentHashMap[Username, String]()
    val dmQueues = new ConcurrentHashMap[String, BoundedSourceQueue[ServerCommand]]()
    Bootstrap(host, port, users, dmQueues)*/

    Bootstrap3(host, port)

    val _ = scala.io.StdIn.readLine()
    system.log.info("★ ★ ★ ★ ★ ★  Shutting down ❌... ★ ★ ★ ★ ★ ★")
    system.terminate()
    scala.concurrent.Await.result(system.whenTerminated, deadline)
  }
}
