package akkastreamchat

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, BoundedSourceQueue, FlowShape, Inlet, Outlet, QueueOfferResult}

import com.netflix.spectator.api.Registry
import de.vandermeer.asciitable.{AsciiTable, CWC_LongestWord}

object StreamOps {
  private val nameId = "offered"

  def printSummary(registry: Registry): Unit = {
    val table = new AsciiTable()
    table.addRule()
    val it = registry.iterator()
    while (it.hasNext()) {
      val it0 = it.next().measure().iterator()
      while (it0.hasNext()) {
        val m = it0.next()
        table.addRow(m.id().toString, m.value(), m.timestamp())
      }
    }
    table.addRule()
    table.getContext.setWidth(200)
    table.getRenderer.setCWC(new CWC_LongestWord())
    println(table.render())

    /*registry
      .stream()
      .iterator()
      .asScala
      .flatMap(_.measure().iterator().asScala)
      .filter(m => m.id().name().equals(nameId))
      .foreach { m =>
        // println(m.toString)
        println(m.id.toString() + "/" + m.value() + "/" + m.timestamp())
      }*/
  }

  def blockingQueue[T](registry: Registry, id: String, size: Int): Source[T, SourceQueue[T]] =
    Source.queue(size).mapMaterializedValue(q => new SourceQueue[T](registry, id, q))

  final class SourceQueue[T](registry: Registry, id: String, queue: BoundedSourceQueue[T]) {

    @volatile private var completed: Boolean = false
    private val baseId                       = registry.createId(nameId, "id", id)
    private val enqueued                     = registry.counter(baseId.withTag("q", "enqueued"))
    private val dropped                      = registry.counter(baseId.withTag("q", "droppedFull"))
    private val closed                       = registry.counter(baseId.withTag("q", "droppedClosed"))
    private val failed                       = registry.counter(baseId.withTag("q", "droppedFailure"))

    def offer(value: T): QueueOfferResult =
      queue.offer(value) match {
        case QueueOfferResult.Enqueued =>
          enqueued.increment()
          QueueOfferResult.Enqueued
        case QueueOfferResult.Dropped =>
          dropped.increment()
          QueueOfferResult.Dropped
        case QueueOfferResult.QueueClosed =>
          closed.increment()
          QueueOfferResult.QueueClosed
        case f @ QueueOfferResult.Failure(_) =>
          failed.increment()
          f
      }

    def complete(): Unit = {
      queue.complete()
      completed = true
    }

    def isOpen: Boolean = !completed
    def size: Int       = queue.size()
  }

  def monitorFlow[T](registry: Registry, id: String): Flow[T, T, NotUsed] =
    Flow[T].via(new MonitorFlow[T](registry, id))

  private final class MonitorFlow[T](registry: Registry, id: String) extends GraphStage[FlowShape[T, T]] {

    private val numEvents       = registry.counter("stream.numEvents", "id", id)
    private val upstreamTimer   = registry.timer("stream.upstreamDelay", "id", id)
    private val downstreamTimer = registry.timer("stream.downstreamDelay", "id", id)

    private val in  = Inlet[T]("MonitorBackpressure.in")
    private val out = Outlet[T]("MonitorBackpressure.out")

    override val shape: FlowShape[T, T] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {

        import MonitorFlow.*

        private var lastUpdate        = registry.clock().monotonicTime()
        private val numEventsUpdater  = numEvents.batchUpdater(MeterBatchSize)
        private val upstreamUpdater   = upstreamTimer.batchUpdater(MeterBatchSize)
        private val downstreamUpdater = downstreamTimer.batchUpdater(MeterBatchSize)

        private var upstreamStart   = -1L
        private var downstreamStart = -1L

        override def onPush(): Unit = {
          val now = registry.clock().monotonicTime()
          numEventsUpdater.increment()
          if (upstreamStart != -1L) {
            upstreamUpdater.record(now - upstreamStart, TimeUnit.NANOSECONDS)
            upstreamStart = -1L
          }
          push(out, grab(in))
          downstreamStart = now
          if (now - lastUpdate > MeterUpdateInterval) {
            updateMeters(now)
          }
        }

        override def onPull(): Unit = {
          val now = registry.clock().monotonicTime()
          if (downstreamStart != -1L) {
            downstreamUpdater.record(now - downstreamStart, TimeUnit.NANOSECONDS)
            downstreamStart = -1L
          }
          pull(in)
          upstreamStart = now
        }

        override def onUpstreamFinish(): Unit = {
          updateMeters(registry.clock().monotonicTime())
          numEventsUpdater.close()
          upstreamUpdater.close()
          downstreamUpdater.close()
          super.onUpstreamFinish()
        }

        private def updateMeters(now: Long): Unit = {
          numEventsUpdater.flush()
          upstreamUpdater.flush()
          downstreamUpdater.flush()
          lastUpdate = now
        }

        setHandlers(in, out, this)
      }
  }

  private object MonitorFlow {
    private val MeterBatchSize      = 1_000_000
    private val MeterUpdateInterval = TimeUnit.SECONDS.toNanos(1L)
  }
}
