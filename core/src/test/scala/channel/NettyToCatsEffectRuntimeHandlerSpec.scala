package cats.netty
package channel

import java.nio.channels.ClosedChannelException

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._
import io.netty.channel._
import io.netty.channel.embedded.EmbeddedChannel
import org.scalatest.BeforeAndAfterAll
import org.slf4j.event.Level
import org.slf4j.helpers.SubstituteLoggerFactory
import org.slf4j.{Logger, LoggerFactory}

import cats.netty.channel.NettyToCatsEffectRuntimeHandlerSpec.ErrorHandler.TestError
import cats.netty.channel.NettyToCatsEffectRuntimeHandlerSpec._
import cats.netty.testkit.EmbeddedChannelF

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Var",
    "DisableSyntax.var"
  )
)
class NettyToCatsEffectRuntimeHandlerSpec extends BaseSpec with BeforeAndAfterAll {

  "inbound events from Netty reach CE runtime" in runIOInCurrentThread {
    val handler = QueueingHandler[Int]()
    makeEmbeddedChannelIO(handler).use { channel =>
      generateAllPossibleSequences((1 to 4).toList).traverse { sequence =>
        for {
          _ <- sequence.traverse {
            case Action.Read(i) =>
              channel.writeAndFlushInbound(i)
            case Action.UserEvent(i) =>
              IO(channel.underlying.pipeline().fireUserEventTriggered(i))
            case Action.Throw(i) =>
              IO(
                channel.underlying
                  .pipeline()
                  .fireExceptionCaught(new Throwable(s"unit_test_error ${i.toString}"))
              )
          }

          _ <- channel.processTasksUntil(handler.actions.size == 4)
          _ <- IO(handler.actions.size shouldEqual 4)
          _ <- IO(handler.actions.clear())

          _ <- sequence.traverse_ {
            case Action.Read(i) =>
              IO(handler.messages.dequeue() shouldEqual i)
            case Action.UserEvent(i) =>
              IO(handler.events.dequeue() shouldEqual i)
            case Action.Throw(i) =>
              IO(
                handler.exceptions.dequeue().getMessage shouldEqual s"unit_test_error ${i.toString}"
              )
          }
        } yield ()
      }.void
    }
  }

  // There is no interface to outbound messages, this simply reaffirms Netty's behavior.
  "outbound events from Netty are unaffected by handler" in runIOInCurrentThread {
    val handler = QueueingHandler[Int]()
    makeEmbeddedChannelIO(handler).use { channel =>
      for {
        // write but do not flush message
        _ <- IO(channel.underlying.pipeline().write(1))

        // shouldn't be in flushed
        _ <- IO(channel.underlying.runPendingTasks())
        _ <- IO(channel.underlying.outboundMessages().isEmpty shouldBe true)

        // write another message, but flush to client
        promise = channel.underlying.newPromise()
        _ <- IO(channel.underlying.pipeline().writeAndFlush(2, promise))

        _ <- channel.processTasksUntil(promise.isDone)
        _ <- IO(channel.underlying.readOutbound[Int]() shouldEqual 1)
        _ <- IO(channel.underlying.readOutbound[Int]() shouldEqual 2)
      } yield ()
    }
  }

  "removing handler allows CE runtime to finish current Effect" in runIOInCurrentThread {
    for {
      startIO <- Deferred[IO, Unit]
      finishIO <- Deferred[IO, Unit]
      ref <- Ref[IO].of(false)
      handler = QueueingHandler[Int](onRead =
        _ => startIO.get *> ref.set(true) *> finishIO.complete(()).void
      )
      chn <- makeEmbeddedChannelIO(handler).allocated.map(_._1)
      _ <- chn.writeAndFlushInbound(1)

      _ <- IO(chn.underlying.pipeline().names().size() shouldBe 2)
      _ <- IO(chn.underlying.pipeline().removeLast())
      _ <- IO(chn.underlying.pipeline().names().size() shouldBe 1)

      r <- ref.get
      _ <- IO(r shouldBe false)
      _ <- startIO.complete(())
      _ <- finishIO.get
      r <- ref.get
      _ <- IO(r shouldBe true)
    } yield ()
  }

  "closes" - {
    "from Netty reach CE runtime" in runIOInCurrentThread {
      val handler = QueueingHandler[Int]()
      makeEmbeddedChannelIO(handler).allocated.map(_._1).flatMap { channel =>
        val cf = channel.underlying.closeFuture()
        // Close signal starts from tail of pipeline, so we're really just testing that ChannelInactive is handled
        channel.close *>
          channel.processTasksUntil(cf.isDone) *>
          IO(cf.isSuccess shouldBe true) *>
          IO(handler.closes.size shouldEqual 1).void
      }
    }

    "double should be idempotent" in runIOInCurrentThread {
      val handler = QueueingHandler[Int]()
      makeEmbeddedChannelIO(handler).allocated.map(_._1).flatMap { channel =>
        val cf = channel.underlying.closeFuture()
        // Close signal starts from tail of pipeline, so we're really just testing that ChannelInactive is handled
        channel.close *>
          channel.processTasksUntil(cf.isDone) *>
          channel.closeViaPromise
            .flatTap { p =>
              channel.processTasksUntil(p.isDone) *> IO(p.isSuccess shouldBe true) *> IO(
                handler.closes.size shouldEqual 1
              ).void
            }
      }
    }

    "long running inbound events are not cancelled on close" in runIOInCurrentThread {
      val handler = QueueingHandler[Int](onRead = _ => IO.sleep(20.millis))

      makeEmbeddedChannelIO(handler).allocated.map(_._1).flatMap { channel =>
        (1 to 5).toList.traverse_(channel.writeAndFlushInbound(_)) *>
          channel.close *>
          IO(handler.messages.size should be < 5) *>
          IO(channel.isActive shouldBe false) *>
          channel.processTasksUntil(handler.closes.nonEmpty) *>
          IO.sleep(100.millis) *> // Give time for dispatcher to close
          IO(channel.underlying.runPendingTasks()) *>
          IO(handler.messages.toList shouldEqual List(1, 2, 3, 4, 5))
      }
    }

    "write activity after channel is closed should have writes fail" in runIOInCurrentThread {
      makeEmbeddedChannelIO(QueueingHandler[Int]()).allocated.map(_._1).flatMap { channel =>
        val closeFut = channel.underlying.closeFuture()
        channel.close *>
          channel.processTasksUntil(closeFut.isDone) *>
          channel.writeOutbound(1).flatMap { cf =>
            IO(cf.isDone shouldBe true) *>
              IO(cf.isSuccess shouldBe false) *>
              IO(cf.cause() shouldBe a[ClosedChannelException]).void
          }
      }
    }
  }

  "an exception from CE runtime gets logged" in runIOInCurrentThread {
    val factory = new SubstituteLoggerFactory
    val logger = factory.getLogger("unit-test-logger")
    val handler = new ErrorHandler

    makeEmbeddedChannelIO[Any](handler, logger).use { channel =>
      def assertErrorLog(msg: String, error: Throwable) =
        channel.processTasksUntil(factory.getEventQueue.size() == 1) *>
          IO(withClue(msg)(factory.getEventQueue.size() shouldEqual 1)) *>
          IO(factory.getEventQueue.poll())
            .flatTap(logLine => IO(logLine.getLevel shouldEqual Level.ERROR))
            .flatTap(logLine => IO(logLine.getMessage shouldEqual msg))
            .flatTap(logLine => IO(logLine.getThrowable shouldEqual error))
            .void

      for {
        _ <- channel.writeAndFlushInbound("hello")
        _ <- assertErrorLog(
          msg = "ErrorHandler: channelRead with java.lang.String",
          error = ErrorHandler.TestError("read")
        )

        _ <- IO(channel.underlying.pipeline.fireUserEventTriggered("hello"))
        _ <- assertErrorLog(
          msg = "ErrorHandler: userEventTriggered with java.lang.String",
          error = ErrorHandler.TestError("user event")
        )

        _ <- IO(channel.underlying.pipeline.fireChannelWritabilityChanged())
        _ <- assertErrorLog(
          msg = "ErrorHandler: channelWritabilityChanged",
          error = ErrorHandler.TestError("writability")
        )

        _ <- IO(channel.underlying.pipeline.fireExceptionCaught(new Throwable()))
        _ <- assertErrorLog(
          msg = "ErrorHandler: exceptionCaught",
          error = ErrorHandler.TestError("exception caught")
        )

        _ <- IO(channel.isActive shouldBe true)
      } yield ()
    }
  }

  "piping an inbound message to outbound with semantic blocking should not block outbound message from being sent" in runIOInCurrentThread {
    val handler = ChannelHandlerF.onlyChannelRead[IO, Int] { (msg, ctx) =>
      IO.async_[Unit] { cb =>
        val _ = ctx
          .pipeline()
          .writeAndFlush(msg)
          .addListener(new ChannelFutureListener {
            override def operationComplete(future: ChannelFuture): Unit = {
              if (future.isSuccess)
                cb(().asRight[Throwable])
              else
                cb(future.cause().asLeft[Unit])
            }
          })
      }
    }

    makeEmbeddedChannelIO(handler).use { channel =>
      channel.writeAndFlushInbound(1) *>
        channel.processTasksUntil(!channel.underlying.outboundMessages().isEmpty) *>
        channel.readOutbound[Int].map(_ shouldEqual 1).void
    }
  }

  "pipeline mutations" - {
    "replacing handler doesn't lead to loss of messages" in runIOInCurrentThread {
      val mutation = Deferred.unsafe[IO, Unit]
      val resultStr = Deferred.unsafe[IO, String]

      val mutatingHandler = ChannelHandlerF.onlyChannelRead[IO, Int]((_, ctx) => {
        // Typically, the handler would turn off autoread and turn it back on after handler
        // is installed, but we mimic w/ deferred.
        val pipeline = ctx.pipeline()
        for {
          _ <- IO(pipeline.remove("testHandler"))
          handler <- ChannelHandlerF.asNetty[IO, String](
            ChannelHandlerF.onlyChannelRead[IO, String]((str, _) => resultStr.complete(str).void)
          )
          _ <- IO(pipeline.addLast("otherHandler", handler))
          _ <- mutation.complete(())
        } yield ()
      })

      makeEmbeddedChannelIO(mutatingHandler)
        .use { ch =>
          resultStr.tryGet.map(_ shouldBe none[String]) *>
            ch.writeAndFlushInbound(1) *>
            mutation.get *>
            ch.runPendingTasksUntil(ch.underlying.pipeline().names().contains("otherHandler")) *>
            IO(ch.underlying.pipeline().names() should contain("otherHandler")) *>
            IO(ch.underlying.pipeline().names().size() shouldBe 2) *>
            resultStr.tryGet.map(_ shouldBe none[String]) *>
            ch.writeAndFlushInbound("new msg") *>
            resultStr.get.map(_ shouldBe "new msg")
        }
        .timeout(3.seconds)
    }
  }

  override protected def afterAll(): Unit = {
    dispClose.unsafeRunSync()
  }

}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.Var",
    "DisableSyntax.var",
    "DisableSyntax.valInAbstract",
    "DisableSyntax.while",
    "DisableSyntax.throw",
    "org.wartremover.warts.Throw"
  )
)
object NettyToCatsEffectRuntimeHandlerSpec {

  private lazy val (generalDispatcher, dispClose) =
    Dispatcher.parallel[IO].allocated.unsafeRunSync()

  private def makeEmbeddedChannelIO[A](
    handler: ChannelHandlerF[IO, A],
    logger: Logger = LoggerFactory.getLogger(getClass)
  ): Resource[IO, EmbeddedChannelF[IO]] =
    Resource.make(
      IO.defer {
        val ch = EmbeddedChannelF[IO](
          generalDispatcher,
          (_: EmbeddedChannel) =>
            ChannelHandlerF
              .asNetty(handler, logger)
              .map(NettyToCatsChannelInitializer.Handlers[IO]("testHandler", _))
        )

        ch.waitForBackpressure.as(ch)
      }
        // The rest if IO's run on current IO thread to mimic Netty single thread event loop.
        .evalOn(global.compute)
    )(
      _.finishAndReleaseAll.flatMap(pipelineHasUnprocessedMessages =>
        IO.whenA(pipelineHasUnprocessedMessages)(
          IO.raiseError(new Throwable("Pipeline has unprocessed messages"))
        )
      )
    )

  /*
  We want tests to run in a separate thread runtime than CE, to mimic Netty <-> CE runtime.
   */
  private def runIOInCurrentThread[A](f: => IO[A]): Unit =
    f.void.unsafeRunSync()(
      IORuntime(
        ExecutionContext.parasitic,
        IORuntime.createDefaultBlockingExecutionContext()._1,
        IORuntime.createDefaultScheduler()._1,
        () => (),
        IORuntimeConfig()
      )
    )

  private[NettyToCatsEffectRuntimeHandlerSpec] abstract class QueueingHandler[I](
    onRead: I => IO[Unit] = (_: I) => IO.unit
  ) extends ChannelHandlerF[IO, I] {
    val messages: mutable.Queue[I] = mutable.Queue.empty[I]
    val events: mutable.Queue[AnyRef] = mutable.Queue.empty[AnyRef]
    val exceptions: mutable.Queue[Throwable] = mutable.Queue.empty[Throwable]
    val closes: mutable.Queue[Unit] = mutable.Queue.empty[Unit]
    val actions: mutable.Queue[Any] = mutable.Queue.empty[Any]

    override def channelRead(msg: I)(implicit ctx: ChannelHandlerContext): IO[Unit] =
      onRead(msg) *> IO(messages.enqueue(msg)) *> IO(actions.enqueue(msg))

    override def userEventTriggered(evt: AnyRef)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO(events.enqueue(evt)) *> IO(actions.enqueue(evt))

    override def exceptionCaught(cause: Throwable)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO(exceptions.enqueue(cause)) *> IO(actions.enqueue(cause))

    override def channelWritabilityChanged(isWriteable: Boolean)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO.unit

    override def channelInactive(implicit context: ChannelHandlerContext): IO[Unit] =
      IO(closes.enqueue(())) *> IO(actions.enqueue(()))
  }

  object QueueingHandler {
    def apply[I](onRead: I => IO[Unit] = (_: I) => IO.unit): QueueingHandler[I] =
      new QueueingHandler[I](onRead) {}
  }

  private class ErrorHandler extends ChannelHandlerF[IO, Any] {

    override def channelRead(msg: Any)(implicit ctx: ChannelHandlerContext): IO[Unit] =
      IO.raiseError(TestError("read"))

    override def userEventTriggered(evt: AnyRef)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] =
      IO.raiseError(TestError("user event"))

    override def exceptionCaught(cause: Throwable)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] =
      IO.raiseError(TestError("exception caught"))

    override def channelWritabilityChanged(isWriteable: Boolean)(implicit
      ctx: ChannelHandlerContext
    ): IO[Unit] = IO.raiseError(TestError("writability"))

    override def channelInactive(implicit ctx: ChannelHandlerContext): IO[Unit] =
      IO.raiseError(TestError("close"))
  }

  object ErrorHandler {
    final case class TestError(msg: String) extends Throwable(msg) with NoStackTrace
  }

  private def generateAllPossibleSequences(arr: List[Int]): List[List[Action]] = {
    val maxDepth = arr.length - 1
    def inner(branch: Int, depth: Int, l: List[Action]): List[List[Action]] = {
      val next = branch match {
        case 1 => Action.Read(arr(depth))
        case 2 => Action.UserEvent(arr(depth))
        case _ => Action.Throw(arr(depth))
      }
      if (depth < maxDepth) {
        (1 to 3)
          .map(inner(_, depth + 1, next :: l))
          .foldLeft(List.empty[List[Action]])((z, ll) => z ::: ll)
      } else
        List((next :: l).reverse)
    }

    (1 to 3)
      .map(inner(_, 0, Nil))
      .foldLeft(List.empty[List[Action]])((z, ll) => z ::: ll)
  }
  sealed abstract class Action extends Product with Serializable

  object Action {
    final case class Read(i: Int) extends Action
    final case class UserEvent(i: Int) extends Action
    final case class Throw(i: Int) extends Action

  }

}
