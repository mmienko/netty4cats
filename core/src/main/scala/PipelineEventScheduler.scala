package cats.netty

import scala.concurrent.duration.FiniteDuration

import cats.effect.Sync
import io.netty.channel.{ChannelHandlerContext, ChannelPipeline}
import io.netty.util.concurrent.ScheduledFuture

import cats.netty.Utils.ValueDiscard

trait PipelineEventScheduler[F[_]] {
  def schedule(evt: AnyRef, delay: FiniteDuration): F[ScheduledFuture[_]]
}

class NettyPipelineEventScheduler[F[_]](ctx: ChannelHandlerContext)(implicit
  F: Sync[F]
) extends PipelineEventScheduler[F] {

  override def schedule(
    evt: AnyRef,
    delay: FiniteDuration
  ): F[ScheduledFuture[_]] =
    F.delay(
      ctx
        .executor()
        .schedule(
          new Runnable {

            override def run(): Unit =
              ValueDiscard[ChannelPipeline](
                ctx.pipeline().fireUserEventTriggered(evt)
              )
          },
          delay.length,
          delay.unit
        )
    )
}
