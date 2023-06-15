package cats.netty
package testkit

import scala.concurrent.duration._

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.syntax.all._
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.{ChannelFuture, ChannelHandler, ChannelPromise}
import io.netty.util.ReferenceCounted

import cats.netty.channel.NettyToCatsChannelInitializer
import cats.netty.testkit.NettyCodec.{NettyDecoder, NettyEncoder}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments"
  )
)
class EmbeddedChannelF[F[_]](val underlying: EmbeddedChannel)(implicit F: Async[F]) {

  def writeInboundEncoded[A](
    xs: A*
  )(implicit encoder: NettyEncoder[A]): F[Unit] =
    for {
      encodingChannel <- F.delay(new EmbeddedChannel(encoder.encoders.toList: _*))
      _ <- xs.traverse_(x => F.delay(encodingChannel.pipeline().writeAndFlush(x)))
      encodedObjects <- xs.traverse(_ => F.delay(encodingChannel.readOutbound[ByteBuf]()))
      _ <- F.delay(underlying.writeInbound(encodedObjects: _*))
    } yield ()

  def writeAndFlushInbound(messages: Any*): F[Unit] =
    F.delay(underlying.writeInbound(messages: _*))
      .flatMap(
        F.raiseWhen(_)(
          new Throwable(
            "The message(s) hit the end of the pipeline; either the message type did NOT match Handler(s) or the Handler(s) continued to fire channel read"
          )
        )
      )

  def writeAndFlushInboundThenCheckThatHandlerProxies(messages: Any*): F[Unit] =
    F.delay(underlying.writeInbound(messages: _*))
      .flatMap(didMsgHitPipelineTail =>
        F.raiseWhen(!didMsgHitPipelineTail)(
          new Throwable(
            "The message(s) did NOT hit the end of the pipeline; some handler fully processed the messages and didn't fire channel read as expected"
          )
        )
      )

  def writeOutboundThenFlush(messages: Any*): F[Boolean] = F.delay(
    underlying.writeOutbound(messages: _*)
  )

  def writeOutbound(msg: Any): F[ChannelFuture] = F.delay(underlying.writeOneOutbound(msg))
  def flushOutbound: F[Unit] = F.delay(underlying.flushOutbound()).void

  def readOutboundDecoded[A <: ReferenceCounted: NettyDecoder, B](
    f: A => B
  ): F[B] = readOutboundDecoded(1000.millis)(f)

  def readOutboundDecoded[A <: ReferenceCounted: NettyDecoder, B](wait: FiniteDuration)(
    f: A => B
  ): F[B] = Resource
    .make(acquireOutboundMessage[A](wait))(release)
    .use(a => F.delay(f(a)))

  def readOutbound[A]: F[A] = F.delay(underlying.readOutbound[A]())

  def close: F[Unit] = F.delay(underlying.close()).void

  def closeViaPromise: F[ChannelPromise] =
    F.delay(underlying.newPromise()).flatTap(p => F.delay(underlying.close(p)).void)

  def isActive: Boolean = underlying.isActive

  def getHandler(handlerName: String): F[Option[ChannelHandler]] = F.delay(
    Option(underlying.pipeline().get(handlerName))
  )

  def waitForBackpressure: F[Unit] =
    processTasksUntil(underlying.config().isAutoRead)

  def waitForReadsToProcess: F[Unit] =
    runPendingTasksUntil(underlying.config().isAutoRead)

  // TODO: this can be optimized to short circuit if time-to-next-task = -1
  def processTasksUntil(cond: => Boolean, wait: FiniteDuration = 1000.millis): F[Unit] = {
    val runNetty = F.delay(underlying.runScheduledPendingTasks())
    runNetty *> F.unlessA(cond)(
      for {
        now <- F.realTime
        _ <- runNetty
          .map(nextTimeNs => math.max(nextTimeNs.nanos.toMillis, 10).millis)
          .flatMap(F.sleep)
          .flatMap(_ => F.realTime)
          .iterateUntil(time => cond || time - now > wait)
      } yield ()
    )
  }

  def runPendingTasksUntil(cond: => Boolean, timeout: FiniteDuration = 300.millis): F[Unit] = {
    val runNetty = F.delay(underlying.runPendingTasks())
    runNetty *> F.unlessA(cond)(
      for {
        now <- F.realTime
        _ <- runNetty
          .flatMap(_ => F.sleep(1.millis))
          .flatMap(_ => F.realTime)
          .iterateUntil(time => cond || time - now > timeout)
      } yield ()
    )
  }

  def finishAndReleaseAll: F[Boolean] = F.delay(underlying.finishAndReleaseAll())

  private def acquireOutboundMessage[A: NettyDecoder](wait: FiniteDuration): F[A] =
    for {
      now <- F.realTime

      runNetty = F.delay(underlying.runPendingTasks()) *> F.sleep(10.millis) *> F.realTime

      _ <- runNetty.iterateUntil(time =>
        !underlying.outboundMessages().isEmpty || time - now > wait
      )

      _ <- F.raiseWhen(underlying.outboundMessages().isEmpty)(
        new UnsupportedOperationException("expected channel with messages to decode")
      )

      decoders = implicitly[NettyDecoder[A]].decoders.toList

      decodingChannel = new EmbeddedChannel(decoders: _*)

      decode = F.delay(decodingChannel.writeInbound { underlying.readOutbound[AnyRef]() })

      // Pull messages off until one new message is generated (considering HTTP aggregation)
      // Messages may be left on the originating channel, left for subsequent calls
      _ <- decode.iterateWhile(_ => decodingChannel.inboundMessages().size < 1)

      res <- F.delay(decodingChannel.readInbound[A])
    } yield res

  private def release[A <: ReferenceCounted](x: A): F[Unit] =
    F.delay(x.release()).void

}

object EmbeddedChannelF {
  def apply[F[_]](underlying: EmbeddedChannel)(implicit F: Async[F]): EmbeddedChannelF[F] =
    new EmbeddedChannelF(underlying)

  def apply[F[_]](handlers: ChannelHandler*)(implicit F: Async[F]): EmbeddedChannelF[F] =
    apply(new EmbeddedChannel(handlers: _*))

  def apply[F[_]](handler: ChannelHandler, name: String)(implicit
    F: Async[F]
  ): EmbeddedChannelF[F] = {
    val ch = new EmbeddedChannel()
    val _ = ch.pipeline().addLast(name, handler)
    apply(ch)
  }

  def apply[F[_]: Async](
    dispatcher: Dispatcher[F],
    onConnected: NettyToCatsChannelInitializer.OnNewConnection[F, EmbeddedChannel]
  ): EmbeddedChannelF[F] =
    apply(new EmbeddedChannel(new NettyToCatsChannelInitializer(dispatcher, onConnected)))
}
